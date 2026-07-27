package com.clawdroid.app.data

import android.content.Context
import android.content.SharedPreferences
import com.clawdroid.app.data.model.AgentOrchestrationSettings
import com.clawdroid.app.data.model.ContextSettings
import com.clawdroid.app.data.model.ModelProvider
import com.clawdroid.app.data.model.ModelSettings
import com.clawdroid.app.data.model.NetworkProxyMode
import com.clawdroid.app.data.model.NetworkProxySettings
import com.clawdroid.app.data.model.ThemeMode
import com.clawdroid.app.data.model.UrlPathMode
import com.clawdroid.app.data.model.defaultBaseUrlFor
import org.json.JSONArray
import org.json.JSONObject

internal object AppSettingsStore {
    private const val prefsName = "clawdroid_app_settings"
    private const val keyThemeMode = "theme_mode"
    private const val keyModelProvider = "model_provider"
    private const val keyModelBaseUrl = "model_base_url"
    private const val keyModelApiKeyLegacy = "model_api_key"
    private const val keyModelApiKeyEncrypted = "model_api_key_encrypted"
    private const val keyModelName = "model_name"
    private const val keyLocalEndpoint = "local_endpoint"
    private const val keyLocalModelName = "local_model_name"
    private const val keyCustomApiPath = "custom_api_path"
    private const val keyUrlPathMode = "url_path_mode"
    private const val keyContextSettings = "context_settings"
    private const val keyNetworkProxy = "network_proxy_settings"
    private const val keyAgentOrchestration = "agent_orchestration_settings"

    private var registeredListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @JvmStatic
    fun registerListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        unregisterListener(context)
        registeredListener = listener
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(listener)
    }

    @JvmStatic
    fun unregisterListener(context: Context) {
        registeredListener?.let { listener ->
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(listener)
            registeredListener = null
        }
    }

    fun loadThemeMode(context: Context): ThemeMode {
        val raw = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(keyThemeMode, ThemeMode.FollowSystem.name)
            .orEmpty()
        return ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.FollowSystem
    }

    fun saveThemeMode(context: Context, themeMode: ThemeMode) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(keyThemeMode, themeMode.name)
            .apply()
    }

    fun loadModelSettings(context: Context): ModelSettings {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val apiKey = loadApiKey(prefs)
        val provider = prefs.getString(keyModelProvider, ModelProvider.OpenAI.name)
            .orEmpty()
            .let { raw ->
                ModelProvider.entries.firstOrNull { it.name == raw } ?: ModelProvider.OpenAI
            }
        val baseUrl = prefs.getString(keyModelBaseUrl, null).orEmpty()
            .takeIf { it.isNotBlank() }
            ?: defaultBaseUrlFor(provider)
        val urlPathMode = prefs.getString(keyUrlPathMode, null).orEmpty()
            .let { raw ->
                UrlPathMode.entries.firstOrNull { it.name == raw } ?: UrlPathMode.AutoAppend
            }
        val contextSettings = loadContextSettings(prefs)
        val proxySettings = loadProxySettings(prefs)

        return ModelSettings(
            provider = provider,
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelName = prefs.getString(keyModelName, "").orEmpty(),
            localEndpoint = prefs.getString(keyLocalEndpoint, "http://127.0.0.1:11434/v1").orEmpty(),
            localModelName = prefs.getString(keyLocalModelName, "").orEmpty(),
            customApiPath = prefs.getString(keyCustomApiPath, "/chat/completions").orEmpty(),
            urlPathMode = urlPathMode,
            proxySettings = proxySettings,
            contextSettings = contextSettings
        )
    }

    fun saveModelSettings(context: Context, settings: ModelSettings) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(keyModelProvider, settings.provider.name)
            .putString(keyModelBaseUrl, settings.baseUrl)
            .putString(keyModelApiKeyEncrypted, AppSecretCipher.encrypt(settings.apiKey))
            .remove(keyModelApiKeyLegacy)
            .putString(keyModelName, settings.modelName)
            .putString(keyLocalEndpoint, settings.localEndpoint)
            .putString(keyLocalModelName, settings.localModelName)
            .putString(keyCustomApiPath, settings.customApiPath)
            .putString(keyUrlPathMode, settings.urlPathMode.name)
            .putString(keyNetworkProxy, serializeProxySettings(settings.proxySettings))
            .putString(keyContextSettings, serializeContextSettings(settings.contextSettings))
            .apply()
    }

    fun loadAgentOrchestrationSettings(context: Context): AgentOrchestrationSettings {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val json = prefs.getString(keyAgentOrchestration, null).orEmpty()
        if (json.isBlank()) return AgentOrchestrationSettings()
        return try {
            val obj = JSONObject(json)
            val allowArr = obj.optJSONArray("toolAllowlist")
            val allowlist = if (allowArr != null) {
                (0 until allowArr.length()).mapNotNull { idx ->
                    allowArr.optString(idx)?.takeIf { it.isNotBlank() }
                }.toSet()
            } else {
                emptySet()
            }
            AgentOrchestrationSettings(
                maxToolLoopTurns = obj.optInt(
                    "maxToolLoopTurns",
                    AgentOrchestrationSettings.DEFAULT_MAX_TOOL_LOOP_TURNS
                ).coerceIn(
                    AgentOrchestrationSettings.MIN_TOOL_LOOP_TURNS,
                    AgentOrchestrationSettings.MAX_TOOL_LOOP_TURNS_CAP
                ),
                maxModelApiCalls = obj.optInt(
                    "maxModelApiCalls",
                    AgentOrchestrationSettings.DEFAULT_MAX_MODEL_API_CALLS
                ).coerceIn(
                    AgentOrchestrationSettings.MIN_MODEL_API_CALLS,
                    AgentOrchestrationSettings.MAX_MODEL_API_CALLS_CAP
                ),
                contextCompressionEnabled = obj.optBoolean("contextCompressionEnabled", true),
                toolAllowlist = allowlist,
                toolAllowlistCustomized = obj.optBoolean("toolAllowlistCustomized", false),
                enforceToolAllowlist = obj.optBoolean("enforceToolAllowlist", true),
                agentCallsEnabled = obj.optBoolean("agentCallsEnabled", true),
                requireCommandReview = obj.optBoolean("requireCommandReview", false)
            )
        } catch (_: Exception) {
            AgentOrchestrationSettings()
        }
    }

    fun saveAgentOrchestrationSettings(context: Context, settings: AgentOrchestrationSettings) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val payload = JSONObject().apply {
            put(
                "maxToolLoopTurns",
                settings.maxToolLoopTurns.coerceIn(
                    AgentOrchestrationSettings.MIN_TOOL_LOOP_TURNS,
                    AgentOrchestrationSettings.MAX_TOOL_LOOP_TURNS_CAP
                )
            )
            put(
                "maxModelApiCalls",
                settings.maxModelApiCalls.coerceIn(
                    AgentOrchestrationSettings.MIN_MODEL_API_CALLS,
                    AgentOrchestrationSettings.MAX_MODEL_API_CALLS_CAP
                )
            )
            put("contextCompressionEnabled", settings.contextCompressionEnabled)
            put("toolAllowlistCustomized", settings.toolAllowlistCustomized)
            put("toolAllowlist", JSONArray(settings.toolAllowlist.toList().sorted()))
            put("enforceToolAllowlist", settings.enforceToolAllowlist)
            put("agentCallsEnabled", settings.agentCallsEnabled)
            put("requireCommandReview", settings.requireCommandReview)
        }.toString()
        prefs.edit().putString(keyAgentOrchestration, payload).apply()
    }

    private fun loadProxySettings(prefs: SharedPreferences): NetworkProxySettings {
        val json = prefs.getString(keyNetworkProxy, null).orEmpty()
        if (json.isBlank()) return NetworkProxySettings()
        return try {
            val obj = JSONObject(json)
            val mode = NetworkProxyMode.entries.firstOrNull {
                it.name == obj.optString("mode")
            } ?: NetworkProxyMode.System
            NetworkProxySettings(
                mode = mode,
                host = obj.optString("host", "127.0.0.1"),
                port = obj.optInt("port", 7890).coerceIn(1, 65535),
                username = obj.optString("username", ""),
                password = runCatching {
                    val encrypted = obj.optString("passwordEncrypted", "")
                    if (encrypted.isBlank()) obj.optString("password", "")
                    else AppSecretCipher.decrypt(encrypted)
                }.getOrDefault("")
            )
        } catch (_: Exception) {
            NetworkProxySettings()
        }
    }

    private fun serializeProxySettings(proxy: NetworkProxySettings): String {
        return JSONObject().apply {
            put("mode", proxy.mode.name)
            put("host", proxy.host)
            put("port", proxy.port)
            put("username", proxy.username)
            put("passwordEncrypted", AppSecretCipher.encrypt(proxy.password))
        }.toString()
    }

    private fun loadContextSettings(prefs: SharedPreferences): ContextSettings {
        val json = prefs.getString(keyContextSettings, null).orEmpty()
        if (json.isBlank()) return ContextSettings()
        return try {
            val obj = JSONObject(json)
            ContextSettings(
                systemPrompt = obj.optString("systemPrompt", ""),
                maxTokens = obj.optInt("maxTokens", 4096),
                contextWindowTokens = obj.optInt("contextWindowTokens", 0)
                    .coerceIn(ContextSettings.MIN_CONTEXT_WINDOW, ContextSettings.MAX_CONTEXT_WINDOW),
                temperature = obj.optDouble("temperature", 0.7).toFloat(),
                topP = obj.optDouble("topP", 1.0).toFloat(),
                topK = if (obj.has("topK") && !obj.isNull("topK")) obj.getInt("topK") else null,
                stopSequences = obj.optJSONArray("stopSequences")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                thinkingBudget = if (obj.has("thinkingBudget") && !obj.isNull("thinkingBudget")) obj.getInt("thinkingBudget") else null
            )
        } catch (_: Exception) {
            ContextSettings()
        }
    }

    private fun serializeContextSettings(cs: ContextSettings): String {
        return JSONObject().apply {
            put("systemPrompt", cs.systemPrompt)
            put("maxTokens", cs.maxTokens)
            put("contextWindowTokens", cs.contextWindowTokens)
            put("temperature", cs.temperature.toDouble())
            put("topP", cs.topP.toDouble())
            cs.topK?.let { put("topK", it) }
            if (cs.stopSequences.isNotEmpty()) {
                put("stopSequences", JSONArray(cs.stopSequences))
            }
            cs.thinkingBudget?.let { put("thinkingBudget", it) }
        }.toString()
    }

    private fun loadApiKey(prefs: SharedPreferences): String {
        val encrypted = prefs.getString(keyModelApiKeyEncrypted, "").orEmpty()
        if (encrypted.isNotBlank()) {
            return runCatching { AppSecretCipher.decrypt(encrypted) }.getOrDefault("")
        }
        val legacy = prefs.getString(keyModelApiKeyLegacy, "").orEmpty()
        if (legacy.isBlank()) {
            return ""
        }
        prefs.edit()
            .putString(keyModelApiKeyEncrypted, AppSecretCipher.encrypt(legacy))
            .remove(keyModelApiKeyLegacy)
            .apply()
        return legacy
    }
}
