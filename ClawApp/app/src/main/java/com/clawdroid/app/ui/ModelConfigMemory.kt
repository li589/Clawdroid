package com.clawdroid.app.ui

/**
 * 模型接入记忆：近期模型名 / API URL / API Key，以及按供应商快照与回退栈。
 */
internal data class ModelConfigSnapshot(
    val provider: ModelProvider,
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelName: String = "",
    val localEndpoint: String = "",
    val localModelName: String = "",
    val customApiPath: String = "/chat/completions",
    val urlPathMode: UrlPathMode = UrlPathMode.AutoAppend,
    val savedAtEpochMs: Long = 0L
) {
    fun toSettings(contextSettings: ContextSettings = ContextSettings()): ModelSettings {
        return ModelSettings(
            provider = provider,
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelName = modelName,
            localEndpoint = localEndpoint.ifBlank { "http://127.0.0.1:11434/v1" },
            localModelName = localModelName,
            customApiPath = customApiPath.ifBlank { "/chat/completions" },
            urlPathMode = urlPathMode,
            contextSettings = contextSettings
        )
    }

    fun summaryLabel(): String {
        val endpoint = when (provider) {
            ModelProvider.Local -> localEndpoint.ifBlank { "(本地)" }
            else -> baseUrl.ifBlank { "(无 URL)" }
        }
        val model = when (provider) {
            ModelProvider.Local -> localModelName.ifBlank { "(未选模型)" }
            else -> modelName.ifBlank { "(未选模型)" }
        }
        return "${provider.displayName} · $model · $endpoint"
    }

    fun maskedApiKey(): String {
        if (apiKey.isBlank()) return "(空)"
        if (apiKey.length <= 8) return "****"
        return apiKey.take(4) + "…" + apiKey.takeLast(4)
    }
}

internal data class ModelConfigMemory(
    val recentModels: List<String> = emptyList(),
    val recentUrls: List<String> = emptyList(),
    val recentApiKeys: List<String> = emptyList(),
    val providerSnapshots: Map<ModelProvider, ModelConfigSnapshot> = emptyMap(),
    val fallbackStack: List<ModelConfigSnapshot> = emptyList()
) {
    val canFallback: Boolean get() = fallbackStack.isNotEmpty()
}

internal object ModelConfigMemoryLogic {
    const val MAX_RECENT_ITEMS = 12
    const val MAX_FALLBACK_STACK = 10

    fun fromSettings(settings: ModelSettings, savedAtEpochMs: Long = System.currentTimeMillis()): ModelConfigSnapshot {
        return ModelConfigSnapshot(
            provider = settings.provider,
            baseUrl = settings.baseUrl.trim(),
            apiKey = settings.apiKey,
            modelName = settings.modelName.trim(),
            localEndpoint = settings.localEndpoint.trim(),
            localModelName = settings.localModelName.trim(),
            customApiPath = settings.customApiPath.trim().ifBlank { "/chat/completions" },
            urlPathMode = settings.urlPathMode,
            savedAtEpochMs = savedAtEpochMs
        )
    }

    fun rememberValue(existing: List<String>, value: String, max: Int = MAX_RECENT_ITEMS): List<String> {
        val normalized = value.trim()
        if (normalized.isBlank()) return existing
        return (listOf(normalized) + existing.filter { !it.equals(normalized, ignoreCase = false) })
            .take(max)
    }

    fun rememberSettings(memory: ModelConfigMemory, settings: ModelSettings): ModelConfigMemory {
        val snapshot = fromSettings(settings)
        var next = memory.copy(
            providerSnapshots = memory.providerSnapshots + (settings.provider to snapshot)
        )
        when (settings.provider) {
            ModelProvider.Local -> {
                next = next.copy(
                    recentUrls = rememberValue(next.recentUrls, settings.localEndpoint),
                    recentModels = rememberValue(next.recentModels, settings.localModelName)
                )
            }
            else -> {
                next = next.copy(
                    recentUrls = rememberValue(next.recentUrls, settings.baseUrl),
                    recentModels = rememberValue(next.recentModels, settings.modelName)
                )
            }
        }
        if (settings.apiKey.isNotBlank()) {
            next = next.copy(recentApiKeys = rememberValue(next.recentApiKeys, settings.apiKey))
        }
        return next
    }

    /**
     * 当关键接入字段变化时，把旧配置压入回退栈。
     */
    fun rememberWithFallback(
        memory: ModelConfigMemory,
        previous: ModelSettings,
        next: ModelSettings
    ): ModelConfigMemory {
        var updated = rememberSettings(memory, next)
        if (isCoarseChange(previous, next)) {
            updated = pushFallback(updated, previous)
        }
        return updated
    }

    fun pushFallback(memory: ModelConfigMemory, settings: ModelSettings): ModelConfigMemory {
        val prevSnap = fromSettings(settings)
        val stack = (listOf(prevSnap) + memory.fallbackStack.filterNot { sameIdentity(it, prevSnap) })
            .take(MAX_FALLBACK_STACK)
        return memory.copy(fallbackStack = stack)
    }

    fun popFallback(memory: ModelConfigMemory): Pair<ModelConfigMemory, ModelConfigSnapshot?> {
        val head = memory.fallbackStack.firstOrNull() ?: return memory to null
        return memory.copy(fallbackStack = memory.fallbackStack.drop(1)) to head
    }

    fun resolveProviderSwitch(
        memory: ModelConfigMemory,
        current: ModelSettings,
        newProvider: ModelProvider
    ): ModelSettings {
        if (current.provider == newProvider) return current
        val remembered = memory.providerSnapshots[newProvider]
        return if (remembered != null) {
            remembered.toSettings(current.contextSettings).copy(proxySettings = current.proxySettings)
        } else {
            current.copy(
                provider = newProvider,
                baseUrl = newProvider.defaultBaseUrl
            )
        }
    }

    fun clearRecent(memory: ModelConfigMemory): ModelConfigMemory {
        return memory.copy(
            recentModels = emptyList(),
            recentUrls = emptyList(),
            recentApiKeys = emptyList()
        )
    }

    fun clearAll(): ModelConfigMemory = ModelConfigMemory()

    /** URL / Key / 供应商 / 路径模式等粗粒度变更，才写入回退栈（避免打字刷栈）。 */
    fun isCoarseChange(a: ModelSettings, b: ModelSettings): Boolean {
        return a.provider != b.provider ||
            a.baseUrl.trim() != b.baseUrl.trim() ||
            a.apiKey != b.apiKey ||
            a.localEndpoint.trim() != b.localEndpoint.trim() ||
            a.customApiPath.trim() != b.customApiPath.trim() ||
            a.urlPathMode != b.urlPathMode ||
            a.proxySettings != b.proxySettings
    }

    fun isSignificantChange(a: ModelSettings, b: ModelSettings): Boolean {
        return isCoarseChange(a, b) ||
            a.modelName.trim() != b.modelName.trim() ||
            a.localModelName.trim() != b.localModelName.trim()
    }

    private fun sameIdentity(a: ModelConfigSnapshot, b: ModelConfigSnapshot): Boolean {
        return a.provider == b.provider &&
            a.baseUrl == b.baseUrl &&
            a.apiKey == b.apiKey &&
            a.modelName == b.modelName &&
            a.localEndpoint == b.localEndpoint &&
            a.localModelName == b.localModelName &&
            a.customApiPath == b.customApiPath &&
            a.urlPathMode == b.urlPathMode
    }
}
