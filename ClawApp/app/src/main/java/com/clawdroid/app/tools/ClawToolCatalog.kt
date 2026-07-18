package com.clawdroid.app.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool catalog with JSON Schema for MCP `tools/list` and AI planners.
 * Permission metadata comes from [ClawToolDefinitions].
 */
object ClawToolCatalog {
    data class ToolDefinition(
        val tool: ClawTool,
        val inputSchema: JSONObject,
        val annotations: JSONObject = JSONObject(),
        val spec: ClawToolSpec = ClawToolDefinitions.spec(tool)
    ) {
        val name: String get() = tool.toolId
        val title: String get() = tool.displayName
        val description: String get() = tool.description
    }

    data class Availability(
        val available: Boolean,
        val reason: String = ""
    )

    fun definitions(
        context: Context? = null,
        includePlanned: Boolean = false,
        onlyEnabled: Boolean = true
    ): List<ToolDefinition> {
        return ClawTool.entries.mapNotNull { tool ->
            val spec = ClawToolDefinitions.spec(tool)
            if (!includePlanned && spec.status == ToolAvailability.Planned) return@mapNotNull null
            if (onlyEnabled && !ClawAssetPromptStore.isToolEnabled(context, tool.toolId, default = true)) {
                return@mapNotNull null
            }
            ToolDefinition(
                tool = tool,
                inputSchema = inputSchemaFor(tool),
                annotations = annotationsFor(spec, availabilityFor(spec)),
                spec = spec
            )
        }
    }

    fun definition(toolId: String, context: Context? = null): ToolDefinition? =
        definitions(context, includePlanned = true, onlyEnabled = false)
            .firstOrNull { it.name == toolId }

    fun availabilityFor(
        spec: ClawToolSpec,
        capabilities: Set<String> = LiveToolCapabilityStore.snapshot()
    ): Availability {
        if (spec.status == ToolAvailability.Planned) {
            return Availability(false, "tool_planned")
        }
        if (spec.requiredCapabilities.isNotEmpty() && capabilities.isEmpty()) {
            return Availability(false, "capabilities_unknown")
        }
        if (spec.requiredCapabilities.isEmpty()) {
            return Availability(true)
        }
        val missing = spec.requiredCapabilities.filterNot { it in capabilities }
        return if (missing.isEmpty()) {
            Availability(true)
        } else {
            Availability(false, "capability_missing:${missing.joinToString(",")}")
        }
    }

    fun toMcpToolsJson(
        context: Context? = null,
        includeUnavailable: Boolean = true,
        capabilities: Set<String> = LiveToolCapabilityStore.snapshot()
    ): JSONArray {
        val tools = JSONArray()
        definitions(context).forEach { def ->
            val availability = availabilityFor(def.spec, capabilities)
            if (!includeUnavailable && !availability.available) return@forEach
            tools.put(toMcpToolJson(def, context, availability))
        }
        return tools
    }

    fun toMcpToolJson(
        def: ToolDefinition,
        context: Context? = null,
        availability: Availability = availabilityFor(def.spec)
    ): JSONObject {
        val overlay = ClawAssetPromptStore.overlayForTool(context, def.name)
        val description = overlay?.optString("summary")?.takeIf { it.isNotBlank() }
            ?: def.description
        return JSONObject()
            .put("name", def.name)
            .put("title", def.title)
            .put("description", description)
            .put("inputSchema", def.inputSchema)
            .put("annotations", annotationsFor(def.spec, availability))
            .put("clawdroid", metaObject(def.spec, overlay, availability))
    }

    fun toCatalogResourceJson(context: Context? = null): JSONObject {
        val arr = JSONArray()
        definitions(context, includePlanned = true, onlyEnabled = false).forEach { def ->
            arr.put(toMcpToolJson(def, context))
        }
        val planned = JSONArray()
        ClawAssetPromptStore.plannedBlueprints(context).forEach { bp ->
            planned.put(plannedBlueprintJson(bp))
        }
        return JSONObject()
            .put("version", 1)
            .put("tools", arr)
            .put("planned", planned)
            .put(
                "permission_tiers",
                JSONArray(ToolPermissionTier.entries.map { it.name })
            )
            .put(
                "liveCapabilities",
                JSONArray(LiveToolCapabilityStore.snapshot().toList())
            )
    }

    fun describeTool(toolId: String, context: Context? = null): JSONObject? {
        val def = definition(toolId, context)
        if (def != null) {
            return toMcpToolJson(def, context)
                .put("outputSchema", outputSchemaFor(def.tool))
                .put("callNotes", def.spec.callNotes)
                .put("constraints", def.spec.constraints)
                .put("examples", JSONArray(def.spec.examples))
        }
        val planned = ClawAssetPromptStore.plannedBlueprint(context, toolId) ?: return null
        return plannedBlueprintJson(planned)
    }

    fun plannedBlueprintJson(bp: PlannedToolBlueprint): JSONObject {
        return JSONObject()
            .put("name", bp.id)
            .put("title", bp.id)
            .put("description", bp.summary.ifBlank { "蓝图占位，尚未实现执行器" })
            .put("inputSchema", emptyObjSchema())
            .put(
                "annotations",
                JSONObject()
                    .put("readOnlyHint", true)
                    .put("destructiveHint", false)
                    .put("openWorldHint", false)
            )
            .put(
                "clawdroid",
                JSONObject()
                    .put("tier", bp.tier)
                    .put("domain", bp.domain)
                    .put("status", "Planned")
                    .put("available", false)
                    .put("unavailableReason", "tool_planned")
                    .put("tags", JSONArray().put(bp.domain).put("planned"))
            )
    }

    fun aiCatalogLines(context: Context? = null): String {
        return definitions(context).joinToString("\n") { def ->
            val availability = availabilityFor(def.spec)
            val flag = if (availability.available) "ok" else "unavailable"
            "- ${def.name} [$flag/${def.spec.tier.name}/${def.spec.risk.name}]: ${def.description}"
        }
    }

    /**
     * Argument keys allowed for AI / loose callers, derived from [inputSchemaFor].
     * Returns `null` when the tool has an empty input schema (passthrough / no key filter).
     * Includes aliases accepted by [ClawToolDispatcher] (e.g. `agent` for `agent_id`).
     */
    fun allowedArgumentKeys(tool: ClawTool): Set<String>? {
        val props = inputSchemaFor(tool).optJSONObject("properties") ?: return null
        if (props.length() == 0) return null
        val keys = linkedSetOf<String>()
        val iterator = props.keys()
        while (iterator.hasNext()) {
            keys += iterator.next()
        }
        keys += ARGUMENT_ALIASES[tool].orEmpty()
        return keys
    }

    private val ARGUMENT_ALIASES: Map<ClawTool, Set<String>> = mapOf(
        ClawTool.GET_SKILL to setOf("id", "name"),
        ClawTool.RUN_AGENT to setOf("agent", "id", "name"),
        ClawTool.TASK_GET to setOf("id"),
        ClawTool.TASK_CANCEL to setOf("id"),
        ClawTool.TASK_SUBMIT to setOf("steps"),
        ClawTool.GET_TOOL to setOf("id", "name"),
        ClawTool.APP_STOP to setOf("package_name"),
        ClawTool.APP_INFO to setOf("package_name"),
        ClawTool.APP_LAUNCH to setOf("package_name", "data")
    )

    private fun metaObject(
        spec: ClawToolSpec,
        overlay: JSONObject?,
        availability: Availability
    ): JSONObject {
        return JSONObject()
            .put("tier", spec.tier.name)
            .put("tierDisplay", spec.tier.displayName)
            .put("grants", JSONArray(spec.grants.map { it.name }))
            .put("backend", spec.backend.name)
            .put("risk", spec.risk.name)
            .put("tags", JSONArray(spec.tags.toList()))
            .put("callNotes", overlay?.optString("callNotes")?.takeIf { it.isNotBlank() } ?: spec.callNotes)
            .put("constraints", overlay?.optString("constraints")?.takeIf { it.isNotBlank() } ?: spec.constraints)
            .put("requiredCapabilities", JSONArray(spec.requiredCapabilities.toList()))
            .put("status", spec.status.name)
            .put("available", availability.available)
            .put("unavailableReason", availability.reason.ifBlank { JSONObject.NULL })
            .put("deprecatedBy", spec.deprecatedBy ?: JSONObject.NULL)
    }

    private fun annotationsFor(spec: ClawToolSpec, availability: Availability): JSONObject {
        return JSONObject()
            .put("readOnlyHint", spec.risk == ToolRisk.Read)
            .put("destructiveHint", spec.risk == ToolRisk.Destructive)
            .put("openWorldHint", ToolPermissionGrant.INTERNET in spec.grants || spec.backend == ToolBackend.Assist)
            .put("unavailable", !availability.available)
    }

    private fun outputSchemaFor(tool: ClawTool): JSONObject {
        return JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("success", JSONObject().put("type", "boolean"))
                    .put("output", JSONObject().put("type", "string"))
                    .put("error", JSONObject().put("type", "string"))
            )
            .put("description", "ClawToolCallResult shape for ${tool.toolId}")
    }

    private fun inputSchemaFor(tool: ClawTool): JSONObject {
        return when (tool) {
            ClawTool.PAGE_CONFIRM -> objSchema(
                optionalString("expected_package", "Expected app package name"),
                optionalString("expected_text", "Visible text that should appear"),
                optionalString("expected_view_id", "Expected viewId substring")
            )
            ClawTool.CLICK_PRECHECK -> objSchema(
                optionalString("expected_package", "Expected app package name"),
                optionalString("target_text", "Clickable target text"),
                optionalString("target_view_id", "Clickable target viewId")
            )
            ClawTool.RUNTIME_PING,
            ClawTool.GET_VERSION,
            ClawTool.GET_HEALTH,
            ClawTool.GET_RUNTIME_STATUS,
            ClawTool.GET_LAST_ERROR,
            ClawTool.PROBE_SESSION,
            ClawTool.GET_CAPABILITIES,
            ClawTool.READ_LATEST_CAPTURE,
            ClawTool.LIST_SKILLS,
            ClawTool.LIST_AGENTS,
            ClawTool.SAFE_TAP,
            ClawTool.ASSIST_PING,
            ClawTool.ASSIST_LIST_TOOLS,
            ClawTool.ASSIST_STATUS,
            ClawTool.TASK_LIST -> emptyObjSchema()
            ClawTool.GET_SKILL -> objSchema(
                requiredString("skill_id", "Skill id from list_skills"),
                required = listOf("skill_id")
            )
            ClawTool.RUN_AGENT -> objSchema(
                requiredString("agent_id", "Agent id from list_agents"),
                optionalString("expected_package", "For confirm_then_safe_tap"),
                optionalString("expected_text", "For confirm_then_safe_tap / click target text"),
                optionalString("expected_view_id", "For confirm_then_safe_tap"),
                optionalString("target_text", "Optional click target text"),
                optionalString("target_view_id", "Optional click target viewId"),
                optionalInt("x1", "Swipe start X", default = 540),
                optionalInt("y1", "Swipe start Y", default = 1800),
                optionalInt("x2", "Swipe end X", default = 540),
                optionalInt("y2", "Swipe end Y", default = 400),
                optionalInt("duration_ms", "Swipe duration", default = 350),
                optionalInt("display_id", "Display id", default = 0),
                required = listOf("agent_id")
            )
            ClawTool.CAPTURE_SCREEN -> objSchema(
                optionalBool("read_after_capture", "Also read/preview the capture after taking it", default = false),
                optionalInt("display_id", "Display id", default = 0)
            )
            ClawTool.READ_FILE_LIMITED -> objSchema(
                requiredString("path", "Absolute path under Runtime allowlist"),
                optionalInt("offset", "Byte offset", default = 0),
                optionalInt("max_bytes", "Max bytes to read (chunk)", default = 65536),
                required = listOf("path")
            )
            ClawTool.INJECT_TAP -> objSchema(
                requiredInt("x", "Tap X"),
                requiredInt("y", "Tap Y"),
                optionalInt("display_id", "Display id", default = 0),
                required = listOf("x", "y")
            )
            ClawTool.INJECT_KEYEVENT -> objSchema(
                optionalString("key", "Named key, e.g. BACK/HOME/ENTER"),
                optionalInt("keycode", "Android keycode"),
                optionalInt("display_id", "Display id", default = 0)
            )
            ClawTool.INJECT_SWIPE -> objSchema(
                requiredInt("x1", "Start X"),
                requiredInt("y1", "Start Y"),
                requiredInt("x2", "End X"),
                requiredInt("y2", "End Y"),
                optionalInt("duration_ms", "Swipe duration ms", default = 350),
                optionalInt("display_id", "Display id", default = 0),
                required = listOf("x1", "y1", "x2", "y2")
            )
            ClawTool.EXECUTE_SHELL_LIMITED -> objSchema(
                requiredString("command", "Allowlisted shell command"),
                required = listOf("command")
            )
            ClawTool.SUBSCRIBE_EVENTS -> objSchema(
                optionalString("operation", "start or stop", default = "start")
            )
            ClawTool.TASK_SUBMIT -> objSchema(
                optionalString("task_json", "Full task JSON object string"),
                optionalString("task_id", "Task id when using steps_json"),
                optionalString("name", "Optional task name"),
                optionalString("steps_json", "JSON array of steps [{action,args,...}]")
            )
            ClawTool.TASK_GET -> objSchema(
                requiredString("task_id", "Runtime task id"),
                required = listOf("task_id")
            )
            ClawTool.TASK_CANCEL -> objSchema(
                requiredString("task_id", "Runtime task id to cancel"),
                required = listOf("task_id")
            )
            ClawTool.LIST_TOOLS -> objSchema(
                optionalString("tag", "Filter by tag, e.g. file/app/assist"),
                optionalString("tier", "Filter by tier: None/Basic/Accessibility/AdbShizuku/Root"),
                optionalString("id_prefix", "Filter by tool id prefix"),
                optionalBool("include_planned", "Include planned blueprint tools", default = false)
            )
            ClawTool.GET_TOOL -> objSchema(
                requiredString("tool_id", "Tool id"),
                required = listOf("tool_id")
            )
            ClawTool.ASSIST_CALL_TOOL -> objSchema(
                requiredString("name", "Remote MCP tool name"),
                optionalString("arguments_json", "JSON object string of arguments"),
                required = listOf("name")
            )
            ClawTool.FILE_READ -> objSchema(
                requiredString("path", "Absolute path or app-relative path"),
                optionalString("mode", "bytes | lines | columns", default = "bytes"),
                optionalInt("offset", "Byte offset when mode=bytes", default = 0),
                optionalInt("max_bytes", "Max bytes", default = 65536),
                optionalInt("line_start", "1-based line start when mode=lines/columns", default = 1),
                optionalInt("line_limit", "Max lines when mode=lines/columns", default = 200),
                optionalString("delimiter", "Column delimiter when mode=columns", default = ","),
                optionalInt("column", "0-based column index when mode=columns", default = 0),
                required = listOf("path")
            )
            ClawTool.FILE_WRITE -> objSchema(
                requiredString("path", "Target path"),
                requiredString("content", "Text content to write"),
                optionalBool("append", "Append instead of overwrite", default = false),
                optionalString("encoding", "utf-8", default = "utf-8"),
                required = listOf("path", "content")
            )
            ClawTool.FILE_REPLACE -> objSchema(
                requiredString("path", "Target path"),
                requiredString("find", "Text or regex to find"),
                requiredString("replace", "Replacement text"),
                optionalBool("regex", "Treat find as regex", default = false),
                optionalInt("line_start", "Optional 1-based line start"),
                optionalInt("line_end", "Optional 1-based line end inclusive"),
                required = listOf("path", "find", "replace")
            )
            ClawTool.FILE_STAT -> objSchema(
                requiredString("path", "Target path"),
                optionalBool("compute_hash", "Compute sha256", default = true),
                required = listOf("path")
            )
            ClawTool.APP_LIST -> objSchema(
                optionalString("query", "Filter substring for package or label"),
                optionalInt("limit", "Max results", default = 50)
            )
            ClawTool.APP_LAUNCH -> objSchema(
                optionalString("package", "Package name"),
                optionalString("action", "Intent action"),
                optionalString("data_uri", "Intent data URI")
            )
            ClawTool.APP_STOP -> objSchema(
                requiredString("package", "Package to force-stop"),
                required = listOf("package")
            )
            ClawTool.APP_INFO -> objSchema(
                requiredString("package", "Package name"),
                required = listOf("package")
            )
            ClawTool.DOWNLOAD_START -> objSchema(
                requiredString("url", "HTTP/HTTPS URL"),
                optionalString("dest_path", "Destination path (default app downloads cache)"),
                optionalString("expected_sha256", "Optional expected hash"),
                optionalInt("threads", "Parallel Range workers 1-8 (requires Accept-Ranges)", default = 1),
                optionalBool("resume", "Use HTTP Range resume when possible (forces single thread)", default = true),
                required = listOf("url")
            )
            ClawTool.DOWNLOAD_STATUS -> objSchema(
                optionalString("download_id", "Download task id; omit to list all")
            )
            ClawTool.DOWNLOAD_CANCEL -> objSchema(
                requiredString("download_id", "Download task id"),
                required = listOf("download_id")
            )
            ClawTool.DOWNLOAD_VERIFY -> objSchema(
                requiredString("path", "Local file path"),
                optionalString("expected_sha256", "Expected sha256 hex"),
                required = listOf("path")
            )
            ClawTool.NOTIFICATION_LIST -> objSchema(
                optionalString("query", "Filter by package / title / text"),
                optionalInt("limit", "Max notifications", default = 50)
            )
            ClawTool.WEB_PREVIEW -> objSchema(
                requiredString("url", "http(s) URL"),
                optionalInt("max_bytes", "Max response bytes to parse", default = 512_000),
                optionalBool("include_images", "Extract img src URLs", default = true),
                required = listOf("url")
            )
            ClawTool.WEB_SEARCH -> objSchema(
                requiredString("query", "Search keywords"),
                optionalInt("max_results", "Max hits 1-10", default = 5),
                optionalString("provider", "auto | wikipedia | ddg", default = "auto"),
                required = listOf("query")
            )
            ClawTool.SANDBOX_SHELL -> objSchema(
                requiredString("command", "Allowlisted sandbox command"),
                optionalInt("timeout_ms", "Timeout milliseconds", default = 8000),
                required = listOf("command")
            )
            ClawTool.CAMERA_CAPTURE -> objSchema(
                optionalString("facing", "back | front", default = "back"),
                optionalInt("max_dimension", "Max JPEG edge pixels", default = 1280)
            )
            ClawTool.SENSOR_READ -> objSchema(
                optionalString("op", "list | read", default = "read"),
                optionalString("type", "accelerometer | gyroscope | light | proximity | magnetic_field"),
                optionalInt("duration_ms", "Max wait for samples", default = 0),
                optionalInt("max_samples", "Samples to collect 1-32", default = 1)
            )
            ClawTool.CAMERA_RECORD -> objSchema(
                optionalString("facing", "back | front", default = "back"),
                optionalInt("duration_ms", "Clip length 500-15000", default = 3000),
                optionalInt("max_dimension", "Max video edge pixels", default = 1280)
            )
            ClawTool.FTP_TRANSFER -> objSchema(
                requiredString("op", "list | get | put"),
                requiredString("host", "FTP/SFTP host"),
                optionalString("protocol", "ftp | sftp", default = "ftp"),
                optionalInt("port", "Port (default 21 ftp / 22 sftp)"),
                optionalString("user", "Username", default = "anonymous"),
                optionalString("password", "Password"),
                optionalString("remote_path", "Remote path", default = "/"),
                optionalString("local_path", "Sandbox-relative or absolute sandbox path"),
                optionalBool("passive", "FTP passive mode (ignored for sftp)", default = true),
                optionalInt("timeout_ms", "Socket timeout", default = 15000),
                required = listOf("op", "host")
            )
            ClawTool.GPU_NPU_PROBE -> emptyObjSchema()
            ClawTool.SHIZUKU_STATUS -> emptyObjSchema()
            ClawTool.SHIZUKU_REQUEST -> emptyObjSchema()
            ClawTool.SHIZUKU_EXEC -> objSchema(
                requiredString("command", "Allowlisted short shell command"),
                required = listOf("command")
            )
        }
    }

    private fun emptyObjSchema(): JSONObject =
        JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())
            .put("additionalProperties", false)

    private fun objSchema(
        vararg props: Pair<String, JSONObject>,
        required: List<String> = emptyList()
    ): JSONObject {
        val properties = JSONObject()
        props.forEach { (name, schema) -> properties.put(name, schema) }
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("additionalProperties", false)
        if (required.isNotEmpty()) {
            schema.put("required", JSONArray(required))
        }
        return schema
    }

    private fun requiredString(name: String, description: String): Pair<String, JSONObject> =
        name to JSONObject().put("type", "string").put("description", description)

    private fun optionalString(
        name: String,
        description: String,
        default: String? = null
    ): Pair<String, JSONObject> {
        val schema = JSONObject().put("type", "string").put("description", description)
        if (default != null) schema.put("default", default)
        return name to schema
    }

    private fun requiredInt(name: String, description: String): Pair<String, JSONObject> =
        name to JSONObject().put("type", "integer").put("description", description)

    private fun optionalInt(
        name: String,
        description: String,
        default: Int? = null
    ): Pair<String, JSONObject> {
        val schema = JSONObject().put("type", "integer").put("description", description)
        if (default != null) schema.put("default", default)
        return name to schema
    }

    private fun optionalBool(
        name: String,
        description: String,
        default: Boolean? = null
    ): Pair<String, JSONObject> {
        val schema = JSONObject().put("type", "boolean").put("description", description)
        if (default != null) schema.put("default", default)
        return name to schema
    }
}
