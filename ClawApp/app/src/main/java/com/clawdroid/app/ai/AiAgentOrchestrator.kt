package com.clawdroid.app.ai

import android.content.Context
import com.clawdroid.app.chat.ChatTextLimits
import com.clawdroid.app.model.ModelApiClient
import com.clawdroid.app.tools.ClawAssetPromptStore
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCatalog
import com.clawdroid.app.ui.ModelProvider
import com.clawdroid.app.ui.ModelSettings
import org.json.JSONArray
import org.json.JSONObject

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

internal data class AiToolStepRecord(
    val tool: ClawTool,
    val arguments: Map<String, String>,
    val success: Boolean,
    val output: String
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
    const val MAX_TOOL_LOOP_TURNS = 4

    @Volatile
    private var appContext: Context? = null

    fun bindContext(context: Context) {
        appContext = context.applicationContext
    }

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

    suspend fun continueAfterTool(
        settings: ModelSettings,
        originalPrompt: String,
        steps: List<AiToolStepRecord>,
        runtimeSnapshot: AiRuntimeSnapshot,
        remainingTurns: Int
    ): Result<AiAgentPlan> {
        if (steps.isEmpty()) {
            return Result.success(AiAgentPlan.AssistantReply("没有可继续的工具步骤。"))
        }
        if (!isConfigured(settings)) {
            return Result.success(
                AiAgentPlan.AssistantReply(steps.last().output.trim().ifBlank { "工具已执行，但模型未配置。" })
            )
        }
        return ModelApiClient.generateReply(
            settings = settings,
            prompt = buildContinueUserPrompt(originalPrompt, steps, remainingTurns),
            systemPrompt = buildContinueSystemPrompt(runtimeSnapshot, remainingTurns)
        ).map { rawReply ->
            parseAgentPlan(rawReply)
        }
    }

    internal fun parseAgentPlan(rawReply: String): AiAgentPlan {
        val trimmed = rawReply.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return AiAgentPlan.AssistantReply(trimmed)
        }

        val json = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: return AiAgentPlan.AssistantReply(trimmed)

        val mode = json.optString("mode", "").trim().lowercase()
        val reply = json.optString("reply", "").trim()
        if (mode != "tool") {
            return AiAgentPlan.AssistantReply(
                message = reply.ifBlank { trimmed }
            )
        }

        val toolId = json.optString("tool", "").trim()
        val tool = ClawTool.byToolId(toolId)
            ?: return AiAgentPlan.AssistantReply(
                message = reply.ifBlank { "模型返回了未知工具 `$toolId`，请改用明确指令。" }
            )

        val arguments = extractArguments(trimmed)

        val validatedArgs = validateToolArguments(tool, arguments)
        if (validatedArgs == null) {
            return AiAgentPlan.AssistantReply(
                message = reply.ifBlank { "工具参数校验失败，请检查参数名和参数值是否符合约束。" }
            )
        }

        return AiAgentPlan.ToolExecution(
            tool = tool,
            arguments = validatedArgs,
            assistantMessage = reply.ifBlank { defaultAssistantMessage(tool) },
            reasoning = json.optString("reason", "").trim()
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
            appendLine("如要调工具，只能从下列 tool_id 中选择（unavailable 表示当前能力不足，勿强行调用）：")
            appendLine(toolCatalog())
            val usage = ClawAssetPromptStore.toolUsagePrompt(appContext)
            if (usage.isNotBlank()) {
                appendLine("--- tool-usage ---")
                appendLine(usage.take(1200))
            }
            val assist = ClawAssetPromptStore.assistPrompt(appContext)
            if (assist.isNotBlank()) {
                appendLine("--- assist-mcp ---")
                appendLine(assist.take(1200))
            }
            appendLine("多步任务优先使用 run_agent（先 list_agents）；也可在后续轮次继续 mode=tool 串联单个工具。")
            appendLine("Skill 指导可用 list_skills / get_skill；工具目录可用 list_tools / get_tool。")
            appendLine("本机工具优先；需要电脑侧能力时用 assist_ping / assist_list_tools / assist_call_tool。")
            appendLine("参数约定：")
            appendLine("""- inject_tap: {"x":"540","y":"1200","display_id":"0"}""")
            appendLine("""- inject_swipe: {"x1":"540","y1":"1800","x2":"540","y2":"400","duration_ms":"350","display_id":"0"}""")
            appendLine("""- execute_shell_limited: {"command":"wm size"}""")
            appendLine("""- subscribe_events: {"operation":"start|stop"}""")
            appendLine("""- capture_screen: {"read_after_capture":"true|false"}""")
            appendLine("""- get_skill: {"skill_id":"assist-mcp-bridge"}""")
            appendLine("""- run_agent: {"agent_id":"runtime_health_sweep"}""")
            appendLine("""- file_read: {"path":"...","mode":"lines","line_start":"1","line_limit":"50"}""")
            appendLine("""- app_launch: {"package":"com.android.settings"}""")
            appendLine("""- download_start: {"url":"https://...","resume":"true"}""")
            appendLine("""- web_search: {"query":"...","provider":"auto","max_results":"5"}""")
            appendLine("""- sandbox_shell: {"command":"ls"}""")
            appendLine("""- assist_call_tool: {"name":"tool_name","arguments_json":"{}"}""")
            appendLine("""- task_submit: {"task_id":"t1","steps_json":"[{\"action\":\"ping\",\"args\":{}}]"}""")
            appendLine("""- task_get / task_cancel: {"task_id":"..."}""")
            appendLine("已知运行时上下文：")
            appendLine("session_summary=${runtimeSnapshot.sessionSummary}")
            appendLine("capability_status=${runtimeSnapshot.capabilityStatus}")
            appendLine("event_streaming=${runtimeSnapshot.eventStreaming}")
            appendLine("若上下文显示运行时尚未连接，优先建议 probe_session、runtime_ping、run_agent(runtime_health_sweep) 或 get_capabilities，不要臆造执行成功。")
            appendLine("若用户要求'帮我看看当前能力/状态/连通性/模块'，优先 run_agent(runtime_health_sweep) 或 get_runtime_status / get_capabilities / probe_session。")
            appendLine("若用户要求'截图并看看'，优先 run_agent(capture_then_preview) 或 capture_screen(read_after_capture=true)。")
            appendLine("若用户要求确认页面后点击，优先 run_agent(confirm_then_safe_tap)。")
            appendLine("若用户要求调用电脑 MCP，优先 assist_status / assist_ping，再 assist_call_tool。")
        }
    }

    internal fun buildContinueSystemPrompt(
        runtimeSnapshot: AiRuntimeSnapshot,
        remainingTurns: Int
    ): String {
        return buildString {
            appendLine("你是 Clawdroid 的本地 AI 编排器，正在继续多步工具循环。")
            appendLine("你必须输出 JSON，且只能输出一个 JSON 对象，不要输出 Markdown。")
            appendLine("""JSON 结构：{"mode":"tool|chat","reply":"给用户看的简短中文回复","tool":"tool_id","arguments":{"key":"value"},"reason":"简短原因"}""")
            appendLine("若用户目标尚未完成且仍需调用工具，返回 mode=tool。")
            appendLine("若目标已完成、无法继续、或剩余轮次不足，返回 mode=chat，并在 reply 中基于真实工具输出做简短总结。")
            appendLine("不要重复调用刚刚失败且参数相同的工具；不要臆造成功。")
            appendLine("剩余可继续工具轮次：$remainingTurns")
            appendLine("可选 tool_id：")
            appendLine(toolCatalog())
            appendLine("多步固定流程优先 run_agent。")
            appendLine("当前运行时上下文：")
            appendLine("session_summary=${runtimeSnapshot.sessionSummary}")
            appendLine("capability_status=${runtimeSnapshot.capabilityStatus}")
            appendLine("event_streaming=${runtimeSnapshot.eventStreaming}")
        }
    }

    internal fun buildContinueUserPrompt(
        originalPrompt: String,
        steps: List<AiToolStepRecord>,
        remainingTurns: Int
    ): String {
        return buildString {
            appendLine("用户原始请求:")
            appendLine(originalPrompt.trim())
            appendLine()
            appendLine("已执行步骤（从早到晚）:")
            steps.forEachIndexed { index, step ->
                val args = step.arguments.entries.joinToString { "${it.key}=${it.value}" }
                    .ifBlank { "none" }
                appendLine("${index + 1}. tool=${step.tool.toolId} success=${step.success} args={$args}")
                appendLine("output:")
                appendLine(truncateStepOutput(step.output))
                appendLine()
            }
            appendLine("剩余可继续工具轮次: $remainingTurns")
            appendLine("请决定下一步：继续 mode=tool，或结束 mode=chat。")
        }
    }

    internal fun truncateStepOutput(output: String): String {
        return ChatTextLimits.truncateForContext(output)
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
        val lines = ClawToolCatalog.aiCatalogLines(appContext)
        return lines.ifBlank {
            ClawTool.entries.joinToString(separator = "\n") { tool ->
                "- ${tool.toolId}: ${tool.displayName}，${tool.description}"
            }
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
            ClawTool.SAFE_TAP -> "我先执行安全点击。"
            ClawTool.LIST_SKILLS -> "我先列出可用 Skills。"
            ClawTool.GET_SKILL -> "我先读取 Skill 说明。"
            ClawTool.LIST_AGENTS -> "我先列出可用 Agents。"
            ClawTool.RUN_AGENT -> "我先运行多步 Agent。"
            ClawTool.TASK_SUBMIT -> "我先向 Runtime 提交任务。"
            ClawTool.TASK_GET -> "我先查询 Runtime 任务状态。"
            ClawTool.TASK_LIST -> "我先列出 Runtime 任务。"
            ClawTool.TASK_CANCEL -> "我先取消 Runtime 任务。"
            else -> "我先尝试执行这个动作。"
        }
    }

    internal fun readinessSummary(settings: ModelSettings): String {
        val providerLabel = settings.provider.displayName
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
            appendLine(ChatTextLimits.truncateForContext(input.originalPrompt))
            appendLine()
            appendLine("执行工具:")
            appendLine("${input.tool.toolId} / ${input.tool.displayName}")
            appendLine("工具参数:")
            appendLine(ChatTextLimits.truncateForContext(argumentSummary))
            appendLine()
            appendLine("工具真实输出:")
            appendLine(ChatTextLimits.truncateForContext(input.toolResult))
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

    private fun validateToolArguments(tool: ClawTool, arguments: Map<String, String>): Map<String, String>? {
        val allowedParams = ClawToolCatalog.allowedArgumentKeys(tool) ?: return arguments
        val validArgs = arguments.filter { (key, _) -> key in allowedParams }
        if (validArgs.size < arguments.size) {
            return null
        }
        for ((key, value) in validArgs) {
            if (!isValidArgumentValue(key, value)) {
                return null
            }
        }
        return validArgs
    }

    private fun isValidArgumentValue(key: String, value: String): Boolean {
        val len = value.length
        if (len > 4096) return false
        return when {
            key in setOf(
                "x", "y", "x1", "y1", "x2", "y2", "display_id", "duration_ms",
                "offset", "max_bytes", "line_start", "line_limit", "line_end",
                "column", "limit", "threads", "port"
            ) -> {
                value.toIntOrNull()?.let { v -> v in -10000..100000 } ?: false
            }
            key in setOf(
                "read_after_capture", "append", "regex", "resume", "compute_hash",
                "include_images", "include_planned"
            ) -> {
                value.lowercase() in setOf("true", "false")
            }
            key in setOf("operation") -> {
                value.lowercase() in setOf("start", "stop")
            }
            key == "command" -> {
                len > 0 && !DANGEROUS_COMMAND_PATTERN.containsMatchIn(value)
            }
            key in setOf("expected_package", "target_package", "package", "package_name") -> {
                value.isBlank() || PACKAGE_NAME_PATTERN.matches(value)
            }
            key in setOf("skill_id", "agent_id", "agent", "id", "name", "tool_id", "task_id", "download_id") -> {
                value.isNotBlank() && value.length <= 128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
            }
            else -> true
        }
    }

    private val DANGEROUS_COMMAND_PATTERN = Regex(
        """.*(?:;\s*|\|\s*|\&\&\s*|>|<|\$\(|`)\s*(?:rm|mv|cp|chmod|chown|wget|curl|nc|bash|sh)\b"""
    )
    private val PACKAGE_NAME_PATTERN = Regex("""^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$""")
}
