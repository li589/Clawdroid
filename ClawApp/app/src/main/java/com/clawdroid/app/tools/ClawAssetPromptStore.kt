package com.clawdroid.app.tools

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

data class PlannedToolBlueprint(
    val id: String,
    val tier: String,
    val domain: String,
    val status: String = "planned",
    val summary: String = ""
)

/**
 * Loads optional overlays / prompts from `assets/claw/`.
 * Missing assets fall back to empty / caller defaults.
 */
object ClawAssetPromptStore {
    private const val OVERLAY_PATH = "claw/tools/catalog.overlay.json"
    private const val ASSIST_PROMPT = "claw/prompts/assist-mcp.md"
    private const val TOOL_USAGE_PROMPT = "claw/prompts/tool-usage.md"
    private const val ASSIST_SKILL = "claw/skills/assist-mcp-bridge.md"

    const val PROMPT_ASSIST_MCP = "assist-mcp"
    const val PROMPT_TOOL_USAGE = "tool-usage"

    fun readText(context: Context?, assetPath: String): String? {
        if (context == null) return null
        return runCatching {
            context.assets.open(assetPath).use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun catalogOverlay(context: Context?): JSONObject {
        val raw = readText(context, OVERLAY_PATH) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    fun assistPrompt(context: Context?): String =
        readText(context, ASSIST_PROMPT).orEmpty()

    fun toolUsagePrompt(context: Context?): String =
        readText(context, TOOL_USAGE_PROMPT).orEmpty()

    fun assistSkillBody(context: Context?): String =
        readText(context, ASSIST_SKILL).orEmpty()

    fun overlayForTool(context: Context?, toolId: String): JSONObject? {
        val tools = catalogOverlay(context).optJSONObject("tools") ?: return null
        return tools.optJSONObject(toolId)
    }

    fun isToolEnabled(context: Context?, toolId: String, default: Boolean = true): Boolean {
        val overlay = overlayForTool(context, toolId) ?: return default
        if (overlay.has("enabled")) {
            return overlay.optBoolean("enabled", default)
        }
        val status = overlay.optString("status", "").lowercase()
        if (status == "planned" || status == "disabled") return false
        return default
    }

    fun plannedBlueprints(context: Context?): List<PlannedToolBlueprint> {
        val planned = catalogOverlay(context).optJSONObject("planned") ?: return emptyList()
        val result = mutableListOf<PlannedToolBlueprint>()
        val keys = planned.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val obj = planned.optJSONObject(id) ?: continue
            result += PlannedToolBlueprint(
                id = id,
                tier = obj.optString("tier", "Basic"),
                domain = obj.optString("domain", "misc"),
                status = obj.optString("status", "planned"),
                summary = obj.optString("summary", "蓝图占位，尚未实现执行器")
            )
        }
        return result.sortedBy { it.id }
    }

    fun plannedBlueprint(context: Context?, toolId: String): PlannedToolBlueprint? =
        plannedBlueprints(context).firstOrNull { it.id.equals(toolId.trim(), ignoreCase = true) }

    fun builtinPromptIds(): List<String> = listOf(PROMPT_ASSIST_MCP, PROMPT_TOOL_USAGE)

    fun builtinPromptBody(context: Context?, name: String): String? {
        return when (name.trim()) {
            PROMPT_ASSIST_MCP -> assistPrompt(context).takeIf { it.isNotBlank() }
            PROMPT_TOOL_USAGE -> toolUsagePrompt(context).takeIf { it.isNotBlank() }
            else -> null
        }
    }

    fun builtinPromptTitle(name: String): String = when (name) {
        PROMPT_ASSIST_MCP -> "Assist MCP Guide"
        PROMPT_TOOL_USAGE -> "Tool Usage Norms"
        else -> name
    }

    fun builtinPromptDescription(name: String): String = when (name) {
        PROMPT_ASSIST_MCP -> "Bidirectional assist MCP (adb forward/reverse) usage guide"
        PROMPT_TOOL_USAGE -> "Permissioned tool calling conventions"
        else -> ""
    }
}
