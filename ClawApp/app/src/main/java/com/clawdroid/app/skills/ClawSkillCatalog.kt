package com.clawdroid.app.skills

import android.content.Context
import com.clawdroid.app.tools.ClawAssetPromptStore

/**
 * Phone-side Skill definition compatible with Cursor-style SKILL.md semantics.
 * Exposed via MCP prompts and resources (clawdroid://skill/...).
 */
data class ClawSkill(
    val id: String,
    val name: String,
    val description: String,
    val bodyMarkdown: String,
    val relatedAgentId: String? = null,
    val relatedTools: List<String> = emptyList()
)

object ClawSkillCatalog {
    @Volatile
    private var appContext: Context? = null

    fun bindContext(context: Context) {
        appContext = context.applicationContext
    }

    fun all(): List<ClawSkill> = builtins()

    fun byId(id: String): ClawSkill? =
        builtins().firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }

    fun toPromptListJson(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        builtins().forEach { skill ->
            arr.put(
                org.json.JSONObject()
                    .put("name", skill.id)
                    .put("title", skill.name)
                    .put("description", skill.description)
                    .put("arguments", org.json.JSONArray())
            )
        }
        return arr
    }

    fun toPromptGetResult(skill: ClawSkill, arguments: Map<String, Any?> = emptyMap()): org.json.JSONObject {
        val goal = arguments["goal"]?.toString()?.trim().orEmpty()
        val text = buildString {
            append(skill.bodyMarkdown.trim())
            if (goal.isNotEmpty()) {
                append("\n\n## Current user goal\n")
                append(goal)
            }
        }
        return org.json.JSONObject()
            .put("description", skill.description)
            .put(
                "messages",
                org.json.JSONArray().put(
                    org.json.JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            org.json.JSONObject()
                                .put("type", "text")
                                .put("text", text)
                        )
                )
            )
    }

    fun toSkillMd(skill: ClawSkill): String {
        return buildString {
            appendLine("---")
            appendLine("name: ${skill.id}")
            appendLine("description: ${skill.description}")
            appendLine("---")
            appendLine()
            append(skill.bodyMarkdown.trim())
            appendLine()
        }
    }

    private fun builtins(): List<ClawSkill> {
        return listOf(
            ClawSkill(
                id = "phone-runtime-ops",
                name = "Device Runtime Ops",
                description = "连接 ClawRuntime、检查模块健康或诊断 Agent 就绪时使用。",
                relatedAgentId = "runtime_health_sweep",
                relatedTools = listOf(
                    "runtime_ping",
                    "probe_session",
                    "get_runtime_status",
                    "get_capabilities",
                    "get_last_error"
                ),
                bodyMarkdown = skillMarkdown(
                    "phone-runtime-ops",
                    "# Device Runtime Ops\n\nPrefer runtime_ping / probe_session / get_capabilities. Use run_agent(runtime_health_sweep)."
                )
            ),
            ClawSkill(
                id = "phone-ui-automation",
                name = "Device UI Automation",
                description = "点击、滑动、页面确认或 inject 驱动界面时使用。",
                relatedAgentId = "confirm_then_safe_tap",
                relatedTools = listOf(
                    "page_confirm",
                    "click_precheck",
                    "safe_tap",
                    "inject_tap",
                    "inject_swipe",
                    "inject_keyevent"
                ),
                bodyMarkdown = skillMarkdown(
                    "phone-ui-automation",
                    "# Device UI Automation\n\npage_confirm → click_precheck → safe_tap. Prefer run_agent for multi-step."
                )
            ),
            ClawSkill(
                id = "phone-capture-inspect",
                name = "Capture & File Inspect",
                description = "截图、预览或读写白名单文件时使用。",
                relatedAgentId = "capture_then_preview",
                relatedTools = listOf(
                    "capture_screen",
                    "read_latest_capture",
                    "file_read",
                    "file_write",
                    "file_stat",
                    "read_file_limited"
                ),
                bodyMarkdown = skillMarkdown(
                    "phone-capture-inspect",
                    "# Capture & File Inspect\n\ncapture_screen(read_after_capture=true) or run_agent(capture_then_preview)."
                )
            ),
            ClawSkill(
                id = "phone-agent-orchestration",
                name = "Agent Orchestration",
                description = "选择多步 Agent/Skill，而不是手搓大量底层工具时使用。",
                relatedTools = listOf("list_skills", "get_skill", "list_agents", "run_agent", "list_tools", "get_tool"),
                bodyMarkdown = skillMarkdown(
                    "phone-agent-orchestration",
                    "# Agent Orchestration\n\nlist_agents / run_agent. Prefer agents for recurring workflows."
                )
            ),
            ClawSkill(
                id = "assist-mcp-bridge",
                name = "Assist MCP Bridge",
                description = "通过 adb reverse 调用电脑 MCP，或诊断协助隧道时使用。",
                relatedAgentId = "assist_then_runtime",
                relatedTools = listOf(
                    "assist_status",
                    "assist_ping",
                    "assist_list_tools",
                    "assist_call_tool"
                ),
                bodyMarkdown = skillMarkdown(
                    "assist-mcp-bridge",
                    "# Assist MCP Bridge\n\nassist_status → assist_ping → assist_list_tools → assist_call_tool."
                )
            )
        )
    }

    private fun skillMarkdown(skillId: String, fallback: String): String {
        return ClawAssetPromptStore.skillBody(appContext, skillId).ifBlank { fallback.trimIndent() }
    }
}
