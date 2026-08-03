package com.clawdroid.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.clawdroid.app.data.FileIndexStore
import com.clawdroid.app.data.MemoryFacade
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.data.model.AgentOrchestrationSettings
import com.clawdroid.app.ui.FieldSupportingText
import com.clawdroid.app.ui.ModernCard
import com.clawdroid.app.ui.SectionTitle
import com.clawdroid.app.ui.SettingsCategoryId
import com.clawdroid.app.ui.SettingsScreenActions
import com.clawdroid.app.ui.SettingsScreenState
import com.clawdroid.app.ui.responsiveCardInnerSpacing
import com.clawdroid.app.ui.responsiveCardPadding
import com.clawdroid.app.ui.responsiveFlowHSpacing
import com.clawdroid.app.ui.responsiveFlowVSpacing

private val DANGEROUS_TOOL_IDS = setOf(
    "sandbox_shell",
    "termux_exec",
    "execute_shell_limited",
    "shizuku_exec",
    "shizuku_request",
    "file_write",
    "file_replace",
    "camera_capture",
    "camera_record",
    "ftp_transfer",
    "app_launch",
    "download_start"
)

private val COMMON_TOOL_IDS: Set<String> =
    AgentOrchestrationSettings.defaultAllowlist() - DANGEROUS_TOOL_IDS

internal fun LazyListScope.agentToolsSettingsSection(
    categoryId: SettingsCategoryId,
    state: SettingsScreenState,
    actions: SettingsScreenActions
) {
    item { SectionTitle(categoryId.title) }
    item {
        AgentOrchestrationSettingsCard(
            agentSettings = state.agentSettings,
            onAgentSettingsChanged = actions.onAgentSettingsChanged
        )
    }
    item { ContextMemoryIndexCard() }
}

@Composable
private fun ContextMemoryIndexCard() {
    val context = LocalContext.current
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    var summary by remember {
        mutableStateOf(MemoryFacade.summary(context))
    }
    fun refresh() {
        summary = MemoryFacade.summary(context)
    }
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "上下文索引与记忆图谱", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "经 MemoryFacade 检索聊天索引 / 文件索引 / 记忆图谱；长对话开启压缩后写入图谱摘要。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = summary, style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        FileIndexStore.scanSandbox(context)
                        refresh()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("扫描沙箱文件") }
                TextButton(
                    onClick = {
                        MemoryFacade.clearAll(context)
                        refresh()
                    }
                ) { Text("清空索引") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentOrchestrationSettingsCard(
    agentSettings: AgentOrchestrationSettings,
    onAgentSettingsChanged: (AgentOrchestrationSettings) -> Unit
) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    val hSpacing = responsiveFlowHSpacing()
    val vSpacing = responsiveFlowVSpacing()

    val selectedCommon = remember(agentSettings) {
        if (agentSettings.toolAllowlistCustomized) {
            agentSettings.toolAllowlist.intersect(COMMON_TOOL_IDS)
        } else {
            COMMON_TOOL_IDS
        }
    }
    val dangerousEnabled = remember(agentSettings) {
        if (agentSettings.toolAllowlistCustomized) {
            agentSettings.toolAllowlist.any { it in DANGEROUS_TOOL_IDS }
        } else {
            false
        }
    }

    fun publishAllowlist(common: Set<String>, includeDangerous: Boolean) {
        val allowlist = buildSet {
            addAll(common.intersect(COMMON_TOOL_IDS))
            if (includeDangerous) addAll(DANGEROUS_TOOL_IDS)
        }
        onAgentSettingsChanged(
            agentSettings.copy(
                toolAllowlist = allowlist,
                toolAllowlistCustomized = true
            )
        )
    }

    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "Agent 编排限制", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "控制工具循环步数、模型 API 预算、允许列表强制执行、Agent 调用与危险命令审查。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SliderParameter(
                label = "最大工具循环步数",
                value = agentSettings.maxToolLoopTurns.toFloat(),
                valueRange = AgentOrchestrationSettings.MIN_TOOL_LOOP_TURNS.toFloat()..
                    AgentOrchestrationSettings.MAX_TOOL_LOOP_TURNS_CAP.toFloat(),
                steps = (AgentOrchestrationSettings.MAX_TOOL_LOOP_TURNS_CAP -
                    AgentOrchestrationSettings.MIN_TOOL_LOOP_TURNS) / 4 - 1,
                valueDisplay = agentSettings.maxToolLoopTurns.toString(),
                onValueChange = { value ->
                    onAgentSettingsChanged(
                        agentSettings.copy(maxToolLoopTurns = value.toInt())
                    )
                }
            )

            OutlinedTextField(
                value = agentSettings.maxModelApiCalls.toString(),
                onValueChange = { raw ->
                    raw.filter { it.isDigit() }.take(5).toIntOrNull()
                        ?.coerceIn(
                            AgentOrchestrationSettings.MIN_MODEL_API_CALLS,
                            AgentOrchestrationSettings.MAX_MODEL_API_CALLS_CAP
                        )
                        ?.let { onAgentSettingsChanged(agentSettings.copy(maxModelApiCalls = it)) }
                },
                label = { Text("最大模型 API 调用") },
                supportingText = {
                    FieldSupportingText(
                        "每次你发送一条消息后，该轮 AI 回复过程中的模型调用上限" +
                            "（默认 ${AgentOrchestrationSettings.DEFAULT_MAX_MODEL_API_CALLS}，" +
                            "范围 ${AgentOrchestrationSettings.MIN_MODEL_API_CALLS}–" +
                            "${AgentOrchestrationSettings.MAX_MODEL_API_CALLS_CAP}）；下一条消息会重新计数"
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "上下文压缩", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "长对话时压缩历史以节省令牌",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = agentSettings.contextCompressionEnabled,
                    onCheckedChange = { enabled ->
                        onAgentSettingsChanged(agentSettings.copy(contextCompressionEnabled = enabled))
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "强制执行允许列表", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "未在列表中的工具在执行时会被拒绝（不仅是隐藏）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = agentSettings.enforceToolAllowlist,
                    onCheckedChange = { enabled ->
                        onAgentSettingsChanged(agentSettings.copy(enforceToolAllowlist = enabled))
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "允许 Agent 调用", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "关闭后阻断 run_agent / run_agents_parallel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = agentSettings.agentCallsEnabled,
                    onCheckedChange = { enabled ->
                        onAgentSettingsChanged(agentSettings.copy(agentCallsEnabled = enabled))
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "危险命令审查", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Shell / 写文件 / 摄像头等执行前需在聊天中确认",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = agentSettings.requireCommandReview,
                    onCheckedChange = { enabled ->
                        onAgentSettingsChanged(agentSettings.copy(requireCommandReview = enabled))
                    }
                )
            }

            HorizontalDivider()

            Text(text = "常用工具", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (agentSettings.toolAllowlistCustomized) {
                    "已自定义允许列表（${agentSettings.toolAllowlist.size} 项）"
                } else {
                    "尚未自定义：显示内置默认常用工具；保存后将写入设备"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(hSpacing),
                verticalArrangement = Arrangement.spacedBy(vSpacing)
            ) {
                COMMON_TOOL_IDS.sorted().forEach { toolId ->
                    FilterChip(
                        selected = toolId in selectedCommon,
                        onClick = {
                            val next = if (toolId in selectedCommon) {
                                selectedCommon - toolId
                            } else {
                                selectedCommon + toolId
                            }
                            publishAllowlist(next, dangerousEnabled)
                        },
                        label = { Text(toolLabel(toolId)) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "危险工具", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Shell、写文件、摄像头、应用启动等（默认关闭）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = dangerousEnabled,
                    onCheckedChange = { enabled ->
                        publishAllowlist(selectedCommon, enabled)
                    }
                )
            }

            if (dangerousEnabled) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(hSpacing),
                    verticalArrangement = Arrangement.spacedBy(vSpacing)
                ) {
                    DANGEROUS_TOOL_IDS.sorted().forEach { toolId ->
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text(toolLabel(toolId)) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        onAgentSettingsChanged(AgentOrchestrationSettings())
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("恢复默认")
                }
            }
        }
    }
}

private fun toolLabel(toolId: String): String {
    return ClawTool.byToolId(toolId)?.displayName ?: toolId
}
