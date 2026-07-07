package com.clawdroid.app.chat

import com.clawdroid.app.ai.AiAgentPlan
import com.clawdroid.app.ai.AiRuntimeSnapshot
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.ui.ModelSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptPlannerTest {
    @Test
    fun planReturnsDirectToolExecutionForRuleMatchedPrompt() = runBlocking {
        val plan = ChatPromptPlanner.plan(
            context = plannerContext("获取能力")
        ) { _, _, _ ->
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
            context = plannerContext("帮我看看屏幕尺寸")
        ) { _, _, _ ->
            error("local action should not call AI planner")
        }

        assertEquals(
            ChatPromptPlan.LocalActionExecution(
                action = ChatLocalAction.ReadScreenSize,
                assistantMessage = "正在读取屏幕尺寸...",
                aiStatus = "规则动作: 屏幕尺寸"
            ),
            plan
        )
    }

    @Test
    fun planReturnsTaskExecutionForConfirmThenSafeTapPrompt() = runBlocking {
        val plan = ChatPromptPlanner.plan(
            context = plannerContext("请先确认页面再安全点击")
        ) { _, _, _ ->
            error("task execution should not call AI planner")
        }

        assertEquals(
            ChatPromptPlan.TaskExecution(
                action = ChatTaskAction.ConfirmThenSafeTap,
                assistantMessage = "正在按“页面确认 -> 点击前检查 -> 安全点击”执行任务...",
                aiStatus = "规则任务: 页面确认后安全点击"
            ),
            plan
        )
    }

    @Test
    fun planReturnsTaskExecutionForProbeThenCapabilitiesPrompt() = runBlocking {
        val plan = ChatPromptPlanner.plan(
            context = plannerContext("帮我先探测再获取能力")
        ) { _, _, _ ->
            error("task execution should not call AI planner")
        }

        assertEquals(
            ChatPromptPlan.TaskExecution(
                action = ChatTaskAction.ProbeThenCapabilities,
                assistantMessage = "正在按“Runtime Probe -> 获取能力”执行任务...",
                aiStatus = "规则任务: 运行时状态检查"
            ),
            plan
        )
    }

    @Test
    fun planReturnsAiToolExecutionAndEnablesReflection() = runBlocking {
        val plan = ChatPromptPlanner.plan(
            context = plannerContext("帮我判断一下当前运行时都支持哪些特性")
        ) { _: ModelSettings, prompt: String, snapshot: AiRuntimeSnapshot ->
            assertEquals("帮我判断一下当前运行时都支持哪些特性", prompt)
            assertEquals("session-ready", snapshot.sessionSummary)
            Result.success(
                AiAgentPlan.ToolExecution(
                    tool = ClawTool.GET_CAPABILITIES,
                    arguments = mapOf("source" to "ai"),
                    assistantMessage = "我先读取能力列表。",
                    reasoning = "用户在询问当前能力"
                )
            )
        }

        assertTrue(plan is ChatPromptPlan.ToolExecution)
        plan as ChatPromptPlan.ToolExecution
        assertEquals(ClawTool.GET_CAPABILITIES, plan.tool)
        assertEquals("ai", plan.arguments["source"])
        assertTrue(plan.reflectResultWithModel)
        assertEquals("AI 决策工具: 读取能力列表", plan.aiStatus)
    }

    @Test
    fun planFallsBackToAssistantReplyWhenAiPlannerFails() = runBlocking {
        val plan = ChatPromptPlanner.plan(
            context = plannerContext("解释一下当前状态")
        ) { _, _, _ ->
            Result.failure(IllegalStateException("network unavailable"))
        }

        assertTrue(plan is ChatPromptPlan.AssistantReply)
        plan as ChatPromptPlan.AssistantReply
        assertTrue(plan.message.contains("模型请求失败"))
        assertEquals("AI 请求失败", plan.aiStatus)
    }

    private fun plannerContext(prompt: String): ChatPlannerContext {
        return ChatPlannerContext(
            prompt = prompt,
            modelSettings = ModelSettings(),
            sessionSummary = "session-ready",
            capabilityStatus = "capability-ready",
            eventStreaming = false
        )
    }
}
