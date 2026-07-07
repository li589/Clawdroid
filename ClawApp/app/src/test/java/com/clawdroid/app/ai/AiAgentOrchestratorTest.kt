package com.clawdroid.app.ai

import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.ui.ModelProvider
import com.clawdroid.app.ui.ModelSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAgentOrchestratorTest {
    @Test
    fun parseAgentPlanReturnsToolExecutionForStructuredToolJson() {
        val plan = AiAgentOrchestrator.parseAgentPlan(
            """
            {"mode":"tool","reply":"我先读取当前能力。","tool":"get_capabilities","arguments":{"source":"ai"},"reason":"用户在查询当前能力"}
            """.trimIndent()
        )

        assertTrue(plan is AiAgentPlan.ToolExecution)
        plan as AiAgentPlan.ToolExecution
        assertEquals(ClawTool.GET_CAPABILITIES, plan.tool)
        assertEquals("我先读取当前能力。", plan.assistantMessage)
        assertEquals("ai", plan.arguments["source"])
    }

    @Test
    fun parseAgentPlanFallsBackToAssistantReplyForPlainText() {
        val plan = AiAgentOrchestrator.parseAgentPlan("这是普通回复")

        assertEquals(
            AiAgentPlan.AssistantReply("这是普通回复"),
            plan
        )
    }

    @Test
    fun readinessSummaryReflectsConfiguredLocalModel() {
        val summary = AiAgentOrchestrator.readinessSummary(
            ModelSettings(
                provider = ModelProvider.Local,
                localEndpoint = "http://127.0.0.1:11434/v1",
                localModelName = "qwen2.5"
            )
        )

        assertTrue(summary.contains("AI 已就绪"))
        assertTrue(summary.contains("Local"))
    }

    @Test
    fun buildToolReflectionPromptIncludesToolAndOutput() {
        val prompt = AiAgentOrchestrator.buildToolReflectionPrompt(
            AiToolReflectionInput(
                originalPrompt = "帮我看看当前能力",
                tool = ClawTool.GET_CAPABILITIES,
                arguments = mapOf("source" to "ai"),
                toolResult = "成功: root=true, accessibility=true",
                runtimeSnapshot = AiRuntimeSnapshot(
                    sessionSummary = "ready",
                    capabilityStatus = "loaded",
                    eventStreaming = false
                )
            )
        )

        assertTrue(prompt.contains("帮我看看当前能力"))
        assertTrue(prompt.contains("get_capabilities / 读取能力列表"))
        assertTrue(prompt.contains("source=ai"))
        assertTrue(prompt.contains("成功: root=true, accessibility=true"))
    }
}
