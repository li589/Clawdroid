package com.clawdroid.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.clawdroid.app.ui.FieldSupportingText
import com.clawdroid.app.ui.ModernCard
import com.clawdroid.app.ui.ResultPanel
import com.clawdroid.app.ui.SectionTitle
import com.clawdroid.app.ui.SettingsCategoryId
import com.clawdroid.app.ui.SettingsScreenActions
import com.clawdroid.app.ui.SettingsScreenState
import com.clawdroid.app.ui.responsiveCardInnerSpacing
import com.clawdroid.app.ui.responsiveCardPadding

internal fun LazyListScope.assistMcpSettingsSection(
    categoryId: SettingsCategoryId,
    state: SettingsScreenState,
    actions: SettingsScreenActions
) {
    item { SectionTitle(categoryId.title) }
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
}

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