package com.clawdroid.app.ai

import com.clawdroid.app.model.ModelApiClient
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.ui.ModelProvider
import com.clawdroid.app.ui.ModelSettings

internal data class AiRuntimeSnapshot(
    val sessionSummary: String,
    val capabilityStatus: String,
    val eventStreaming: Boolean
)

internal data class AiToolReflectionInput(
    val originalPrompt: String,
    val tool: ClawTool,
    val arguments: Map<String, String>,
    val toolResult: String,
    val runtimeSnapshot: AiRuntimeSnapshot
)

internal sealed interface AiAgentPlan {
    data class ToolExecution(
        val tool: ClawTool,
        val arguments: Map<String, String>,
        val assistantMessage: String,
        val reasoning: String
    ) : AiAgentPlan

    data class AssistantReply(
        val message: String
    ) : AiAgentPlan
}

internal object AiAgentOrchestrator {
    suspend fun plan(
        settings: ModelSettings,
        prompt: String,
        runtimeSnapshot: AiRuntimeSnapshot
    ): Result<AiAgentPlan> {
        val normalizedPrompt = prompt.trim()
        if (normalizedPrompt.isBlank()) {
            return Result.success(AiAgentPlan.AssistantReply("请输入要执行的内容。"))
        }
        return ModelApiClient.generateReply(
            settings = settings,
            prompt = normalizedPrompt,
            systemPrompt = buildSystemPrompt(runtimeSnapshot)
        ).map { rawReply ->
            parseAgentPlan(rawReply)
        }
    }

    internal fun parseAgentPlan(rawReply: String): AiAgentPlan {
        val trimmed = rawReply.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return AiAgentPlan.AssistantReply(trimmed)
        }

        val mode = extractStringField(trimmed, "mode").orEmpty().trim().lowercase()
        val reply = extractStringField(trimmed, "reply").orEmpty().trim()
        if (mode != "tool") {
            return AiAgentPlan.AssistantReply(
                message = reply.ifBlank { trimmed }
            )
        }

        val toolId = extractStringField(trimmed, "tool").orEmpty().trim()
        val tool = ClawTool.byToolId(toolId)
            ?: return AiAgentPlan.AssistantReply(
                message = reply.ifBlank { "模型返回了未知工具 `$toolId`，请改用明确指令。" }
            )

        val arguments = extractArguments(trimmed)

        return AiAgentPlan.ToolExecution(
            tool = tool,
            arguments = arguments,
            assistantMessage = reply.ifBlank { defaultAssistantMessage(tool) },
            reasoning = extractStringField(trimmed, "reason").orEmpty().trim()
        )
    }

    internal fun buildSystemPrompt(runtimeSnapshot: AiRuntimeSnapshot): String {
        return buildString {
            appendLine("你是 Clawdroid 的本地 AI 编排器。")
            appendLine("你的任务是先判断是否应调用工具，再决定是否直接回复。")
            appendLine("你必须输出 JSON，且只能输出一个 JSON 对象，不要输出 Markdown。")
            appendLine("""JSON 结构：{"mode":"tool|chat","reply":"给用户看的简短中文回复","tool":"tool_id","arguments":{"key":"value"},"reason":"简短原因"}""")
            appendLine("只有在用户明确要求执行动作、查询运行时、截图、读取能力、操作事件流或执行受限命令时，才返回 mode=tool。")
            appendLine("如果用户是在闲聊、询问解释、或者缺少执行前提，则返回 mode=chat。")
            appendLine("reply 必须是自然、简洁的中文。")
            appendLine("如要调工具，只能从下列 tool_id 中选择：")
            appendLine(toolCatalog())
            appendLine("参数约定：")
            appendLine("""- inject_tap: {"x":"540","y":"1200","display_id":"0"}""")
            appendLine("""- inject_swipe: {"x1":"540","y1":"1800","x2":"540","y2":"400","duration_ms":"350","display_id":"0"}""")
            appendLine("""- execute_shell_limited: {"command":"wm size"}""")
            appendLine("""- subscribe_events: {"operation":"start|stop"}""")
            appendLine("""- capture_screen: {"read_after_capture":"true|false"}""")
            appendLine("已知运行时上下文：")
            appendLine("session_summary=${runtimeSnapshot.sessionSummary}")
            appendLine("capability_status=${runtimeSnapshot.capabilityStatus}")
            appendLine("event_streaming=${runtimeSnapshot.eventStreaming}")
            appendLine("若上下文显示运行时尚未连接，优先建议 probe_session、runtime_ping 或 get_capabilities，不要臆造执行成功。")
            appendLine("若用户要求'帮我看看当前能力/状态/连通性'，优先选择 get_capabilities、get_health、probe_session。")
            appendLine("若用户要求'截图并看看'，优先 capture_screen，并将 read_after_capture 设为 true。")
        }
    }

    suspend fun reflectToolResult(
        settings: ModelSettings,
        input: AiToolReflectionInput
    ): Result<String> {
        if (!isConfigured(settings)) {
            return Result.success(input.toolResult)
        }
        return ModelApiClient.generateReply(
            settings = settings,
            prompt = buildToolReflectionPrompt(input),
            systemPrompt = buildToolReflectionSystemPrompt(input.runtimeSnapshot)
        ).map { it.trim() }
    }

    private fun toolCatalog(): String {
        return ClawTool.entries.joinToString(separator = "\n") { tool ->
            "- ${tool.toolId}: ${tool.displayName}，${tool.description}"
        }
    }

    private fun defaultAssistantMessage(tool: ClawTool): String {
        return when (tool) {
            ClawTool.RUNTIME_PING -> "我先检查一下 Runtime 是否在线。"
            ClawTool.PROBE_SESSION -> "我先做一次 Runtime 会话探测。"
            ClawTool.GET_CAPABILITIES -> "我先读取当前能力列表。"
            ClawTool.CAPTURE_SCREEN -> "我先执行截图。"
            ClawTool.READ_LATEST_CAPTURE -> "我先读取最近截图预览。"
            ClawTool.SUBSCRIBE_EVENTS -> "我来处理事件流状态。"
            ClawTool.EXECUTE_SHELL_LIMITED -> "我先执行受限 Shell 命令。"
            else -> "我先尝试执行这个动作。"
        }
    }

    internal fun readinessSummary(settings: ModelSettings): String {
        val providerLabel = when (settings.provider) {
            ModelProvider.OpenAI -> "OpenAI"
            ModelProvider.Gemini -> "Gemini"
            ModelProvider.Anthropic -> "Anthropic"
            ModelProvider.OpenAICompatible -> "OpenAI-Compatible"
            ModelProvider.Custom -> "Custom"
            ModelProvider.Local -> "Local"
        }
        return if (isConfigured(settings)) {
            "AI 已就绪: $providerLabel，可执行模型决策 + 工具编排"
        } else {
            "AI 待配置: $providerLabel，当前仍可使用规则指令与快捷动作"
        }
    }

    internal fun buildToolReflectionSystemPrompt(runtimeSnapshot: AiRuntimeSnapshot): String {
        return buildString {
            appendLine("你是 Clawdroid 的工具执行总结助手。")
            appendLine("你只能基于真实工具输出总结，不要臆造成功、截图内容或运行状态。")
            appendLine("如果工具执行失败，要直接指出失败原因，并给出一个简短下一步建议。")
            appendLine("请输出简洁中文，不要输出 JSON、Markdown 列表或代码块，控制在 2 到 3 句内。")
            appendLine("当前运行时上下文：")
            appendLine("session_summary=${runtimeSnapshot.sessionSummary}")
            appendLine("capability_status=${runtimeSnapshot.capabilityStatus}")
            appendLine("event_streaming=${runtimeSnapshot.eventStreaming}")
        }
    }

    internal fun buildToolReflectionPrompt(input: AiToolReflectionInput): String {
        val argumentSummary = input.arguments.entries.joinToString { "${it.key}=${it.value}" }
            .ifBlank { "none" }
        return buildString {
            appendLine("用户原始请求:")
            appendLine(input.originalPrompt)
            appendLine()
            appendLine("执行工具:")
            appendLine("${input.tool.toolId} / ${input.tool.displayName}")
            appendLine("工具参数:")
            appendLine(argumentSummary)
            appendLine()
            appendLine("工具真实输出:")
            appendLine(input.toolResult)
            appendLine()
            appendLine("请给用户一个简短总结，并在必要时说明下一步建议。")
        }
    }

    private fun isConfigured(settings: ModelSettings): Boolean {
        return when (settings.provider) {
            ModelProvider.Local -> settings.localEndpoint.isNotBlank() && settings.localModelName.isNotBlank()
            else -> settings.baseUrl.isNotBlank() && settings.modelName.isNotBlank() && settings.apiKey.isNotBlank()
        }
    }

    private fun extractStringField(content: String, key: String): String? {
        val match = Regex("""\"$key\"\s*:\s*\"([^\"]*)\"""").find(content) ?: return null
        return match.groupValues[1]
    }

    private fun extractArguments(content: String): Map<String, String> {
        val objectMatch = Regex("""\"arguments\"\s*:\s*\{([^}]*)\}""").find(content) ?: return emptyMap()
        val body = objectMatch.groupValues[1]
        val pairRegex = Regex("""\"([^\"]+)\"\s*:\s*\"([^\"]*)\"""")
        return pairRegex.findAll(body).associate { match ->
            match.groupValues[1] to match.groupValues[2]
        }
    }
}
