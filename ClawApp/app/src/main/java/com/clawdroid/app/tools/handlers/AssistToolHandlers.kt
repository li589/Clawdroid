package com.clawdroid.app.tools.handlers

import com.clawdroid.app.mcp.assist.AssistMcpClient
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ToolHandler
import com.clawdroid.app.tools.ToolServiceRegistry
import org.json.JSONObject

fun assistToolHandlers(services: ToolServiceRegistry): Map<ClawTool, ToolHandler> = mapOf(
    ClawTool.ASSIST_PING to ToolHandler { _, _ ->
        services.assistOrMissing { ctrl ->
            val result = ctrl.ping()
            ClawToolCallResult(
                success = result.ok,
                output = "ok=${result.ok} code=${result.errorCode} latencyMs=${result.latencyMs}\n${result.message}\n${result.raw.take(500)}",
                error = if (result.ok) null else result.errorCode.name.lowercase()
            )
        }
    },
    ClawTool.ASSIST_LIST_TOOLS to ToolHandler { _, _ ->
        services.assistOrMissing { ctrl ->
            val result = ctrl.listTools()
            val tools = AssistMcpClient.toolsFromListResult(result.result)
            ClawToolCallResult(
                success = result.ok,
                output = if (result.ok) {
                    buildString {
                        appendLine("count=${tools.length()} latencyMs=${result.latencyMs}")
                        for (i in 0 until tools.length()) {
                            val t = tools.optJSONObject(i) ?: continue
                            appendLine("- ${t.optString("name")}: ${t.optString("description").take(120)}")
                        }
                    }
                } else {
                    result.message
                },
                error = if (result.ok) null else result.errorCode.name.lowercase()
            )
        }
    },
    ClawTool.ASSIST_CALL_TOOL to ToolHandler { _, arguments ->
        services.assistOrMissing { ctrl ->
            val name = arguments.string("name", "tool", "tool_name")
            if (name.isBlank()) {
                return@assistOrMissing ClawToolCallResult(false, "失败: name 不能为空", error = "missing_name")
            }
            val argsJson = arguments.string("arguments_json", "arguments")
            val argsObj = when {
                argsJson.isBlank() -> JSONObject()
                argsJson.trim().startsWith("{") -> runCatching { JSONObject(argsJson) }.getOrElse {
                    return@assistOrMissing ClawToolCallResult(false, "arguments_json 非法 JSON", error = "invalid_arguments")
                }
                else -> JSONObject()
            }
            val nested = arguments["arguments"]
            if (nested is Map<*, *>) {
                nested.forEach { (k, v) -> if (k != null) argsObj.put(k.toString(), v) }
            }
            val correlation = AssistMcpClient.correlationId()
            val result = ctrl.callTool(name, argsObj)
            ClawToolCallResult(
                success = result.ok,
                output = buildString {
                    appendLine("correlationId=$correlation")
                    appendLine("ok=${result.ok} code=${result.errorCode} latencyMs=${result.latencyMs}")
                    append(result.result?.toString(2) ?: result.message)
                },
                error = if (result.ok) null else result.errorCode.name.lowercase()
            )
        }
    },
    ClawTool.ASSIST_STATUS to ToolHandler { _, _ ->
        services.assistOrMissing { ctrl ->
            ClawToolCallResult(success = true, output = ctrl.statusSnapshot().toString(2))
        }
    }
)
