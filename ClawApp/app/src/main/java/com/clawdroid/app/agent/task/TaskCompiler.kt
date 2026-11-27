package com.clawdroid.app.agent.task

import com.clawdroid.app.agent.AgentPlan
import com.clawdroid.app.agent.AgentPlanStep
import com.clawdroid.app.skills.AgentExecutionMode
import com.clawdroid.app.skills.AgentRuntimeBridge
import com.clawdroid.app.skills.ClawAgentDefinition
import org.json.JSONObject

object TaskCompiler {
    fun fromAgentPlan(
        taskId: String,
        title: String,
        plan: AgentPlan,
        originPrompt: String = title
    ): ExecutionTask = ExecutionTask(
        id = taskId,
        title = title,
        originPrompt = originPrompt,
        steps = plan.steps.map { step ->
            ExecutionStep(
                id = step.id,
                title = step.title,
                kind = when (step.kind) {
                    AgentPlanStep.Kind.AwaitRuntimeTask -> StepKind.RuntimeTask
                    AgentPlanStep.Kind.Tool,
                    AgentPlanStep.Kind.CatalogAgent,
                    AgentPlanStep.Kind.LocalAction -> StepKind.Tool
                },
                payload = step.payload,
                sideEffectKey = step.id
            )
        }
    )

    fun fromAgentDefinition(
        taskId: String,
        agent: ClawAgentDefinition,
        arguments: Map<String, Any?> = emptyMap()
    ): ExecutionTask? {
        if (agent.id == "confirm_then_safe_tap") {
            val confirm = JSONObject().apply {
                put("expected_package", arguments["expected_package"] ?: "")
                put("expected_text", arguments["expected_text"] ?: "")
                put("expected_view_id", arguments["expected_view_id"] ?: "")
            }.toString()
            val resolve = JSONObject().apply {
                put("expected_package", arguments["expected_package"] ?: "")
                put("target_text", arguments["target_text"] ?: arguments["expected_text"] ?: "")
                put("target_view_id", arguments["target_view_id"] ?: arguments["expected_view_id"] ?: "")
            }.toString()
            val tap = JSONObject().apply {
                put("expected_package", arguments["expected_package"] ?: "")
            }.toString()
            return ExecutionTask(
                id = taskId,
                title = agent.name,
                originPrompt = agent.id,
                steps = listOf(
                    ExecutionStep("confirm-page", "页面确认", StepKind.ConfirmPage, confirm),
                    ExecutionStep("resolve-target", "点击前检查", StepKind.ResolveTarget, resolve),
                    ExecutionStep("safe-tap", "安全点击", StepKind.SafeTap, tap, sideEffectKey = "$taskId:tap")
                )
            )
        }
        if (agent.executionMode == AgentExecutionMode.RuntimeTask) {
            val payload = AgentRuntimeBridge.buildStepsJson(agent, arguments) ?: return null
            return ExecutionTask(
                id = taskId,
                title = agent.name,
                originPrompt = agent.id,
                steps = listOf(
                    ExecutionStep(
                        id = "runtime-sequence",
                        title = agent.name,
                        kind = StepKind.RuntimeTask,
                        payload = payload,
                        sideEffectKey = taskId
                    )
                )
            )
        }
        val plan = AgentPlan(
            steps = agent.steps.mapIndexed { index, stepId ->
                AgentPlanStep(
                    id = "${agent.id}-$index",
                    title = agent.stepTitles.getOrElse(index) { stepId },
                    kind = AgentPlanStep.Kind.Tool,
                    payload = stepId
                )
            }
        )
        return fromAgentPlan(taskId, agent.name, plan, originPrompt = agent.id)
    }
}
