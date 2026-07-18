package com.clawdroid.app.mcp.assist

import android.content.Context
import com.clawdroid.app.ui.AppSecretCipher

data class AssistMcpConfig(
    val enabled: Boolean = false,
    val hostUrl: String = "http://127.0.0.1:8766/mcp",
    val token: String = "",
    val timeoutMs: Int = 15_000
)

object AssistMcpSettingsStore {
    private const val PREFS = "clawdroid_assist_mcp"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOST = "host_url"
    private const val KEY_TOKEN = "token_encrypted"
    private const val KEY_TIMEOUT = "timeout_ms"

    fun load(context: Context): AssistMcpConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tokenEnc = prefs.getString(KEY_TOKEN, "").orEmpty()
        val token = if (tokenEnc.isBlank()) {
            ""
        } else {
            runCatching { AppSecretCipher.decrypt(tokenEnc) }.getOrDefault("")
        }
        return AssistMcpConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            hostUrl = prefs.getString(KEY_HOST, "http://127.0.0.1:8766/mcp").orEmpty()
                .ifBlank { "http://127.0.0.1:8766/mcp" },
            token = token,
            timeoutMs = prefs.getInt(KEY_TIMEOUT, 15_000).coerceIn(3_000, 120_000)
        )
    }

    fun save(context: Context, config: AssistMcpConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_HOST, config.hostUrl.trim())
            .putString(
                KEY_TOKEN,
                if (config.token.isBlank()) "" else AppSecretCipher.encrypt(config.token)
            )
            .putInt(KEY_TIMEOUT, config.timeoutMs.coerceIn(3_000, 120_000))
            .apply()
    }
}
