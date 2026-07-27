package com.clawdroid.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.clawdroid.app.env.AppPermissionManager
import com.clawdroid.app.termux.TermuxBridge
import com.clawdroid.app.termux.TermuxConsoleScreen
import com.clawdroid.app.ui.ModernCard
import com.clawdroid.app.ui.ResultPanel
import com.clawdroid.app.ui.SectionTitle
import com.clawdroid.app.ui.SettingsCategoryId
import com.clawdroid.app.ui.responsiveCardInnerSpacing
import com.clawdroid.app.ui.responsiveCardPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TERMUX_PACKAGE = "com.termux"
private const val DEFAULT_CLEANUP_DISTRO = "ubuntu"
private const val ALLOW_EXTERNAL_APPS_LINE = "allow-external-apps=true"
private const val TERMUX_PROPERTIES_HINT =
    "~/.termux/termux.properties\n\n$ALLOW_EXTERNAL_APPS_LINE"

internal fun LazyListScope.termuxShellSettingsSection(categoryId: SettingsCategoryId) {
    item { SectionTitle(categoryId.title) }
    item { TermuxShellSettingsCard() }
}

@Composable
private fun TermuxShellSettingsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showConsole by remember { mutableStateOf(false) }
    var showCleanupConfirm by remember { mutableStateOf(false) }
    var cleanupBusy by remember { mutableStateOf(false) }
    var permissionStatus by remember {
        mutableStateOf(buildTermuxPermissionStatus(context))
    }
    var actionFeedback by remember { mutableStateOf("") }
    val termuxInstalled = remember {
        TermuxBridge.isTermuxInstalled(context)
    }
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionStatus = buildTermuxPermissionStatus(context)
        actionFeedback = if (granted) {
            "已授予 RUN_COMMAND。请确认 Termux 中 allow-external-apps=true 后即可调用 termux_exec。"
        } else {
            "系统未授予 RUN_COMMAND（多数机型也不会在「其它权限」里显示该开关）。" +
                "可再点一次「请求权限」，或使用「Root 授权并检查」。"
        }
    }

    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "Termux 集成", style = MaterialTheme.typography.titleMedium)
            ResultPanel(
                text = buildString {
                    appendLine(
                        if (termuxInstalled) {
                            "已检测到 Termux（$TERMUX_PACKAGE）"
                        } else {
                            "未检测到 Termux。可从 F-Droid / GitHub 安装后再配置。"
                        }
                    )
                    append(permissionStatus)
                }
            )

            Text(text = "RUN_COMMAND 权限", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "这是 Termux 定义的危险权限，多数国产系统不会在「应用信息 → 其它权限」里列出。" +
                    "请用下方按钮弹出系统授权框，或用 Root 授权并检查（会 pm grant，并写入/核对 allow-external-apps）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        if (!termuxInstalled) {
                            actionFeedback = "请先安装 Termux，再请求 RUN_COMMAND。"
                            return@FilledTonalButton
                        }
                        if (TermuxBridge.hasRunCommandPermission(context)) {
                            permissionStatus = buildTermuxPermissionStatus(context)
                            actionFeedback = "RUN_COMMAND 已授权。"
                            return@FilledTonalButton
                        }
                        actionFeedback = "正在请求 RUN_COMMAND…"
                        permissionLauncher.launch(TermuxBridge.RUN_COMMAND_PERMISSION)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = termuxInstalled
                ) {
                    Text("请求 RUN_COMMAND 权限")
                }
                FilledTonalButton(
                    onClick = {
                        actionFeedback = "正在 Root 授权并检查…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                AppPermissionManager.grantAndVerifyTermuxIntegrationViaRoot(context)
                            }
                            permissionStatus = buildTermuxPermissionStatus(context)
                            actionFeedback = result.output
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = termuxInstalled
                ) {
                    Text("Root 授权并检查")
                }
            }
            if (actionFeedback.isNotBlank()) {
                ResultPanel(text = actionFeedback)
            }

            Text(text = "坏容器清理", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "安装超时或被打断后可能留下空 rootfs / 残留 lock。" +
                    "点下方清理会执行 proot-distro remove（默认 $DEFAULT_CLEANUP_DISTRO），成功后再删白名单锁文件。" +
                    "安装进行中请勿点；长安装依赖已有 timeout 自动拉长。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(
                onClick = { showCleanupConfirm = true },
                enabled = termuxInstalled && !cleanupBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (cleanupBusy) "正在清理…" else "清理坏容器（$DEFAULT_CLEANUP_DISTRO）")
            }

            if (showCleanupConfirm) {
                AlertDialog(
                    onDismissRequest = { if (!cleanupBusy) showCleanupConfirm = false },
                    title = { Text("清理坏容器？") },
                    text = {
                        Text(
                            "将移除 proot-distro 容器「$DEFAULT_CLEANUP_DISTRO」并清理对应 lock。" +
                                "安装进行中请取消。清理后可重新 install。"
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showCleanupConfirm = false
                                cleanupBusy = true
                                actionFeedback = "正在清理 $DEFAULT_CLEANUP_DISTRO…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        TermuxBridge(context).cleanupBrokenDistro(DEFAULT_CLEANUP_DISTRO)
                                    }
                                    cleanupBusy = false
                                    actionFeedback = result.output
                                }
                            },
                            enabled = !cleanupBusy
                        ) {
                            Text("确认清理")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showCleanupConfirm = false },
                            enabled = !cleanupBusy
                        ) {
                            Text("取消")
                        }
                    }
                )
            }

            Text(text = "allow-external-apps（与 RUN_COMMAND 是两回事）", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "即使已授予 RUN_COMMAND，Termux 仍会拒绝外部调用，除非 ~/.termux/termux.properties 含下面这行。" +
                    "推荐直接点上方「Root 授权并检查」（会写入并 force-stop Termux 重载）。无 Root 时请手动写入：",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CopyableConfigLine(
                displayText = TERMUX_PROPERTIES_HINT,
                copyText = ALLOW_EXTERNAL_APPS_LINE,
                copyLabel = "allow-external-apps"
            )
            Text(
                text = "手动写入后必须强制停止并重新打开 Termux，配置才会生效。" +
                    "在 Termux 内也可执行：mkdir -p ~/.termux && echo allow-external-apps=true >> ~/.termux/termux.properties",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (termuxInstalled) {
                    FilledTonalButton(
                        onClick = {
                            val launch = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
                                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://termux.dev"))
                            runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("打开 Termux")
                    }
                } else {
                    FilledTonalButton(
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://f-droid.org/packages/com.termux/")
                            )
                            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("F-Droid 安装")
                    }
                }
                FilledTonalButton(
                    onClick = {
                        permissionStatus = buildTermuxPermissionStatus(context)
                        showConsole = !showConsole
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (showConsole) "收起控制台" else "内置控制台")
                }
            }

            if (showConsole) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 360.dp, max = 520.dp)
                ) {
                    TermuxConsoleScreen(modifier = Modifier.fillMaxSize())
                }
            }

            HorizontalDivider()

            Text(text = "应用内沙箱 Shell", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "无需 Termux 时，Agent 可使用 sandbox_shell 在应用 filesDir/sandbox 下执行白名单短命令（无 Root/Shizuku）。" +
                    "检测是否安装 Termux 请用 execute_shell_limited：pm path com.termux（不要用管道/grep）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CopyableConfigLine(
    displayText: String,
    copyText: String,
    copyLabel: String
) {
    val context = LocalContext.current
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
            IconButton(
                onClick = {
                    copyTextToClipboard(context, copyLabel, copyText)
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "复制 $copyLabel",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun copyTextToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "已复制: $text", Toast.LENGTH_SHORT).show()
}

private fun buildTermuxPermissionStatus(context: Context): String {
    return buildString {
        append("RUN_COMMAND=")
        append(
            if (TermuxBridge.hasRunCommandPermission(context)) "已授权" else "未授权"
        )
    }
}
