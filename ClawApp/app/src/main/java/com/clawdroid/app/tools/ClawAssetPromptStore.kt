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

enum class ClawTextPackKind {
    Index,
    Prompt,
    Helper,
    Agent,
    Skill,
    ToolOverlay
}

data class ClawTextPackEntry(
    val id: String,
    val kind: ClawTextPackKind,
    val title: String,
    val description: String,
    val assetPath: String
)

/**
 * Loads prompts / skills / helpers from `assets/claw/`.
 * Missing assets fall back to empty / caller defaults.
 */
object ClawAssetPromptStore {
    private const val OVERLAY_PATH = "claw/tools/catalog.overlay.json"
    private const val INDEX_PATH = "claw/INDEX.md"

    const val PROMPT_ASSIST_MCP = "assist-mcp"
    const val PROMPT_TOOL_USAGE = "tool-usage"
    const val PROMPT_ORCHESTRATOR = "orchestrator"
    const val PROMPT_CONTINUE = "continue"
    const val PROMPT_TOOL_REFLECTION = "tool-reflection"
    const val PROMPT_CONTEXT_COMPRESS = "context-compress"
    const val PROMPT_CHAT_WELCOME = "chat-welcome"
    const val PROMPT_CHAT_SUGGESTIONS = "chat-suggestions"

    private val skillAssetIds = listOf(
        "phone-runtime-ops",
        "phone-ui-automation",
        "phone-capture-inspect",
        "phone-agent-orchestration",
        "assist-mcp-bridge"
    )

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
        readText(context, "claw/prompts/assist-mcp.md").orEmpty()

    fun toolUsagePrompt(context: Context?): String =
        readText(context, "claw/prompts/tool-usage.md").orEmpty()

    fun orchestratorPrompt(context: Context?): String =
        readText(context, "claw/prompts/orchestrator.md").orEmpty()

    fun continuePrompt(context: Context?): String =
        readText(context, "claw/prompts/continue.md").orEmpty()

    fun toolReflectionPrompt(context: Context?): String =
        readText(context, "claw/prompts/tool-reflection.md").orEmpty()

    fun contextCompressPrompt(context: Context?): String =
        readText(context, "claw/prompts/context-compress.md").orEmpty()

    fun chatWelcomePrompt(context: Context?): String =
        readText(context, "claw/prompts/chat-welcome.md").orEmpty()

    fun chatSuggestions(context: Context?): List<String> {
        val raw = readText(context, "claw/prompts/chat-suggestions.txt").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
    }

    fun routingHints(context: Context?): String =
        readText(context, "claw/helpers/routing-hints.md").orEmpty()

    fun agentPhrasesMarkdown(context: Context?): String =
        readText(context, "claw/helpers/agent-phrases.md").orEmpty()

    fun agentCatalogMarkdown(context: Context?): String =
        readText(context, "claw/agents/catalog.md").orEmpty()

    fun indexMarkdown(context: Context?): String =
        readText(context, INDEX_PATH).orEmpty()

    fun skillBody(context: Context?, skillId: String): String =
        readText(context, "claw/skills/${skillId.trim()}.md").orEmpty()

    fun assistSkillBody(context: Context?): String =
        skillBody(context, "assist-mcp-bridge")

    fun phraseForTool(context: Context?, toolId: String): String? {
        val md = agentPhrasesMarkdown(context)
        if (md.isBlank()) return null
        val needle = "| ${toolId.trim()} |"
        val line = md.lineSequence().firstOrNull { it.contains(needle) } ?: return null
        val cells = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        return cells.getOrNull(1)?.takeIf { it.isNotBlank() && it != "_default" }
    }

    fun defaultToolPhrase(context: Context?): String {
        val md = agentPhrasesMarkdown(context)
        val line = md.lineSequence().firstOrNull { it.contains("| _default |") }
        val cells = line?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }
        return cells?.getOrNull(1) ?: "我先尝试执行这个动作。"
    }

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

    fun listPackEntries(): List<ClawTextPackEntry> {
        val prompts = listOf(
            ClawTextPackEntry(PROMPT_ORCHESTRATOR, ClawTextPackKind.Prompt, "编排系统提示", "AI 工具决策主提示", "claw/prompts/orchestrator.md"),
            ClawTextPackEntry(PROMPT_CONTINUE, ClawTextPackKind.Prompt, "多步续写提示", "工具循环继续决策", "claw/prompts/continue.md"),
            ClawTextPackEntry(PROMPT_TOOL_REFLECTION, ClawTextPackKind.Prompt, "工具总结提示", "工具结果中文总结", "claw/prompts/tool-reflection.md"),
            ClawTextPackEntry(PROMPT_TOOL_USAGE, ClawTextPackKind.Prompt, "工具调用规范", "权限与调用约定", "claw/prompts/tool-usage.md"),
            ClawTextPackEntry(PROMPT_ASSIST_MCP, ClawTextPackKind.Prompt, "协助 MCP 指南", "adb forward/reverse 双向桥", "claw/prompts/assist-mcp.md"),
            ClawTextPackEntry(PROMPT_CHAT_WELCOME, ClawTextPackKind.Prompt, "聊天欢迎语", "新对话首条助手消息", "claw/prompts/chat-welcome.md"),
            ClawTextPackEntry(PROMPT_CHAT_SUGGESTIONS, ClawTextPackKind.Prompt, "聊天建议词", "输入区上方建议 chip", "claw/prompts/chat-suggestions.txt")
        )
        val helpers = listOf(
            ClawTextPackEntry("routing-hints", ClawTextPackKind.Helper, "路由意图辅助", "自然语言 vs 规则直达", "claw/helpers/routing-hints.md"),
            ClawTextPackEntry("agent-phrases", ClawTextPackKind.Helper, "Agent 辅助短句", "执行工具前的口播短句", "claw/helpers/agent-phrases.md")
        )
        val agents = listOf(
            ClawTextPackEntry("agent-catalog", ClawTextPackKind.Agent, "Agent 能力集", "多步 Agent 说明与选用", "claw/agents/catalog.md")
        )
        val skills = skillAssetIds.map { id ->
            ClawTextPackEntry(
                id = id,
                kind = ClawTextPackKind.Skill,
                title = id,
                description = "Skill 正文 assets/claw/skills/$id.md",
                assetPath = "claw/skills/$id.md"
            )
        }
        return listOf(
            ClawTextPackEntry("index", ClawTextPackKind.Index, "文本包索引", "assets/claw 目录说明", INDEX_PATH)
        ) + prompts + helpers + agents + skills + listOf(
            ClawTextPackEntry(
                "catalog-overlay",
                ClawTextPackKind.ToolOverlay,
                "工具目录覆盖",
                "catalog.overlay.json",
                OVERLAY_PATH
            )
        )
    }

    fun readPackBody(context: Context?, entry: ClawTextPackEntry): String =
        readText(context, entry.assetPath).orEmpty()

    fun builtinPromptIds(): List<String> = listOf(
        PROMPT_ORCHESTRATOR,
        PROMPT_CONTINUE,
        PROMPT_TOOL_REFLECTION,
        PROMPT_TOOL_USAGE,
        PROMPT_ASSIST_MCP,
        PROMPT_CHAT_WELCOME
    )

    fun builtinPromptBody(context: Context?, name: String): String? {
        return when (name.trim()) {
            PROMPT_ASSIST_MCP -> assistPrompt(context).takeIf { it.isNotBlank() }
            PROMPT_TOOL_USAGE -> toolUsagePrompt(context).takeIf { it.isNotBlank() }
            PROMPT_ORCHESTRATOR -> orchestratorPrompt(context).takeIf { it.isNotBlank() }
            PROMPT_CONTINUE -> continuePrompt(context).takeIf { it.isNotBlank() }
            PROMPT_TOOL_REFLECTION -> toolReflectionPrompt(context).takeIf { it.isNotBlank() }
            PROMPT_CHAT_WELCOME -> chatWelcomePrompt(context).takeIf { it.isNotBlank() }
            else -> null
        }
    }

    fun builtinPromptTitle(name: String): String = when (name) {
        PROMPT_ASSIST_MCP -> "Assist MCP Guide"
        PROMPT_TOOL_USAGE -> "Tool Usage Norms"
        PROMPT_ORCHESTRATOR -> "Orchestrator Prompt"
        PROMPT_CONTINUE -> "Continue Loop Prompt"
        PROMPT_TOOL_REFLECTION -> "Tool Reflection Prompt"
        PROMPT_CHAT_WELCOME -> "Chat Welcome"
        else -> name
    }

    fun builtinPromptDescription(name: String): String = when (name) {
        PROMPT_ASSIST_MCP -> "Bidirectional assist MCP (adb forward/reverse) usage guide"
        PROMPT_TOOL_USAGE -> "Permissioned tool calling conventions"
        PROMPT_ORCHESTRATOR -> "Primary AI orchestration system prompt"
        PROMPT_CONTINUE -> "Multi-step tool loop continuation prompt"
        PROMPT_TOOL_REFLECTION -> "Summarize tool results for the user"
        PROMPT_CHAT_WELCOME -> "Welcome message for new chat sessions"
        else -> ""
    }
}
