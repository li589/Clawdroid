package com.clawdroid.app.ai

import org.json.JSONObject

/**
 * Structured critique after a tool step (Stage B).
 * Free-text replies still degrade to [CritiqueAction.Continue] with the raw summary.
 */
enum class CritiqueAction {
    Continue,
    Retry,
    Replan,
    Stop
}

data class ToolReflectionCritique(
    val ok: Boolean?,
    val action: CritiqueAction,
    val summary: String,
    val hint: String = ""
) {
    fun asSystemHint(): String {
        val actionLabel = when (action) {
            CritiqueAction.Continue -> "继续"
            CritiqueAction.Retry -> "建议重试同工具（改参数）"
            CritiqueAction.Replan -> "建议换工具或改计划"
            CritiqueAction.Stop -> "建议结束并汇报"
        }
        return buildString {
            append("结构化反思: action=$actionLabel")
            if (ok == false) append(" ok=false")
            if (summary.isNotBlank()) append(" | ").append(summary.take(240))
            if (hint.isNotBlank()) append(" | hint=").append(hint.take(200))
        }
    }
}

object ToolReflectionCritiqueParser {
    fun parse(raw: String): ToolReflectionCritique {
        val text = raw.trim()
        if (text.isBlank()) {
            return ToolReflectionCritique(ok = null, action = CritiqueAction.Continue, summary = "")
        }
        extractJsonObject(text)?.let { obj ->
            val actionRaw = obj.optString("action", "continue").trim().lowercase()
            val action = when (actionRaw) {
                "retry" -> CritiqueAction.Retry
                "replan", "change", "switch" -> CritiqueAction.Replan
                "stop", "abort", "done" -> CritiqueAction.Stop
                else -> CritiqueAction.Continue
            }
            val ok = when {
                obj.has("ok") -> obj.optBoolean("ok")
                obj.has("success") -> obj.optBoolean("success")
                else -> null
            }
            val summary = obj.optString("summary")
                .ifBlank { obj.optString("message") }
                .ifBlank { text.take(280) }
            val hint = obj.optString("hint")
                .ifBlank { obj.optString("next") }
            return ToolReflectionCritique(ok = ok, action = action, summary = summary.trim(), hint = hint.trim())
        }
        return ToolReflectionCritique(
            ok = null,
            action = CritiqueAction.Continue,
            summary = text.take(400)
        )
    }

    /** Local failure hint without an extra model call. */
    fun failureHeuristic(toolId: String, output: String): ToolReflectionCritique {
        val lower = output.lowercase()
        val action = when {
            lower.contains("timeout") || lower.contains("timed out") || lower.contains("超时") ->
                CritiqueAction.Retry
            lower.contains("not allow") || lower.contains("拒绝") || lower.contains("denied") ->
                CritiqueAction.Replan
            else -> CritiqueAction.Replan
        }
        return ToolReflectionCritique(
            ok = false,
            action = action,
            summary = "工具 $toolId 失败",
            hint = when (action) {
                CritiqueAction.Retry -> "可加大 timeout 或缩短命令后重试，勿盲目同参连打"
                else -> "换工具、改参数或先探测能力，勿重复无进展调用"
            }
        )
    }

    private fun extractJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }
}
