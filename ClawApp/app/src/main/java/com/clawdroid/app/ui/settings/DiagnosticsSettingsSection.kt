package com.clawdroid.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.clawdroid.app.runtime.RuntimeSecretStore
import com.clawdroid.app.ui.ModernCard
import com.clawdroid.app.ui.SectionTitle
import com.clawdroid.app.ui.SettingsCategoryId
import com.clawdroid.app.ui.SettingsScreenState
import com.clawdroid.app.ui.StatusCard
import com.clawdroid.app.ui.responsiveCardInnerSpacing
import com.clawdroid.app.ui.responsiveCardPadding

internal fun LazyListScope.diagnosticsSettingsSection(
    categoryId: SettingsCategoryId,
    state: SettingsScreenState,
    onGetVersion: () -> Unit = {},
    onGetHealth: () -> Unit = {},
    onGetLastError: () -> Unit = {}
) {
    item { SectionTitle(categoryId.title) }
    item { RuntimeSecretOverrideCard() }
    item {
        StatusCard(
            title = "应用与连接",
            content = "版本: ${state.versionName}\n包名: ${state.packageName}\nSocket: ${state.socketName}\n${state.connectionSummary}"
        )
    }
    item {
        RuntimeDiagnosticsCard(
            state = state,
            onRefresh = {
                onGetVersion()
                onGetHealth()
                onGetLastError()
            }
        )
    }
    item {
        StatusCard(
            title = "配置摘要",
            content = state.runtimeConfigSummary
        )
    }
    item {
        StatusCard(
            title = "说明",
            content = "概览页保留状态与快捷操作；Ping / 探测 / 权限修复 / 页面确认等手动诊断集中在本页。"
        )
    }
}

@Composable
private fun RuntimeDiagnosticsCard(
    state: SettingsScreenState,
    onRefresh: () -> Unit
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(text = "Runtime", style = MaterialTheme.typography.titleMedium)
                FilledTonalButton(onClick = onRefresh) {
                    Text("刷新")
                }
            }
            if (state.runtimeCompatBanner.isNotBlank()) {
                Text(
                    text = "对齐: ${state.runtimeCompatBanner}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "Version:\n${state.runtimeVersionStatus}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Health:\n${state.runtimeHealthStatus}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Last Error:\n${state.runtimeLastErrorStatus}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun RuntimeSecretOverrideCard() {
    val context = LocalContext.current
    var draft by remember {
        mutableStateOf(
            RuntimeSecretStore.getOverride(context.applicationContext).orEmpty()
        )
    }
    var secretVisible by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(
            if (RuntimeSecretStore.usingOverride(context.applicationContext)) {
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
                text = if (RuntimeSecretStore.usingOverride(context.applicationContext)) {
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
                        RuntimeSecretStore.setOverride(
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
                        RuntimeSecretStore.clearOverride(context.applicationContext)
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