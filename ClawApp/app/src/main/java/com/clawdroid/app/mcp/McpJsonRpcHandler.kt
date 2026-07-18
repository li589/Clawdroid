package com.clawdroid.app.mcp

import com.clawdroid.app.fault.FaultCodes
import com.clawdroid.app.fault.FaultIsolation
import com.clawdroid.app.skills.ClawAgentCatalog
import com.clawdroid.app.skills.ClawSkillCatalog
import com.clawdroid.app.tools.CapabilityProbe
import com.clawdroid.app.tools.ClawAssetPromptStore
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolCatalog
import com.clawdroid.app.tools.ClawToolDispatcher
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/**
 * MCP JSON-RPC 2.0 handler: tools + prompts (Skills) + resources.
 */
class McpJsonRpcHandler(
    private val dispatcher: ClawToolDispatcher,
    private val serverName: String = "clawdroid-assist",
    private val serverVersion: String = "0.3.0",
    private val appContext: android.content.Context? = null,
    private val capabilityProbe: CapabilityProbe? = null
) {
    companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        const val JSONRPC = "2.0"
        private val SUPPORTED_PROTOCOL_VERSIONS = setOf(
            "2024-11-05",
            "2025-03-26"
        )
    }

    suspend fun handle(raw: String): String? {
        val request = runCatching { JSONObject(raw) }.getOrNull()
            ?: return errorResponse(null, -32700, "Parse error").toString()

        val method = request.optString("method", "")
        val id = if (request.has("id") && !request.isNull("id")) request.get("id") else null
        val isNotification = !request.has("id") || request.isNull("id")

        return try {
            when (method) {
                "initialize" -> success(id, initializeResult(request.optJSONObject("params")))
                "notifications/initialized", "initialized" -> null
                "ping" -> success(id, JSONObject())
                "tools/list" -> {
                    runCatching { capabilityProbe?.refreshIfStale() }
                    success(
                        id,
                        JSONObject().put("tools", ClawToolCatalog.toMcpToolsJson(appContext))
                    )
                }
                "tools/call" -> {
                    if (isNotification) null else success(id, callTool(request.optJSONObject("params"), id))
                }
                "prompts/list" -> success(
                    id,
                    JSONObject().put("prompts", mergePromptList())
                )
                "prompts/get" -> {
                    if (isNotification) {
                        null
                    } else {
                        val params = request.optJSONObject("params")
                        val name = params?.optString("name").orEmpty().trim()
                        when {
                            name.isEmpty() -> errorResponse(id, -32602, "Invalid params: prompt name is required").toString()
                            ClawSkillCatalog.byId(name) != null -> success(id, getPrompt(params))
                            ClawAssetPromptStore.builtinPromptBody(appContext, name) != null ->
                                success(id, getBuiltinPrompt(name))
                            else ->
                                errorResponse(id, -32602, "Invalid params: unknown prompt '$name'").toString()
                        }
                    }
                }
                "resources/list" -> success(id, JSONObject().put("resources", listResources()))
                "resources/read" -> {
                    if (isNotification) {
                        null
                    } else {
                        val params = request.optJSONObject("params")
                        val uri = params?.optString("uri").orEmpty().trim()
                        when {
                            uri.isEmpty() -> errorResponse(id, -32602, "Invalid params: uri is required").toString()
                            !isKnownResourceUri(uri) ->
                                errorResponse(id, -32602, "Invalid params: unsupported uri '$uri'").toString()
                            else -> success(id, readResource(params))
                        }
                    }
                }
                "resources/templates/list" -> success(
                    id,
                    JSONObject().put("resourceTemplates", resourceTemplates())
                )
                "" -> errorResponse(id, -32600, "Invalid Request").toString()
                else -> {
                    if (isNotification) {
                        null
                    } else {
                        errorResponse(id, -32601, "Method not found: $method").toString()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            FaultIsolation.recordFault("mcp:handle", error)
            if (isNotification) {
                null
            } else {
                errorResponse(
                    id,
                    -32603,
                    "Internal error (${FaultCodes.MCP_INTERNAL}): ${error.message ?: error::class.java.simpleName}"
                ).toString()
            }
        }
    }

    private suspend fun callTool(params: JSONObject?, requestId: Any?): JSONObject {
        return try {
            if (params == null) {
                return toolError("missing params")
            }
            val name = params.optString("name", "").trim()
            if (name.isEmpty()) {
                return toolError("tool name is required")
            }
            val argsJson = params.optJSONObject("arguments") ?: JSONObject()
            val arguments = argsJson.toMap()
            val correlationId = UUID.randomUUID().toString()
            val result = dispatcher.execute(name, arguments)
            toToolResult(result, requestId = requestId, correlationId = correlationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            FaultIsolation.recordFault("mcp:callTool", error)
            throw error
        }
    }

    private fun getPrompt(params: JSONObject?): JSONObject {
        val name = params?.optString("name").orEmpty().trim()
        val skill = requireNotNull(ClawSkillCatalog.byId(name))
        val args = params?.optJSONObject("arguments")?.toMap().orEmpty()
        return ClawSkillCatalog.toPromptGetResult(skill, args)
    }

    private fun isKnownResourceUri(uri: String): Boolean {
        return uri == "clawdroid://catalog/tools" ||
            uri == "clawdroid://catalog/agents" ||
            uri == "clawdroid://catalog/skills" ||
            uri == "clawdroid://prompt/assist-mcp" ||
            uri == "clawdroid://prompt/tool-usage" ||
            (uri.startsWith("clawdroid://skill/") &&
                ClawSkillCatalog.byId(uri.removePrefix("clawdroid://skill/")) != null) ||
            (uri.startsWith("clawdroid://agent/") &&
                ClawAgentCatalog.byId(uri.removePrefix("clawdroid://agent/")) != null)
    }

    private fun mergePromptList(): JSONArray {
        val arr = ClawSkillCatalog.toPromptListJson()
        ClawAssetPromptStore.builtinPromptIds().forEach { id ->
            arr.put(
                JSONObject()
                    .put("name", id)
                    .put("title", ClawAssetPromptStore.builtinPromptTitle(id))
                    .put("description", ClawAssetPromptStore.builtinPromptDescription(id))
                    .put("arguments", JSONArray())
            )
        }
        return arr
    }

    private fun getBuiltinPrompt(name: String): JSONObject {
        val text = ClawAssetPromptStore.builtinPromptBody(appContext, name).orEmpty()
        return JSONObject()
            .put("description", ClawAssetPromptStore.builtinPromptDescription(name))
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONObject()
                                .put("type", "text")
                                .put("text", text)
                        )
                )
            )
    }

    private fun listResources(): JSONArray {
        val resources = JSONArray()
        resources.put(
            resourceMeta(
                uri = "clawdroid://catalog/tools",
                name = "Tool Catalog",
                description = "JSON list of MCP tools with schemas",
                mimeType = "application/json"
            )
        )
        resources.put(
            resourceMeta(
                uri = "clawdroid://prompt/assist-mcp",
                name = "Assist MCP Guide",
                description = ClawAssetPromptStore.builtinPromptDescription(ClawAssetPromptStore.PROMPT_ASSIST_MCP),
                mimeType = "text/markdown"
            )
        )
        resources.put(
            resourceMeta(
                uri = "clawdroid://prompt/tool-usage",
                name = "Tool Usage Norms",
                description = ClawAssetPromptStore.builtinPromptDescription(ClawAssetPromptStore.PROMPT_TOOL_USAGE),
                mimeType = "text/markdown"
            )
        )
        resources.put(
            resourceMeta(
                uri = "clawdroid://catalog/agents",
                name = "Agent Catalog",
                description = "Executable multi-step phone agents",
                mimeType = "application/json"
            )
        )
        resources.put(
            resourceMeta(
                uri = "clawdroid://catalog/skills",
                name = "Skill Catalog",
                description = "Cursor-style skills available on device",
                mimeType = "application/json"
            )
        )
        ClawSkillCatalog.all().forEach { skill ->
            resources.put(
                resourceMeta(
                    uri = "clawdroid://skill/${skill.id}",
                    name = skill.name,
                    description = skill.description,
                    mimeType = "text/markdown"
                )
            )
        }
        ClawAgentCatalog.all().forEach { agent ->
            resources.put(
                resourceMeta(
                    uri = "clawdroid://agent/${agent.id}",
                    name = agent.name,
                    description = agent.description,
                    mimeType = "application/json"
                )
            )
        }
        return resources
    }

    private fun resourceTemplates(): JSONArray {
        return JSONArray()
            .put(
                JSONObject()
                    .put("uriTemplate", "clawdroid://skill/{skill_id}")
                    .put("name", "Skill Markdown")
                    .put("description", "Read a skill SKILL.md by id")
                    .put("mimeType", "text/markdown")
            )
            .put(
                JSONObject()
                    .put("uriTemplate", "clawdroid://agent/{agent_id}")
                    .put("name", "Agent Definition")
                    .put("description", "Read an agent definition by id")
                    .put("mimeType", "application/json")
            )
    }

    private fun readResource(params: JSONObject?): JSONObject {
        val uri = params?.optString("uri").orEmpty().trim()
        if (uri.isEmpty()) {
            return JSONObject()
                .put("contents", JSONArray())
        }
        val text = when {
            uri == "clawdroid://catalog/tools" ->
                ClawToolCatalog.toCatalogResourceJson(appContext).toString(2)
            uri == "clawdroid://catalog/agents" -> agentsJson().toString(2)
            uri == "clawdroid://catalog/skills" -> skillsJson().toString(2)
            uri == "clawdroid://prompt/assist-mcp" ->
                ClawAssetPromptStore.assistPrompt(appContext).ifBlank { "# Assist MCP\n(empty asset)" }
            uri == "clawdroid://prompt/tool-usage" ->
                ClawAssetPromptStore.toolUsagePrompt(appContext).ifBlank { "# Tool Usage\n(empty asset)" }
            uri.startsWith("clawdroid://skill/") -> {
                val id = uri.removePrefix("clawdroid://skill/")
                val skill = ClawSkillCatalog.byId(id)
                    ?: return contentsError("unknown skill: $id")
                ClawSkillCatalog.toSkillMd(skill)
            }
            uri.startsWith("clawdroid://agent/") -> {
                val id = uri.removePrefix("clawdroid://agent/")
                val agent = ClawAgentCatalog.byId(id)
                    ?: return contentsError("unknown agent: $id")
                JSONObject()
                    .put("id", agent.id)
                    .put("name", agent.name)
                    .put("description", agent.description)
                    .put("steps", JSONArray(agent.steps))
                    .put("step_titles", JSONArray(agent.stepTitles))
                    .put("skill_id", agent.skillId)
                    .toString(2)
            }
            else -> return contentsError("unsupported uri: $uri")
        }
        val mime = when {
            uri.endsWith(".md") ||
                uri.startsWith("clawdroid://skill/") ||
                uri.startsWith("clawdroid://prompt/") -> "text/markdown"
            else -> "application/json"
        }
        return JSONObject().put(
            "contents",
            JSONArray().put(
                JSONObject()
                    .put("uri", uri)
                    .put("mimeType", mime)
                    .put("text", text)
            )
        )
    }

    private fun agentsJson(): JSONArray {
        val arr = JSONArray()
        ClawAgentCatalog.all().forEach { agent ->
            arr.put(
                JSONObject()
                    .put("id", agent.id)
                    .put("name", agent.name)
                    .put("description", agent.description)
                    .put("steps", JSONArray(agent.steps))
                    .put("step_titles", JSONArray(agent.stepTitles))
                    .put("skill_id", agent.skillId)
            )
        }
        return arr
    }

    private fun skillsJson(): JSONArray {
        val arr = JSONArray()
        ClawSkillCatalog.all().forEach { skill ->
            arr.put(
                JSONObject()
                    .put("id", skill.id)
                    .put("name", skill.name)
                    .put("description", skill.description)
                    .put("related_agent_id", skill.relatedAgentId)
                    .put("related_tools", JSONArray(skill.relatedTools))
            )
        }
        return arr
    }

    private fun resourceMeta(
        uri: String,
        name: String,
        description: String,
        mimeType: String
    ): JSONObject {
        return JSONObject()
            .put("uri", uri)
            .put("name", name)
            .put("description", description)
            .put("mimeType", mimeType)
    }

    private fun contentsError(message: String): JSONObject {
        return JSONObject().put(
            "contents",
            JSONArray().put(
                JSONObject()
                    .put("uri", "clawdroid://error")
                    .put("mimeType", "text/plain")
                    .put("text", message)
            )
        )
    }

    private fun toToolResult(
        result: ClawToolCallResult,
        requestId: Any? = null,
        correlationId: String = UUID.randomUUID().toString()
    ): JSONObject {
        val content = JSONArray()
        content.put(
            JSONObject()
                .put("type", "text")
                .put("text", result.output)
        )
        result.shellOutput?.takeIf { it.isNotBlank() }?.let { shell ->
            content.put(
                JSONObject()
                    .put("type", "text")
                    .put("text", "shell_output:\n$shell")
            )
        }
        result.previewBytes?.takeIf { it.isNotEmpty() }?.let { bytes ->
            val mime = when (result.captureArtifact?.format?.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                else -> "image/png"
            }
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put("mimeType", mime)
                    .put("data", Base64.getEncoder().encodeToString(bytes))
            )
        }
        return JSONObject()
            .put("content", content)
            .put("isError", !result.success)
            .put(
                "_meta",
                JSONObject()
                    .put("requestId", requestId?.toString() ?: JSONObject.NULL)
                    .put("correlationId", correlationId)
                    .put("error", result.error ?: JSONObject.NULL)
            )
    }

    private fun toolError(message: String): JSONObject {
        return JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject().put("type", "text").put("text", message)
                )
            )
            .put("isError", true)
    }

    private fun initializeResult(params: JSONObject?): JSONObject {
        val requested = params?.optString("protocolVersion").orEmpty().trim()
        val version = when {
            requested.isBlank() -> PROTOCOL_VERSION
            requested in SUPPORTED_PROTOCOL_VERSIONS -> requested
            else -> PROTOCOL_VERSION
        }
        return JSONObject()
            .put("protocolVersion", version)
            .put(
                "capabilities",
                JSONObject()
                    .put("tools", JSONObject().put("listChanged", false))
                    .put("prompts", JSONObject().put("listChanged", false))
                    .put(
                        "resources",
                        JSONObject()
                            .put("subscribe", false)
                            .put("listChanged", false)
                    )
            )
            .put(
                "serverInfo",
                JSONObject()
                    .put("name", serverName)
                    .put("version", serverVersion)
                    .put(
                        "roles",
                        JSONArray().put("phone-server").put("phone-client")
                    )
                    .put(
                        "clawdroid",
                        JSONObject()
                            .put("assistBidirectional", true)
                            .put("adbForward", true)
                            .put("adbReverseClient", true)
                    )
            )
            .put(
                "instructions",
                buildString {
                    appendLine("Clawdroid 协助 MCP: 本机工具 + Skills + Agents；可用 assist_* 回调电脑 MCP。")
                    appendLine("1) Prefer list_tools / prompts/list for discovery.")
                    appendLine("2) Prefer on-device tools; use assist_call_tool only for PC-side work.")
                    appendLine("3) Prefer Authorization: Bearer <token> (query token is discouraged).")
                    appendLine("4) On tunnel errors, re-run adb forward/reverse.")
                    append("Agents: ${ClawAgentCatalog.all().joinToString { it.id }}")
                }
            )
    }

    private fun success(id: Any?, result: JSONObject): String {
        val response = JSONObject()
            .put("jsonrpc", JSONRPC)
            .put("result", result)
        if (id != null) {
            response.put("id", id)
        } else {
            response.put("id", JSONObject.NULL)
        }
        return response.toString()
    }

    private fun errorResponse(id: Any?, code: Int, message: String): JSONObject {
        val response = JSONObject()
            .put("jsonrpc", JSONRPC)
            .put(
                "error",
                JSONObject()
                    .put("code", code)
                    .put("message", message)
            )
        if (id != null) {
            response.put("id", id)
        } else {
            response.put("id", JSONObject.NULL)
        }
        return response
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = normalize(opt(key))
        }
        return out
    }

    private fun normalize(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> value.toMap()
            is JSONArray -> buildList {
                for (i in 0 until value.length()) {
                    add(normalize(value.opt(i)))
                }
            }
            else -> value
        }
    }
}

internal fun newMcpSessionId(): String = UUID.randomUUID().toString().replace("-", "")
