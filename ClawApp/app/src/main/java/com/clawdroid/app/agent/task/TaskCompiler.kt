package com.clawdroid.app.agent.task

import com.clawdroid.app.agent.AgentPlan
import com.clawdroid.app.agent.AgentPlanStep

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
}
