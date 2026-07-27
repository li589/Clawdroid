package com.clawdroid.app.ui
import com.clawdroid.app.data.ModelConfigMemoryStore
import com.clawdroid.app.data.AppSettingsStore

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.clawdroid.app.model.ModelApiClient
import com.clawdroid.app.model.ModelApiUrlBuilder
import com.clawdroid.app.model.NetworkProxySupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.FollowSystem,
    val settingsNav: SettingsNav = SettingsNav.Hub,
    val modelSettings: ModelSettings = ModelSettings(),
    val agentSettings: AgentOrchestrationSettings = AgentOrchestrationSettings(),
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
            agentSettings = AppSettingsStore.loadAgentOrchestrationSettings(appContext),
            configMemory = ModelConfigMemoryStore.load(appContext)
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** 上一次已提交到「记忆」的配置，用于回退栈对比（不是每次按键）。 */
    private var lastCommittedSettings: ModelSettings = uiState.value.modelSettings

    private var draftPersistJob: Job? = null
    private var agentPersistJob: Job? = null
    private var pingJob: Job? = null

    fun openCategory(id: SettingsCategoryId) {
        updateState { it.copy(settingsNav = SettingsNav.Category(id)) }
    }

    fun navigateHub() {
        updateState { it.copy(settingsNav = SettingsNav.Hub) }
    }

    fun updateAgentSettings(agentSettings: AgentOrchestrationSettings) {
        val sanitized = sanitizeAgentSettings(agentSettings)
        updateState { it.copy(agentSettings = sanitized) }
        schedulePersistAgentSettings(sanitized)
    }

    fun selectThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch(Dispatchers.IO) {
            AppSettingsStore.saveThemeMode(appContext, themeMode)
        }
        updateState { it.copy(themeMode = themeMode) }
    }

    /**
     * 编辑中的草稿更新：先刷新 UI，磁盘写入防抖后放到 IO，避免输入卡顿。
     */
    fun updateModelSettings(modelSettings: ModelSettings) {
        val sanitized = ModelInputSanitizer.sanitize(modelSettings)
        updateState {
            it.copy(
                modelSettings = sanitized.settings,
                inputWarning = sanitized.warnings.firstOrNull().orEmpty(),
                memoryStatus = ""
            )
        }
        schedulePersistModelSettings(sanitized.settings)
    }

    /** 输入完成（失焦 / IME Done）或主动提交时形成记忆。 */
    fun commitConfigMemory(reason: String = "已保存到记忆") {
        val current = uiState.value.modelSettings
        val sanitized = ModelInputSanitizer.sanitize(current).settings
        var memory = uiState.value.configMemory
        if (ModelConfigMemoryLogic.isCoarseChange(lastCommittedSettings, sanitized)) {
            memory = ModelConfigMemoryLogic.pushFallback(memory, lastCommittedSettings)
        }
        memory = ModelConfigMemoryLogic.rememberSettings(memory, sanitized)
        lastCommittedSettings = sanitized
        updateState {
            it.copy(
                modelSettings = sanitized,
                configMemory = memory,
                memoryStatus = reason,
                inputWarning = ""
            )
        }
        persistSettingsAndMemory(sanitized, memory)
    }

    fun selectProvider(provider: ModelProvider) {
        val current = uiState.value.modelSettings
        if (current.provider == provider) return

        // 纯内存切换：立即更新 UI，落盘与三轮 ping 异步执行，避免主线程卡顿
        val memorySaved = ModelConfigMemoryLogic.rememberSettings(uiState.value.configMemory, current)
        val memoryStacked = ModelConfigMemoryLogic.pushFallback(memorySaved, current)
        val restored = ModelConfigMemoryLogic.resolveProviderSwitch(memoryStacked, current, provider)
        val sanitized = ModelInputSanitizer.sanitize(restored).settings
        val memory = ModelConfigMemoryLogic.rememberSettings(memoryStacked, sanitized)
        lastCommittedSettings = sanitized

        val restoredLabel = if (memorySaved.providerSnapshots.containsKey(provider)) {
            "已恢复 ${provider.displayName} 上次配置"
        } else {
            "已切换到 ${provider.displayName} 默认地址"
        }

        updateState {
            it.copy(
                modelSettings = sanitized,
                configMemory = memory,
                memoryStatus = restoredLabel,
                availableModels = emptyList(),
                modelListStatus = "",
                inputWarning = "",
                modelTesting = false,
                modelTestStatus = "$restoredLabel\n正在准备连通探测（3 轮）…"
            )
        }
        persistSettingsAndMemory(sanitized, memory)
        startConnectionPing(rounds = AUTO_PING_ROUNDS, commitMemory = false)
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
        val sanitized = ModelInputSanitizer.sanitize(restored).settings
        var memory = uiState.value.configMemory
        if (ModelConfigMemoryLogic.isCoarseChange(lastCommittedSettings, sanitized)) {
            memory = ModelConfigMemoryLogic.pushFallback(memory, lastCommittedSettings)
        }
        memory = ModelConfigMemoryLogic.rememberSettings(memory, sanitized)
        lastCommittedSettings = sanitized
        updateState {
            it.copy(
                modelSettings = sanitized,
                configMemory = memory,
                memoryStatus = "已恢复供应商记忆: ${snap.summaryLabel()}",
                availableModels = emptyList(),
                modelTestStatus = "已恢复 ${provider.displayName}，准备连通探测…"
            )
        }
        persistSettingsAndMemory(sanitized, memory)
        startConnectionPing(rounds = AUTO_PING_ROUNDS, commitMemory = false)
    }

    fun fallbackToPreviousConfig() {
        val (memory, snap) = ModelConfigMemoryLogic.popFallback(uiState.value.configMemory)
        if (snap == null) {
            updateState { it.copy(memoryStatus = "没有可回退的配置") }
            return
        }
        val restored = snap.toSettings(uiState.value.modelSettings.contextSettings)
        val sanitized = ModelInputSanitizer.sanitize(restored).settings
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
        persistSettingsAndMemory(sanitized, memory)
    }

    fun clearConfigMemory(keepProviderSnapshots: Boolean = true) {
        val current = uiState.value.configMemory
        val cleared = if (keepProviderSnapshots) {
            ModelConfigMemoryLogic.clearRecent(current).copy(fallbackStack = emptyList())
        } else {
            ModelConfigMemoryLogic.clearAll()
        }
        updateState {
            it.copy(
                configMemory = cleared,
                memoryStatus = if (keepProviderSnapshots) "已清除近期记忆与回退栈" else "已清空全部配置记忆"
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            ModelConfigMemoryStore.save(appContext, cleared)
        }
    }

    fun updateContextSettings(contextSettings: ContextSettings) {
        updateModelSettings(uiState.value.modelSettings.copy(contextSettings = contextSettings))
    }

    fun toggleAdvancedSettings() {
        updateState { it.copy(showAdvancedSettings = !it.showAdvancedSettings) }
    }

    fun testModelConnection() {
        commitConfigMemory("测试前已写入记忆")
        startConnectionPing(rounds = AUTO_PING_ROUNDS, commitMemory = true)
    }

    /**
     * 后台多轮连通探测：不阻塞 UI；新一次探测会取消上一次。
     */
    private fun startConnectionPing(rounds: Int, commitMemory: Boolean) {
        pingJob?.cancel()
        val settings = uiState.value.modelSettings
        val validation = ModelInputSanitizer.validationError(settings)
        if (validation != null) {
            updateState {
                it.copy(
                    modelTesting = false,
                    modelTestStatus = "跳过连通探测：$validation"
                )
            }
            return
        }

        val endpoint = runCatching { ModelApiUrlBuilder.buildChatUrl(settings) }
            .getOrDefault(settings.resolvedEndpoint())
        val proxyHint = NetworkProxySupport.describe(settings.proxySettings)
        val providerName = settings.provider.displayName

        pingJob = viewModelScope.launch {
            updateState {
                it.copy(
                    modelTesting = true,
                    modelTestStatus = "连通探测 0/$rounds…\n供应商: $providerName\n${requestUrlHint(endpoint)}\n代理: $proxyHint"
                )
            }

            var successCount = 0
            val roundLines = mutableListOf<String>()
            for (round in 1..rounds) {
                ensureActive()
                updateState {
                    it.copy(
                        modelTesting = true,
                        modelTestStatus = buildString {
                            append("连通探测 $round/$rounds…\n")
                            append("供应商: $providerName\n")
                            append(requestUrlHint(endpoint))
                            append('\n')
                            append("代理: $proxyHint")
                            if (roundLines.isNotEmpty()) {
                                append("\n——\n")
                                append(roundLines.joinToString("\n"))
                            }
                        }
                    )
                }

                val startedAt = System.currentTimeMillis()
                val roundResult = ModelApiClient.testConnection(settings)
                val elapsedMs = System.currentTimeMillis() - startedAt
                ensureActive()

                val line = roundResult.fold(
                    onSuccess = { reply ->
                        successCount += 1
                        "第${round}轮 ✓ ${elapsedMs}ms · ${reply.take(48)}"
                    },
                    onFailure = { err ->
                        "第${round}轮 ✗ ${elapsedMs}ms · ${err.message ?: err::class.java.simpleName}"
                    }
                )
                roundLines += line

                if (round < rounds && isActive) {
                    delay(BETWEEN_PING_DELAY_MS)
                }
            }

            if (!isActive) return@launch

            val summary = buildString {
                append(
                    if (successCount == rounds) {
                        "连通探测完成：全部成功 ($successCount/$rounds)"
                    } else if (successCount > 0) {
                        "连通探测完成：部分成功 ($successCount/$rounds)"
                    } else {
                        "连通探测完成：全部失败 (0/$rounds)"
                    }
                )
                append("\n供应商: ").append(providerName)
                append('\n').append(requestUrlHint(endpoint))
                append("\n代理: ").append(proxyHint)
                append("\n——\n")
                append(roundLines.joinToString("\n"))
            }

            if (commitMemory) {
                commitConfigMemory(
                    if (successCount > 0) "探测成功，已更新记忆" else "探测结束，已更新记忆"
                )
            }
            updateState {
                it.copy(
                    modelTesting = false,
                    modelTestStatus = summary
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
            val result = ModelApiClient.listModels(settings)
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
        val current = uiState.value.modelSettings
        val updated = when (current.provider) {
            ModelProvider.Local -> current.copy(localModelName = modelName)
            else -> current.copy(modelName = modelName)
        }
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

    private fun schedulePersistModelSettings(settings: ModelSettings) {
        draftPersistJob?.cancel()
        draftPersistJob = viewModelScope.launch {
            delay(DRAFT_PERSIST_DEBOUNCE_MS)
            withContext(Dispatchers.IO) {
                AppSettingsStore.saveModelSettings(appContext, settings)
            }
        }
    }

    private fun schedulePersistAgentSettings(settings: AgentOrchestrationSettings) {
        agentPersistJob?.cancel()
        agentPersistJob = viewModelScope.launch {
            delay(DRAFT_PERSIST_DEBOUNCE_MS)
            withContext(Dispatchers.IO) {
                AppSettingsStore.saveAgentOrchestrationSettings(appContext, settings)
            }
        }
    }

    private fun sanitizeAgentSettings(settings: AgentOrchestrationSettings): AgentOrchestrationSettings {
        return settings.copy(
            maxToolLoopTurns = settings.maxToolLoopTurns.coerceIn(
                AgentOrchestrationSettings.MIN_TOOL_LOOP_TURNS,
                AgentOrchestrationSettings.MAX_TOOL_LOOP_TURNS_CAP
            ),
            maxModelApiCalls = settings.maxModelApiCalls.coerceIn(
                AgentOrchestrationSettings.MIN_MODEL_API_CALLS,
                AgentOrchestrationSettings.MAX_MODEL_API_CALLS_CAP
            )
        )
    }

    private fun persistSettingsAndMemory(settings: ModelSettings, memory: ModelConfigMemory) {
        draftPersistJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            AppSettingsStore.saveModelSettings(appContext, settings)
            ModelConfigMemoryStore.save(appContext, memory)
        }
    }

    private fun requestUrlHint(endpoint: String): String = "请求 URL: $endpoint"

    private fun updateState(transform: (SettingsUiState) -> SettingsUiState) {
        _uiState.update(transform)
    }

    companion object {
        const val AUTO_PING_ROUNDS = 3
        private const val BETWEEN_PING_DELAY_MS = 250L
        private const val DRAFT_PERSIST_DEBOUNCE_MS = 350L

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
