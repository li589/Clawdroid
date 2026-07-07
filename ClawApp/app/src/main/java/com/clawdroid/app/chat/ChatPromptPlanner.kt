package com.clawdroid.app.chat

import com.clawdroid.app.ai.AiAgentOrchestrator
import com.clawdroid.app.ai.AiAgentPlan
import com.clawdroid.app.ai.AiRuntimeSnapshot
import com.clawdroid.app.orchestrator.DirectCommandOrchestrator
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.ui.ModelSettings

internal data class ChatPlannerContext(
    val prompt: String,
    val modelSettings: ModelSettings,
    val sessionSummary: String,
    val capabilityStatus: String,
    val eventStreaming: Boolean
)

internal enum class ChatLocalAction {
    SafeTap,
    ReadScreenSize
}

internal enum class ChatTaskAction {
    ConfirmThenSafeTap,
    ProbeThenCapabilities
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
                            normalizedPrompt,
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
}
