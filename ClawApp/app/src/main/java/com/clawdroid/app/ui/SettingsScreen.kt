package com.clawdroid.app.ui

import androidx.compose.foundation.lazy.LazyListScope
import com.clawdroid.app.ui.settings.SettingsCategoryHubCard
import com.clawdroid.app.ui.settings.SettingsReadinessCard
import com.clawdroid.app.ui.settings.agentToolsSettingsSection
import com.clawdroid.app.ui.settings.appearanceSettingsSection
import com.clawdroid.app.ui.settings.assistMcpSettingsSection
import com.clawdroid.app.ui.settings.diagnosticsSettingsSection
import com.clawdroid.app.ui.settings.modelSettingsSection
import com.clawdroid.app.ui.settings.modelSettingsValidationMessage
import com.clawdroid.app.ui.settings.promptsSkillsSettingsSection
import com.clawdroid.app.ui.settings.termuxShellSettingsSection

internal fun LazyListScope.settingsScreen(
    state: SettingsScreenState,
    actions: SettingsScreenActions
) {
    when (val nav = state.settingsNav) {
        SettingsNav.Hub -> settingsHubScreen(state, actions)
        is SettingsNav.Category -> settingsCategoryScreen(nav.id, state, actions)
    }
}

private fun LazyListScope.settingsHubScreen(
    state: SettingsScreenState,
    actions: SettingsScreenActions
) {
    val validationMessage = modelSettingsValidationMessage(state.modelSettings)
    item { SectionTitle("验收概览") }
    item {
        SettingsReadinessCard(
            currentThemeMode = state.currentThemeMode,
            modelSettings = state.modelSettings,
            connectionSummary = state.modelTestStatus,
            validationMessage = validationMessage
        )
    }
    item { SectionTitle("设置分类") }
    item {
        SettingsCategoryHubCard(onOpenCategory = actions.onOpenCategory)
    }
}

private fun LazyListScope.settingsCategoryScreen(
    categoryId: SettingsCategoryId,
    state: SettingsScreenState,
    actions: SettingsScreenActions
) {
    when (categoryId) {
        SettingsCategoryId.Appearance -> appearanceSettingsSection(
            categoryId = categoryId,
            currentThemeMode = state.currentThemeMode,
            onThemeModeSelected = actions.onThemeModeSelected
        )
        SettingsCategoryId.Model -> modelSettingsSection(categoryId, state, actions)
        SettingsCategoryId.AgentTools -> agentToolsSettingsSection(categoryId, state, actions)
        SettingsCategoryId.PromptsSkills -> promptsSkillsSettingsSection(categoryId)
        SettingsCategoryId.AssistMcp -> assistMcpSettingsSection(categoryId, state, actions)
        SettingsCategoryId.TermuxShell -> termuxShellSettingsSection(categoryId)
        SettingsCategoryId.Diagnostics -> diagnosticsSettingsSection(categoryId, state)
    }
}

// 注意：SettingsScreenState 和 SettingsScreenActions 已迁移至 SettingsStateHolders.kt
// ClawdroidShell 使用 buildSettingsScreenState() 和 settingsViewModel.buildSettingsScreenActions() 构建
