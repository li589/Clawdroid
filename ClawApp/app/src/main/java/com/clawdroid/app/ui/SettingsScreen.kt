package com.clawdroid.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.clawdroid.app.ui.ContextSettings.Companion.MAX_MAX_TOKENS
import com.clawdroid.app.ui.ContextSettings.Companion.MAX_TEMPERATURE
import com.clawdroid.app.ui.ContextSettings.Companion.MAX_THINKING_BUDGET
import com.clawdroid.app.ui.ContextSettings.Companion.MIN_MAX_TOKENS
import com.clawdroid.app.ui.ContextSettings.Companion.MIN_TEMPERATURE
import com.clawdroid.app.ui.ContextSettings.Companion.MIN_THINKING_BUDGET

// ---------------------------------------------------------------------------
// 主设置页面
// ---------------------------------------------------------------------------

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
            connectionSummary = state.modelTestStatus,
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
            modelListStatus = state.modelListStatus,
            modelListLoading = state.modelListLoading,
            availableModels = state.availableModels,
            validationMessage = validationMessage,
            onModelSettingsChanged = actions.onModelSettingsChanged,
            onTestModelConnection = actions.onTestModelConnection,
            onFetchModelList = actions.onFetchModelList,
            onSelectModelFromList = actions.onSelectModelFromList
        )
    }
    item {
        ApiEndpointSettingsCard(
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
    item { SectionTitle("高级配置") }
    item {
        AdvancedSettingsToggle(
            expanded = state.showAdvancedSettings,
            onToggle = actions.onToggleAdvancedSettings
        )
    }
    if (state.showAdvancedSettings) {
        item {
            ContextSettingsCard(
                modelSettings = state.modelSettings,
                onContextSettingsChanged = actions.onContextSettingsChanged
            )
        }
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

// ---------------------------------------------------------------------------
// 设置总览卡片
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// 主题设置卡片
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// 供应商选择卡片（分组展示）
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelProviderSettingsCard(
    modelSettings: ModelSettings,
    modelTestStatus: String,
    modelTesting: Boolean,
    modelListStatus: String,
    modelListLoading: Boolean,
    availableModels: List<String>,
    validationMessage: String?,
    onModelSettingsChanged: (ModelSettings) -> Unit,
    onTestModelConnection: () -> Unit,
    onFetchModelList: () -> Unit,
    onSelectModelFromList: (String) -> Unit
) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    val hSpacing = responsiveFlowHSpacing()
    val vSpacing = responsiveFlowVSpacing()
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "模型供应商", style = MaterialTheme.typography.titleMedium)

            // 官方供应商
            ProviderGroupSection(
                title = "官方",
                providers = ModelProvider.officialProviders,
                selectedProvider = modelSettings.provider,
                hSpacing = hSpacing,
                vSpacing = vSpacing,
                onProviderSelected = { p ->
                    onModelSettingsChanged(modelSettings.copy(provider = p, baseUrl = p.defaultBaseUrl))
                }
            )

            // 国内供应商
            ProviderGroupSection(
                title = "国内",
                providers = ModelProvider.chineseProviders,
                selectedProvider = modelSettings.provider,
                hSpacing = hSpacing,
                vSpacing = vSpacing,
                onProviderSelected = { p ->
                    onModelSettingsChanged(modelSettings.copy(provider = p, baseUrl = p.defaultBaseUrl))
                }
            )

            // 聚合平台
            ProviderGroupSection(
                title = "聚合平台",
                providers = ModelProvider.aggregatorProviders,
                selectedProvider = modelSettings.provider,
                hSpacing = hSpacing,
                vSpacing = vSpacing,
                onProviderSelected = { p ->
                    onModelSettingsChanged(modelSettings.copy(provider = p, baseUrl = p.defaultBaseUrl))
                }
            )

            // 协议兼容
            ProviderGroupSection(
                title = "协议兼容",
                providers = ModelProvider.protocolProviders,
                selectedProvider = modelSettings.provider,
                hSpacing = hSpacing,
                vSpacing = vSpacing,
                onProviderSelected = { p ->
                    onModelSettingsChanged(modelSettings.copy(provider = p, baseUrl = p.defaultBaseUrl))
                }
            )

            // 自定义 / 本地
            Row(
                horizontalArrangement = Arrangement.spacedBy(hSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {
                        onModelSettingsChanged(modelSettings.copy(provider = ModelProvider.Custom, baseUrl = ""))
                    },
                    label = { Text(modelProviderLabel(ModelProvider.Custom)) }
                )
                AssistChip(
                    onClick = {
                        onModelSettingsChanged(modelSettings.copy(provider = ModelProvider.Local))
                    },
                    label = { Text(modelProviderLabel(ModelProvider.Local)) }
                )
            }

            HorizontalDivider()
            ResultPanel(
                text = "当前: ${modelProviderLabel(modelSettings.provider)}\n${modelProviderHint(modelSettings.provider)}"
            )

            // 模型选择 + 获取列表
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = modelSettings.modelName,
                        onValueChange = { onModelSettingsChanged(modelSettings.copy(modelName = it)) },
                        label = { Text("模型名称") },
                        supportingText = { FieldSupportingText("如 gpt-4o / claude-3-5-sonnet") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (modelSettings.provider != ModelProvider.Local) {
                                Box {
                                    IconButton(onClick = { modelDropdownExpanded = true }) {
                                        Icon(Icons.Default.List, contentDescription = "选择模型")
                                    }
                                    DropdownMenu(
                                        expanded = modelDropdownExpanded && availableModels.isNotEmpty(),
                                        onDismissRequest = { modelDropdownExpanded = false }
                                    ) {
                                        availableModels.take(50).forEach { model ->
                                            DropdownMenuItem(
                                                text = { Text(model, style = MaterialTheme.typography.bodySmall) },
                                                onClick = {
                                                    onSelectModelFromList(model)
                                                    modelDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
                FilledTonalButton(
                    onClick = onFetchModelList,
                    enabled = !modelListLoading && modelSettings.provider != ModelProvider.Local && modelSettings.apiKey.isNotBlank()
                ) {
                    Icon(
                        imageVector = if (modelListLoading) Icons.Default.Refresh else Icons.Default.List,
                        contentDescription = null
                    )
                }
            }
            if (modelListStatus.isNotBlank()) {
                ResultPanel(text = modelListStatus)
            }

            // 测试连接按钮
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderGroupSection(
    title: String,
    providers: List<ModelProvider>,
    selectedProvider: ModelProvider,
    hSpacing: androidx.compose.ui.unit.Dp,
    vSpacing: androidx.compose.ui.unit.Dp,
    onProviderSelected: (ModelProvider) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(hSpacing),
        verticalArrangement = Arrangement.spacedBy(vSpacing)
    ) {
        providers.forEach { provider ->
            FilterChip(
                selected = provider == selectedProvider,
                onClick = { onProviderSelected(provider) },
                label = { Text(provider.displayName) }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// API 端点设置卡片（URL + 路径模式）
// ---------------------------------------------------------------------------

@Composable
private fun ApiEndpointSettingsCard(
    modelSettings: ModelSettings,
    onModelSettingsChanged: (ModelSettings) -> Unit
) {
    // 仅非本地模型显示此卡片
    if (modelSettings.provider == ModelProvider.Local) return

    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    val hSpacing = responsiveFlowHSpacing()
    var apiKeyVisible by remember { mutableStateOf(false) }

    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "API 接入配置", style = MaterialTheme.typography.titleMedium)

            // URL 路径模式选择
            Text(
                text = "URL 路径模式",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(hSpacing)
            ) {
                UrlPathMode.entries.forEach { mode ->
                    FilterChip(
                        selected = modelSettings.urlPathMode == mode,
                        onClick = { onModelSettingsChanged(modelSettings.copy(urlPathMode = mode)) },
                        label = { Text(urlPathModeLabel(mode)) }
                    )
                }
            }
            Text(
                text = urlPathModeHint(modelSettings.urlPathMode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Base URL
            OutlinedTextField(
                value = modelSettings.baseUrl,
                onValueChange = { onModelSettingsChanged(modelSettings.copy(baseUrl = it)) },
                label = { Text("API Base URL") },
                supportingText = { FieldSupportingText(baseUrlHintFor(modelSettings)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // 自定义路径（仅 AppendCustom 或 Custom 模式显示）
            if (modelSettings.urlPathMode == UrlPathMode.AppendCustom || modelSettings.provider == ModelProvider.Custom) {
                OutlinedTextField(
                    value = modelSettings.customApiPath,
                    onValueChange = { onModelSettingsChanged(modelSettings.copy(customApiPath = it)) },
                    label = { Text("自定义 API 路径") },
                    supportingText = { FieldSupportingText("例如 /chat/completions 或 /v1/chat/completions") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // API Key
            OutlinedTextField(
                value = modelSettings.apiKey,
                onValueChange = { onModelSettingsChanged(modelSettings.copy(apiKey = it)) },
                label = { Text("API Key") },
                supportingText = { FieldSupportingText("已加密存储 | ${authHeaderHintFor(modelSettings.provider)}") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    FilledTonalButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Text(if (apiKeyVisible) "隐藏" else "显示")
                    }
                },
                singleLine = true
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 本地模型设置卡片
// ---------------------------------------------------------------------------

@Composable
private fun LocalModelSettingsCard(
    modelSettings: ModelSettings,
    onModelSettingsChanged: (ModelSettings) -> Unit
) {
    if (modelSettings.provider != ModelProvider.Local) return

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
                text = "Ollama / LM Studio / vLLM 等本地推理服务",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = modelSettings.localEndpoint,
                onValueChange = { onModelSettingsChanged(modelSettings.copy(localEndpoint = it)) },
                label = { Text("本地接口地址") },
                supportingText = { FieldSupportingText("Ollama 默认 http://127.0.0.1:11434/v1") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = modelSettings.localModelName,
                onValueChange = { onModelSettingsChanged(modelSettings.copy(localModelName = it)) },
                label = { Text("本地模型名称") },
                supportingText = { FieldSupportingText("Ollama: qwen2.5 / llama3 等") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 上下文设置卡片（高级配置）
// ---------------------------------------------------------------------------

@Composable
private fun ContextSettingsCard(
    modelSettings: ModelSettings,
    onContextSettingsChanged: (ContextSettings) -> Unit
) {
    val ctx = modelSettings.contextSettings
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()

    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "上下文与生成参数", style = MaterialTheme.typography.titleMedium)

            // System Prompt
            OutlinedTextField(
                value = ctx.systemPrompt,
                onValueChange = { onContextSettingsChanged(ctx.copy(systemPrompt = it)) },
                label = { Text("System Prompt") },
                supportingText = { FieldSupportingText("设定 AI 角色和行为约束") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 6
            )

            // Max Tokens
            OutlinedTextField(
                value = ctx.maxTokens.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS)
                        ?.let { onContextSettingsChanged(ctx.copy(maxTokens = it)) }
                },
                label = { Text("Max Tokens") },
                supportingText = { FieldSupportingText("单次响应最大令牌数 (${MIN_MAX_TOKENS}-${MAX_MAX_TOKENS})") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            // Temperature
            SliderParameter(
                label = "Temperature",
                value = ctx.temperature,
                valueRange = MIN_TEMPERATURE..MAX_TEMPERATURE,
                steps = 18,
                valueDisplay = "%.2f".format(ctx.temperature),
                onValueChange = { onContextSettingsChanged(ctx.copy(temperature = it)) }
            )

            // Top P
            SliderParameter(
                label = "Top P",
                value = ctx.topP,
                valueRange = 0f..1f,
                steps = 9,
                valueDisplay = "%.2f".format(ctx.topP),
                onValueChange = { onContextSettingsChanged(ctx.copy(topP = it)) }
            )

            // Top K (仅 Anthropic 系列)
            if (modelSettings.provider.apiPathStyle == ApiPathStyle.Anthropic) {
                OutlinedTextField(
                    value = ctx.topK?.toString() ?: "",
                    onValueChange = { value ->
                        val topK = value.toIntOrNull()?.coerceIn(1, 4096)
                        onContextSettingsChanged(ctx.copy(topK = topK))
                    },
                    label = { Text("Top K") },
                    supportingText = { FieldSupportingText("1-4096，空值使用默认") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            // Thinking Budget (仅 Claude 系列)
            if (modelSettings.provider == ModelProvider.Anthropic ||
                modelSettings.provider == ModelProvider.ClaudeCode) {
                OutlinedTextField(
                    value = ctx.thinkingBudget?.toString() ?: "",
                    onValueChange = { value ->
                        val budget = value.toIntOrNull()?.coerceIn(MIN_THINKING_BUDGET, MAX_THINKING_BUDGET)
                        onContextSettingsChanged(ctx.copy(thinkingBudget = budget))
                    },
                    label = { Text("Thinking Budget") },
                    supportingText = { FieldSupportingText("Claude 3.7+ extended thinking 令牌数 (${MIN_THINKING_BUDGET}-${MAX_THINKING_BUDGET})") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            // Stop Sequences
            OutlinedTextField(
                value = ctx.stopSequences.joinToString(", "),
                onValueChange = { value ->
                    val seqs = value.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    onContextSettingsChanged(ctx.copy(stopSequences = seqs))
                },
                label = { Text("Stop Sequences") },
                supportingText = { FieldSupportingText("英文逗号分隔，如 END, STOP") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

@Composable
private fun SliderParameter(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueDisplay: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(text = valueDisplay, style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
    }
}

// ---------------------------------------------------------------------------
// 高级设置折叠
// ---------------------------------------------------------------------------

@Composable
private fun AdvancedSettingsToggle(
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(responsiveCardPadding()),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (expanded) "收起高级配置" else "展开高级配置",
                style = MaterialTheme.typography.titleMedium
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 辅助函数
// ---------------------------------------------------------------------------

private fun themeModeLabel(mode: ThemeMode): String {
    return when (mode) {
        ThemeMode.FollowSystem -> "跟随系统"
        ThemeMode.Dark -> "深色"
        ThemeMode.Light -> "浅色"
    }
}

private fun urlPathModeLabel(mode: UrlPathMode): String {
    return when (mode) {
        UrlPathMode.AutoAppend -> "自动补全"
        UrlPathMode.FullUrl -> "完整 URL"
        UrlPathMode.AppendCustom -> "自定义路径"
    }
}

private fun urlPathModeHint(mode: UrlPathMode): String {
    return when (mode) {
        UrlPathMode.AutoAppend -> "自动追加 /chat/completions 或 /messages"
        UrlPathMode.FullUrl -> "直接使用填写的 URL，不做任何拼接"
        UrlPathMode.AppendCustom -> "追加下方自定义路径字段"
    }
}

private fun baseUrlHintFor(settings: ModelSettings): String {
    return when (settings.provider.apiPathStyle) {
        ApiPathStyle.OpenAI -> "默认: ${settings.provider.defaultBaseUrl}"
        ApiPathStyle.Anthropic -> "默认: ${settings.provider.defaultBaseUrl}"
        ApiPathStyle.Custom -> "填写完整 URL，包含路径"
    }
}

private fun authHeaderHintFor(provider: ModelProvider): String {
    return when (provider) {
        ModelProvider.Anthropic, ModelProvider.AnthropicCompatible, ModelProvider.ClaudeCode ->
            "Header: ${provider.authHeaderName}"
        else -> "Header: ${provider.authHeaderName} = Bearer ..."
    }
}

private fun modelSettingsValidationMessage(settings: ModelSettings): String? {
    return when (settings.provider) {
        ModelProvider.Local -> when {
            settings.localEndpoint.isBlank() -> "本地接口地址不能为空"
            settings.localModelName.isBlank() -> "本地模型名称不能为空"
            else -> null
        }
        ModelProvider.Custom -> when {
            settings.baseUrl.isBlank() -> "API URL 不能为空"
            settings.modelName.isBlank() -> "模型名称不能为空"
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

// ---------------------------------------------------------------------------
// 兼容旧 API（保留给其他模块调用）
// ---------------------------------------------------------------------------

// 注意：SettingsScreenState 和 SettingsScreenActions 已迁移至 SettingsStateHolders.kt
// ClawdroidShell 使用 buildSettingsScreenState() 和 settingsViewModel.buildSettingsScreenActions() 构建
