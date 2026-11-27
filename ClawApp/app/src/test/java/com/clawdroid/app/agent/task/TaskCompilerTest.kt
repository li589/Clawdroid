package com.clawdroid.app.agent.task

import com.clawdroid.app.agent.AgentPlan
import com.clawdroid.app.agent.AgentPlanStep
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskCompilerTest {
    @Test
    fun compilerPreservesStepOrder() {
        val plan = AgentPlan(
            steps = listOf(
                AgentPlanStep("1", "Ping", AgentPlanStep.Kind.Tool, "ping"),
                AgentPlanStep("2", "Capabilities", AgentPlanStep.Kind.Tool, "get_capabilities")
            )
        )

        val task = TaskCompiler.fromAgentPlan("t1", "health", plan)

        assertEquals(listOf("ping", "get_capabilities"), task.steps.map { it.payload })
        assertEquals(listOf(StepKind.Tool, StepKind.Tool), task.steps.map { it.kind })
    }

    @Test
    fun compilerMapsAwaitRuntimeStep() {
        val plan = AgentPlan(
            steps = listOf(
                AgentPlanStep("1", "Runtime", AgentPlanStep.Kind.AwaitRuntimeTask, "runtime-1")
            )
        )

        val task = TaskCompiler.fromAgentPlan("t1", "runtime", plan)

        assertEquals(StepKind.RuntimeTask, task.steps.single().kind)
        assertEquals("runtime-1", task.steps.single().payload)
    }
}
