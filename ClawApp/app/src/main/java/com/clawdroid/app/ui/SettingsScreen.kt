package com.clawdroid.app.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

internal fun LazyListScope.settingsScreen(
    state: SettingsScreenState,
    actions: SettingsScreenActions
) {
    val validationMessage = modelSettingsValidationMessage(state.modelSettings)
    item { SectionTitle("验收概览") }
    item {
        SettingsReadinessCard(
            currentThemeMode = state.currentThemeMode,
            modelSettings = state.modelSettings,
            connectionSummary = state.connectionSummary,
            validationMessage = validationMessage
        )
    }
    item { SectionTitle("外观偏好") }
    item {
        ThemeSettingsCard(
            currentThemeMode = state.currentThemeMode,
            onThemeModeSelected = actions.onThemeModeSelected
        )
    }
    item { SectionTitle("模型接入") }
    item {
        ModelProviderSettingsCard(
            modelSettings = state.modelSettings,
            modelTestStatus = state.modelTestStatus,
            modelTesting = state.modelTesting,
            validationMessage = validationMessage,
            onModelSettingsChanged = actions.onModelSettingsChanged,
            onTestModelConnection = actions.onTestModelConnection
        )
    }
    item {
        CloudModelSettingsCard(
            modelSettings = state.modelSettings,
            onModelSettingsChanged = actions.onModelSettingsChanged
        )
    }
    item {
        LocalModelSettingsCard(
            modelSettings = state.modelSettings,
            onModelSettingsChanged = actions.onModelSettingsChanged
        )
    }
    item { SectionTitle("运行诊断") }
    item {
        StatusCard(
            title = "应用与连接",
            content = "版本: ${state.versionName}\n包名: ${state.packageName}\nClawRuntime Socket: ${state.socketName}\n连接摘要: ${state.connectionSummary}"
        )
    }
    item {
        StatusCard(
            title = "ClawRuntime 诊断摘要",
            content = "Version:\n${state.runtimeVersionStatus}\n\nHealth:\n${state.runtimeHealthStatus}\n\nLast Error:\n${state.runtimeLastErrorStatus}"
        )
    }
    item {
        StatusCard(
            title = "ClawRuntime 配置摘要",
            content = state.runtimeConfigSummary
        )
    }
    item {
        StatusCard(
            title = "页面分工",
            content = "聊天页: 自然语言、语音转写、图片入口\n概览页: 状态、连接策略、动态指标、系统信息\n设置页: 主题、模型配置、诊断信息"
        )
    }
    item {
        StatusCard(
            title = "配置分层",
            content = "应用级: 模型供应商、API Key、接口地址\n运行级: ClawRuntime 侧维护 Socket、审计、限流、白名单"
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsReadinessCard(
    currentThemeMode: ThemeMode,
    modelSettings: ModelSettings,
    connectionSummary: String,
    validationMessage: String?
) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    val hSpacing = responsiveFlowHSpacing()
    val vSpacing = responsiveFlowVSpacing()
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "设置总览", style = MaterialTheme.typography.titleLarge)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(hSpacing),
                verticalArrangement = Arrangement.spacedBy(vSpacing)
            ) {
                StatusChip("主题 ${themeModeLabel(currentThemeMode)}")
                StatusChip("模型 ${modelProviderLabel(modelSettings.provider)}")
                StatusChip(
                    if (validationMessage == null) "配置就绪" else "配置待完善: ${validationMessage.take(12)}"
                )
            }
            ResultPanel(
                text = buildString {
                    append("连接摘要: ")
                    append(connectionSummary)
                    append('\n')
                    append("模型配置: ")
                    append(validationMessage ?: "字段完整，可直接测试连接或用于聊天")
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeSettingsCard(
    currentThemeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit
) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    val hSpacing = responsiveFlowHSpacing()
    val vSpacing = responsiveFlowVSpacing()
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "外观主题", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "当前模式: ${themeModeLabel(currentThemeMode)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(hSpacing),
                verticalArrangement = Arrangement.spacedBy(vSpacing)
            ) {
                ThemeMode.entries.forEach { mode ->
                    AssistChip(
                        onClick = { onThemeModeSelected(mode) },
                        label = { Text(themeModeLabel(mode)) }
                    )
                }
            }
            ResultPanel(text = "主题偏好已本地保存，重启后仍会保留。")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelProviderSettingsCard(
    modelSettings: ModelSettings,
    modelTestStatus: String,
    modelTesting: Boolean,
    validationMessage: String?,
    onModelSettingsChanged: (ModelSettings) -> Unit,
    onTestModelConnection: () -> Unit
) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    val hSpacing = responsiveFlowHSpacing()
    val vSpacing = responsiveFlowVSpacing()
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "模型供应商", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(hSpacing),
                verticalArrangement = Arrangement.spacedBy(vSpacing)
            ) {
                ModelProvider.entries.forEach { provider ->
                    AssistChip(
                        onClick = {
                            onModelSettingsChanged(
                                modelSettings.copy(
                                    provider = provider,
                                    baseUrl = defaultBaseUrlFor(provider)
                                )
                            )
                        },
                        label = { Text(modelProviderLabel(provider)) }
                    )
                }
            }
            ResultPanel(
                text = "当前: ${modelProviderLabel(modelSettings.provider)}\n${modelProviderHint(modelSettings.provider)}"
            )
            PrimaryActionButton(
                text = if (modelTesting) "测试中..." else "测试模型连接",
                onClick = onTestModelConnection,
                modifier = Modifier.fillMaxWidth(),
                enabled = !modelTesting && validationMessage == null
            )
            validationMessage?.let {
                ResultPanel(text = "还不能测试：$it")
            }
            ResultPanel(text = modelTestStatus)
        }
    }
}

@Composable
private fun CloudModelSettingsCard(
    modelSettings: ModelSettings,
    onModelSettingsChanged: (ModelSettings) -> Unit
) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    var apiKeyVisible by remember { mutableStateOf(false) }
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "云端模型接口", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = modelSettings.baseUrl,
                onValueChange = {
                    onModelSettingsChanged(modelSettings.copy(baseUrl = it))
                },
                label = { Text("API Base URL") },
                supportingText = { FieldSupportingText("兼容官方接口或自定义网关") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = modelSettings.apiKey,
                onValueChange = {
                    onModelSettingsChanged(modelSettings.copy(apiKey = it))
                },
                label = { Text("API Key") },
                supportingText = { FieldSupportingText("已加密存储") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (apiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    FilledTonalButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Text(if (apiKeyVisible) "隐藏" else "显示")
                    }
                },
                singleLine = true
            )
            OutlinedTextField(
                value = modelSettings.modelName,
                onValueChange = {
                    onModelSettingsChanged(modelSettings.copy(modelName = it))
                },
                label = { Text("模型名称") },
                supportingText = { FieldSupportingText("例如 gpt-4、gemini-2.0") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

@Composable
private fun LocalModelSettingsCard(
    modelSettings: ModelSettings,
    onModelSettingsChanged: (ModelSettings) -> Unit
) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "本地模型接口", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (modelSettings.provider == ModelProvider.Local) {
                    "当前为本地模型模式"
                } else {
                    "预留接口，需切换供应商到 Local"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = modelSettings.localEndpoint,
                onValueChange = {
                    onModelSettingsChanged(modelSettings.copy(localEndpoint = it))
                },
                label = { Text("本地接口地址") },
                supportingText = { FieldSupportingText("Ollama / LM Studio / 本地网关") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = modelSettings.localModelName,
                onValueChange = {
                    onModelSettingsChanged(modelSettings.copy(localModelName = it))
                },
                label = { Text("本地模型名称") },
                supportingText = { FieldSupportingText("例如 qwen2.5、llama3") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

private fun themeModeLabel(mode: ThemeMode): String {
    return when (mode) {
        ThemeMode.FollowSystem -> "跟随系统"
        ThemeMode.Dark -> "深色"
        ThemeMode.Light -> "浅色"
    }
}

private fun modelProviderLabel(provider: ModelProvider): String {
    return when (provider) {
        ModelProvider.OpenAI -> "OpenAI"
        ModelProvider.Gemini -> "Gemini"
        ModelProvider.Anthropic -> "Anthropic"
        ModelProvider.OpenAICompatible -> "OpenAI-Compatible"
        ModelProvider.Custom -> "Custom"
        ModelProvider.Local -> "Local"
    }
}

private fun modelProviderHint(provider: ModelProvider): String {
    return when (provider) {
        ModelProvider.OpenAI -> "标准 OpenAI 接口"
        ModelProvider.Gemini -> "Gemini 官方或兼容网关"
        ModelProvider.Anthropic -> "Anthropic 官方或兼容代理"
        ModelProvider.OpenAICompatible -> "第三方聚合平台和代理"
        ModelProvider.Custom -> "自定义 HTTP API"
        ModelProvider.Local -> "Ollama、LM Studio、vLLM 等本地服务"
    }
}

private fun modelSettingsValidationMessage(settings: ModelSettings): String? {
    return when (settings.provider) {
        ModelProvider.Local -> when {
            settings.localEndpoint.isBlank() -> "本地接口地址不能为空"
            settings.localModelName.isBlank() -> "本地模型名称不能为空"
            else -> null
        }
        else -> when {
            settings.baseUrl.isBlank() -> "API Base URL 不能为空"
            settings.modelName.isBlank() -> "模型名称不能为空"
            settings.apiKey.isBlank() -> "API Key 不能为空"
            else -> null
        }
    }
}
