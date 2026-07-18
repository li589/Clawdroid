package com.clawdroid.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.clawdroid.app.model.ModelApiUrlBuilder
import com.clawdroid.app.model.NetworkProxySupport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.FollowSystem,
    val modelSettings: ModelSettings = ModelSettings(),
    val modelTestStatus: String = "未测试模型接口",
    val modelTesting: Boolean = false,
    val modelListStatus: String = "",
    val modelListLoading: Boolean = false,
    val availableModels: List<String> = emptyList(),
    val showAdvancedSettings: Boolean = false,
    val configMemory: ModelConfigMemory = ModelConfigMemory(),
    val memoryStatus: String = "",
    val inputWarning: String = ""
)

internal class SettingsViewModel(
    private val appContext: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            themeMode = AppSettingsStore.loadThemeMode(appContext),
            modelSettings = AppSettingsStore.loadModelSettings(appContext),
            configMemory = ModelConfigMemoryStore.load(appContext)
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** 上一次已提交到「记忆」的配置，用于回退栈对比（不是每次按键）。 */
    private var lastCommittedSettings: ModelSettings = uiState.value.modelSettings

    fun selectThemeMode(themeMode: ThemeMode) {
        AppSettingsStore.saveThemeMode(appContext, themeMode)
        updateState { it.copy(themeMode = themeMode) }
    }

    /**
     * 编辑中的草稿更新：只落盘当前表单，不写入近期记忆芯片。
     * 记忆仅在 [commitConfigMemory] / 测试 / 选模型等提交点写入。
     */
    fun updateModelSettings(modelSettings: ModelSettings) {
        val sanitized = ModelInputSanitizer.sanitize(modelSettings)
        AppSettingsStore.saveModelSettings(appContext, sanitized.settings)
        updateState {
            it.copy(
                modelSettings = sanitized.settings,
                inputWarning = sanitized.warnings.firstOrNull().orEmpty(),
                memoryStatus = ""
            )
        }
    }

    /** 输入完成（失焦 / IME Done）或主动提交时形成记忆。 */
    fun commitConfigMemory(reason: String = "已保存到记忆") {
        val current = uiState.value.modelSettings
        val sanitized = ModelInputSanitizer.sanitize(current).settings
        AppSettingsStore.saveModelSettings(appContext, sanitized)
        var memory = uiState.value.configMemory
        if (ModelConfigMemoryLogic.isCoarseChange(lastCommittedSettings, sanitized)) {
            memory = ModelConfigMemoryLogic.pushFallback(memory, lastCommittedSettings)
        }
        memory = ModelConfigMemoryLogic.rememberSettings(memory, sanitized)
        ModelConfigMemoryStore.save(appContext, memory)
        lastCommittedSettings = sanitized
        updateState {
            it.copy(
                modelSettings = sanitized,
                configMemory = memory,
                memoryStatus = reason,
                inputWarning = ""
            )
        }
    }

    fun selectProvider(provider: ModelProvider) {
        val current = uiState.value.modelSettings
        if (current.provider == provider) return
        // 切换供应商前，先把当前完整配置提交记忆
        val memorySaved = ModelConfigMemoryLogic.rememberSettings(uiState.value.configMemory, current)
        val memoryStacked = ModelConfigMemoryLogic.pushFallback(memorySaved, current)
        val restored = ModelConfigMemoryLogic.resolveProviderSwitch(memoryStacked, current, provider)
        val sanitized = ModelInputSanitizer.sanitize(restored).settings
        val memory = ModelConfigMemoryLogic.rememberSettings(memoryStacked, sanitized)
        AppSettingsStore.saveModelSettings(appContext, sanitized)
        ModelConfigMemoryStore.save(appContext, memory)
        lastCommittedSettings = sanitized
        updateState {
            it.copy(
                modelSettings = sanitized,
                configMemory = memory,
                memoryStatus = if (memorySaved.providerSnapshots.containsKey(provider)) {
                    "已恢复 ${provider.displayName} 上次配置"
                } else {
                    "已切换到 ${provider.displayName} 默认地址"
                },
                availableModels = emptyList(),
                modelListStatus = "",
                inputWarning = ""
            )
        }
    }

    fun applyRememberedUrl(url: String) {
        val current = uiState.value.modelSettings
        val updated = when (current.provider) {
            ModelProvider.Local -> current.copy(localEndpoint = url)
            else -> current.copy(baseUrl = url)
        }
        updateModelSettings(updated)
        commitConfigMemory("已填入记忆 URL")
    }

    fun applyRememberedModel(modelName: String) {
        val current = uiState.value.modelSettings
        val updated = when (current.provider) {
            ModelProvider.Local -> current.copy(localModelName = modelName)
            else -> current.copy(modelName = modelName)
        }
        updateModelSettings(updated)
        commitConfigMemory("已填入记忆模型: $modelName")
    }

    fun applyRememberedApiKey(apiKey: String) {
        updateModelSettings(uiState.value.modelSettings.copy(apiKey = apiKey))
        commitConfigMemory("已填入记忆 API Key")
    }

    fun applyProviderSnapshot(provider: ModelProvider) {
        val snap = uiState.value.configMemory.providerSnapshots[provider] ?: return
        val restored = snap.toSettings(uiState.value.modelSettings.contextSettings)
        updateModelSettings(restored)
        commitConfigMemory("已恢复供应商记忆: ${snap.summaryLabel()}")
        updateState { it.copy(availableModels = emptyList()) }
    }

    fun fallbackToPreviousConfig() {
        val (memory, snap) = ModelConfigMemoryLogic.popFallback(uiState.value.configMemory)
        if (snap == null) {
            updateState { it.copy(memoryStatus = "没有可回退的配置") }
            return
        }
        val restored = snap.toSettings(uiState.value.modelSettings.contextSettings)
        val sanitized = ModelInputSanitizer.sanitize(restored).settings
        AppSettingsStore.saveModelSettings(appContext, sanitized)
        ModelConfigMemoryStore.save(appContext, memory)
        lastCommittedSettings = sanitized
        updateState {
            it.copy(
                modelSettings = sanitized,
                configMemory = memory,
                memoryStatus = "已回退: ${snap.summaryLabel()}",
                availableModels = emptyList(),
                modelListStatus = "",
                inputWarning = ""
            )
        }
    }

    fun clearConfigMemory(keepProviderSnapshots: Boolean = true) {
        val current = uiState.value.configMemory
        val cleared = if (keepProviderSnapshots) {
            ModelConfigMemoryLogic.clearRecent(current).copy(fallbackStack = emptyList())
        } else {
            ModelConfigMemoryLogic.clearAll()
        }
        ModelConfigMemoryStore.save(appContext, cleared)
        updateState {
            it.copy(
                configMemory = cleared,
                memoryStatus = if (keepProviderSnapshots) "已清除近期记忆与回退栈" else "已清空全部配置记忆"
            )
        }
    }

    fun updateContextSettings(contextSettings: ContextSettings) {
        updateModelSettings(uiState.value.modelSettings.copy(contextSettings = contextSettings))
    }

    fun toggleAdvancedSettings() {
        updateState { it.copy(showAdvancedSettings = !it.showAdvancedSettings) }
    }

    fun testModelConnection() {
        // 无论成败，测试都视为一次正式提交记忆
        commitConfigMemory("测试前已写入记忆")
        val settings = uiState.value.modelSettings
        val endpoint = runCatching { ModelApiUrlBuilder.buildChatUrl(settings) }.getOrDefault(settings.resolvedEndpoint())
        val proxyHint = NetworkProxySupport.describe(settings.proxySettings)
        viewModelScope.launch {
            updateState {
                it.copy(
                    modelTesting = true,
                    modelTestStatus = "测试中...\n${requestUrlHint(endpoint)}\n代理: $proxyHint"
                )
            }
            val status = com.clawdroid.app.model.ModelApiClient.testConnection(settings).fold(
                onSuccess = { reply ->
                    "连接成功: ${settings.provider.displayName} / ${reply.take(80)}\n${requestUrlHint(endpoint)}\n代理: $proxyHint"
                },
                onFailure = { err ->
                    "连接失败: ${err.message ?: err::class.java.simpleName}\n${requestUrlHint(endpoint)}\n代理: $proxyHint"
                }
            )
            // 再提交一次（含测试时可能被 sanitize 的字段）
            commitConfigMemory(if (status.startsWith("连接成功")) "测试成功，已更新记忆" else "测试结束，已更新记忆")
            updateState {
                it.copy(
                    modelTesting = false,
                    modelTestStatus = status
                )
            }
        }
    }

    fun fetchModelList() {
        commitConfigMemory("拉取列表前已写入记忆")
        val settings = uiState.value.modelSettings
        viewModelScope.launch {
            updateState {
                it.copy(
                    modelListLoading = true,
                    modelListStatus = "正在获取模型列表..."
                )
            }
            val result = com.clawdroid.app.model.ModelApiClient.listModels(settings)
            result.fold(
                onSuccess = { models ->
                    updateState {
                        it.copy(
                            modelListLoading = false,
                            modelListStatus = if (models.isEmpty()) {
                                "获取成功，但列表为空（站点可能未开放 /models，或当前 Key 无可用模型）"
                            } else {
                                "获取到 ${models.size} 个模型，可用搜索/标签筛选"
                            },
                            availableModels = models
                        )
                    }
                },
                onFailure = { err ->
                    updateState {
                        it.copy(
                            modelListLoading = false,
                            modelListStatus = "获取失败: ${err.message ?: err::class.java.simpleName}",
                            availableModels = emptyList()
                        )
                    }
                }
            )
        }
    }

    fun selectModelFromList(modelName: String) {
        val updated = uiState.value.modelSettings.copy(modelName = modelName)
        updateModelSettings(updated)
        commitConfigMemory("已选择模型: $modelName")
        updateState {
            it.copy(
                modelListStatus = "已选择: $modelName（仍可筛选切换，共 ${it.availableModels.size} 个）"
            )
        }
    }

    fun clearAvailableModels() {
        updateState {
            it.copy(
                availableModels = emptyList(),
                modelListStatus = if (it.modelListStatus.startsWith("已选择")) {
                    it.modelListStatus.substringBefore("（")
                } else {
                    it.modelListStatus
                }
            )
        }
    }

    fun markLatestModelCallSuccess() {
        val providerName = uiState.value.modelSettings.provider.name
        updateState { it.copy(modelTestStatus = "最近模型调用成功: $providerName") }
    }

    private fun requestUrlHint(endpoint: String): String = "请求 URL: $endpoint"

    private fun updateState(transform: (SettingsUiState) -> SettingsUiState) {
        _uiState.update(transform)
    }

    companion object {
        fun provideFactory(appContext: Context): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    return SettingsViewModel(appContext = appContext) as T
                }
            }
        }
    }
}

@Composable
internal fun rememberSettingsViewModel(context: Context): SettingsViewModel {
    val factory = remember(context) {
        SettingsViewModel.provideFactory(context.applicationContext)
    }
    return viewModel(factory = factory)
}
