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
    val runtimeVersionStatus: String,
    val runtimeHealthStatus: String,
    val runtimeLastErrorStatus: String,
    val runtimeConfigSummary: String
)

internal data class SettingsScreenActions(
    val onThemeModeSelected: (ThemeMode) -> Unit,
    val onModelSettingsChanged: (ModelSettings) -> Unit,
    val onTestModelConnection: () -> Unit
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
        onTestModelConnection = ::testModelConnection
    )
}
