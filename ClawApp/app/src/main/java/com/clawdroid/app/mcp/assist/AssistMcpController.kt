package com.clawdroid.app.mcp.assist

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.net.URI

data class AssistMcpUiState(
    val enabled: Boolean = false,
    val hostUrl: String = "http://127.0.0.1:8766/mcp",
    val token: String = "",
    val timeoutMs: Int = 15_000,
    val statusText: String = "未启用",
    val endpointHint: String = "",
    val lastError: String = "",
    val lastLatencyMs: Long = 0L,
    val lastErrorCode: String = ""
)

class AssistMcpController(
    private val appContext: Context
) {
    private val _state = MutableStateFlow(AssistMcpSettingsStore.load(appContext).toUiState())
    val state: StateFlow<AssistMcpUiState> = _state.asStateFlow()

    private val client = AssistMcpClient { AssistMcpSettingsStore.load(appContext) }

    fun isEnabled(): Boolean = AssistMcpSettingsStore.load(appContext).enabled

    fun setEnabled(enabled: Boolean) {
        persist { it.copy(enabled = enabled) }
        refreshHint()
        if (enabled) {
            probe()
        } else {
            _state.update {
                it.copy(statusText = "未启用", lastError = "", lastErrorCode = "")
            }
        }
    }

    fun updateHostUrl(url: String) {
        persist { it.copy(hostUrl = url.trim()) }
        refreshHint()
    }

    fun updateToken(token: String) {
        persist { it.copy(token = token) }
    }

    fun updateTimeoutMs(timeoutMs: Int) {
        persist { it.copy(timeoutMs = timeoutMs.coerceIn(3_000, 120_000)) }
    }

    fun probe() {
        val result = client.ping()
        _state.update {
            it.copy(
                statusText = if (result.ok) {
                    "已连通 (${result.latencyMs}ms)"
                } else {
                    "不可用: ${result.errorCode.name.lowercase()} — ${result.message}"
                },
                lastError = if (result.ok) "" else result.message,
                lastErrorCode = result.errorCode.name,
                lastLatencyMs = result.latencyMs
            )
        }
    }

    fun ping(): AssistRpcResult = client.ping().also { applyResult("ping", it) }

    fun listTools(): AssistRpcResult = client.listTools().also { applyResult("tools/list", it) }

    fun callTool(name: String, arguments: JSONObject): AssistRpcResult =
        client.callTool(name, arguments).also { applyResult("tools/call:$name", it) }

    fun statusSnapshot(): JSONObject {
        val cfg = AssistMcpSettingsStore.load(appContext)
        val ui = _state.value
        return JSONObject()
            .put("enabled", cfg.enabled)
            .put("hostUrl", cfg.hostUrl)
            .put("hasToken", cfg.token.isNotBlank())
            .put("timeoutMs", cfg.timeoutMs)
            .put("statusText", ui.statusText)
            .put("lastError", ui.lastError)
            .put("lastErrorCode", ui.lastErrorCode)
            .put("lastLatencyMs", ui.lastLatencyMs)
            .put("endpointHint", ui.endpointHint)
    }

    private fun applyResult(op: String, result: AssistRpcResult) {
        _state.update {
            it.copy(
                statusText = if (result.ok) {
                    "$op 成功 (${result.latencyMs}ms)"
                } else {
                    "$op 失败: ${result.errorCode.name.lowercase()}"
                },
                lastError = if (result.ok) "" else result.message,
                lastErrorCode = result.errorCode.name,
                lastLatencyMs = result.latencyMs
            )
        }
    }

    private fun persist(transform: (AssistMcpConfig) -> AssistMcpConfig) {
        val next = transform(AssistMcpSettingsStore.load(appContext))
        AssistMcpSettingsStore.save(appContext, next)
        _state.update { next.toUiState(endpointHint = it.endpointHint, statusText = it.statusText) }
    }

    private fun refreshHint() {
        val cfg = AssistMcpSettingsStore.load(appContext)
        val port = extractPort(cfg.hostUrl) ?: 8766
        val hint = buildString {
            appendLine("手机 → 电脑（adb reverse）:")
            appendLine("  adb reverse tcp:$port tcp:$port")
            appendLine("Host URL 示例: http://127.0.0.1:$port/mcp")
            appendLine("断开 USB/无线调试后需重新执行 reverse。")
            append("大文件请用 download_*，勿经 MCP 传二进制。")
        }
        _state.update { it.copy(endpointHint = hint) }
    }

    private fun AssistMcpConfig.toUiState(
        endpointHint: String = "",
        statusText: String = if (enabled) "已启用（未探测）" else "未启用"
    ): AssistMcpUiState {
        val port = extractPort(hostUrl) ?: 8766
        val hint = endpointHint.ifBlank {
            "adb reverse tcp:$port tcp:$port\nHost: $hostUrl"
        }
        return AssistMcpUiState(
            enabled = enabled,
            hostUrl = hostUrl,
            token = token,
            timeoutMs = timeoutMs,
            statusText = statusText,
            endpointHint = hint
        )
    }

    private fun extractPort(url: String): Int? {
        return runCatching {
            val uri = URI(url.trim())
            when {
                uri.port > 0 -> uri.port
                uri.scheme.equals("https", true) -> 443
                uri.scheme.equals("http", true) -> 80
                else -> null
            }
        }.getOrNull()
    }

    init {
        refreshHint()
    }
}
