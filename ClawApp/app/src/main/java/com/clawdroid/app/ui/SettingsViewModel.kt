package com.clawdroid.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawdroid.app.model.ModelApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.FollowSystem,
    val modelSettings: ModelSettings = ModelSettings(),
    val modelTestStatus: String = "未测试模型接口",
    val modelTesting: Boolean = false
)

internal class SettingsViewModel(
    private val appContext: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            themeMode = AppSettingsStore.loadThemeMode(appContext),
            modelSettings = AppSettingsStore.loadModelSettings(appContext)
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun selectThemeMode(themeMode: ThemeMode) {
        AppSettingsStore.saveThemeMode(appContext, themeMode)
        updateState { it.copy(themeMode = themeMode) }
    }

    fun updateModelSettings(modelSettings: ModelSettings) {
        AppSettingsStore.saveModelSettings(appContext, modelSettings)
        updateState { it.copy(modelSettings = modelSettings) }
    }

    fun testModelConnection() {
        val settings = uiState.value.modelSettings
        viewModelScope.launch {
            updateState {
                it.copy(
                    modelTesting = true,
                    modelTestStatus = "测试中..."
                )
            }
            val status = ModelApiClient.testConnection(settings).fold(
                onSuccess = { it },
                onFailure = { "连接失败: ${it.message ?: it::class.java.simpleName}" }
            )
            updateState {
                it.copy(
                    modelTesting = false,
                    modelTestStatus = status
                )
            }
        }
    }

    fun markLatestModelCallSuccess() {
        val providerName = uiState.value.modelSettings.provider.name
        updateState { it.copy(modelTestStatus = "最近模型调用成功: $providerName") }
    }

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
