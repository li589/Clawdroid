package com.clawdroid.app.ai

import android.content.Context
import com.clawdroid.app.chat.ChatTextLimits
import com.clawdroid.app.model.ModelApiClient
import com.clawdroid.app.model.ModelGenerationResult
import com.clawdroid.app.model.ModelToolCall
import com.clawdroid.app.tools.ClawAssetPromptStore
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCatalog
import com.clawdroid.app.data.model.ModelProvider
import com.clawdroid.app.data.model.ModelSettings
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
    /** @deprecated Prefer [com.clawdroid.app.data.model.AgentOrchestrationSettings.maxToolLoopTurns]. */
    const val MAX_TOOL_LOOP_TURNS = 16

    @Volatile
    private var appContext: Context? = null

    fun bindContext(context: Context) {
        appContext = context.applicationContext
        ModelApiClient.bindContext(context)
    }

    suspend fun plan(
        settings: ModelSettings,
        prompt: String,
        runtimeSnapshot: AiRuntimeSnapshot,
        userImage: com.clawdroid.app.model.ModelUserImage? = null
    ): Result<AiAgentPlan> {
        val normalizedPrompt = prompt.trim()
        if (normalizedPrompt.isBlank() && userImage == null) {
            return Result.success(AiAgentPlan.AssistantReply("请输入要执行的内容。"))
        }
        val effectivePrompt = normalizedPrompt.ifBlank { "请根据附图回答。" }
        return ModelApiClient.generateAgentTurn(
            settings = settings,
            prompt = effectivePrompt,
            systemPrompt = buildSystemPrompt(runtimeSnapshot),
            enableNativeTools = true,
            userImage = userImage
        ).map { generation ->
            planFromGeneration(generation)
        }
    }

    suspend fun continueAfterTool(
        settings: ModelSettings,
        originalPrompt: String,
        steps: List<AiToolStepRecord>,
        runtimeSnapshot: AiRuntimeSnapshot,
        remainingTurns: Int,
        workingSummary: String = ""
    ): Result<AiAgentPlan> {
        if (steps.isEmpty()) {
            return Result.success(AiAgentPlan.AssistantReply("没有可继续的工具步骤。"))
        }
        if (!isConfigured(settings)) {
            return Result.success(
                AiAgentPlan.AssistantReply(steps.last().output.trim().ifBlank { "工具已执行，但模型未配置。" })
            )
        }
        return ModelApiClient.generateAgentTurn(
            settings = settings,
            prompt = buildContinueUserPrompt(originalPrompt, steps, remainingTurns, workingSummary),
            systemPrompt = buildContinueSystemPrompt(runtimeSnapshot, remainingTurns),
            enableNativeTools = true
        ).map { generation ->
            planFromGeneration(generation)
        }
    }

    /**
     * 优先消费原生 tool_calls / tool_use；否则回退解析文本中的约定 JSON。
     */
    internal fun planFromGeneration(generation: ModelGenerationResult): AiAgentPlan {
        val nativeCall = generation.toolCalls.firstOrNull()
        if (nativeCall != null) {
            return planFromNativeToolCall(nativeCall, assistantHint = generation.text)
        }
        return parseAgentPlan(generation.text)
    }

    internal fun planFromNativeToolCall(
        call: ModelToolCall,
        assistantHint: String = ""
    ): AiAgentPlan {
        val replyHint = assistantHint.trim()
        val tool = ClawTool.byToolId(call.name.trim())
            ?: return AiAgentPlan.AssistantReply(
                message = replyHint.ifBlank { "模型调用了未知工具 `${call.name}`，请改用明确指令。" }
            )
        val argsObject = runCatching {
            val raw = call.argumentsJson.trim().ifBlank { "{}" }
            JSONObject(raw)
        }.getOrElse {
            return AiAgentPlan.AssistantReply(
                message = replyHint.ifBlank { "工具参数 JSON 无法解析，请检查模型输出。" }
            )
        }
        val arguments = linkedMapOf<String, String>()
        val keys = argsObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            arguments[key] = jsonValueToArgumentString(argsObject.opt(key))
        }
        val validatedArgs = validateToolArguments(tool, arguments)
            ?: return AiAgentPlan.AssistantReply(
                message = replyHint.ifBlank { "工具参数校验失败，请检查参数名和参数值是否符合约束。" }
            )
        return AiAgentPlan.ToolExecution(
            tool = tool,
            arguments = validatedArgs,
            assistantMessage = replyHint.ifBlank { defaultAssistantMessage(tool) },
            reasoning = "native_tool_call"
        )
    }

    internal fun parseAgentPlan(rawReply: String): AiAgentPlan {
        val trimmed = rawReply.trim()
        val payload = extractJsonPayload(trimmed)
            ?: return AiAgentPlan.AssistantReply(trimmed)

        val json = runCatching { JSONObject(payload) }.getOrNull()
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

        val arguments = extractArguments(json)

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

    /**
     * 从模型原文中提取单个 JSON 对象：去 Markdown fence、忽略前后说明文字、
     * 用括号平衡扫描支持嵌套对象（arguments / steps_json 等）。
     */
    internal fun extractJsonPayload(rawReply: String): String? {
        val trimmed = rawReply.trim()
        if (trimmed.isEmpty()) return null

        val candidates = buildList {
            add(trimmed)
            stripMarkdownFences(trimmed)?.let { add(it) }
            // 正文中的 ```json ... ``` 片段
            MARKDOWN_FENCE_FINDER.findAll(trimmed).forEach { match ->
                match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }

        for (candidate in candidates) {
            val text = candidate.trim()
            if (text.startsWith("{") && text.endsWith("}")) {
                if (runCatching { JSONObject(text) }.isSuccess) return text
            }
            findBalancedJsonObject(text)?.let { obj ->
                if (runCatching { JSONObject(obj) }.isSuccess) return obj
            }
        }
        return findBalancedJsonObject(trimmed)?.takeIf {
            runCatching { JSONObject(it) }.isSuccess
        }
    }

    internal fun buildSystemPrompt(runtimeSnapshot: AiRuntimeSnapshot): String {
        return buildString {
            val orchestrator = ClawAssetPromptStore.orchestratorPrompt(appContext)
            if (orchestrator.isNotBlank()) {
                appendLine(orchestrator.take(2800))
            } else {
                appendLine("你是 Clawdroid 的本地 AI 编排器。")
                appendLine("优先使用函数/工具调用；否则输出 JSON：mode/tool/arguments/reply/reason。")
                appendLine("闲聊用 mode=chat；明确动作才 mode=tool。多步优先 run_agent。")
            }
            appendLine("如要调工具，只能从下列 tool_id 中选择（unavailable 表示当前能力不足，勿强行调用）：")
            appendLine(toolCatalog())
            val usage = ClawAssetPromptStore.toolUsagePrompt(appContext)
            if (usage.isNotBlank()) {
                appendLine("--- tool-usage ---")
                appendLine(usage.take(1400))
            }
            val assist = ClawAssetPromptStore.assistPrompt(appContext)
            if (assist.isNotBlank()) {
                appendLine("--- assist-mcp ---")
                appendLine(assist.take(1200))
            }
            val agents = ClawAssetPromptStore.agentCatalogMarkdown(appContext)
            if (agents.isNotBlank()) {
                appendLine("--- agents ---")
                appendLine(agents.take(1200))
            }
            val routing = ClawAssetPromptStore.routingHints(appContext)
            if (routing.isNotBlank()) {
                appendLine("--- routing ---")
                appendLine(routing.take(900))
            }
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
        }
    }

    internal fun buildContinueSystemPrompt(
        runtimeSnapshot: AiRuntimeSnapshot,
        remainingTurns: Int
    ): String {
        return buildString {
            val continuePrompt = ClawAssetPromptStore.continuePrompt(appContext)
            if (continuePrompt.isNotBlank()) {
                appendLine(continuePrompt.take(1200))
            } else {
                appendLine("你是 Clawdroid 的本地 AI 编排器，正在继续多步工具循环。")
                appendLine("优先原生工具调用；否则只输出一个 JSON：mode/tool/arguments/reply/reason。")
                appendLine("目标未完成且仍需工具 → mode=tool；目标完成或无法继续 → mode=chat。")
                appendLine("不要重复失败调用；不要臆造成功。多步优先 run_agent。")
            }
            appendLine("剩余可继续工具轮次：$remainingTurns")
            appendLine("可选 tool_id：")
            appendLine(toolCatalog())
            appendLine("当前运行时上下文：")
            appendLine("session_summary=${runtimeSnapshot.sessionSummary}")
            appendLine("capability_status=${runtimeSnapshot.capabilityStatus}")
            appendLine("event_streaming=${runtimeSnapshot.eventStreaming}")
        }
    }

    internal fun buildContinueUserPrompt(
        originalPrompt: String,
        steps: List<AiToolStepRecord>,
        remainingTurns: Int,
        workingSummary: String = ""
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
            if (workingSummary.isNotBlank()) {
                appendLine()
                appendLine("压缩记忆（旧轮摘要）：")
                appendLine(ChatTextLimits.truncateForContext(workingSummary, 1200))
            }
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
        return reflectToolCritique(settings, input).map { critique ->
            critique.summary.ifBlank { input.toolResult }
        }
    }

    suspend fun reflectToolCritique(
        settings: ModelSettings,
        input: AiToolReflectionInput
    ): Result<ToolReflectionCritique> {
        if (!isConfigured(settings)) {
            return Result.success(
                ToolReflectionCritique(
                    ok = null,
                    action = CritiqueAction.Continue,
                    summary = input.toolResult
                )
            )
        }
        return ModelApiClient.generateReply(
            settings = settings,
            prompt = buildToolReflectionPrompt(input),
            systemPrompt = buildToolReflectionSystemPrompt(input.runtimeSnapshot)
        ).map { raw -> ToolReflectionCritiqueParser.parse(raw.trim()) }
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
        ClawAssetPromptStore.phraseForTool(appContext, tool.toolId)?.let { return it }
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
            ClawTool.TASK_WAIT -> "我先等待 Runtime 任务到达终态。"
            ClawTool.TASK_CANCEL -> "我先取消 Runtime 任务。"
            else -> ClawAssetPromptStore.defaultToolPhrase(appContext)
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
            val reflection = ClawAssetPromptStore.toolReflectionPrompt(appContext)
            if (reflection.isNotBlank()) {
                appendLine(reflection.take(800))
            } else {
                appendLine("你是 Clawdroid 的工具执行总结助手。")
                appendLine("优先输出 JSON：{\"ok\":bool,\"action\":\"continue|retry|replan|stop\",\"summary\":\"...\",\"hint\":\"...\"}")
                appendLine("否则 2–3 句中文；只基于真实工具输出。")
            }
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
            appendLine("请输出结构化 JSON（ok/action/summary/hint）；无法 JSON 时再写短中文总结。")
        }
    }

    private fun isConfigured(settings: ModelSettings): Boolean {
        return when (settings.provider) {
            ModelProvider.Local -> settings.localEndpoint.isNotBlank() && settings.localModelName.isNotBlank()
            else -> settings.baseUrl.isNotBlank() && settings.modelName.isNotBlank() && settings.apiKey.isNotBlank()
        }
    }

    private fun extractArguments(json: JSONObject): Map<String, String> {
        val args = json.optJSONObject("arguments") ?: return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = args.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = jsonValueToArgumentString(args.opt(key))
        }
        return result
    }

    /**
     * 工具参数统一为 String map：数字/布尔直接 toString，对象/数组压成紧凑 JSON 字符串
     *（兼容模型把 x 写成 540、把 steps_json 写成数组等情况）。
     */
    internal fun jsonValueToArgumentString(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> ""
            is String -> value
            is Number -> {
                // 避免 540.0 污染整型参数校验
                if (value is Double || value is Float) {
                    val d = value.toDouble()
                    if (d.isFinite() && d == d.toLong().toDouble()) d.toLong().toString() else value.toString()
                } else {
                    value.toString()
                }
            }
            is Boolean -> value.toString()
            is JSONObject -> value.toString()
            is JSONArray -> value.toString()
            else -> value.toString()
        }
    }

    private fun stripMarkdownFences(text: String): String? {
        val match = MARKDOWN_FENCE_WHOLE.matchEntire(text.trim()) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    private fun findBalancedJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
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

    private val MARKDOWN_FENCE_WHOLE = Regex(
        """^```(?:json|JSON)?\s*\r?\n?(.*?)\r?\n?```\s*$""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val MARKDOWN_FENCE_FINDER = Regex(
        """```(?:json|JSON)?\s*\r?\n?(.*?)\r?\n?```""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
}
