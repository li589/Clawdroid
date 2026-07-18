package com.clawdroid.app.skills

import com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

enum class AgentExecutionMode {
    /** Steps run in-app via [ClawToolDispatcher] (accessibility / composite ops). */
    InApp,

    /**
     * All steps map to Runtime IPC actions and can be submitted with `task_submit`.
     * Prefer this for pure daemon workflows (ping/status/capabilities…).
     */
    RuntimeTask
}

/**
 * Multi-step phone agents executable via MCP / in-app orchestration.
 */
data class ClawAgentDefinition(
    val id: String,
    val name: String,
    val description: String,
    val steps: List<String>,
    val stepTitles: List<String>,
    val skillId: String? = null,
    val executionMode: AgentExecutionMode = AgentExecutionMode.InApp
)

fun interface AgentStepListener {
    suspend fun onStep(
        index: Int,
        stepId: String,
        title: String,
        started: Boolean,
        result: ClawToolCallResult?
    )
}

private suspend fun notifyStep(
    listener: AgentStepListener?,
    index: Int,
    stepId: String,
    title: String,
    started: Boolean,
    result: ClawToolCallResult?
) {
    if (listener == null) {
        return
    }
    runCatching {
        listener.onStep(index, stepId, title, started, result)
    }
}

object ClawAgentCatalog {
    fun all(): List<ClawAgentDefinition> = agents

    fun byId(id: String): ClawAgentDefinition? =
        agents.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }

    private val agents = listOf(
        ClawAgentDefinition(
            id = "runtime_health_sweep",
            name = "运行时体检",
            description = "Ping -> Runtime Status -> Capabilities (Runtime task_submit)",
            steps = listOf("runtime_ping", "get_runtime_status", "get_capabilities"),
            stepTitles = listOf("Ping", "Runtime Status", "获取能力"),
            skillId = "phone-runtime-ops",
            executionMode = AgentExecutionMode.RuntimeTask
        ),
        ClawAgentDefinition(
            id = "probe_then_capabilities",
            name = "运行时状态检查",
            description = "probe_session -> get_capabilities",
            steps = listOf("probe_session", "get_capabilities"),
            stepTitles = listOf("Runtime Probe", "获取能力"),
            skillId = "phone-runtime-ops"
        ),
        ClawAgentDefinition(
            id = "capture_then_preview",
            name = "截图并预览",
            description = "capture_screen -> read_latest_capture",
            steps = listOf("capture_screen", "read_latest_capture"),
            stepTitles = listOf("截图", "预览"),
            skillId = "phone-capture-inspect"
        ),
        ClawAgentDefinition(
            id = "swipe_then_capture",
            name = "滑动后截图",
            description = "inject_swipe -> capture_screen -> read_latest_capture",
            steps = listOf("inject_swipe", "capture_screen", "read_latest_capture"),
            stepTitles = listOf("滑动", "截图", "预览"),
            skillId = "phone-ui-automation"
        ),
        ClawAgentDefinition(
            id = "confirm_then_safe_tap",
            name = "页面确认后安全点击",
            description = "page_confirm -> click_precheck -> safe_tap",
            steps = listOf("page_confirm", "click_precheck", "safe_tap"),
            stepTitles = listOf("页面确认", "点击前检查", "安全点击"),
            skillId = "phone-ui-automation"
        ),
        ClawAgentDefinition(
            id = "assist_then_runtime",
            name = "协助连通后体检",
            description = "assist_ping -> get_runtime_status",
            steps = listOf("assist_ping", "get_runtime_status"),
            stepTitles = listOf("协助探测", "Runtime Status"),
            skillId = "assist-mcp-bridge"
        )
    )
}

class ClawAgentRunner(
    private val dispatcher: ClawToolDispatcher
) {
    suspend fun run(
        agentId: String,
        arguments: Map<String, Any?> = emptyMap(),
        stepListener: AgentStepListener? = null,
        onRuntimeTaskSubmitted: (suspend (String) -> Unit)? = null
    ): ClawToolCallResult {
        val agent = ClawAgentCatalog.byId(agentId)
            ?: return ClawToolCallResult(
                success = false,
                output = "未知 Agent: $agentId。可用: ${ClawAgentCatalog.all().joinToString { it.id }}",
                error = "unknown_agent"
            )

        if (AgentRuntimeBridge.canSubmitAsRuntimeTask(agent)) {
            return runAsRuntimeTask(agent, arguments, stepListener, onRuntimeTaskSubmitted)
        }

        return runInApp(agent, arguments, stepListener)
    }

    private suspend fun runAsRuntimeTask(
        agent: ClawAgentDefinition,
        arguments: Map<String, Any?>,
        stepListener: AgentStepListener?,
        onRuntimeTaskSubmitted: (suspend (String) -> Unit)?
    ): ClawToolCallResult {
        val taskId = "agent-${agent.id}-${System.currentTimeMillis()}"
        val stepsJson = AgentRuntimeBridge.buildStepsJson(agent, arguments)
            ?: return ClawToolCallResult(
                success = false,
                output = "Agent ${agent.id} 无法映射为 Runtime task_submit 步骤。",
                error = "runtime_task_unmapped"
            )

        val report = StringBuilder()
        report.appendLine("Agent: ${agent.id} (${agent.name})")
        report.appendLine("Mode: RuntimeTask (task_submit)")
        report.appendLine("Steps: ${agent.steps.joinToString(" -> ")}")
        report.appendLine()

        val firstStep = agent.steps.first()
        val firstTitle = agent.stepTitles.firstOrNull() ?: firstStep
        notifyStep(stepListener, 0, firstStep, firstTitle, started = true, result = null)

        val submit = dispatcher.execute(
            ClawTool.TASK_SUBMIT,
            mapOf(
                "task_id" to taskId,
                "name" to agent.name,
                "steps_json" to stepsJson
            )
        )
        if (!submit.success) {
            notifyStep(stepListener, 0, firstStep, firstTitle, started = false, result = submit)
            report.appendLine(submit.output.trim())
            return ClawToolCallResult(
                success = false,
                output = report.toString().trim(),
                error = submit.error ?: "task_submit_failed",
                runtimeTaskId = submit.runtimeTaskId,
                taskSnapshot = submit.taskSnapshot
            )
        }

        onRuntimeTaskSubmitted?.invoke(taskId)
        report.appendLine("Submitted: task_id=$taskId")
        var finishedCount = 0
        var startedCount = 1
        var finalSnapshot: ClawRuntimeTaskSnapshot? = submit.taskSnapshot
        var lastOutput = submit.output

        poll@ for (attempt in 0 until RUNTIME_TASK_POLL_ATTEMPTS) {
            coroutineContext.ensureActive()
            delay(RUNTIME_TASK_POLL_INTERVAL_MS)
            val getResult = dispatcher.execute(
                ClawTool.TASK_GET,
                mapOf("task_id" to taskId)
            )
            lastOutput = getResult.output
            val snapshot = getResult.taskSnapshot ?: continue
            finalSnapshot = snapshot

            val completed = snapshot.completedSteps.coerceIn(0, agent.steps.size)
            while (finishedCount < completed) {
                val index = finishedCount
                val stepId = agent.steps[index]
                val title = agent.stepTitles.getOrElse(index) { stepId }
                if (startedCount <= index) {
                    notifyStep(stepListener, index, stepId, title, started = true, result = null)
                    startedCount = index + 1
                }
                notifyStep(stepListener, 
                    index,
                    stepId,
                    title,
                    started = false,
                    result = ClawToolCallResult(success = true, output = snapshot.summaryLine())
                )
                finishedCount++
            }

            val state = snapshot.state.lowercase()
            if (state !in TERMINAL_RUNTIME_STATES) {
                val runningIndex = snapshot.currentStep.coerceIn(0, agent.steps.lastIndex)
                if (startedCount <= runningIndex) {
                    val stepId = agent.steps[runningIndex]
                    val title = agent.stepTitles.getOrElse(runningIndex) { stepId }
                    notifyStep(stepListener, runningIndex, stepId, title, started = true, result = null)
                    startedCount = runningIndex + 1
                }
                continue
            }

            if (finishedCount < agent.steps.size) {
                val index = finishedCount.coerceAtMost(agent.steps.lastIndex)
                val stepId = agent.steps[index]
                val title = agent.stepTitles.getOrElse(index) { stepId }
                if (startedCount <= index) {
                    notifyStep(stepListener, index, stepId, title, started = true, result = null)
                }
                val ok = state == "succeeded"
                notifyStep(stepListener, 
                    index,
                    stepId,
                    title,
                    started = false,
                    result = ClawToolCallResult(
                        success = ok,
                        output = snapshot.summaryLine(),
                        error = snapshot.error.takeIf { it.isNotBlank() }
                    )
                )
            }
            break@poll
        }

        val finalState = finalSnapshot?.state?.lowercase().orEmpty()
        val terminal = finalState in TERMINAL_RUNTIME_STATES
        report.appendLine(finalSnapshot?.summaryLine() ?: lastOutput)
        if (!terminal) {
            // Poll budget exhausted while Runtime task is still active — hand off to event tracking.
            report.append(
                "本地轮询超时，已改为事件跟踪 Runtime 任务 $taskId（状态=${finalSnapshot?.state ?: "unknown"}）。"
            )
            val trackingSnapshot = finalSnapshot ?: ClawRuntimeTaskSnapshot(
                taskId = taskId,
                state = "Running",
                totalSteps = agent.steps.size,
                currentStep = finishedCount.coerceAtMost(agent.steps.lastIndex),
                completedSteps = finishedCount,
                name = agent.name
            )
            return ClawToolCallResult(
                success = true,
                output = report.toString().trim(),
                error = ERROR_RUNTIME_TASK_DETACHED,
                runtimeTaskId = taskId,
                taskSnapshot = trackingSnapshot
            )
        }
        val success = finalState == "succeeded"
        if (success) {
            report.append("Agent completed successfully.")
        } else {
            report.append("Agent Runtime task ended: ${finalSnapshot?.state}")
        }
        return ClawToolCallResult(
            success = success,
            output = report.toString().trim(),
            error = if (success) {
                null
            } else {
                finalSnapshot?.error?.takeIf { it.isNotBlank() } ?: finalSnapshot?.state
            },
            runtimeTaskId = taskId,
            taskSnapshot = finalSnapshot
        )
    }

    private suspend fun runInApp(
        agent: ClawAgentDefinition,
        arguments: Map<String, Any?>,
        stepListener: AgentStepListener?
    ): ClawToolCallResult {
        val report = StringBuilder()
        report.appendLine("Agent: ${agent.id} (${agent.name})")
        report.appendLine("Steps: ${agent.steps.joinToString(" -> ")}")
        report.appendLine()

        var lastPreview = ByteArray(0)
        var lastCapture = dispatcher.peekLastCapture()

        agent.steps.forEachIndexed { index, step ->
            coroutineContext.ensureActive()
            val title = agent.stepTitles.getOrElse(index) { step }
            notifyStep(stepListener, 
                index = index,
                stepId = step,
                title = title,
                started = true,
                result = null
            )
            val stepArgs = argumentsForStep(step, arguments)
            val result = dispatcher.execute(step, stepArgs)
            report.appendLine("## Step ${index + 1}: $step")
            report.appendLine(if (result.success) "OK" else "FAIL")
            report.appendLine(result.output.trim())
            report.appendLine()
            if (result.previewBytes?.isNotEmpty() == true) {
                lastPreview = result.previewBytes
            }
            if (result.captureArtifact != null) {
                lastCapture = result.captureArtifact
            }
            notifyStep(stepListener, 
                index = index,
                stepId = step,
                title = title,
                started = false,
                result = result
            )
            if (!result.success) {
                return ClawToolCallResult(
                    success = false,
                    output = report.toString().trim(),
                    error = result.error ?: "agent_step_failed",
                    captureArtifact = lastCapture,
                    previewBytes = lastPreview.takeIf { it.isNotEmpty() }
                )
            }
        }

        return ClawToolCallResult(
            success = true,
            output = report.append("Agent completed successfully.").toString().trim(),
            captureArtifact = lastCapture,
            previewBytes = lastPreview.takeIf { it.isNotEmpty() }
        )
    }

    private fun argumentsForStep(
        step: String,
        arguments: Map<String, Any?>
    ): Map<String, Any?> {
        return when (step) {
            "capture_screen" -> mapOf(
                "read_after_capture" to false,
                "display_id" to (arguments["display_id"] ?: 0)
            )
            "inject_swipe" -> mapOf(
                "x1" to (arguments["x1"] ?: 540),
                "y1" to (arguments["y1"] ?: 1800),
                "x2" to (arguments["x2"] ?: 540),
                "y2" to (arguments["y2"] ?: 400),
                "duration_ms" to (arguments["duration_ms"] ?: 350),
                "display_id" to (arguments["display_id"] ?: 0)
            )
            "page_confirm" -> mapOf(
                "expected_package" to (arguments["expected_package"] ?: ""),
                "expected_text" to (arguments["expected_text"] ?: ""),
                "expected_view_id" to (arguments["expected_view_id"] ?: "")
            )
            "click_precheck" -> mapOf(
                "expected_package" to (
                    arguments["click_expected_package"]
                        ?: arguments["expected_package"]
                        ?: ""
                ),
                "target_text" to (
                    arguments["target_text"]
                        ?: arguments["expected_text"]
                        ?: ""
                ),
                "target_view_id" to (
                    arguments["target_view_id"]
                        ?: arguments["expected_view_id"]
                        ?: ""
                )
            )
            else -> emptyMap()
        }
    }

    companion object {
        const val ERROR_RUNTIME_TASK_DETACHED = "runtime_task_detached"

        private const val RUNTIME_TASK_POLL_INTERVAL_MS = 500L
        private const val RUNTIME_TASK_POLL_ATTEMPTS = 120
        private val TERMINAL_RUNTIME_STATES = setOf("succeeded", "failed", "cancelled")
    }
}
