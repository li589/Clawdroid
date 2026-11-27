package com.clawdroid.app.agent.task

import com.clawdroid.app.agent.AgentPlan
import com.clawdroid.app.agent.AgentPlanStep
import com.clawdroid.app.skills.ClawAgentCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun compilerBuildsSingleRuntimeSequenceForCatalogAgent() {
        val agent = requireNotNull(ClawAgentCatalog.byId("runtime_health_sweep"))

        val task = requireNotNull(TaskCompiler.fromAgentDefinition("t1", agent))

        assertEquals(StepKind.RuntimeTask, task.steps.single().kind)
        assertTrue(task.steps.single().payload.contains("get_capabilities"))
    }

    @Test
    fun confirmThenSafeTapCompilesAutomationSteps() {
        val agent = requireNotNull(ClawAgentCatalog.byId("confirm_then_safe_tap"))

        val task = requireNotNull(
            TaskCompiler.fromAgentDefinition(
                "t1",
                agent,
                mapOf(
                    "expected_package" to "com.example.app",
                    "expected_text" to "页面",
                    "expected_view_id" to "root",
                    "target_text" to "继续",
                    "target_view_id" to "continue"
                )
            )
        )

        assertEquals(
            listOf(StepKind.ConfirmPage, StepKind.ResolveTarget, StepKind.SafeTap),
            task.steps.map { it.kind }
        )
        assertTrue(task.steps.first().payload.contains("com.example.app"))
        assertTrue(task.steps[1].payload.contains("continue"))
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
