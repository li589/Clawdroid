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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clawdroid.app.model.usesAnthropicKeyHeader
import com.clawdroid.app.ui.ContextSettings.Companion.MAX_MAX_TOKENS
import com.clawdroid.app.ui.ContextSettings.Companion.MAX_TEMPERATURE
import com.clawdroid.app.ui.ContextSettings.Companion.MAX_THINKING_BUDGET
import com.clawdroid.app.ui.ContextSettings.Companion.MIN_MAX_TOKENS
import com.clawdroid.app.ui.ContextSettings.Companion.MIN_TEMPERATURE
import com.clawdroid.app.ui.ContextSettings.Companion.MIN_THINKING_BUDGET


@Composable
private fun focusCommitModifier(onCommit: () -> Unit): Modifier {
    var hadFocus by remember { mutableStateOf(false) }
    return Modifier.onFocusChanged { state ->
        if (hadFocus && !state.isFocused) {
            onCommit()
        }
        hadFocus = state.isFocused
    }
}

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
            rememberedModels = state.configMemory.recentModels,
            onModelSettingsChanged = actions.onModelSettingsChanged,
            onSelectProvider = actions.onSelectProvider,
            onTestModelConnection = actions.onTestModelConnection,
            onFetchModelList = actions.onFetchModelList,
            onSelectModelFromList = actions.onSelectModelFromList,
            onClearAvailableModels = actions.onClearAvailableModels,
            onCommitConfigMemory = actions.onCommitConfigMemory,
            onApplyRememberedModel = actions.onApplyRememberedModel
        )
    }
    item {
        ApiEndpointSettingsCard(
            modelSettings = state.modelSettings,
            rememberedUrls = state.configMemory.recentUrls,
            rememberedApiKeys = state.configMemory.recentApiKeys,
            inputWarning = state.inputWarning,
            onModelSettingsChanged = actions.onModelSettingsChanged,
            onCommitConfigMemory = actions.onCommitConfigMemory,
            onApplyRememberedUrl = actions.onApplyRememberedUrl,
            onApplyRememberedApiKey = actions.onApplyRememberedApiKey
        )
    }
    item {
        NetworkProxySettingsCard(
            modelSettings = state.modelSettings,
            onModelSettingsChanged = actions.onModelSettingsChanged,
            onCommitConfigMemory = actions.onCommitConfigMemory
        )
    }
    item {
        ModelConfigMemoryCard(
            memory = state.configMemory,
            memoryStatus = state.memoryStatus,
            onApplyProviderSnapshot = actions.onApplyProviderSnapshot,
            onFallbackConfig = actions.onFallbackConfig,
            onClearConfigMemory = actions.onClearConfigMemory
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
    item { SectionTitle("协助 MCP") }
    item {
        McpServerSettingsCard(
            enabled = state.mcpEnabled,
            running = state.mcpRunning,
            port = state.mcpPort,
            token = state.mcpToken,
            statusText = state.mcpStatusText,
            endpointHint = state.mcpEndpointHint,
            onEnabledChanged = actions.onMcpEnabledChanged,
            onPortChanged = actions.onMcpPortChanged,
            onRegenerateToken = actions.onMcpRegenerateToken
        )
    }
    item {
        AssistMcpClientSettingsCard(
            enabled = state.assistEnabled,
            hostUrl = state.assistHostUrl,
            token = state.assistToken,
            statusText = state.assistStatusText,
            endpointHint = state.assistEndpointHint,
            onEnabledChanged = actions.onAssistEnabledChanged,
            onHostUrlChanged = actions.onAssistHostUrlChanged,
            onTokenChanged = actions.onAssistTokenChanged,
            onProbe = actions.onAssistProbe
        )
    }
    item { SectionTitle("运行诊断") }
    item { RuntimeSecretOverrideCard() }
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
// Runtime 密钥覆盖（设备侧；空则回退 BuildConfig）
// ---------------------------------------------------------------------------

@Composable
private fun RuntimeSecretOverrideCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var draft by remember {
        mutableStateOf(
            com.clawdroid.app.runtime.RuntimeSecretStore.getOverride(context.applicationContext).orEmpty()
        )
    }
    var secretVisible by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(
            if (com.clawdroid.app.runtime.RuntimeSecretStore.usingOverride(context.applicationContext)) {
                "当前使用设备覆盖密钥"
            } else {
                "当前使用编译期默认密钥"
            }
        )
    }
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "Runtime 共享密钥", style = MaterialTheme.typography.titleLarge)
            Text(
                text = if (com.clawdroid.app.runtime.RuntimeSecretStore.usingOverride(context.applicationContext)) {
                    "状态：设备覆盖生效（优先于编译期密钥）"
                } else {
                    "状态：使用编译期默认密钥（APK 可逆向；生产请改用设备覆盖）"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "覆盖值必须与 Magisk runtime.yaml 的 auth.shared_secret 一致。留空并保存或点清除则回退编译期密钥。修改后需重开应用以重建 IPC 客户端。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("设备侧覆盖密钥") },
                singleLine = true,
                visualTransformation = if (secretVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    FilledTonalButton(onClick = { secretVisible = !secretVisible }) {
                        Text(if (secretVisible) "隐藏" else "显示")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        com.clawdroid.app.runtime.RuntimeSecretStore.setOverride(
                            context.applicationContext,
                            draft
                        )
                        status = if (draft.isBlank()) {
                            "已清除覆盖，重启后使用编译期密钥"
                        } else {
                            "已保存覆盖，请重启应用使 IPC 生效"
                        }
                    }
                ) {
                    Text("保存")
                }
                TextButton(
                    onClick = {
                        draft = ""
                        com.clawdroid.app.runtime.RuntimeSecretStore.clearOverride(context.applicationContext)
                        status = "已清除覆盖，重启后使用编译期密钥"
                    }
                ) {
                    Text("清除")
                }
            }
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 协助 MCP（手机侧服务 + 电脑协助客户端）
// ---------------------------------------------------------------------------

@Composable
private fun McpServerSettingsCard(
    enabled: Boolean,
    running: Boolean,
    port: Int,
    token: String,
    statusText: String,
    endpointHint: String,
    onEnabledChanged: (Boolean) -> Unit,
    onPortChanged: (Int) -> Unit,
    onRegenerateToken: () -> Unit
) {
    var portText by remember(port) { mutableStateOf(port.toString()) }
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "协助 MCP · 手机侧服务", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "电脑经 adb forward 调用本机工具 / Skills / Agents（server: clawdroid-assist）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (running) "服务运行中" else "服务已关闭")
                Switch(checked = enabled, onCheckedChange = onEnabledChanged)
            }
            OutlinedTextField(
                value = portText,
                onValueChange = { value ->
                    portText = value.filter { it.isDigit() }.take(5)
                    portText.toIntOrNull()?.let(onPortChanged)
                },
                label = { Text("监听端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = token,
                onValueChange = {},
                readOnly = true,
                label = { Text("访问 Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            FilledTonalButton(onClick = onRegenerateToken) {
                Text("重新生成 Token")
            }
            ResultPanel(text = "状态\n$statusText")
            if (endpointHint.isNotBlank()) {
                ResultPanel(text = "连接说明\n$endpointHint")
            }
        }
    }
}

@Composable
private fun AssistMcpClientSettingsCard(
    enabled: Boolean,
    hostUrl: String,
    token: String,
    statusText: String,
    endpointHint: String,
    onEnabledChanged: (Boolean) -> Unit,
    onHostUrlChanged: (String) -> Unit,
    onTokenChanged: (String) -> Unit,
    onProbe: () -> Unit
) {
    var tokenVisible by remember { mutableStateOf(false) }
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "协助 MCP · 电脑协助端点", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "手机通过 adb reverse 调用电脑 MCP（assist_ping / assist_list_tools / assist_call_tool）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (enabled) "客户端已启用" else "客户端已关闭")
                Switch(checked = enabled, onCheckedChange = onEnabledChanged)
            }
            OutlinedTextField(
                value = hostUrl,
                onValueChange = onHostUrlChanged,
                label = { Text("电脑 MCP URL") },
                supportingText = { FieldSupportingText("例如 http://127.0.0.1:8766/mcp") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChanged,
                label = { Text("电脑 MCP Token（可选）") },
                singleLine = true,
                visualTransformation = if (tokenVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    FilledTonalButton(onClick = { tokenVisible = !tokenVisible }) {
                        Text(if (tokenVisible) "隐藏" else "显示")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            FilledTonalButton(onClick = onProbe) {
                Text("探测连通")
            }
            ResultPanel(text = "状态\n$statusText")
            if (endpointHint.isNotBlank()) {
                ResultPanel(text = "连接说明\n$endpointHint")
            }
        }
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
    rememberedModels: List<String>,
    onModelSettingsChanged: (ModelSettings) -> Unit,
    onSelectProvider: (ModelProvider) -> Unit,
    onTestModelConnection: () -> Unit,
    onFetchModelList: () -> Unit,
    onSelectModelFromList: (String) -> Unit,
    onClearAvailableModels: () -> Unit,
    onCommitConfigMemory: () -> Unit,
    onApplyRememberedModel: (String) -> Unit
) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    val hSpacing = responsiveFlowHSpacing()
    val vSpacing = responsiveFlowVSpacing()
    val focusManager = LocalFocusManager.current
    var showModelPicker by remember(availableModels) {
        mutableStateOf(availableModels.isNotEmpty())
    }

    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "模型供应商", style = MaterialTheme.typography.titleMedium)

            ProviderGroupSection(
                title = "官方",
                providers = ModelProvider.officialProviders,
                selectedProvider = modelSettings.provider,
                hSpacing = hSpacing,
                vSpacing = vSpacing,
                onProviderSelected = onSelectProvider
            )
            ProviderGroupSection(
                title = "国内",
                providers = ModelProvider.chineseProviders,
                selectedProvider = modelSettings.provider,
                hSpacing = hSpacing,
                vSpacing = vSpacing,
                onProviderSelected = onSelectProvider
            )
            ProviderGroupSection(
                title = "聚合平台",
                providers = ModelProvider.aggregatorProviders,
                selectedProvider = modelSettings.provider,
                hSpacing = hSpacing,
                vSpacing = vSpacing,
                onProviderSelected = onSelectProvider
            )
            ProviderGroupSection(
                title = "协议兼容",
                providers = ModelProvider.protocolProviders,
                selectedProvider = modelSettings.provider,
                hSpacing = hSpacing,
                vSpacing = vSpacing,
                onProviderSelected = onSelectProvider
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(hSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = { onSelectProvider(ModelProvider.Custom) },
                    label = { Text(modelProviderLabel(ModelProvider.Custom)) }
                )
                AssistChip(
                    onClick = { onSelectProvider(ModelProvider.Local) },
                    label = { Text(modelProviderLabel(ModelProvider.Local)) }
                )
            }

            HorizontalDivider()
            ResultPanel(
                text = "当前: ${modelProviderLabel(modelSettings.provider)}\n${modelProviderHint(modelSettings.provider)}"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = modelSettings.modelName,
                    onValueChange = { onModelSettingsChanged(modelSettings.copy(modelName = it)) },
                    label = { Text("模型名称") },
                    supportingText = {
                        FieldSupportingText(
                            if (availableModels.isEmpty()) {
                                "手动填写，或点右侧按钮拉取列表后筛选；失焦/完成才记入记忆"
                            } else {
                                "已拉取 ${availableModels.size} 个，可在下方搜索筛选"
                            }
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .then(focusCommitModifier(onCommitConfigMemory)),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            onCommitConfigMemory()
                            focusManager.clearFocus()
                        }
                    ),
                    trailingIcon = {
                        if (availableModels.isNotEmpty()) {
                            IconButton(onClick = { showModelPicker = !showModelPicker }) {
                                Icon(
                                    imageVector = if (showModelPicker) {
                                        Icons.Default.KeyboardArrowUp
                                    } else {
                                        Icons.Default.KeyboardArrowDown
                                    },
                                    contentDescription = "展开模型列表"
                                )
                            }
                        }
                    }
                )
                FilledTonalButton(
                    onClick = {
                        showModelPicker = true
                        onFetchModelList()
                    },
                    enabled = !modelListLoading && (
                        modelSettings.provider == ModelProvider.Local ||
                            modelSettings.apiKey.isNotBlank()
                        ) && modelSettings.resolvedEndpoint().isNotBlank()
                ) {
                    Icon(
                        imageVector = if (modelListLoading) Icons.Default.Refresh else Icons.Default.List,
                        contentDescription = "拉取模型列表"
                    )
                }
            }

            if (availableModels.isNotEmpty() && showModelPicker) {
                ModelListPickerPanel(
                    models = availableModels,
                    selectedModel = modelSettings.modelName,
                    onSelect = onSelectModelFromList,
                    onClose = {
                        showModelPicker = false
                        onClearAvailableModels()
                    },
                    onCollapse = { showModelPicker = false }
                )
            }

            if (rememberedModels.isNotEmpty()) {
                MemoryChipRow(
                    title = "模型记忆",
                    values = rememberedModels.take(8),
                    onSelect = onApplyRememberedModel
                )
            }
            if (modelListStatus.isNotBlank()) {
                ResultPanel(text = modelListStatus)
            }

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
private fun ModelListPickerPanel(
    models: List<String>,
    selectedModel: String,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    onCollapse: () -> Unit
) {
    var query by remember(models) { mutableStateOf("") }
    var activeToken by remember(models) { mutableStateOf<String?>(null) }
    val tokens = remember(models) { ModelListCatalog.suggestTokens(models) }
    val result = remember(models, query, activeToken) {
        ModelListCatalog.filter(models, query = query, activeToken = activeToken)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "模型列表筛选",
                    style = MaterialTheme.typography.titleSmall
                )
                Row {
                    TextButton(onClick = onCollapse) { Text("收起") }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "关闭并清空列表")
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索模型") },
                placeholder = { Text("名称 / 厂商 / 关键字，如 qwen、claude、gpt") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "清除搜索")
                        }
                    }
                }
            )

            if (tokens.isNotEmpty()) {
                Text(
                    text = "快捷标签",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = activeToken == null,
                        onClick = { activeToken = null },
                        label = { Text("全部") }
                    )
                    tokens.forEach { token ->
                        FilterChip(
                            selected = activeToken.equals(token, ignoreCase = true),
                            onClick = {
                                activeToken = if (activeToken.equals(token, ignoreCase = true)) {
                                    null
                                } else {
                                    token
                                }
                            },
                            label = { Text(token) }
                        )
                    }
                }
            }

            Text(
                text = result.summary() + if (result.shown >= 300 && result.total > 300) {
                    "（最多展示 300 条，请继续缩小搜索）"
                } else {
                    ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (result.filtered.isEmpty()) {
                ResultPanel(text = "没有匹配的模型，试试换个关键字或标签")
            } else {
                // 使用普通滚动，避免嵌在设置页 LazyColumn 内再套 LazyColumn
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    result.filtered.forEach { model ->
                        val selected = model == selectedModel
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(model) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = model,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) {
                                Text(
                                    text = "已选",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
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
    rememberedUrls: List<String>,
    rememberedApiKeys: List<String>,
    inputWarning: String,
    onModelSettingsChanged: (ModelSettings) -> Unit,
    onCommitConfigMemory: () -> Unit,
    onApplyRememberedUrl: (String) -> Unit,
    onApplyRememberedApiKey: (String) -> Unit
) {
    // 仅非本地模型显示此卡片
    if (modelSettings.provider == ModelProvider.Local) return

    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    val hSpacing = responsiveFlowHSpacing()
    val focusManager = LocalFocusManager.current
    var apiKeyVisible by remember { mutableStateOf(false) }

    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "API 接入配置", style = MaterialTheme.typography.titleMedium)
            if (inputWarning.isNotBlank()) {
                ResultPanel(text = "输入已消毒: $inputWarning")
            }

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
                supportingText = {
                    FieldSupportingText(
                        baseUrlHintFor(modelSettings) + " · 仅 http/https，失焦后写入记忆"
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(focusCommitModifier(onCommitConfigMemory)),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        onCommitConfigMemory()
                        focusManager.clearFocus()
                    }
                )
            )
            if (rememberedUrls.isNotEmpty()) {
                MemoryChipRow(
                    title = "URL 记忆",
                    values = rememberedUrls.take(8),
                    labelFor = { it },
                    onSelect = onApplyRememberedUrl
                )
            }

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
                supportingText = {
                    FieldSupportingText(
                        "已加密存储 | ${authHeaderHintFor(modelSettings.provider)} · 失焦后写入记忆"
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(focusCommitModifier(onCommitConfigMemory)),
                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        onCommitConfigMemory()
                        focusManager.clearFocus()
                    }
                ),
                trailingIcon = {
                    FilledTonalButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Text(if (apiKeyVisible) "隐藏" else "显示")
                    }
                },
                singleLine = true
            )
            if (rememberedApiKeys.isNotEmpty()) {
                MemoryChipRow(
                    title = "API Key 记忆",
                    values = rememberedApiKeys.take(6),
                    labelFor = { key ->
                        if (key.length <= 8) "****" else key.take(4) + "…" + key.takeLast(4)
                    },
                    onSelect = onApplyRememberedApiKey
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 网络代理 / VPN 设置卡片
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetworkProxySettingsCard(
    modelSettings: ModelSettings,
    onModelSettingsChanged: (ModelSettings) -> Unit,
    onCommitConfigMemory: () -> Unit
) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    val hSpacing = responsiveFlowHSpacing()
    val vSpacing = responsiveFlowVSpacing()
    val focusManager = LocalFocusManager.current
    val proxy = modelSettings.proxySettings
    var passwordVisible by remember { mutableStateOf(false) }

    fun updateProxy(block: (NetworkProxySettings) -> NetworkProxySettings) {
        onModelSettingsChanged(modelSettings.copy(proxySettings = block(proxy)))
    }

    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "网络代理 / VPN", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "AI 请求出口。选「跟随系统」时走设备 VPN；本地 Clash/V2Ray 等可选 HTTP/SOCKS。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(hSpacing),
                verticalArrangement = Arrangement.spacedBy(vSpacing)
            ) {
                NetworkProxyMode.entries.forEach { mode ->
                    FilterChip(
                        selected = proxy.mode == mode,
                        onClick = {
                            updateProxy { it.copy(mode = mode) }
                            onCommitConfigMemory()
                        },
                        label = { Text(mode.displayName) }
                    )
                }
            }

            Text(
                text = proxy.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (proxy.isCustomProxy()) {
                OutlinedTextField(
                    value = proxy.host,
                    onValueChange = { value -> updateProxy { it.copy(host = value) } },
                    label = { Text("代理主机") },
                    supportingText = { FieldSupportingText("常见 127.0.0.1（本机 Clash / 系统代理）") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(focusCommitModifier(onCommitConfigMemory)),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    )
                )
                OutlinedTextField(
                    value = proxy.port.toString(),
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(5)
                        val port = digits.toIntOrNull()?.coerceIn(1, 65535) ?: proxy.port
                        updateProxy { it.copy(port = if (digits.isEmpty()) proxy.port else port) }
                    },
                    label = { Text("代理端口") },
                    supportingText = { FieldSupportingText("HTTP 常见 7890；SOCKS 常见 7891 / 1080") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(focusCommitModifier(onCommitConfigMemory)),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    )
                )
                if (proxy.mode == NetworkProxyMode.Http) {
                    OutlinedTextField(
                        value = proxy.username,
                        onValueChange = { value -> updateProxy { it.copy(username = value) } },
                        label = { Text("代理用户名（可选）") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(focusCommitModifier(onCommitConfigMemory)),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                    )
                    OutlinedTextField(
                        value = proxy.password,
                        onValueChange = { value -> updateProxy { it.copy(password = value) } },
                        label = { Text("代理密码（可选）") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(focusCommitModifier(onCommitConfigMemory)),
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                onCommitConfigMemory()
                                focusManager.clearFocus()
                            }
                        ),
                        trailingIcon = {
                            FilledTonalButton(onClick = { passwordVisible = !passwordVisible }) {
                                Text(if (passwordVisible) "隐藏" else "显示")
                            }
                        },
                        singleLine = true
                    )
                }
            }
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


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryChipRow(
    title: String,
    values: List<String>,
    labelFor: (String) -> String = { it },
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            values.forEach { value ->
                AssistChip(
                    onClick = { onSelect(value) },
                    label = {
                        Text(
                            text = labelFor(value),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelConfigMemoryCard(
    memory: ModelConfigMemory,
    memoryStatus: String,
    onApplyProviderSnapshot: (ModelProvider) -> Unit,
    onFallbackConfig: () -> Unit,
    onClearConfigMemory: () -> Unit
) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    val snapshots = memory.providerSnapshots.values.sortedByDescending { it.savedAtEpochMs }

    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "配置记忆与回退", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "自动记住模型名、API URL、API Key；切换供应商时恢复该供应商上次配置；可回退到改动前。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onFallbackConfig,
                    enabled = memory.canFallback,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (memory.canFallback) "回退上一配置" else "无可回退")
                }
                FilledTonalButton(
                    onClick = onClearConfigMemory,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("清除近期记忆")
                }
            }
            if (snapshots.isNotEmpty()) {
                Text(
                    text = "供应商快照",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    snapshots.take(10).forEach { snap ->
                        AssistChip(
                            onClick = { onApplyProviderSnapshot(snap.provider) },
                            label = {
                                Text(
                                    text = snap.provider.displayName,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        )
                    }
                }
                ResultPanel(
                    text = snapshots.take(3).joinToString("\n") { "• ${it.summaryLabel()}" }
                )
            }
            if (memoryStatus.isNotBlank()) {
                ResultPanel(text = memoryStatus)
            }
        }
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
        UrlPathMode.AutoAppend -> "聊天追加 /chat/completions 或 /messages；拉列表用 /models"
        UrlPathMode.FullUrl -> "填完整聊天 URL；拉列表时自动改写为 /models"
        UrlPathMode.AppendCustom -> "追加下方自定义路径；拉列表仍走 /models"
    }
}

private fun baseUrlHintFor(settings: ModelSettings): String {
    return when (settings.provider) {
        ModelProvider.OpenAICompatible ->
            "中转站示例: https://你的域名/v1（不要填到 chat/completions）"
        ModelProvider.AnthropicCompatible ->
            "Claude 中转示例: https://你的域名/v1"
        ModelProvider.SiliconFlow ->
            "默认: https://api.siliconflow.cn/v1"
        ModelProvider.OpenRouter ->
            "必须含 /api/v1，例如 https://openrouter.ai/api/v1"
        ModelProvider.Custom ->
            "填写完整 URL，包含路径"
        else -> "默认: ${settings.provider.defaultBaseUrl}"
    }
}

private fun authHeaderHintFor(provider: ModelProvider): String {
    return when {
        provider.usesAnthropicKeyHeader() -> "Header: x-api-key（同时兼容 Bearer）"
        else -> "Header: ${provider.authHeaderName} = Bearer ..."
    }
}

private fun modelSettingsValidationMessage(settings: ModelSettings): String? {
    return ModelInputSanitizer.validationError(settings)
}

// ---------------------------------------------------------------------------
// 兼容旧 API（保留给其他模块调用）
// ---------------------------------------------------------------------------

// 注意：SettingsScreenState 和 SettingsScreenActions 已迁移至 SettingsStateHolders.kt
// ClawdroidShell 使用 buildSettingsScreenState() 和 settingsViewModel.buildSettingsScreenActions() 构建
