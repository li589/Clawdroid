package com.clawdroid.app.orchestrator

import com.clawdroid.app.tools.ClawTool

/**
 * 直接指令编排器，用于从聊天输入文本识别工具意图，属于“大脑”一侧的极简编排层。
 *
 * 当前是 MVP 版本：仅做关键词匹配；后续可接入 LLM 推理、参数提取、任务链等。
 */
object DirectCommandOrchestrator {
    private val integerPattern = Regex("-?\\d+")

    sealed class ParsedIntent {
        data object None : ParsedIntent()
        data class RawText(val text: String) : ParsedIntent()
        data class ToolCall(
            val tool: ClawTool,
            val arguments: Map<String, String> = emptyMap()
        ) : ParsedIntent()
    }

    fun parse(text: String): ParsedIntent {
        val normalized = text.trim()

        if (normalized.isEmpty()) {
            return ParsedIntent.None
        }

        // 以 / 开头的输入执行严格前缀匹配，降低 prompt 注入风险
        if (normalized.startsWith("/")) {
            return matchDirectCommand(normalized)
        }

        // ========== Skills / Agents / Runtime Tasks ==========
        parseAgentSkillOrTaskIntent(normalized)?.let { return it }

        // ========== 本地感知工具 ==========
        if (matchAny(normalized, listOf("确认页面", "确认当前页面", "页面确认"))) {
            return ParsedIntent.ToolCall(ClawTool.PAGE_CONFIRM)
        }
        if (matchAny(normalized, listOf("点击前检查", "检查可点击", "检查点击"))) {
            return ParsedIntent.ToolCall(ClawTool.CLICK_PRECHECK)
        }
        if (matchAny(normalized, listOf("安全点击", "执行安全点击"))) {
            // 安全点击不在工具枚举中，因为它是两个工具的组合：CLICK_PRECHECK + INJECT_TAP
            // 这里返回 RawText，由上层手工处理
            return ParsedIntent.RawText("安全点击")
        }

        // ========== 运行时调用工具 ==========
        if (matchAny(normalized, listOf("ping", "ping runtime", "连通", "连接测试", "发送 ping"))) {
            return ParsedIntent.ToolCall(ClawTool.RUNTIME_PING)
        }
        if (matchAny(normalized, listOf("版本", "版本信息", "get version"))) {
            return ParsedIntent.ToolCall(ClawTool.GET_VERSION)
        }
        if (matchAny(normalized, listOf("健康", "健康状态", "get health"))) {
            return ParsedIntent.ToolCall(ClawTool.GET_HEALTH)
        }
        if (matchAny(normalized, listOf("模块状态", "runtime status", "统一状态", "运行时状态", "get runtime status"))) {
            return ParsedIntent.ToolCall(ClawTool.GET_RUNTIME_STATUS)
        }
        if (matchAny(normalized, listOf("最近错误", "last error", "错误状态"))) {
            return ParsedIntent.ToolCall(ClawTool.GET_LAST_ERROR)
        }
        if (matchAny(normalized, listOf("获取能力", "能力", "capabilities", "get capabilities"))) {
            return ParsedIntent.ToolCall(ClawTool.GET_CAPABILITIES)
        }
        if (matchAny(normalized, listOf("probe", "session probe", "runtime probe", "会话探测"))) {
            return ParsedIntent.ToolCall(ClawTool.PROBE_SESSION)
        }
        if (matchAny(normalized, listOf("截图并预览", "截屏并预览", "截图预览"))) {
            return ParsedIntent.ToolCall(
                ClawTool.CAPTURE_SCREEN,
                arguments = mapOf("read_after_capture" to "true")
            )
        }
        if (matchAny(normalized, listOf("截图", "截屏", "屏幕截图", "capture", "capture screen"))) {
            return ParsedIntent.ToolCall(ClawTool.CAPTURE_SCREEN)
        }
        if (matchAny(normalized, listOf("读取最近截图", "预览最近截图", "查看最近截图", "读取并预览最近截图"))) {
            return ParsedIntent.ToolCall(ClawTool.READ_LATEST_CAPTURE)
        }
        if (matchAny(normalized, listOf("读取文件", "read file", "读文件"))) {
            return ParsedIntent.ToolCall(ClawTool.READ_FILE_LIMITED)
        }
        if (matchAny(normalized, listOf("点击", "tap", "inject tap")) && !normalized.contains("前")) {
            return parseTapIntent(normalized)
        }
        if (matchAny(normalized, listOf("按键", "keyevent", "返回键", "back key", "inject key"))) {
            val key = when {
                normalized.contains("home", ignoreCase = true) || normalized.contains("主页") -> "HOME"
                normalized.contains("enter", ignoreCase = true) || normalized.contains("回车") -> "ENTER"
                else -> "BACK"
            }
            return ParsedIntent.ToolCall(
                ClawTool.INJECT_KEYEVENT,
                arguments = mapOf("key" to key)
            )
        }
        if (matchAny(normalized, listOf("滑动", "swipe", "inject swipe"))) {
            return parseSwipeIntent(normalized)
        }
        if (matchAny(normalized, listOf("执行 shell", "执行命令", "shell"))) {
            return ParsedIntent.ToolCall(ClawTool.EXECUTE_SHELL_LIMITED)
        }
        if (matchAny(normalized, listOf("订阅事件", "开始订阅", "事件流"))) {
            return ParsedIntent.ToolCall(ClawTool.SUBSCRIBE_EVENTS)
        }

        return ParsedIntent.RawText(normalized)
    }

    private fun matchAny(text: String, keywords: List<String>): Boolean {
        val lower = text.lowercase()
        return keywords.any { kw -> lower.contains(kw.lowercase()) }
    }

    /**
     * 对以 / 开头的命令执行严格前缀匹配。
     * 关键词必须从命令起始处（跳过 /）开始匹配，避免 prompt 注入绕过。
     */
    private fun matchDirectCommand(text: String): ParsedIntent {
        val stripped = text.removePrefix("/").trim()
        val lower = stripped.lowercase()
        parseSlashAgentSkillOrTask(stripped, lower)?.let { return it }
        return when {
            lower.startsWith("确认") || lower.startsWith("page confirm") ->
                ParsedIntent.ToolCall(ClawTool.PAGE_CONFIRM)
            lower.startsWith("点击前检查") || lower.startsWith("检查点击") ->
                ParsedIntent.ToolCall(ClawTool.CLICK_PRECHECK)
            lower.startsWith("安全点击") ->
                ParsedIntent.RawText("安全点击")
            lower.startsWith("ping") || lower.startsWith("连通") || lower.startsWith("连接测试") ->
                ParsedIntent.ToolCall(ClawTool.RUNTIME_PING)
            lower.startsWith("版本") ->
                ParsedIntent.ToolCall(ClawTool.GET_VERSION)
            lower.startsWith("健康") ->
                ParsedIntent.ToolCall(ClawTool.GET_HEALTH)
            lower.startsWith("模块状态") || lower.startsWith("runtime status") || lower.startsWith("统一状态") ->
                ParsedIntent.ToolCall(ClawTool.GET_RUNTIME_STATUS)
            lower.startsWith("最近错误") || lower.startsWith("last error") ->
                ParsedIntent.ToolCall(ClawTool.GET_LAST_ERROR)
            lower.startsWith("获取能力") || lower.startsWith("capabilities") ->
                ParsedIntent.ToolCall(ClawTool.GET_CAPABILITIES)
            lower.startsWith("probe") || lower.startsWith("会话探测") ->
                ParsedIntent.ToolCall(ClawTool.PROBE_SESSION)
            lower.startsWith("截图并预览") || lower.startsWith("截屏并预览") ->
                ParsedIntent.ToolCall(
                    ClawTool.CAPTURE_SCREEN,
                    arguments = mapOf("read_after_capture" to "true")
                )
            lower.startsWith("截图") || lower.startsWith("截屏") || lower.startsWith("capture") ->
                ParsedIntent.ToolCall(ClawTool.CAPTURE_SCREEN)
            lower.startsWith("读取最近截图") || lower.startsWith("预览最近截图") ->
                ParsedIntent.ToolCall(ClawTool.READ_LATEST_CAPTURE)
            lower.startsWith("读取文件") || lower.startsWith("read file") ->
                ParsedIntent.ToolCall(ClawTool.READ_FILE_LIMITED)
            lower.startsWith("点击") || lower.startsWith("tap") ->
                parseTapIntent(text)
            lower.startsWith("按键") || lower.startsWith("keyevent") || lower.startsWith("返回键") ->
                ParsedIntent.ToolCall(
                    ClawTool.INJECT_KEYEVENT,
                    arguments = mapOf("key" to "BACK")
                )
            lower.startsWith("滑动") || lower.startsWith("swipe") ->
                parseSwipeIntent(text)
            lower.startsWith("执行 shell") || lower.startsWith("执行命令") || lower.startsWith("shell") ->
                ParsedIntent.ToolCall(ClawTool.EXECUTE_SHELL_LIMITED)
            lower.startsWith("订阅事件") || lower.startsWith("开始订阅") ->
                ParsedIntent.ToolCall(ClawTool.SUBSCRIBE_EVENTS)
            else -> ParsedIntent.RawText(text)
        }
    }

    private fun parseAgentSkillOrTaskIntent(text: String): ParsedIntent? {
        val lower = text.lowercase()
        if (matchAny(lower, listOf("列出 agents", "列出agents", "list agents", "列出代理", "有哪些 agent"))) {
            return ParsedIntent.ToolCall(ClawTool.LIST_AGENTS)
        }
        if (matchAny(lower, listOf("列出 skills", "列出skills", "list skills", "列出技能", "有哪些 skill"))) {
            return ParsedIntent.ToolCall(ClawTool.LIST_SKILLS)
        }
        if (matchAny(lower, listOf("任务列表", "列出任务", "list tasks", "runtime tasks"))) {
            return ParsedIntent.ToolCall(ClawTool.TASK_LIST)
        }

        extractPrefixedId(text, listOf("运行 agent", "执行 agent", "run agent", "run_agent"))?.let { agentId ->
            return ParsedIntent.ToolCall(
                ClawTool.RUN_AGENT,
                arguments = mapOf("agent_id" to agentId)
            )
        }
        extractPrefixedId(text, listOf("读取 skill", "查看 skill", "get skill", "get_skill"))?.let { skillId ->
            return ParsedIntent.ToolCall(
                ClawTool.GET_SKILL,
                arguments = mapOf("skill_id" to skillId)
            )
        }
        extractPrefixedId(text, listOf("查询任务", "task get", "task_get"))?.let { taskId ->
            return ParsedIntent.ToolCall(
                ClawTool.TASK_GET,
                arguments = mapOf("task_id" to taskId)
            )
        }
        extractPrefixedId(text, listOf("取消 runtime 任务", "取消runtime任务", "task cancel", "task_cancel"))?.let { taskId ->
            return ParsedIntent.ToolCall(
                ClawTool.TASK_CANCEL,
                arguments = mapOf("task_id" to taskId)
            )
        }
        return null
    }

    private fun parseSlashAgentSkillOrTask(stripped: String, lower: String): ParsedIntent? {
        when {
            lower == "agents" || lower.startsWith("agents ") || lower == "list_agents" || lower == "list agents" ->
                return ParsedIntent.ToolCall(ClawTool.LIST_AGENTS)
            lower == "skills" || lower.startsWith("skills ") || lower == "list_skills" || lower == "list skills" ->
                return ParsedIntent.ToolCall(ClawTool.LIST_SKILLS)
            lower == "task_list" || lower == "tasks" || lower.startsWith("task_list ") || lower == "list_tasks" ->
                return ParsedIntent.ToolCall(ClawTool.TASK_LIST)
        }

        extractSlashArg(stripped, listOf("agent", "run_agent", "run-agent"))?.let { agentId ->
            return if (agentId.isBlank()) {
                ParsedIntent.ToolCall(ClawTool.LIST_AGENTS)
            } else {
                ParsedIntent.ToolCall(
                    ClawTool.RUN_AGENT,
                    arguments = mapOf("agent_id" to agentId)
                )
            }
        }
        extractSlashArg(stripped, listOf("skill", "get_skill", "get-skill"))?.let { skillId ->
            return if (skillId.isBlank()) {
                ParsedIntent.ToolCall(ClawTool.LIST_SKILLS)
            } else {
                ParsedIntent.ToolCall(
                    ClawTool.GET_SKILL,
                    arguments = mapOf("skill_id" to skillId)
                )
            }
        }
        extractSlashArg(stripped, listOf("task_get", "task-get"))?.let { taskId ->
            if (taskId.isNotBlank()) {
                return ParsedIntent.ToolCall(
                    ClawTool.TASK_GET,
                    arguments = mapOf("task_id" to taskId)
                )
            }
        }
        extractSlashArg(stripped, listOf("task_cancel", "task-cancel"))?.let { taskId ->
            if (taskId.isNotBlank()) {
                return ParsedIntent.ToolCall(
                    ClawTool.TASK_CANCEL,
                    arguments = mapOf("task_id" to taskId)
                )
            }
        }
        extractSlashArg(stripped, listOf("task_submit", "task-submit"))?.let { rest ->
            val parts = rest.split(Regex("\\s+"), limit = 2)
            val taskId = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "app-task-${System.currentTimeMillis()}"
            val action = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "ping"
            return ParsedIntent.ToolCall(
                ClawTool.TASK_SUBMIT,
                arguments = mapOf(
                    "task_id" to taskId,
                    "name" to "chat-submit",
                    "steps_json" to """[{"action":"$action","args":{}}]"""
                )
            )
        }
        return null
    }

    private fun extractPrefixedId(text: String, prefixes: List<String>): String? {
        val lower = text.lowercase()
        for (prefix in prefixes) {
            val index = lower.indexOf(prefix.lowercase())
            if (index < 0) continue
            val rest = text.substring(index + prefix.length).trim()
            if (rest.isBlank()) return null
            return rest.split(Regex("\\s+")).firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun extractSlashArg(stripped: String, commands: List<String>): String? {
        val lower = stripped.lowercase()
        for (command in commands) {
            if (lower == command) {
                return ""
            }
            val prefix = "$command "
            if (lower.startsWith(prefix)) {
                return stripped.substring(command.length).trim().takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun parseTapIntent(text: String): ParsedIntent.ToolCall {
        val values = integerPattern.findAll(text).map { it.value }.toList()
        if (values.size >= 2) {
            return ParsedIntent.ToolCall(
                ClawTool.INJECT_TAP,
                arguments = buildMap {
                    put("x", values[0])
                    put("y", values[1])
                    if (values.size >= 3) {
                        put("display_id", values[2])
                    }
                }
            )
        }
        return ParsedIntent.ToolCall(ClawTool.INJECT_TAP)
    }

    private fun parseSwipeIntent(text: String): ParsedIntent.ToolCall {
        val values = integerPattern.findAll(text).map { it.value }.toList()
        if (values.size >= 4) {
            return ParsedIntent.ToolCall(
                ClawTool.INJECT_SWIPE,
                arguments = buildMap {
                    put("x1", values[0])
                    put("y1", values[1])
                    put("x2", values[2])
                    put("y2", values[3])
                    if (values.size >= 5) {
                        put("duration_ms", values[4])
                    }
                    if (values.size >= 6) {
                        put("display_id", values[5])
                    }
                }
            )
        }
        return ParsedIntent.ToolCall(ClawTool.INJECT_SWIPE)
    }
}
