package com.clawdroid.app.mcp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.SecureRandom

data class McpServerUiState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val port: Int = McpSettingsStore.DEFAULT_PORT,
    val token: String = "",
    val statusText: String = "未启动",
    val endpointHint: String = ""
)

class McpServerController(
    private val appContext: Context,
    private val handlerFactory: () -> McpJsonRpcHandler
) {
    private val _state = MutableStateFlow(McpSettingsStore.load(appContext).toUiState())
    val state: StateFlow<McpServerUiState> = _state.asStateFlow()

    private var server: McpHttpServer? = null

    fun updatePort(port: Int) {
        val normalized = port.coerceIn(1024, 65535)
        persist { it.copy(port = normalized) }
        if (_state.value.enabled) {
            start()
        }
    }

    fun regenerateToken() {
        persist { it.copy(token = generateToken()) }
        if (_state.value.enabled) {
            start()
        }
    }

    fun setEnabled(enabled: Boolean) {
        persist { it.copy(enabled = enabled) }
        if (enabled) {
            start()
        } else {
            stop(persistDisabled = true)
        }
    }

    fun start() {
        pause()
        val cfg = McpSettingsStore.load(appContext)
        val token = cfg.token.ifBlank {
            generateToken().also { t ->
                McpSettingsStore.save(appContext, cfg.copy(token = t))
            }
        }
        val port = cfg.port.coerceIn(1024, 65535)
        val handler = handlerFactory()
        try {
            val http = McpHttpServer(handler = handler, port = port, authToken = token)
            http.start()
            server = http
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    enabled = false,
                    running = false,
                    token = token,
                    port = port,
                    statusText = "启动失败: ${error.message ?: error::class.java.simpleName}",
                    endpointHint = ""
                )
            }
            McpSettingsStore.save(appContext, cfg.copy(enabled = false, token = token, port = port))
            return
        }
        _state.update {
            it.copy(
                enabled = true,
                running = true,
                token = token,
                port = port,
                statusText = "运行中 (127.0.0.1:$port)",
                endpointHint = buildEndpointHint(port, token)
            )
        }
        McpSettingsStore.save(appContext, cfg.copy(enabled = true, token = token, port = port))
    }

    /** Stop listening without clearing the user's enabled preference. */
    fun pause() {
        runCatching { server?.stop() }
        server = null
        _state.update {
            it.copy(
                running = false,
                statusText = if (it.enabled) "已暂停（配置仍为开启）" else "已停止",
                endpointHint = if (it.enabled) buildEndpointHint(it.port, it.token) else ""
            )
        }
    }

    fun stop(persistDisabled: Boolean = true) {
        pause()
        if (persistDisabled) {
            _state.update {
                it.copy(
                    enabled = false,
                    statusText = "已停止",
                    endpointHint = ""
                )
            }
            val cfg = McpSettingsStore.load(appContext)
            McpSettingsStore.save(appContext, cfg.copy(enabled = false))
        }
    }

    fun restoreIfEnabled() {
        val cfg = McpSettingsStore.load(appContext)
        _state.value = cfg.toUiState()
        if (cfg.enabled) {
            start()
        }
    }

    private fun persist(transform: (McpSettingsStore.Config) -> McpSettingsStore.Config) {
        val next = transform(McpSettingsStore.load(appContext))
        McpSettingsStore.save(appContext, next)
        _state.update {
            it.copy(
                enabled = next.enabled,
                port = next.port,
                token = next.token,
                endpointHint = if (it.running) buildEndpointHint(next.port, next.token) else it.endpointHint
            )
        }
    }

    private fun buildEndpointHint(port: Int, token: String): String {
        return buildString {
            appendLine("电脑 → 手机（adb forward）:")
            appendLine("  adb forward tcp:$port tcp:$port")
            appendLine("SSE: http://127.0.0.1:$port/sse")
            appendLine("JSON: POST http://127.0.0.1:$port/mcp")
            appendLine("Header: Authorization: Bearer $token")
            appendLine("Cursor mcp.json url: http://127.0.0.1:$port/sse")
            appendLine("断开调试后需重新 forward。")
            append("手机 → 电脑请在下方「电脑协助端点」配置并执行 adb reverse。")
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun McpSettingsStore.Config.toUiState(): McpServerUiState {
        return McpServerUiState(
            enabled = enabled,
            running = false,
            port = port,
            token = token,
            statusText = if (enabled) "等待启动..." else "未启动",
            endpointHint = ""
        )
    }
}

object McpSettingsStore {
    const val DEFAULT_PORT = 8765
    private const val PREFS = "clawdroid_mcp_server"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PORT = "port"
    private const val KEY_TOKEN = "token"

    data class Config(
        val enabled: Boolean = false,
        val port: Int = DEFAULT_PORT,
        val token: String = ""
    )

    fun load(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            port = prefs.getInt(KEY_PORT, DEFAULT_PORT),
            token = prefs.getString(KEY_TOKEN, "").orEmpty()
        )
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_TOKEN, config.token)
            .apply()
    }
}
