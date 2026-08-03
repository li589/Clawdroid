package com.clawdroid.app.ai

import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolDispatcher
import com.clawdroid.app.data.model.AgentOrchestrationSettings
import com.clawdroid.app.ui.ChatAiLoop
import com.clawdroid.app.data.model.ChatMessageState
import com.clawdroid.app.ui.ChatUiState
import com.clawdroid.app.data.model.ModelSettings
import com.clawdroid.app.ui.OverviewAutomationState
import com.clawdroid.app.ui.OverviewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow

internal interface AgentToolLoopHost {
    val uiState: StateFlow<ChatUiState>
    val toolDispatcher: ClawToolDispatcher?
    var currentTaskJob: Job?

    fun appendAgentEvent(event: AgentRunEvent)
    fun updateChatMessage(
        messageId: String,
        content: String,
        state: ChatMessageState = ChatMessageState.Streaming,
        attachmentLabel: String? = null
    )
    fun finishChat()
    fun updateState(transform: (ChatUiState) -> ChatUiState)
    fun enrichToolArguments(
        tool: ClawTool,
        arguments: Map<String, String>,
        automationUiState: OverviewAutomationState,
        normalizedPrompt: String
    ): Map<String, String>
    suspend fun executeAgentById(
        agentId: String,
        arguments: Map<String, Any?> = emptyMap(),
        ensureTaskUi: Boolean,
        originPrompt: String = "",
        finishTaskUi: Boolean = ensureTaskUi,
        bindRuntimeOnSubmit: Boolean = true
    ): String
    fun ensureTaskStepSlot(stepIndex: Int, title: String)
    fun markTaskStepRunning(stepIndex: Int, detail: String)
    fun markTaskStepFinished(stepIndex: Int, success: Boolean, detail: String)
    fun startDynamicTaskExecution(
        title: String,
        summary: String,
        initialStepTitles: List<String>,
        originPrompt: String,
        preserveJob: Job? = null
    )
    fun finishAiLoopTaskExecution(success: Boolean, summary: String)
    suspend fun finalizeToolReply(
        replyMessageId: String,
        normalizedPrompt: String,
        tool: ClawTool,
        arguments: Map<String, String>,
        assistantMessage: String?,
        result: String,
        reflectResultWithModel: Boolean,
        modelSettings: ModelSettings,
        onModelCallSuccess: () -> Unit
    )
    fun syncRuntimeTaskTracking(
        tool: ClawTool,
        arguments: Map<String, String>,
        result: ClawToolCallResult,
        normalizedPrompt: String
    )
    suspend fun awaitSubmittedRuntimeTask(
        dispatcher: ClawToolDispatcher,
        submitResult: ClawToolCallResult
    ): ClawToolCallResult
    fun currentAiRuntimeSnapshot(): AiRuntimeSnapshot
    fun loadAgentSettings(): AgentOrchestrationSettings
    fun noteModelApiCall(onModelCallSuccess: () -> Unit): Boolean
    suspend fun handleToolIntentWhenDispatcherMissing(
        tool: ClawTool,
        arguments: Map<String, String>,
        normalizedPrompt: String,
        replyMessageId: String,
        assistantMessage: String?,
        reflectResultWithModel: Boolean,
        modelSettings: ModelSettings,
        automationUiState: OverviewAutomationState,
        onModelCallSuccess: () -> Unit
    )
    fun applyChatToolEffects(
        tool: ClawTool,
        arguments: Map<String, String>,
        result: ClawToolCallResult
    )
    suspend fun awaitCommandReview(
        tool: ClawTool,
        arguments: Map<String, String>
    ): Boolean
}

internal class AgentToolLoopController(
    private val host: AgentToolLoopHost,
    private val overviewController: OverviewController
) {
    suspend fun runAiToolLoop(
        initialTool: ClawTool,
        initialArguments: Map<String, String>,
        initialAssistantMessage: String?,
        normalizedPrompt: String,
        replyMessageId: String,
        modelSettings: ModelSettings,
        automationUiState: OverviewAutomationState,
        onModelCallSuccess: () -> Unit
    ) {
        val dispatcher = host.toolDispatcher
        if (dispatcher == null) {
            host.handleToolIntentWhenDispatcherMissing(
                tool = initialTool,
                arguments = initialArguments,
                normalizedPrompt = normalizedPrompt,
                replyMessageId = replyMessageId,
                assistantMessage = initialAssistantMessage,
                reflectResultWithModel = true,
                modelSettings = modelSettings,
                automationUiState = automationUiState,
                onModelCallSuccess = onModelCallSuccess
            )
            return
        }

        val steps = mutableListOf<AiToolStepRecord>()
        var tool = initialTool
        var arguments = initialArguments
        var assistantMessage = initialAssistantMessage
        var softWarnHint: String? = null
        val maxTurns = host.loadAgentSettings().maxToolLoopTurns
            .coerceIn(
                AgentOrchestrationSettings.MIN_TOOL_LOOP_TURNS,
                AgentOrchestrationSettings.MAX_TOOL_LOOP_TURNS_CAP
            )
        val selfJob = currentCoroutineContext()[Job]
        // Clear self-binding before creating the task card so beginTaskExecution
        // cannot treat this turn's job as a "foreign" job to replace.
        if (host.currentTaskJob === selfJob) {
            host.currentTaskJob = null
        }
        host.startDynamicTaskExecution(
            title = "AI 工具循环",
            summary = "正在按模型决策执行工具，最多 $maxTurns 步；可随时取消。",
            initialStepTitles = listOf(tool.displayName),
            originPrompt = normalizedPrompt,
            preserveJob = selfJob
        )
        host.currentTaskJob = selfJob

        repeat(maxTurns) { turnIndex ->
            currentCoroutineContext().ensureActive()
            if (host.uiState.value.apiCallsRemaining <= 0) {
                host.finishAiLoopTaskExecution(
                    success = steps.all { it.success },
                    summary = "本轮 API 预算已耗尽。"
                )
                host.updateState { it.copy(awaitingBudgetContinue = true, latestAiStatus = "本轮 API 预算已耗尽") }
                host.updateChatMessage(
                    replyMessageId,
                    buildAssistantReply(
                        assistantMessage,
                        "本轮 API 预算已耗尽，已停止后续工具步骤。发送下一条消息会重新计数。",
                        ChatAiLoop.buildTranscript(steps)
                    ),
                    ChatMessageState.Final
                )
                host.finishChat()
                return
            }
            val turn = turnIndex + 1
            host.ensureTaskStepSlot(turnIndex, tool.displayName)
            host.markTaskStepRunning(turnIndex, "正在执行 ${tool.displayName}")
            host.updateState { state ->
                state.copy(latestAiStatus = "AI 工具循环 $turn/$maxTurns: ${tool.displayName}")
            }
            host.updateChatMessage(
                replyMessageId,
                assistantMessage ?: "正在执行 ${tool.displayName}（第 $turn 步）...",
                ChatMessageState.Streaming
            )

            val enrichedArgs = host.enrichToolArguments(tool, arguments, automationUiState, normalizedPrompt)
            when (
                val preDecision = ToolLoopDetector.evaluate(
                    steps = steps,
                    nextTool = tool,
                    nextArguments = enrichedArgs
                )
            ) {
                is ToolLoopDecision.HardStop -> {
                    host.finishAiLoopTaskExecution(
                        success = steps.all { it.success },
                        summary = "AI 工具循环已停止（连续失败环）。"
                    )
                    host.updateChatMessage(
                        replyMessageId,
                        buildAssistantReply(
                            assistantMessage,
                            preDecision.message,
                            ChatAiLoop.buildTranscript(steps)
                        ),
                        ChatMessageState.Final
                    )
                    host.finishChat()
                    return
                }
                is ToolLoopDecision.ReusePriorResult -> {
                    host.appendAgentEvent(
                        AgentRunEvent(
                            kind = AgentRunEventKind.SoftWarn,
                            title = "复用已有结果",
                            detail = preDecision.message
                        )
                    )
                    softWarnHint = preDecision.message + "\n已有输出摘要: " + preDecision.priorOutput.take(400)
                    steps += AiToolStepRecord(
                        tool = tool,
                        arguments = enrichedArgs,
                        success = true,
                        output = "【系统】${preDecision.message}\n${preDecision.priorOutput}"
                    )
                    host.markTaskStepFinished(turnIndex, true, "复用已有结果")
                    val remainingAfterReuse = maxTurns - turn
                    if (remainingAfterReuse <= 0) {
                        host.finishAiLoopTaskExecution(success = true, summary = "AI 工具循环已结束（达到最大步数）。")
                        host.finalizeToolReply(
                            replyMessageId = replyMessageId,
                            normalizedPrompt = normalizedPrompt,
                            tool = tool,
                            arguments = enrichedArgs,
                            assistantMessage = assistantMessage,
                            result = ChatAiLoop.buildTranscript(steps),
                            reflectResultWithModel = true,
                            modelSettings = modelSettings,
                            onModelCallSuccess = onModelCallSuccess
                        )
                        return
                    }
                    if (!host.noteModelApiCall(onModelCallSuccess)) {
                        host.finishAiLoopTaskExecution(
                            success = true,
                            summary = "本轮 API 预算已耗尽。"
                        )
                        host.updateState { it.copy(awaitingBudgetContinue = true, latestAiStatus = "本轮 API 预算已耗尽") }
                        host.updateChatMessage(
                            replyMessageId,
                            buildAssistantReply(
                                assistantMessage,
                                "本轮 API 预算已耗尽，已停止后续工具步骤。发送下一条消息会重新计数。",
                                ChatAiLoop.buildTranscript(steps)
                            ),
                            ChatMessageState.Final
                        )
                        host.finishChat()
                        return
                    }
                    val reusePrompt = "$normalizedPrompt\n\n系统提示: ${softWarnHint.orEmpty()}"
                    softWarnHint = null
                    val reusePlan = AiAgentOrchestrator.continueAfterTool(
                        settings = modelSettings,
                        originalPrompt = reusePrompt,
                        steps = steps,
                        runtimeSnapshot = host.currentAiRuntimeSnapshot(),
                        remainingTurns = remainingAfterReuse,
                        workingSummary = host.uiState.value.compressedMemory
                    ).getOrElse { error ->
                        host.finishAiLoopTaskExecution(success = false, summary = "AI 续步失败，已停止工具循环。")
                        host.updateChatMessage(
                            replyMessageId,
                            buildAssistantReply(
                                assistantMessage,
                                "模型续步失败：${error.message ?: error::class.java.simpleName}",
                                ChatAiLoop.buildTranscript(steps)
                            ),
                            ChatMessageState.Final
                        )
                        host.finishChat()
                        return
                    }
                    when (reusePlan) {
                        is AiAgentPlan.AssistantReply -> {
                            host.finishAiLoopTaskExecution(success = true, summary = "AI 工具循环已完成。")
                            host.updateChatMessage(
                                replyMessageId,
                                buildAssistantReply(
                                    assistantMessage,
                                    reusePlan.message,
                                    ChatAiLoop.buildTranscript(steps)
                                ),
                                ChatMessageState.Final
                            )
                            host.finishChat()
                            return
                        }
                        is AiAgentPlan.ToolExecution -> {
                            tool = reusePlan.tool
                            arguments = reusePlan.arguments
                            assistantMessage = reusePlan.assistantMessage
                        }
                    }
                    return@repeat
                }
                is ToolLoopDecision.SoftWarn -> {
                    host.appendAgentEvent(
                        AgentRunEvent(
                            kind = AgentRunEventKind.SoftWarn,
                            title = "无进展，已跳过重复调用",
                            detail = preDecision.message
                        )
                    )
                    softWarnHint = preDecision.message
                    steps += AiToolStepRecord(
                        tool = tool,
                        arguments = enrichedArgs,
                        success = true,
                        output = "【系统】无进展：${preDecision.message}（已跳过本次工具执行，请换策略）"
                    )
                    host.markTaskStepFinished(turnIndex, true, "无进展跳过")
                    val remainingAfterSoft = maxTurns - turn
                    if (remainingAfterSoft <= 0) {
                        host.finishAiLoopTaskExecution(success = true, summary = "AI 工具循环已结束（达到最大步数）。")
                        host.finalizeToolReply(
                            replyMessageId = replyMessageId,
                            normalizedPrompt = normalizedPrompt,
                            tool = tool,
                            arguments = enrichedArgs,
                            assistantMessage = assistantMessage,
                            result = ChatAiLoop.buildTranscript(steps),
                            reflectResultWithModel = true,
                            modelSettings = modelSettings,
                            onModelCallSuccess = onModelCallSuccess
                        )
                        return
                    }
                    if (!host.noteModelApiCall(onModelCallSuccess)) {
                        host.finishAiLoopTaskExecution(
                            success = true,
                            summary = "本轮 API 预算已耗尽。"
                        )
                        host.updateState { it.copy(awaitingBudgetContinue = true, latestAiStatus = "本轮 API 预算已耗尽") }
                        host.updateChatMessage(
                            replyMessageId,
                            buildAssistantReply(
                                assistantMessage,
                                "本轮 API 预算已耗尽，已停止后续工具步骤。发送下一条消息会重新计数。",
                                ChatAiLoop.buildTranscript(steps)
                            ),
                            ChatMessageState.Final
                        )
                        host.finishChat()
                        return
                    }
                    val softPrompt = "$normalizedPrompt\n\n系统提示: ${softWarnHint.orEmpty()}"
                    softWarnHint = null
                    val softPlan = AiAgentOrchestrator.continueAfterTool(
                        settings = modelSettings,
                        originalPrompt = softPrompt,
                        steps = steps,
                        runtimeSnapshot = host.currentAiRuntimeSnapshot(),
                        remainingTurns = remainingAfterSoft,
                        workingSummary = host.uiState.value.compressedMemory
                    ).getOrElse { error ->
                        host.finishAiLoopTaskExecution(success = false, summary = "AI 续步失败，已停止工具循环。")
                        host.updateChatMessage(
                            replyMessageId,
                            buildAssistantReply(
                                assistantMessage,
                                "模型续步失败：${error.message ?: error::class.java.simpleName}",
                                ChatAiLoop.buildTranscript(steps)
                            ),
                            ChatMessageState.Final
                        )
                        host.finishChat()
                        return
                    }
                    when (softPlan) {
                        is AiAgentPlan.AssistantReply -> {
                            host.finishAiLoopTaskExecution(success = true, summary = "AI 工具循环已完成。")
                            host.updateChatMessage(
                                replyMessageId,
                                buildAssistantReply(
                                    assistantMessage,
                                    softPlan.message,
                                    ChatAiLoop.buildTranscript(steps)
                                ),
                                ChatMessageState.Final
                            )
                            host.finishChat()
                            return
                        }
                        is AiAgentPlan.ToolExecution -> {
                            tool = softPlan.tool
                            arguments = softPlan.arguments
                            assistantMessage = softPlan.assistantMessage
                        }
                    }
                    return@repeat
                }
                ToolLoopDecision.Continue -> Unit
            }
            val stepStartedAt = System.currentTimeMillis()
            host.appendAgentEvent(
                AgentRunEvent(
                    kind = if (tool == ClawTool.RUN_AGENT || tool == ClawTool.RUN_AGENTS_PARALLEL) {
                        AgentRunEventKind.SubAgent
                    } else {
                        AgentRunEventKind.ToolCall
                    },
                    title = tool.displayName,
                    detail = enrichedArgs.entries.take(4).joinToString(", ") { "${it.key}=${it.value.take(40)}" },
                    subAgentName = enrichedArgs.stringArg("agent_id", "agent", "id", "name", "agent_ids")
                        .takeIf { it.isNotBlank() }
                )
            )
            val (stepSuccess, stepOutput) = when (tool) {
                ClawTool.RUN_AGENT -> {
                    if (!host.awaitCommandReview(tool, enrichedArgs)) {
                        false to "已拒绝：用户未批准 Agent 调用 ${enrichedArgs.stringArg("agent_id", "agent", "id", "name")}。"
                    } else {
                        val agentId = enrichedArgs.stringArg("agent_id", "agent", "id", "name")
                        val reply = if (agentId.isBlank()) {
                            "失败: agent_id 不能为空"
                        } else {
                            host.executeAgentById(
                                agentId = agentId,
                                arguments = enrichedArgs.mapValues { (_, value) -> value },
                                ensureTaskUi = false,
                                finishTaskUi = false,
                                bindRuntimeOnSubmit = true,
                                originPrompt = normalizedPrompt
                            )
                        }
                        val ok = !reply.startsWith("失败") && !reply.contains("任务中止")
                        ok to reply
                    }
                }
                ClawTool.RUN_AGENTS_PARALLEL -> {
                    if (!host.awaitCommandReview(tool, enrichedArgs)) {
                        false to "已拒绝：用户未批准并行 Agent 调用。"
                    } else {
                        executeAgentsParallel(enrichedArgs, normalizedPrompt)
                    }
                }
                else -> {
                    if (!host.awaitCommandReview(tool, enrichedArgs)) {
                        false to "已拒绝：用户未批准执行 ${tool.displayName}。"
                    } else {
                        var result = dispatcher.execute(tool, enrichedArgs.mapValues { (_, value) -> value })
                        host.applyChatToolEffects(tool, enrichedArgs, result)
                        if (result.captureArtifact != null) {
                            dispatcher.rememberCapture(result.captureArtifact)
                        }
                        host.syncRuntimeTaskTracking(tool, enrichedArgs, result, normalizedPrompt)
                        if (tool == ClawTool.TASK_SUBMIT && result.success && !isRebootOrientedTask(enrichedArgs)) {
                            result = host.awaitSubmittedRuntimeTask(dispatcher, result)
                        }
                        if (tool == ClawTool.EXECUTE_SHELL_LIMITED && isRebootShellCommand(enrichedArgs["command"])) {
                            val accepted = result.success || result.output.contains("reboot", ignoreCase = true)
                            accepted to if (accepted) {
                                "已接受重启请求。设备即将重启，无需再 task_get / 轮询。"
                            } else {
                                result.output
                            }
                        } else {
                            result.success to result.output
                        }
                    }
                }
            }
            host.appendAgentEvent(
                AgentRunEvent(
                    kind = AgentRunEventKind.ToolResult,
                    title = "${tool.displayName} 结果",
                    detail = stepOutput.take(200),
                    success = stepSuccess,
                    durationMs = System.currentTimeMillis() - stepStartedAt
                )
            )
            host.markTaskStepFinished(turnIndex, stepSuccess, stepOutput)
            steps += AiToolStepRecord(
                tool = tool,
                arguments = enrichedArgs,
                success = stepSuccess,
                output = stepOutput
            )

            if (!stepSuccess) {
                val critique = ToolReflectionCritiqueParser.failureHeuristic(tool.toolId, stepOutput)
                host.appendAgentEvent(
                    AgentRunEvent(
                        kind = AgentRunEventKind.SoftWarn,
                        title = "反思钩子",
                        detail = critique.asSystemHint(),
                        success = false
                    )
                )
                softWarnHint = critique.asSystemHint()
                if (critique.action == CritiqueAction.Stop) {
                    host.finishAiLoopTaskExecution(
                        success = false,
                        summary = "反思建议停止：${critique.summary}"
                    )
                    host.finalizeToolReply(
                        replyMessageId = replyMessageId,
                        normalizedPrompt = normalizedPrompt,
                        tool = tool,
                        arguments = enrichedArgs,
                        assistantMessage = assistantMessage,
                        result = ChatAiLoop.buildTranscript(steps),
                        reflectResultWithModel = true,
                        modelSettings = modelSettings,
                        onModelCallSuccess = onModelCallSuccess
                    )
                    return
                }
            }

            val remainingTurns = maxTurns - turn
            if (remainingTurns <= 0) {
                val allOk = steps.all { it.success }
                host.finishAiLoopTaskExecution(
                    success = allOk,
                    summary = if (allOk) {
                        "AI 工具循环已结束（达到最大步数）。"
                    } else {
                        "AI 工具循环已结束：部分步骤失败。"
                    }
                )
                host.finalizeToolReply(
                    replyMessageId = replyMessageId,
                    normalizedPrompt = normalizedPrompt,
                    tool = tool,
                    arguments = enrichedArgs,
                    assistantMessage = assistantMessage,
                    result = ChatAiLoop.buildTranscript(steps),
                    reflectResultWithModel = true,
                    modelSettings = modelSettings,
                    onModelCallSuccess = onModelCallSuccess
                )
                return
            }

            currentCoroutineContext().ensureActive()
            if (!host.noteModelApiCall(onModelCallSuccess)) {
                host.finishAiLoopTaskExecution(
                    success = steps.all { it.success },
                    summary = "本轮 API 预算已耗尽。"
                )
                host.updateState { it.copy(awaitingBudgetContinue = true, latestAiStatus = "本轮 API 预算已耗尽") }
                host.updateChatMessage(
                    replyMessageId,
                    buildAssistantReply(
                        assistantMessage,
                        "本轮 API 预算已耗尽，已停止后续工具步骤。发送下一条消息会重新计数。",
                        ChatAiLoop.buildTranscript(steps)
                    ),
                    ChatMessageState.Final
                )
                host.finishChat()
                return
            }
            val continueUserPrompt = if (softWarnHint.isNullOrBlank()) {
                normalizedPrompt
            } else {
                "$normalizedPrompt\n\n系统提示: $softWarnHint"
            }
            softWarnHint = null
            val continuePlan = AiAgentOrchestrator.continueAfterTool(
                settings = modelSettings,
                originalPrompt = continueUserPrompt,
                steps = steps,
                runtimeSnapshot = host.currentAiRuntimeSnapshot(),
                remainingTurns = remainingTurns,
                workingSummary = host.uiState.value.compressedMemory
            ).fold(
                onSuccess = { it },
                onFailure = { error ->
                    host.finishAiLoopTaskExecution(
                        success = false,
                        summary = "AI 续步失败，已停止工具循环。"
                    )
                    host.updateState { state ->
                        state.copy(latestAiStatus = "AI 续步失败，已回退原始结果")
                    }
                    host.updateChatMessage(
                        replyMessageId,
                        buildAssistantReply(
                            assistantMessage,
                            "模型续步失败：${error.message ?: error::class.java.simpleName}",
                            ChatAiLoop.buildTranscript(steps)
                        ),
                        ChatMessageState.Final
                    )
                    host.finishChat()
                    return
                }
            )

            when (continuePlan) {
                is AiAgentPlan.AssistantReply -> {
                    val allOk = steps.all { it.success }
                    host.finishAiLoopTaskExecution(
                        success = allOk,
                        summary = if (allOk) {
                            "AI 工具循环已完成。"
                        } else {
                            "AI 工具循环已结束：存在失败步骤。"
                        }
                    )
                    host.updateState { state -> state.copy(latestAiStatus = "AI 工具循环已完成") }
                    host.updateChatMessage(
                        replyMessageId,
                        buildAssistantReply(assistantMessage, continuePlan.message, ChatAiLoop.buildTranscript(steps)),
                        ChatMessageState.Final
                    )
                    host.finishChat()
                    return
                }

                is AiAgentPlan.ToolExecution -> {
                    tool = continuePlan.tool
                    arguments = continuePlan.arguments
                    assistantMessage = continuePlan.assistantMessage
                }
            }
        }
    }

    private suspend fun executeAgentsParallel(
        enrichedArgs: Map<String, String>,
        normalizedPrompt: String
    ): Pair<Boolean, String> {
        val rawIds = enrichedArgs.stringArg("agent_ids", "agents", "agent_id", "agent")
        val ids = rawIds
            .split(',', ';', '|', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (ids.isEmpty()) {
            return false to "失败: agent_ids 不能为空"
        }
        return coroutineScope {
            val deferred = ids.map { agentId ->
                async(Dispatchers.Default) {
                    host.appendAgentEvent(
                        AgentRunEvent(
                            kind = AgentRunEventKind.SubAgent,
                            title = "并行 Agent",
                            detail = "启动 $agentId",
                            subAgentName = agentId
                        )
                    )
                    val started = System.currentTimeMillis()
                    val reply = host.executeAgentById(
                        agentId = agentId,
                        arguments = enrichedArgs.mapValues { (_, value) -> value },
                        ensureTaskUi = false,
                        finishTaskUi = false,
                        bindRuntimeOnSubmit = false,
                        originPrompt = normalizedPrompt
                    )
                    val ok = !reply.startsWith("失败") && !reply.contains("任务中止")
                    host.appendAgentEvent(
                        AgentRunEvent(
                            kind = AgentRunEventKind.ToolResult,
                            title = "并行 Agent 结果",
                            detail = reply.take(160),
                            success = ok,
                            durationMs = System.currentTimeMillis() - started,
                            subAgentName = agentId
                        )
                    )
                    agentId to (ok to reply)
                }
            }
            val results = deferred.awaitAll()
            val allOk = results.all { it.second.first }
            val output = buildString {
                appendLine("并行 Agent 结果（${results.size}）：")
                results.forEach { (id, pair) ->
                    appendLine("--- $id (${if (pair.first) "ok" else "fail"}) ---")
                    appendLine(pair.second)
                }
            }
            allOk to output
        }
    }
}

private fun Map<String, String>.stringArg(vararg keys: String): String {
    return keys.firstNotNullOfOrNull { key ->
        this[key]?.trim()?.takeIf { it.isNotEmpty() }
    }.orEmpty()
}

private fun isRebootShellCommand(command: String?): Boolean {
    val normalized = command.orEmpty().trim().lowercase()
    return normalized == "reboot" || normalized == "svc power reboot"
}

private fun isRebootOrientedTask(arguments: Map<String, String>): Boolean {
    if (isRebootShellCommand(arguments["command"])) return true
    val blob = buildString {
        append(arguments["task_json"])
        append('\n')
        append(arguments["steps_json"])
        append('\n')
        append(arguments["steps"])
        append('\n')
        append(arguments["name"])
        append('\n')
        append(arguments["task_id"])
    }.lowercase()
    return blob.contains("\"command\":\"reboot\"") ||
        blob.contains("\"command\": \"reboot\"") ||
        blob.contains("svc power reboot") ||
        arguments["task_id"]?.contains("reboot", ignoreCase = true) == true ||
        arguments["name"]?.contains("reboot", ignoreCase = true) == true ||
        arguments["name"]?.contains("重启") == true
}

private fun buildAssistantReply(
    assistantMessage: String?,
    reflectionMessage: String?,
    result: String
): String {
    val lines = buildList {
        assistantMessage?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        reflectionMessage?.trim()?.takeIf {
            it.isNotEmpty() && it != assistantMessage?.trim() && it != result.trim()
        }?.let(::add)
        add(result)
    }
    return lines.joinToString("\n")
}
