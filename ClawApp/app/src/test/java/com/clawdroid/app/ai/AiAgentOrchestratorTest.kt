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
    fun parseAgentPlanRejectsUnknownArgsForSchemaTools() {
        val plan = AiAgentOrchestrator.parseAgentPlan(
            """
            {"mode":"tool","reply":"tap","tool":"inject_tap","arguments":{"x":"10","y":"20","evil":"1"},"reason":"bad"}
            """.trimIndent()
        )
        assertTrue(plan is AiAgentPlan.AssistantReply)
    }

    @Test
    fun parseAgentPlanAcceptsSchemaArgsForInjectTap() {
        val plan = AiAgentOrchestrator.parseAgentPlan(
            """
            {"mode":"tool","reply":"tap","tool":"inject_tap","arguments":{"x":"10","y":"20","display_id":"0"},"reason":"ok"}
            """.trimIndent()
        )
        assertTrue(plan is AiAgentPlan.ToolExecution)
        plan as AiAgentPlan.ToolExecution
        assertEquals(ClawTool.INJECT_TAP, plan.tool)
        assertEquals("10", plan.arguments["x"])
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
        assertTrue(summary.contains("本地模型"))
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

    @Test
    fun buildContinueUserPromptIncludesPriorStepsAndRemainingTurns() {
        val prompt = AiAgentOrchestrator.buildContinueUserPrompt(
            originalPrompt = "先探测再看能力",
            steps = listOf(
                AiToolStepRecord(
                    tool = ClawTool.PROBE_SESSION,
                    arguments = emptyMap(),
                    success = true,
                    output = "probe-ok"
                )
            ),
            remainingTurns = 2
        )

        assertTrue(prompt.contains("先探测再看能力"))
        assertTrue(prompt.contains("tool=probe_session success=true"))
        assertTrue(prompt.contains("probe-ok"))
        assertTrue(prompt.contains("剩余可继续工具轮次: 2"))
    }

    @Test
    fun buildContinueSystemPromptMentionsRemainingTurns() {
        val prompt = AiAgentOrchestrator.buildContinueSystemPrompt(
            runtimeSnapshot = AiRuntimeSnapshot(
                sessionSummary = "ready",
                capabilityStatus = "loaded",
                eventStreaming = false
            ),
            remainingTurns = 3
        )
        assertTrue(prompt.contains("剩余可继续工具轮次：3"))
        assertTrue(prompt.contains("mode=tool"))
        assertTrue(prompt.contains("mode=chat"))
    }

    @Test
    fun truncateStepOutputKeepsShortTextAndCutsLongText() {
        assertEquals("short", AiAgentOrchestrator.truncateStepOutput("short"))
        val long = "x".repeat(2500)
        val truncated = AiAgentOrchestrator.truncateStepOutput(long)
        assertTrue(truncated.contains("...(truncated)"))
        assertTrue(truncated.length < long.length)
    }

    @Test
    fun parseAgentPlanCanEndLoopWithChatMode() {
        val plan = AiAgentOrchestrator.parseAgentPlan(
            """{"mode":"chat","reply":"探测与能力均已完成。","tool":"","arguments":{},"reason":"done"}"""
        )
        assertEquals(AiAgentPlan.AssistantReply("探测与能力均已完成。"), plan)
    }
}
