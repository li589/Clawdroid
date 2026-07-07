package com.clawdroid.app.ui

import android.content.Context

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
        return ModelSettings(
            provider = provider,
            baseUrl = prefs.getString(keyModelBaseUrl, defaultBaseUrlFor(provider)).orEmpty(),
            apiKey = apiKey,
            modelName = prefs.getString(keyModelName, "").orEmpty(),
            localEndpoint = prefs.getString(keyLocalEndpoint, "http://127.0.0.1:11434/v1").orEmpty(),
            localModelName = prefs.getString(keyLocalModelName, "").orEmpty()
        )
    }

    fun saveModelSettings(context: Context, settings: ModelSettings) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(keyModelProvider, settings.provider.name)
            .putString(keyModelBaseUrl, settings.baseUrl)
            .putString(keyModelApiKeyEncrypted, AppSecretCipher.encrypt(settings.apiKey))
            .remove(keyModelApiKeyLegacy)
            .putString(keyModelName, settings.modelName)
            .putString(keyLocalEndpoint, settings.localEndpoint)
            .putString(keyLocalModelName, settings.localModelName)
            .apply()
    }

    private fun loadApiKey(prefs: android.content.SharedPreferences): String {
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
