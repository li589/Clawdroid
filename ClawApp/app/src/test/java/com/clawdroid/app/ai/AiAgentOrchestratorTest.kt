package com.clawdroid.app.ai

import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.data.model.ModelProvider
import com.clawdroid.app.data.model.ModelSettings
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

    @Test
    fun parseAgentPlanStripsMarkdownFence() {
        val plan = AiAgentOrchestrator.parseAgentPlan(
            """
            ```json
            {"mode":"tool","reply":"截图","tool":"capture_screen","arguments":{"read_after_capture":true},"reason":"ok"}
            ```
            """.trimIndent()
        )
        assertTrue(plan is AiAgentPlan.ToolExecution)
        plan as AiAgentPlan.ToolExecution
        assertEquals(ClawTool.CAPTURE_SCREEN, plan.tool)
        assertEquals("true", plan.arguments["read_after_capture"])
    }

    @Test
    fun parseAgentPlanExtractsJsonWrappedInProse() {
        val plan = AiAgentOrchestrator.parseAgentPlan(
            """
            好的，我来执行：
            {"mode":"tool","reply":"点击","tool":"inject_tap","arguments":{"x":540,"y":1200,"display_id":0},"reason":"tap"}
            以上是计划。
            """.trimIndent()
        )
        assertTrue(plan is AiAgentPlan.ToolExecution)
        plan as AiAgentPlan.ToolExecution
        assertEquals(ClawTool.INJECT_TAP, plan.tool)
        assertEquals("540", plan.arguments["x"])
        assertEquals("1200", plan.arguments["y"])
        assertEquals("0", plan.arguments["display_id"])
    }

    @Test
    fun parseAgentPlanSupportsNestedArgumentObjects() {
        val plan = AiAgentOrchestrator.parseAgentPlan(
            """
            {"mode":"tool","reply":"提交任务","tool":"task_submit","arguments":{"task_id":"t1","steps_json":[{"action":"ping","args":{}}]},"reason":"nested"}
            """.trimIndent()
        )
        assertTrue(plan is AiAgentPlan.ToolExecution)
        plan as AiAgentPlan.ToolExecution
        assertEquals(ClawTool.TASK_SUBMIT, plan.tool)
        assertEquals("t1", plan.arguments["task_id"])
        val steps = plan.arguments["steps_json"].orEmpty()
        assertTrue(steps.contains("\"action\":\"ping\"") || steps.contains("\"action\": \"ping\""))
    }

    @Test
    fun parseAgentPlanSupportsAssistCallWithObjectArgumentsJson() {
        val plan = AiAgentOrchestrator.parseAgentPlan(
            """
            {"mode":"tool","reply":"调用电脑工具","tool":"assist_call_tool","arguments":{"name":"demo_tool","arguments_json":{"q":"hello"}},"reason":"assist"}
            """.trimIndent()
        )
        assertTrue(plan is AiAgentPlan.ToolExecution)
        plan as AiAgentPlan.ToolExecution
        assertEquals(ClawTool.ASSIST_CALL_TOOL, plan.tool)
        assertEquals("demo_tool", plan.arguments["name"])
        assertTrue(plan.arguments["arguments_json"].orEmpty().contains("hello"))
    }

    @Test
    fun jsonValueToArgumentStringNormalizesTypes() {
        assertEquals("true", AiAgentOrchestrator.jsonValueToArgumentString(true))
        assertEquals("42", AiAgentOrchestrator.jsonValueToArgumentString(42))
        assertEquals("42", AiAgentOrchestrator.jsonValueToArgumentString(42.0))
        assertEquals("""{"a":1}""", AiAgentOrchestrator.jsonValueToArgumentString(org.json.JSONObject("""{"a":1}""")))
    }

    @Test
    fun planFromNativeToolCallMapsOpenAiStyleArguments() {
        val plan = AiAgentOrchestrator.planFromNativeToolCall(
            call = com.clawdroid.app.model.ModelToolCall(
                id = "call_1",
                name = "inject_tap",
                argumentsJson = """{"x":10,"y":20,"display_id":0}"""
            ),
            assistantHint = "我先点击。"
        )
        assertTrue(plan is AiAgentPlan.ToolExecution)
        plan as AiAgentPlan.ToolExecution
        assertEquals(ClawTool.INJECT_TAP, plan.tool)
        assertEquals("10", plan.arguments["x"])
        assertEquals("20", plan.arguments["y"])
        assertEquals("我先点击。", plan.assistantMessage)
        assertEquals("native_tool_call", plan.reasoning)
    }

    @Test
    fun planFromGenerationPrefersNativeToolCallsOverTextJson() {
        val plan = AiAgentOrchestrator.planFromGeneration(
            com.clawdroid.app.model.ModelGenerationResult(
                text = """{"mode":"chat","reply":"忽略这段","tool":"","arguments":{},"reason":"x"}""",
                toolCalls = listOf(
                    com.clawdroid.app.model.ModelToolCall(
                        name = "get_capabilities",
                        argumentsJson = """{"source":"ai"}"""
                    )
                )
            )
        )
        assertTrue(plan is AiAgentPlan.ToolExecution)
        plan as AiAgentPlan.ToolExecution
        assertEquals(ClawTool.GET_CAPABILITIES, plan.tool)
    }
}
