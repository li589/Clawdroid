package com.clawdroid.app.chat

import com.clawdroid.app.ai.AiAgentPlan
import com.clawdroid.app.ai.AiRuntimeSnapshot
import com.clawdroid.app.model.ModelUserImage
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.data.model.ModelSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptPlannerTest {
    @Test
    fun planReturnsDirectToolExecutionForRuleMatchedPrompt() = runBlocking {
        val plan = ChatPromptPlanner.plan(
            context = plannerContext("\u83b7\u53d6\u80fd\u529b")
        ) { _, _, _, _ ->
            error("rule-matched prompt should not call AI planner")
        }

        assertTrue(plan is ChatPromptPlan.ToolExecution)
        plan as ChatPromptPlan.ToolExecution
        assertEquals(ClawTool.GET_CAPABILITIES, plan.tool)
        assertFalse(plan.reflectResultWithModel)
    }

    @Test
    fun planReturnsLocalActionForScreenSizePrompt() = runBlocking {
        val plan = ChatPromptPlanner.plan(
            context = plannerContext("\u5e2e\u6211\u770b\u770b\u5c4f\u5e55\u5c3a\u5bf8")
        ) { _, _, _, _ ->
            error("local action should not call AI planner")
        }

        assertEquals(
            ChatPromptPlan.LocalActionExecution(
                action = ChatLocalAction.ReadScreenSize,
                assistantMessage = "\u6b63\u5728\u8bfb\u53d6\u5c4f\u5e55\u5c3a\u5bf8...",
                aiStatus = "\u89c4\u5219\u52a8\u4f5c: \u5c4f\u5e55\u5c3a\u5bf8"
            ),
            plan
        )
    }

    @Test
    fun planReturnsTaskExecutionForConfirmThenSafeTapPrompt() = runBlocking {
        val plan = ChatPromptPlanner.plan(
            context = plannerContext("\u8bf7\u5148\u786e\u8ba4\u9875\u9762\u518d\u5b89\u5168\u70b9\u51fb")
        ) { _, _, _, _ ->
            error("task execution should not call AI planner")
        }

        assertEquals(
            ChatPromptPlan.TaskExecution(
                action = ChatTaskAction.ConfirmThenSafeTap,
                assistantMessage = "\u6b63\u5728\u6309\u201c\u9875\u9762\u786e\u8ba4 -> \u70b9\u51fb\u524d\u68c0\u67e5 -> \u5b89\u5168\u70b9\u51fb\u201d\u6267\u884c\u4efb\u52a1...",
                aiStatus = "\u89c4\u5219\u4efb\u52a1: \u9875\u9762\u786e\u8ba4\u540e\u5b89\u5168\u70b9\u51fb"
            ),
            plan
        )
    }

    @Test
    fun planReturnsTaskExecutionForProbeThenCapabilitiesPrompt() = runBlocking {
        val plan = ChatPromptPlanner.plan(
            context = plannerContext("\u5e2e\u6211\u5148\u63a2\u6d4b\u518d\u83b7\u53d6\u80fd\u529b")
        ) { _, _, _, _ ->
            error("task execution should not call AI planner")
        }

        assertEquals(
            ChatPromptPlan.TaskExecution(
                action = ChatTaskAction.ProbeThenCapabilities,
                assistantMessage = "\u6b63\u5728\u6309\u201cRuntime Probe -> \u83b7\u53d6\u80fd\u529b\u201d\u6267\u884c\u4efb\u52a1...",
                aiStatus = "\u89c4\u5219\u4efb\u52a1: \u8fd0\u884c\u65f6\u72b6\u6001\u68c0\u67e5"
            ),
            plan
        )
    }

    @Test
    fun planReturnsTaskExecutionForCaptureThenPreviewPrompt() = runBlocking {
        val plan = ChatPromptPlanner.plan(
            context = plannerContext("\u5e2e\u6211\u622a\u56fe\u5e76\u9884\u89c8")
        ) { _, _, _, _ ->
            error("task execution should not call AI planner")
        }

        assertEquals(
            ChatPromptPlan.TaskExecution(
                action = ChatTaskAction.CaptureThenPreview,
                assistantMessage = "\u6b63\u5728\u6309\u201c\u622a\u56fe -> \u9884\u89c8\u201d\u6267\u884c\u4efb\u52a1...",
                aiStatus = "\u89c4\u5219\u4efb\u52a1: \u622a\u56fe\u5e76\u9884\u89c8"
            ),
            plan
        )
    }

    @Test
    fun planReturnsTaskExecutionForRuntimeHealthSweepPrompt() = runBlocking {
        val plan = ChatPromptPlanner.plan(
            context = plannerContext("\u5e2e\u6211\u505a\u4e00\u6b21\u8fd0\u884c\u65f6\u4f53\u68c0")
        ) { _, _, _, _ ->
            error("task execution should not call AI planner")
        }

        assertEquals(
            ChatPromptPlan.TaskExecution(
                action = ChatTaskAction.RuntimeHealthSweep,
                assistantMessage = "\u6b63\u5728\u6309\u201cPing -> Runtime Status -> \u83b7\u53d6\u80fd\u529b\u201d\u6267\u884c\u4efb\u52a1...",
                aiStatus = "\u89c4\u5219\u4efb\u52a1: \u8fd0\u884c\u65f6\u4f53\u68c0"
            ),
            plan
        )
    }

    @Test
    fun planReturnsTaskExecutionForSwipeThenCapturePrompt() = runBlocking {
        val plan = ChatPromptPlanner.plan(
            context = plannerContext("\u6ed1\u52a8\u540e\u622a\u56fe")
        ) { _, _, _, _ ->
            error("task execution should not call AI planner")
        }

        assertEquals(
            ChatPromptPlan.TaskExecution(
                action = ChatTaskAction.SwipeThenCapture,
                assistantMessage = "\u6b63\u5728\u6309\u201c\u6ed1\u52a8 -> \u622a\u56fe -> \u9884\u89c8\u201d\u6267\u884c\u4efb\u52a1...",
                aiStatus = "\u89c4\u5219\u4efb\u52a1: \u6ed1\u52a8\u540e\u622a\u56fe"
            ),
            plan
        )
    }

    @Test
    fun planReturnsAiToolExecutionAndEnablesReflection() = runBlocking {
        val userPrompt =
            "\u5e2e\u6211\u5224\u65ad\u4e00\u4e0b\u5f53\u524d\u8fd0\u884c\u65f6\u90fd\u652f\u6301\u54ea\u4e9b\u7279\u6027"
        val plan = ChatPromptPlanner.plan(
            context = plannerContext(userPrompt)
        ) { _: ModelSettings, prompt: String, snapshot: AiRuntimeSnapshot, _: ModelUserImage? ->
            assertEquals(userPrompt, prompt)
            assertEquals("session-ready", snapshot.sessionSummary)
            Result.success(
                AiAgentPlan.ToolExecution(
                    tool = ClawTool.GET_CAPABILITIES,
                    arguments = mapOf("source" to "ai"),
                    assistantMessage = "\u6211\u5148\u8bfb\u53d6\u80fd\u529b\u5217\u8868\u3002",
                    reasoning = "\u7528\u6237\u5728\u8be2\u95ee\u5f53\u524d\u80fd\u529b"
                )
            )
        }

        assertTrue(plan is ChatPromptPlan.ToolExecution)
        plan as ChatPromptPlan.ToolExecution
        assertEquals(ClawTool.GET_CAPABILITIES, plan.tool)
        assertEquals("ai", plan.arguments["source"])
        assertTrue(plan.reflectResultWithModel)
        assertEquals(
            "AI \u51b3\u7b56\u5de5\u5177: \u8bfb\u53d6\u80fd\u529b\u5217\u8868",
            plan.aiStatus
        )
    }

    @Test
    fun planRoutesConversationalCapabilityPromptToAi() = runBlocking {
        var aiCalled = false
        val plan = ChatPromptPlanner.plan(
            context = plannerContext("\u5e2e\u6211\u770b\u770b\u73b0\u5728\u6709\u54ea\u4e9b\u80fd\u529b")
        ) { _, _, _, _ ->
            aiCalled = true
            Result.success(AiAgentPlan.AssistantReply("ok"))
        }
        assertTrue(aiCalled)
        assertTrue(plan is ChatPromptPlan.AssistantReply)
    }

    @Test
    fun planFallsBackToAssistantReplyWhenAiPlannerFails() = runBlocking {
        val plan = ChatPromptPlanner.plan(
            context = plannerContext("\u89e3\u91ca\u4e00\u4e0b\u5f53\u524d\u72b6\u6001")
        ) { _, _, _, _ ->
            Result.failure(IllegalStateException("network unavailable"))
        }

        assertTrue(plan is ChatPromptPlan.AssistantReply)
        plan as ChatPromptPlan.AssistantReply
        assertTrue(plan.message.contains("\u6a21\u578b\u8bf7\u6c42\u5931\u8d25"))
        assertEquals("AI \u8bf7\u6c42\u5931\u8d25", plan.aiStatus)
    }

    @Test
    fun buildAiPromptWithHistoryIncludesRecentTurns() {
        val prompt = ChatPromptPlanner.buildAiPromptWithHistory(
            currentPrompt = "ping again",
            recentChat = listOf(
                ChatHistoryTurn(role = "user", content = "probe first"),
                ChatHistoryTurn(role = "assistant", content = "probe done")
            )
        )
        assertTrue(prompt.contains("probe first"))
        assertTrue(prompt.contains("probe done"))
        assertTrue(prompt.contains("ping again"))
    }

    @Test
    fun buildAiPromptWithHistoryReturnsRawPromptWhenEmpty() {
        assertEquals(
            "alone",
            ChatPromptPlanner.buildAiPromptWithHistory(
                currentPrompt = "alone",
                recentChat = emptyList()
            )
        )
    }

    @Test
    fun planPassesHistoryIntoAiPlannerPrompt() = runBlocking {
        var capturedPrompt = ""
        val plan = ChatPromptPlanner.plan(
            context = plannerContext(
                prompt = "cancel previous",
                recentChat = listOf(
                    ChatHistoryTurn(role = "user", content = "submit runtime task"),
                    ChatHistoryTurn(role = "assistant", content = "submitted task-9")
                )
            )
        ) { _, prompt, _, _ ->
            capturedPrompt = prompt
            Result.success(AiAgentPlan.AssistantReply("ok"))
        }

        assertTrue(plan is ChatPromptPlan.AssistantReply)
        assertTrue(capturedPrompt.contains("submit runtime task"))
        assertTrue(capturedPrompt.contains("task-9"))
        assertTrue(capturedPrompt.contains("cancel previous"))
    }

    private fun plannerContext(
        prompt: String,
        recentChat: List<ChatHistoryTurn> = emptyList()
    ): ChatPlannerContext {
        return ChatPlannerContext(
            prompt = prompt,
            modelSettings = ModelSettings(),
            sessionSummary = "session-ready",
            capabilityStatus = "capability-ready",
            eventStreaming = false,
            recentChat = recentChat
        )
    }
}
