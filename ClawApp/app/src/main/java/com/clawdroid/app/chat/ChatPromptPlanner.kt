package com.clawdroid.app.chat

import com.clawdroid.app.ai.AiAgentOrchestrator
import com.clawdroid.app.ai.AiAgentPlan
import com.clawdroid.app.ai.AiRuntimeSnapshot
import com.clawdroid.app.orchestrator.DirectCommandOrchestrator
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.ui.ModelSettings

internal data class ChatHistoryTurn(
    val role: String,
    val content: String
)

internal data class ChatPlannerContext(
    val prompt: String,
    val modelSettings: ModelSettings,
    val sessionSummary: String,
    val capabilityStatus: String,
    val eventStreaming: Boolean,
    val recentChat: List<ChatHistoryTurn> = emptyList()
)

internal enum class ChatLocalAction {
    SafeTap,
    ReadScreenSize
}

internal enum class ChatTaskAction {
    ConfirmThenSafeTap,
    ProbeThenCapabilities,
    CaptureThenPreview,
    RuntimeHealthSweep,
    SwipeThenCapture
}

internal sealed interface ChatPromptPlan {
    data class AssistantReply(
        val message: String,
        val aiStatus: String
    ) : ChatPromptPlan

    data class ToolExecution(
        val tool: ClawTool,
        val arguments: Map<String, String>,
        val assistantMessage: String? = null,
        val aiStatus: String,
        val reflectResultWithModel: Boolean
    ) : ChatPromptPlan

    data class LocalActionExecution(
        val action: ChatLocalAction,
        val assistantMessage: String,
        val aiStatus: String
    ) : ChatPromptPlan

    data class TaskExecution(
        val action: ChatTaskAction,
        val assistantMessage: String,
        val aiStatus: String
    ) : ChatPromptPlan
}

internal object ChatPromptPlanner {
    suspend fun plan(
        context: ChatPlannerContext,
        aiPlanner: suspend (ModelSettings, String, AiRuntimeSnapshot) -> Result<AiAgentPlan> = {
                settings,
                prompt,
                snapshot ->
            AiAgentOrchestrator.plan(
                settings = settings,
                prompt = prompt,
                runtimeSnapshot = snapshot
            )
        }
    ): ChatPromptPlan {
        val normalizedPrompt = context.prompt.trim()
        if (normalizedPrompt.isBlank()) {
            return ChatPromptPlan.AssistantReply(
                message = "请输入要执行的内容。",
                aiStatus = "空输入"
            )
        }

        when {
            shouldConfirmThenSafeTap(normalizedPrompt) -> {
                return ChatPromptPlan.TaskExecution(
                    action = ChatTaskAction.ConfirmThenSafeTap,
                    assistantMessage = "正在按“页面确认 -> 点击前检查 -> 安全点击”执行任务...",
                    aiStatus = "规则任务: 页面确认后安全点击"
                )
            }

            shouldProbeThenReadCapabilities(normalizedPrompt) -> {
                return ChatPromptPlan.TaskExecution(
                    action = ChatTaskAction.ProbeThenCapabilities,
                    assistantMessage = "正在按“Runtime Probe -> 获取能力”执行任务...",
                    aiStatus = "规则任务: 运行时状态检查"
                )
            }

            shouldCaptureThenPreview(normalizedPrompt) -> {
                return ChatPromptPlan.TaskExecution(
                    action = ChatTaskAction.CaptureThenPreview,
                    assistantMessage = "正在按“截图 -> 预览”执行任务...",
                    aiStatus = "规则任务: 截图并预览"
                )
            }

            shouldRuntimeHealthSweep(normalizedPrompt) -> {
                return ChatPromptPlan.TaskExecution(
                    action = ChatTaskAction.RuntimeHealthSweep,
                    assistantMessage = "正在按“Ping -> Runtime Status -> 获取能力”执行任务...",
                    aiStatus = "规则任务: 运行时体检"
                )
            }

            shouldSwipeThenCapture(normalizedPrompt) -> {
                return ChatPromptPlan.TaskExecution(
                    action = ChatTaskAction.SwipeThenCapture,
                    assistantMessage = "正在按“滑动 -> 截图 -> 预览”执行任务...",
                    aiStatus = "规则任务: 滑动后截图"
                )
            }
        }

        return when (val intent = DirectCommandOrchestrator.parse(normalizedPrompt)) {
            DirectCommandOrchestrator.ParsedIntent.None -> {
                ChatPromptPlan.AssistantReply(
                    message = "未识别到有效指令。",
                    aiStatus = "未识别指令"
                )
            }

            is DirectCommandOrchestrator.ParsedIntent.ToolCall -> {
                ChatPromptPlan.ToolExecution(
                    tool = intent.tool,
                    arguments = intent.arguments,
                    aiStatus = "规则指令命中: ${intent.tool.displayName}",
                    reflectResultWithModel = false
                )
            }

            is DirectCommandOrchestrator.ParsedIntent.RawText -> {
                when {
                    intent.text == "安全点击" || normalizedPrompt.contains("执行点击") -> {
                        ChatPromptPlan.LocalActionExecution(
                            action = ChatLocalAction.SafeTap,
                            assistantMessage = "正在执行安全点击...",
                            aiStatus = "规则动作: 安全点击"
                        )
                    }

                    normalizedPrompt.contains("wm size", ignoreCase = true) ||
                        normalizedPrompt.contains("屏幕尺寸") -> {
                        ChatPromptPlan.LocalActionExecution(
                            action = ChatLocalAction.ReadScreenSize,
                            assistantMessage = "正在读取屏幕尺寸...",
                            aiStatus = "规则动作: 屏幕尺寸"
                        )
                    }

                    else -> {
                        aiPlanner(
                            context.modelSettings,
                            buildAiPromptWithHistory(normalizedPrompt, context.recentChat),
                            AiRuntimeSnapshot(
                                sessionSummary = context.sessionSummary,
                                capabilityStatus = context.capabilityStatus,
                                eventStreaming = context.eventStreaming
                            )
                        ).fold(
                            onSuccess = { plan ->
                                when (plan) {
                                    is AiAgentPlan.AssistantReply -> {
                                        ChatPromptPlan.AssistantReply(
                                            message = plan.message,
                                            aiStatus = "AI 直接回复"
                                        )
                                    }

                                    is AiAgentPlan.ToolExecution -> {
                                        ChatPromptPlan.ToolExecution(
                                            tool = plan.tool,
                                            arguments = plan.arguments,
                                            assistantMessage = plan.assistantMessage,
                                            aiStatus = "AI 决策工具: ${plan.tool.displayName}",
                                            reflectResultWithModel = true
                                        )
                                    }
                                }
                            },
                            onFailure = { error ->
                                ChatPromptPlan.AssistantReply(
                                    message = "模型请求失败：${error.message ?: error::class.java.simpleName}\n你也可以试试：ping、获取能力、截图、确认页面、点击前检查。",
                                    aiStatus = "AI 请求失败"
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    internal fun buildAiPromptWithHistory(
        currentPrompt: String,
        recentChat: List<ChatHistoryTurn>,
        maxTurns: Int = 6,
        maxCharsPerTurn: Int = ChatTextLimits.MAX_HISTORY_TURN_CHARS
    ): String {
        if (recentChat.isEmpty()) {
            return currentPrompt
        }
        return buildString {
            appendLine("最近对话（供理解指代，不要复述）：")
            recentChat.takeLast(maxTurns).forEach { turn ->
                val clipped = ChatTextLimits.truncateForContext(turn.content, maxCharsPerTurn)
                if (clipped.isNotBlank()) {
                    appendLine("- ${turn.role}: $clipped")
                }
            }
            appendLine()
            appendLine("当前用户请求：")
            append(currentPrompt.trim())
        }
    }

    private fun shouldConfirmThenSafeTap(prompt: String): Boolean {
        return prompt.contains("确认页面后安全点击") ||
            (prompt.contains("确认页面") && prompt.contains("安全点击"))
    }

    private fun shouldProbeThenReadCapabilities(prompt: String): Boolean {
        return prompt.contains("检查运行时状态") ||
            prompt.contains("运行时状态检查") ||
            prompt.contains("先探测再获取能力") ||
            prompt.contains("探测并获取能力") ||
            prompt.contains("probe后获取能力") ||
            prompt.contains("probe 后获取能力")
    }

    private fun shouldCaptureThenPreview(prompt: String): Boolean {
        return prompt.contains("截图并预览") ||
            prompt.contains("截屏并预览") ||
            prompt.contains("截图预览") ||
            prompt.contains("截图后预览") ||
            prompt.contains("截图并读取")
    }

    private fun shouldRuntimeHealthSweep(prompt: String): Boolean {
        return prompt.contains("完整运行时体检") ||
            prompt.contains("运行时体检") ||
            prompt.contains("Runtime 体检") ||
            prompt.contains("runtime体检") ||
            (prompt.contains("ping") && prompt.contains("健康") && prompt.contains("能力"))
    }

    private fun shouldSwipeThenCapture(prompt: String): Boolean {
        return prompt.contains("滑动后截图") ||
            prompt.contains("滑动并截图") ||
            prompt.contains("滑动后截屏") ||
            (prompt.contains("滑动") && prompt.contains("截图"))
    }
}
