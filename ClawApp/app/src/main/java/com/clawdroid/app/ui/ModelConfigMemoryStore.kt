package com.clawdroid.app.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object ModelConfigMemoryStore {
    private const val prefsName = "clawdroid_app_settings"
    private const val keyMemory = "model_config_memory_v1"

    fun load(context: Context): ModelConfigMemory {
        val raw = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(keyMemory, null)
            .orEmpty()
        if (raw.isBlank()) return ModelConfigMemory()
        return runCatching { deserialize(raw) }.getOrDefault(ModelConfigMemory())
    }

    fun save(context: Context, memory: ModelConfigMemory) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(keyMemory, serialize(memory))
            .apply()
    }

    internal fun serialize(memory: ModelConfigMemory): String {
        return JSONObject().apply {
            put("recentModels", JSONArray(memory.recentModels))
            put("recentUrls", JSONArray(memory.recentUrls))
            put(
                "recentApiKeys",
                JSONArray(memory.recentApiKeys.map { AppSecretCipher.encrypt(it) })
            )
            put(
                "providerSnapshots",
                JSONObject().apply {
                    memory.providerSnapshots.forEach { (provider, snap) ->
                        put(provider.name, snapshotJson(snap))
                    }
                }
            )
            put(
                "fallbackStack",
                JSONArray().apply {
                    memory.fallbackStack.forEach { put(snapshotJson(it)) }
                }
            )
        }.toString()
    }

    internal fun deserialize(raw: String): ModelConfigMemory {
        val root = JSONObject(raw)
        val recentModels = stringList(root.optJSONArray("recentModels"))
        val recentUrls = stringList(root.optJSONArray("recentUrls"))
        val recentApiKeys = stringList(root.optJSONArray("recentApiKeys")).map { encrypted ->
            runCatching { AppSecretCipher.decrypt(encrypted) }.getOrDefault("")
        }.filter { it.isNotBlank() }
        val providerSnapshots = linkedMapOf<ModelProvider, ModelConfigSnapshot>()
        root.optJSONObject("providerSnapshots")?.let { obj ->
            obj.keys().forEach { key ->
                val provider = ModelProvider.entries.firstOrNull { it.name == key } ?: return@forEach
                providerSnapshots[provider] = parseSnapshot(obj.getJSONObject(key), provider)
            }
        }
        val fallbackStack = mutableListOf<ModelConfigSnapshot>()
        root.optJSONArray("fallbackStack")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val provider = ModelProvider.entries.firstOrNull {
                    it.name == item.optString("provider")
                } ?: continue
                fallbackStack += parseSnapshot(item, provider)
            }
        }
        return ModelConfigMemory(
            recentModels = recentModels,
            recentUrls = recentUrls,
            recentApiKeys = recentApiKeys,
            providerSnapshots = providerSnapshots,
            fallbackStack = fallbackStack
        )
    }

    private fun snapshotJson(snap: ModelConfigSnapshot): JSONObject {
        return JSONObject().apply {
            put("provider", snap.provider.name)
            put("baseUrl", snap.baseUrl)
            put("apiKey", AppSecretCipher.encrypt(snap.apiKey))
            put("modelName", snap.modelName)
            put("localEndpoint", snap.localEndpoint)
            put("localModelName", snap.localModelName)
            put("customApiPath", snap.customApiPath)
            put("urlPathMode", snap.urlPathMode.name)
            put("savedAtEpochMs", snap.savedAtEpochMs)
        }
    }

    private fun parseSnapshot(obj: JSONObject, provider: ModelProvider): ModelConfigSnapshot {
        val encryptedKey = obj.optString("apiKey", "")
        val apiKey = if (encryptedKey.isBlank()) {
            ""
        } else {
            runCatching { AppSecretCipher.decrypt(encryptedKey) }.getOrDefault("")
        }
        val mode = UrlPathMode.entries.firstOrNull {
            it.name == obj.optString("urlPathMode")
        } ?: UrlPathMode.AutoAppend
        return ModelConfigSnapshot(
            provider = provider,
            baseUrl = obj.optString("baseUrl", ""),
            apiKey = apiKey,
            modelName = obj.optString("modelName", ""),
            localEndpoint = obj.optString("localEndpoint", ""),
            localModelName = obj.optString("localModelName", ""),
            customApiPath = obj.optString("customApiPath", "/chat/completions"),
            urlPathMode = mode,
            savedAtEpochMs = obj.optLong("savedAtEpochMs", 0L)
        )
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index)?.takeIf { it.isNotBlank() }
        }
    }
}
