package com.clawdroid.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clawdroid.app.data.model.ApiPathStyle
import com.clawdroid.app.data.model.ContextSettings
import com.clawdroid.app.data.model.ContextSettings.Companion.MAX_MAX_TOKENS
import com.clawdroid.app.data.model.ContextSettings.Companion.MAX_TEMPERATURE
import com.clawdroid.app.data.model.ContextSettings.Companion.MAX_THINKING_BUDGET
import com.clawdroid.app.data.model.ContextSettings.Companion.MIN_MAX_TOKENS
import com.clawdroid.app.data.model.ContextSettings.Companion.MIN_TEMPERATURE
import com.clawdroid.app.data.model.ContextSettings.Companion.MIN_THINKING_BUDGET
import com.clawdroid.app.ui.FieldSupportingText
import com.clawdroid.app.data.model.ModelConfigMemory
import com.clawdroid.app.data.model.ModelContextWindowCatalog
import com.clawdroid.app.ui.ModelListCatalog
import com.clawdroid.app.data.model.ModelProvider
import com.clawdroid.app.data.model.ModelSettings
import com.clawdroid.app.ui.ModernCard
import com.clawdroid.app.data.model.NetworkProxyMode
import com.clawdroid.app.data.model.NetworkProxySettings
import com.clawdroid.app.ui.PrimaryActionButton
import com.clawdroid.app.ui.ResultPanel
import com.clawdroid.app.ui.SectionTitle
import com.clawdroid.app.ui.SettingsCategoryId
import com.clawdroid.app.ui.SettingsScreenActions
import com.clawdroid.app.ui.SettingsScreenState
import com.clawdroid.app.data.model.UrlPathMode
import com.clawdroid.app.data.model.modelProviderDescription
import com.clawdroid.app.data.model.modelProviderLabel
import com.clawdroid.app.ui.responsiveCardInnerSpacing
import com.clawdroid.app.ui.responsiveCardPadding
import com.clawdroid.app.ui.responsiveFlowHSpacing
import com.clawdroid.app.ui.responsiveFlowVSpacing

internal fun LazyListScope.modelSettingsSection(
    categoryId: SettingsCategoryId,
    state: SettingsScreenState,
    actions: SettingsScreenActions
) {
    val validationMessage = modelSettingsValidationMessage(state.modelSettings)
    item { SectionTitle(categoryId.title) }
    item {
        UnifiedModelApiSettingsCard(
            modelSettings = state.modelSettings,
            modelTestStatus = state.modelTestStatus,
            modelTesting = state.modelTesting,
            modelListStatus = state.modelListStatus,
            modelListLoading = state.modelListLoading,
            availableModels = state.availableModels,
            validationMessage = validationMessage,
            rememberedModels = state.configMemory.recentModels,
            rememberedUrls = state.configMemory.recentUrls,
            rememberedApiKeys = state.configMemory.recentApiKeys,
            inputWarning = state.inputWarning,
            onModelSettingsChanged = actions.onModelSettingsChanged,
            onSelectProvider = actions.onSelectProvider,
            onTestModelConnection = actions.onTestModelConnection,
            onFetchModelList = actions.onFetchModelList,
            onSelectModelFromList = actions.onSelectModelFromList,
            onClearAvailableModels = actions.onClearAvailableModels,
            onCommitConfigMemory = actions.onCommitConfigMemory,
            onApplyRememberedModel = actions.onApplyRememberedModel,
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnifiedModelApiSettingsCard(
    modelSettings: ModelSettings,
    modelTestStatus: String,
    modelTesting: Boolean,
    modelListStatus: String,
    modelListLoading: Boolean,
    availableModels: List<String>,
    validationMessage: String?,
    rememberedModels: List<String>,
    rememberedUrls: List<String>,
    rememberedApiKeys: List<String>,
    inputWarning: String,
    onModelSettingsChanged: (ModelSettings) -> Unit,
    onSelectProvider: (ModelProvider) -> Unit,
    onTestModelConnection: () -> Unit,
    onFetchModelList: () -> Unit,
    onSelectModelFromList: (String) -> Unit,
    onClearAvailableModels: () -> Unit,
    onCommitConfigMemory: () -> Unit,
    onApplyRememberedModel: (String) -> Unit,
    onApplyRememberedUrl: (String) -> Unit,
    onApplyRememberedApiKey: (String) -> Unit
) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    val hSpacing = responsiveFlowHSpacing()
    val vSpacing = responsiveFlowVSpacing()
    val focusManager = LocalFocusManager.current
    var showModelPicker by remember(availableModels) {
        mutableStateOf(availableModels.isNotEmpty())
    }
    var apiKeyVisible by remember { mutableStateOf(false) }
    val selected = modelSettings.provider
    val isLocal = selected == ModelProvider.Local

    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "模型 API 接入", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "选择供应商后立即切换配置；连通探测在后台自动跑 3 轮，不阻塞界面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ProviderGroupSection(
                title = "官方",
                providers = ModelProvider.officialProviders,
                selectedProvider = selected,
                hSpacing = hSpacing,
                vSpacing = vSpacing,
                onProviderSelected = onSelectProvider
            )
            ProviderGroupSection(
                title = "国内",
                providers = ModelProvider.chineseProviders,
                selectedProvider = selected,
                hSpacing = hSpacing,
                vSpacing = vSpacing,
                onProviderSelected = onSelectProvider
            )
            ProviderGroupSection(
                title = "聚合平台",
                providers = ModelProvider.aggregatorProviders,
                selectedProvider = selected,
                hSpacing = hSpacing,
                vSpacing = vSpacing,
                onProviderSelected = onSelectProvider
            )
            ProviderGroupSection(
                title = "协议兼容",
                providers = ModelProvider.protocolProviders,
                selectedProvider = selected,
                hSpacing = hSpacing,
                vSpacing = vSpacing,
                onProviderSelected = onSelectProvider
            )
            ProviderGroupSection(
                title = "其他",
                providers = ModelProvider.otherProviders,
                selectedProvider = selected,
                hSpacing = hSpacing,
                vSpacing = vSpacing,
                onProviderSelected = onSelectProvider
            )

            HorizontalDivider()
            Text(
                text = "当前供应商 · ${modelProviderLabel(selected)}",
                style = MaterialTheme.typography.titleSmall
            )
            ResultPanel(text = modelProviderDescription(selected))

            if (inputWarning.isNotBlank()) {
                ResultPanel(text = "输入已消毒: $inputWarning")
            }

            if (isLocal) {
                OutlinedTextField(
                    value = modelSettings.localEndpoint,
                    onValueChange = { onModelSettingsChanged(modelSettings.copy(localEndpoint = it)) },
                    label = { Text("本地接口地址") },
                    supportingText = { FieldSupportingText(selected.baseUrlGuidance()) },
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
            } else {
                Text(
                    text = "URL 路径模式",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(hSpacing)) {
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

                OutlinedTextField(
                    value = modelSettings.baseUrl,
                    onValueChange = { onModelSettingsChanged(modelSettings.copy(baseUrl = it)) },
                    label = { Text("API Base URL") },
                    supportingText = {
                        FieldSupportingText("${selected.baseUrlGuidance()} · 仅 http/https，失焦后写入记忆")
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

                if (modelSettings.urlPathMode == UrlPathMode.AppendCustom ||
                    selected == ModelProvider.Custom
                ) {
                    OutlinedTextField(
                        value = modelSettings.customApiPath,
                        onValueChange = { onModelSettingsChanged(modelSettings.copy(customApiPath = it)) },
                        label = { Text("自定义 API 路径") },
                        supportingText = { FieldSupportingText("例如 /chat/completions 或 /v1/chat/completions") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = modelSettings.apiKey,
                    onValueChange = { onModelSettingsChanged(modelSettings.copy(apiKey = it)) },
                    label = { Text("API Key") },
                    supportingText = {
                        FieldSupportingText("已加密存储 | ${selected.authLabel()} · 失焦后写入记忆")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(focusCommitModifier(onCommitConfigMemory)),
                    visualTransformation = if (apiKeyVisible) {
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

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = if (isLocal) modelSettings.localModelName else modelSettings.modelName,
                    onValueChange = { value ->
                        if (isLocal) {
                            onModelSettingsChanged(modelSettings.copy(localModelName = value))
                        } else {
                            onModelSettingsChanged(modelSettings.copy(modelName = value))
                        }
                    },
                    label = { Text(if (isLocal) "本地模型名称" else "模型名称") },
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
                        isLocal || modelSettings.apiKey.isNotBlank()
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
                    selectedModel = if (isLocal) modelSettings.localModelName else modelSettings.modelName,
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
                text = if (modelTesting) "探测中（3 轮）…" else "手动连通探测（3 轮）",
                onClick = onTestModelConnection,
                modifier = Modifier.fillMaxWidth(),
                enabled = !modelTesting && validationMessage == null
            )
            validationMessage?.let {
                ResultPanel(text = "还不能探测：$it")
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
    hSpacing: Dp,
    vSpacing: Dp,
    onProviderSelected: (ModelProvider) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
}

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

            OutlinedTextField(
                value = ctx.systemPrompt,
                onValueChange = { onContextSettingsChanged(ctx.copy(systemPrompt = it)) },
                label = { Text("System Prompt") },
                supportingText = { FieldSupportingText("设定 AI 角色和行为约束") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 6
            )

            OutlinedTextField(
                value = ctx.maxTokens.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS)
                        ?.let { onContextSettingsChanged(ctx.copy(maxTokens = it)) }
                },
                label = { Text("Max Tokens（单次回复）") },
                supportingText = { FieldSupportingText("单次响应最大令牌数 (${MIN_MAX_TOKENS}-${MAX_MAX_TOKENS})") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            val modelName = modelSettings.modelName.ifBlank { modelSettings.localModelName }
            val catalogDefault = ModelContextWindowCatalog.resolve(modelName, LocalContext.current)
            val effectiveWindow = ctx.effectiveContextWindow(modelName)
            OutlinedTextField(
                value = if (ctx.contextWindowTokens <= 0) "" else ctx.contextWindowTokens.toString(),
                onValueChange = { value ->
                    if (value.isBlank()) {
                        onContextSettingsChanged(ctx.copy(contextWindowTokens = 0))
                    } else {
                        value.toIntOrNull()
                            ?.coerceIn(ContextSettings.MIN_CONTEXT_WINDOW, ContextSettings.MAX_CONTEXT_WINDOW)
                            ?.let { onContextSettingsChanged(ctx.copy(contextWindowTokens = it)) }
                    }
                },
                label = { Text("上下文窗口 Tokens（0/空=按模型目录自动）") },
                supportingText = {
                    FieldSupportingText(
                        "当前生效 $effectiveWindow · 目录默认 $catalogDefault（$modelName）"
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Text(
                text = "常见模型上下文长度（点选填入）",
                style = MaterialTheme.typography.labelLarge
            )
            ModelContextWindowCatalog.suggestForUi(modelName, limit = 12, appContext = LocalContext.current)
                .forEach { entry ->
                    TextButton(
                        onClick = {
                            onContextSettingsChanged(ctx.copy(contextWindowTokens = entry.contextWindow))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "${entry.pattern} · ${entry.contextWindow} · ${entry.source}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2
                        )
                    }
                }

            SliderParameter(
                label = "Temperature",
                value = ctx.temperature,
                valueRange = MIN_TEMPERATURE..MAX_TEMPERATURE,
                steps = 18,
                valueDisplay = "%.2f".format(ctx.temperature),
                onValueChange = { onContextSettingsChanged(ctx.copy(temperature = it)) }
            )

            SliderParameter(
                label = "Top P",
                value = ctx.topP,
                valueRange = 0f..1f,
                steps = 9,
                valueDisplay = "%.2f".format(ctx.topP),
                onValueChange = { onContextSettingsChanged(ctx.copy(topP = it)) }
            )

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
