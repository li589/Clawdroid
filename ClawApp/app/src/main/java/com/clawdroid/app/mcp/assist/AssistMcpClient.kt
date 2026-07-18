package com.clawdroid.app.mcp.assist

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class AssistErrorCode {
    OK,
    DISABLED,
    TUNNEL_DOWN,
    AUTH_FAILED,
    TIMEOUT,
    PROTOCOL_ERROR,
    REMOTE_ERROR,
    UNKNOWN
}

data class AssistRpcResult(
    val ok: Boolean,
    val errorCode: AssistErrorCode = AssistErrorCode.OK,
    val latencyMs: Long = 0L,
    val result: JSONObject? = null,
    val raw: String = "",
    val message: String = ""
)

/**
 * Minimal MCP JSON-RPC client targeting a computer host over adb reverse.
 * Prefers `POST /mcp` request/response style (same as phone server).
 */
class AssistMcpClient(
    private val configProvider: () -> AssistMcpConfig
) {
    private val idSeq = AtomicLong(1)

    fun ping(): AssistRpcResult {
        val init = initialize()
        if (!init.ok) return init
        return call("ping", JSONObject())
    }

    fun initialize(): AssistRpcResult {
        return call(
            "initialize",
            JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put(
                    "capabilities",
                    JSONObject().put("roots", JSONObject()).put("sampling", JSONObject())
                )
                .put(
                    "clientInfo",
                    JSONObject().put("name", "clawdroid-assist-client").put("version", "0.2.0")
                )
        )
    }

    fun listTools(): AssistRpcResult = call("tools/list", JSONObject())

    fun callTool(name: String, arguments: JSONObject): AssistRpcResult {
        return call(
            "tools/call",
            JSONObject()
                .put("name", name)
                .put("arguments", arguments)
        )
    }

    fun call(method: String, params: JSONObject?): AssistRpcResult {
        val cfg = configProvider()
        if (!cfg.enabled) {
            return AssistRpcResult(
                ok = false,
                errorCode = AssistErrorCode.DISABLED,
                message = "协助 MCP 客户端未启用"
            )
        }
        val started = System.currentTimeMillis()
        val requestId = idSeq.getAndIncrement()
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestId)
            .put("method", method)
            .apply {
                if (params != null) put("params", params)
            }
            .toString()

        return try {
            val connection = open(cfg, body)
            val code = connection.responseCode
            val text = readBody(connection)
            val latency = System.currentTimeMillis() - started
            when {
                code == HttpURLConnection.HTTP_UNAUTHORIZED || code == 403 ->
                    AssistRpcResult(
                        ok = false,
                        errorCode = AssistErrorCode.AUTH_FAILED,
                        latencyMs = latency,
                        raw = text,
                        message = "鉴权失败 HTTP $code"
                    )
                code !in 200..299 ->
                    AssistRpcResult(
                        ok = false,
                        errorCode = classifyHttpFailure(code, text),
                        latencyMs = latency,
                        raw = text,
                        message = "HTTP $code: ${text.take(200)}"
                    )
                else -> parseRpc(text, latency)
            }
        } catch (error: java.net.SocketTimeoutException) {
            AssistRpcResult(
                ok = false,
                errorCode = AssistErrorCode.TIMEOUT,
                latencyMs = System.currentTimeMillis() - started,
                message = "超时: ${error.message}"
            )
        } catch (error: java.net.ConnectException) {
            AssistRpcResult(
                ok = false,
                errorCode = AssistErrorCode.TUNNEL_DOWN,
                latencyMs = System.currentTimeMillis() - started,
                message = "隧道不可达（请检查 adb reverse）: ${error.message}"
            )
        } catch (error: Exception) {
            AssistRpcResult(
                ok = false,
                errorCode = AssistErrorCode.UNKNOWN,
                latencyMs = System.currentTimeMillis() - started,
                message = error.message ?: error::class.java.simpleName
            )
        }
    }

    private fun parseRpc(text: String, latency: Long): AssistRpcResult {
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return AssistRpcResult(
                ok = false,
                errorCode = AssistErrorCode.PROTOCOL_ERROR,
                latencyMs = latency,
                raw = text,
                message = "响应不是 JSON"
            )
        if (obj.has("error") && !obj.isNull("error")) {
            val err = obj.getJSONObject("error")
            return AssistRpcResult(
                ok = false,
                errorCode = AssistErrorCode.REMOTE_ERROR,
                latencyMs = latency,
                result = err,
                raw = text,
                message = err.optString("message", "remote error")
            )
        }
        val result = when {
            obj.has("result") && obj.get("result") is JSONObject -> obj.getJSONObject("result")
            obj.has("result") -> JSONObject().put("value", obj.get("result"))
            else -> JSONObject()
        }
        return AssistRpcResult(
            ok = true,
            errorCode = AssistErrorCode.OK,
            latencyMs = latency,
            result = result,
            raw = text,
            message = "ok"
        )
    }

    private fun open(cfg: AssistMcpConfig, body: String): HttpURLConnection {
        val url = normalizeMcpUrl(cfg.hostUrl)
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = cfg.timeoutMs
        connection.readTimeout = cfg.timeoutMs
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        if (cfg.token.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer ${cfg.token}")
            connection.setRequestProperty("X-Clawdroid-Token", cfg.token)
        }
        connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
        return connection
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        return stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }
            .orEmpty()
    }

    private fun classifyHttpFailure(code: Int, text: String): AssistErrorCode {
        if (code == 404 || text.contains("Connection refused", ignoreCase = true)) {
            return AssistErrorCode.TUNNEL_DOWN
        }
        return AssistErrorCode.PROTOCOL_ERROR
    }

    companion object {
        fun normalizeMcpUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (url.endsWith("/sse")) {
                url = url.removeSuffix("/sse") + "/mcp"
            }
            if (!url.endsWith("/mcp") && !url.contains("/message")) {
                url = "$url/mcp"
            }
            return url
        }

        fun correlationId(): String = UUID.randomUUID().toString()

        fun toolsFromListResult(result: JSONObject?): JSONArray {
            return result?.optJSONArray("tools") ?: JSONArray()
        }
    }
}
