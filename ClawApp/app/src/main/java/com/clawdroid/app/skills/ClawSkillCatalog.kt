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
        val assistBody = ClawAssetPromptStore.assistSkillBody(appContext).ifBlank {
            """
                # Assist MCP Bridge

                Prefer on-device tools; use assist_* when PC MCP is needed.
                1. assist_status 2. assist_ping 3. assist_list_tools 4. assist_call_tool
                Re-run adb reverse on tunnel_down.
            """.trimIndent()
        }
        return listOf(
            ClawSkill(
                id = "phone-runtime-ops",
                name = "Device Runtime Ops",
                description = "Use when connecting to ClawRuntime, checking module health, or diagnosing agent readiness.",
                relatedAgentId = "runtime_health_sweep",
                relatedTools = listOf(
                    "runtime_ping",
                    "probe_session",
                    "get_runtime_status",
                    "get_capabilities",
                    "get_last_error"
                ),
                bodyMarkdown = """
                    # Device Runtime Ops

                    Prefer on-device tools over guessing device state. Use assist_* only for PC-side work.

                    ## Workflow
                    1. `runtime_ping` or `probe_session`
                    2. `get_runtime_status` for Magisk/module/daemon summary
                    3. `get_capabilities` for feature switches
                    4. On failure, `get_last_error`

                    ## Rules
                    - Do not invent Root/Accessibility/LSPosed status.
                    - If auth or IPC fails, stop and report the exact tool output.
                    - Prefer `run_agent` with `runtime_health_sweep` for a full sweep.
                """.trimIndent()
            ),
            ClawSkill(
                id = "phone-ui-automation",
                name = "Device UI Automation",
                description = "Use when tapping, swiping, confirming pages, or driving UI through accessibility + Runtime inject.",
                relatedAgentId = "confirm_then_safe_tap",
                relatedTools = listOf(
                    "page_confirm",
                    "click_precheck",
                    "safe_tap",
                    "inject_tap",
                    "inject_swipe",
                    "inject_keyevent"
                ),
                bodyMarkdown = """
                    # Device UI Automation

                    ## Safe click path
                    1. `page_confirm` with expected package/text/viewId
                    2. `click_precheck` for the target
                    3. `safe_tap` using the resolved point

                    ## Direct inject
                    - `inject_tap` / `inject_swipe` / `inject_keyevent` when coordinates or keys are known
                    - Prefer named keys (`BACK`, `HOME`, `ENTER`) for `inject_keyevent`

                    ## Rules
                    - Prefer safe_tap over blind inject when a11y is available.
                    - If page_confirm fails, do not continue to tap.
                    - For multi-step flows, prefer `run_agent`.
                """.trimIndent()
            ),
            ClawSkill(
                id = "phone-capture-inspect",
                name = "Capture & File Inspect",
                description = "Use when taking screenshots, previewing captures, or reading/writing allowlisted files.",
                relatedAgentId = "capture_then_preview",
                relatedTools = listOf(
                    "capture_screen",
                    "read_latest_capture",
                    "file_read",
                    "file_write",
                    "file_stat",
                    "read_file_limited"
                ),
                bodyMarkdown = """
                    # Capture & File Inspect

                    ## Screenshot
                    - `capture_screen` with `read_after_capture=true` when the user wants to see the image
                    - Or run agent `capture_then_preview`

                    ## Files
                    - Prefer `file_read` / `file_write` / `file_replace` / `file_stat`
                    - Sandbox paths are Basic; system allowlisted paths need Runtime
                    - `read_file_limited` remains for compatibility

                    ## Rules
                    - Capture success does not imply preview success; check both outputs.
                """.trimIndent()
            ),
            ClawSkill(
                id = "phone-agent-orchestration",
                name = "Agent Orchestration",
                description = "Use when choosing multi-step agents/skills instead of calling many low-level tools manually.",
                relatedTools = listOf("list_skills", "get_skill", "list_agents", "run_agent", "list_tools", "get_tool"),
                bodyMarkdown = """
                    # Agent Orchestration

                    ## Discovery
                    - `list_tools` / `get_tool` for permissioned tool catalog
                    - `list_skills` / `get_skill` for guidance documents
                    - `list_agents` for executable multi-step workflows

                    ## Execution
                    - Call `run_agent` with an agent id from `list_agents`
                    - Pass optional swipe/page args when the agent supports them

                    ## Available agents
                    - `runtime_health_sweep`
                    - `probe_then_capabilities`
                    - `capture_then_preview`
                    - `swipe_then_capture`
                    - `confirm_then_safe_tap`
                    - `assist_then_runtime`

                    Prefer agents for recurring workflows; use atomic tools for one-off actions.
                    Prefer on-device tools; use assist_* for PC MCP.
                """.trimIndent()
            ),
            ClawSkill(
                id = "assist-mcp-bridge",
                name = "Assist MCP Bridge",
                description = "Use when calling computer MCP tools over adb reverse, or diagnosing assist tunnels.",
                relatedAgentId = "assist_then_runtime",
                relatedTools = listOf(
                    "assist_status",
                    "assist_ping",
                    "assist_list_tools",
                    "assist_call_tool"
                ),
                bodyMarkdown = assistBody
            )
        )
    }
}
