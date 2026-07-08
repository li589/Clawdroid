package com.clawdroid.app.ui

internal data class SettingsScreenState(
    val versionName: String,
    val packageName: String,
    val socketName: String,
    val currentThemeMode: ThemeMode,
    val connectionSummary: String,
    val modelSettings: ModelSettings,
    val modelTestStatus: String,
    val modelTesting: Boolean,
    val modelListStatus: String,
    val modelListLoading: Boolean,
    val availableModels: List<String>,
    val showAdvancedSettings: Boolean,
    val runtimeVersionStatus: String,
    val runtimeHealthStatus: String,
    val runtimeLastErrorStatus: String,
    val runtimeConfigSummary: String
)

internal data class SettingsScreenActions(
    val onThemeModeSelected: (ThemeMode) -> Unit,
    val onModelSettingsChanged: (ModelSettings) -> Unit,
    val onContextSettingsChanged: (ContextSettings) -> Unit,
    val onTestModelConnection: () -> Unit,
    val onFetchModelList: () -> Unit,
    val onSelectModelFromList: (String) -> Unit,
    val onToggleAdvancedSettings: () -> Unit
)

internal fun buildSettingsScreenState(
    versionName: String,
    packageName: String,
    socketName: String,
    connectionSummary: String,
    runtimeVersionStatus: String,
    runtimeHealthStatus: String,
    runtimeLastErrorStatus: String,
    runtimeConfigSummary: String,
    settingsState: SettingsUiState
): SettingsScreenState {
    return SettingsScreenState(
        versionName = versionName,
        packageName = packageName,
        socketName = socketName,
        currentThemeMode = settingsState.themeMode,
        connectionSummary = connectionSummary,
        modelSettings = settingsState.modelSettings,
        modelTestStatus = settingsState.modelTestStatus,
        modelTesting = settingsState.modelTesting,
        modelListStatus = settingsState.modelListStatus,
        modelListLoading = settingsState.modelListLoading,
        availableModels = settingsState.availableModels,
        showAdvancedSettings = settingsState.showAdvancedSettings,
        runtimeVersionStatus = runtimeVersionStatus,
        runtimeHealthStatus = runtimeHealthStatus,
        runtimeLastErrorStatus = runtimeLastErrorStatus,
        runtimeConfigSummary = runtimeConfigSummary
    )
}

internal fun SettingsViewModel.buildSettingsScreenActions(): SettingsScreenActions {
    return SettingsScreenActions(
        onThemeModeSelected = ::selectThemeMode,
        onModelSettingsChanged = ::updateModelSettings,
        onContextSettingsChanged = ::updateContextSettings,
        onTestModelConnection = ::testModelConnection,
        onFetchModelList = ::fetchModelList,
        onSelectModelFromList = ::selectModelFromList,
        onToggleAdvancedSettings = ::toggleAdvancedSettings
    )
}
