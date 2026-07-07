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
