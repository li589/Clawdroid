package com.clawdroid.app.ui

import com.clawdroid.app.data.model.ChatMessage
import com.clawdroid.app.data.model.ChatMessageState
import com.clawdroid.app.data.model.ChatRole
import com.clawdroid.app.data.model.ChatTaskExecutionState
import com.clawdroid.app.data.model.ChatTaskFailureState
import com.clawdroid.app.data.model.ChatTaskProgressState
import com.clawdroid.app.data.model.ChatTaskStepState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.clawdroid.app.R
import com.clawdroid.app.automation.AutomationTaskState
import com.clawdroid.app.env.LocalEnvironmentStatus
import com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot
import com.clawdroid.app.runtime.ClawRuntimeConnectionState

private enum class StatusTone {
    Neutral,
    Active,
    Success,
    Warning,
    Danger
}

private val primaryButtonMinHeight = 52.dp

// 响应式配置
@Composable
internal fun responsiveCardPadding() = rememberAdaptableLayoutConfig().cardPadding

@Composable
internal fun responsiveCardInnerSpacing() = rememberAdaptableLayoutConfig().cardInnerSpacing

@Composable
internal fun responsiveFlowHSpacing() = rememberAdaptableLayoutConfig().flowRowHorizontalSpacing

@Composable
internal fun responsiveFlowVSpacing() = rememberAdaptableLayoutConfig().flowRowVerticalSpacing

@Composable
internal fun StatusCard(title: String, content: String, maxContentLines: Int = Int.MAX_VALUE) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = innerSpacing),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    overflow = if (maxContentLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
                    maxLines = maxContentLines,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(innerSpacing)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OverviewHeroCard(
    sessionState: ClawRuntimeConnectionState,
    localEnvironmentStatus: LocalEnvironmentStatus,
    eventStreaming: Boolean,
    daemonMetrics: String,
    runtimeMetrics: String,
    windowSummary: String,
    runtimeLoaded: Boolean?,
    runtimeProcess: String,
    degradedReason: String
) {
    val hSpacing = responsiveFlowHSpacing()
    val vSpacing = responsiveFlowVSpacing()
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    val rootLabel = rootHeroLabel(localEnvironmentStatus.rootGranted)
    val a11yLabel = accessibilityHeroLabel(localEnvironmentStatus.accessibilityEnabled)
    val runtimeLabel = runtimeHeroLabel(sessionState, localEnvironmentStatus)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardDefaults.elevatedShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(pad)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(innerSpacing)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "状态",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HeroBadge(
                        text = if (eventStreaming) "活跃" else "空闲",
                        accent = if (eventStreaming) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(hSpacing),
                    verticalArrangement = Arrangement.spacedBy(vSpacing)
                ) {
                    StatusChip(
                        label = "LSPosed ${lsposedStatusLabel(localEnvironmentStatus)}",
                        tone = toneForLsposed(localEnvironmentStatus)
                    )
                    StatusChip(
                        label = "Shizuku ${shizukuEnvLabel(localEnvironmentStatus)}",
                        tone = if (localEnvironmentStatus.shizukuPermissionGranted) {
                            StatusTone.Success
                        } else {
                            StatusTone.Warning
                        }
                    )
                    StatusChip(
                        label = "SELinux ${selinuxStatusLabel(localEnvironmentStatus)}",
                        tone = when (localEnvironmentStatus.selinuxEnforcing) {
                            true -> StatusTone.Warning
                            false -> StatusTone.Success
                            null -> StatusTone.Neutral
                        }
                    )
                    StatusChip(
                        label = "事件 ${eventSubscriptionLabel(eventStreaming)}",
                        tone = if (eventStreaming) StatusTone.Active else StatusTone.Neutral
                    )
                    StatusChip(
                        label = "网络 ${networkStatusLabel(localEnvironmentStatus)}",
                        tone = if (localEnvironmentStatus.networkConnected) {
                            StatusTone.Success
                        } else {
                            StatusTone.Warning
                        }
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.32f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(innerSpacing),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MetricLine(
                            label = "负载",
                            value = daemonMetrics.take(80).ifBlank { "—" }
                        )
                        MetricLine(
                            label = "窗口",
                            value = windowSummary.take(80).ifBlank { "—" }
                        )
                        MetricLine(
                            label = "系统",
                            value = localEnvironmentStatus.systemVersionLabel
                                .ifBlank { "—" }
                                .take(120)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(innerSpacing)
                ) {
                    HeroInfoTile(
                        modifier = Modifier.weight(1f),
                        title = "Root",
                        value = rootLabel,
                        accent = MaterialTheme.colorScheme.primary
                    )
                    HeroInfoTile(
                        modifier = Modifier.weight(1f),
                        title = "A11y",
                        value = a11yLabel,
                        accent = MaterialTheme.colorScheme.secondary
                    )
                    HeroInfoTile(
                        modifier = Modifier.weight(1f),
                        title = "Runtime",
                        value = runtimeLabel,
                        accent = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
internal fun SectionTitle(title: String) {
    val config = rememberAdaptableLayoutConfig()
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = config.sectionTitleTopPadding, bottom = config.sectionTitleBottomPadding)
    )
}

@Composable
internal fun BrandPageHeader(
    modifier: Modifier = Modifier,
    title: String = "Clawdroid",
    subtitle: String? = null,
    onBackClick: (() -> Unit)? = null,
    compactTitle: Boolean = false
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            if (onBackClick != null) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .offset(x = (-10).dp)
                        .size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Text(
                text = title,
                style = if (compactTitle) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = if (onBackClick != null) {
                    Modifier.offset(x = (-10).dp)
                } else {
                    Modifier
                }
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (onBackClick != null) {
                    Modifier.padding(start = 30.dp)
                } else {
                    Modifier
                }
            )
        }
    }
}

@Composable
internal fun FloatingBottomNavBar(
    modifier: Modifier = Modifier,
    currentPage: ConsolePage,
    onPageSelected: (ConsolePage) -> Unit
) {
    val config = rememberAdaptableLayoutConfig()
    val glassShape = RoundedCornerShape(28.dp)
    val glassFill = MaterialTheme.colorScheme.surface.copy(alpha = 0.38f)
    val glassHighlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = config.navBarPaddingH, vertical = 2.dp)
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 10.dp,
                    shape = glassShape,
                    ambientColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.18f),
                    spotColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.22f)
                )
                .clip(glassShape)
                .background(glassFill)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            glassHighlight,
                            Color.Transparent
                        )
                    )
                )
                .border(
                    BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
                    ),
                    shape = glassShape
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(config.navBarSpacing)
            ) {
                FloatingNavButton(
                    modifier = Modifier.weight(1f),
                    selected = currentPage == ConsolePage.Overview,
                    icon = Icons.Outlined.Home,
                    label = "概览",
                    onClick = { onPageSelected(ConsolePage.Overview) }
                )
                FloatingNavButton(
                    modifier = Modifier.weight(1f),
                    selected = currentPage == ConsolePage.Chat,
                    icon = Icons.Outlined.ChatBubbleOutline,
                    label = "聊天",
                    onClick = { onPageSelected(ConsolePage.Chat) }
                )
                FloatingNavButton(
                    modifier = Modifier.weight(1f),
                    selected = currentPage == ConsolePage.Settings,
                    icon = Icons.Outlined.Settings,
                    label = "设置",
                    onClick = { onPageSelected(ConsolePage.Settings) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatWorkspaceCard(
    messages: List<ChatMessage>,
    input: String,
    pendingImageLabel: String?,
    isBusy: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceClick: () -> Unit,
    onImageClick: () -> Unit,
    onClearHistory: () -> Unit
) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    val hSpacing = responsiveFlowHSpacing()
    val vSpacing = responsiveFlowVSpacing()
    val config = rememberAdaptableLayoutConfig()
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(innerSpacing)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "智能控制台", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "自然语言交互 Runtime 工具",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(hSpacing),
                    verticalArrangement = Arrangement.spacedBy(vSpacing)
                ) {
                    if (isBusy) {
                        StatusChip("处理中", tone = StatusTone.Active)
                    }
                    AssistChip(
                        onClick = onClearHistory,
                        label = { Text("清空历史") }
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = config.chatAreaMinHeight, max = config.chatAreaMaxHeight),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(innerSpacing),
                    verticalArrangement = Arrangement.spacedBy(innerSpacing)
                ) {
                    messages.takeLast(10).forEach { message ->
                        ChatBubble(message)
                    }
                }
            }
            pendingImageLabel?.let {
                ResultPanel(text = "待发送附件: $it")
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(innerSpacing),
                    verticalArrangement = Arrangement.spacedBy(innerSpacing)
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChange,
                        label = { Text("输入指令") },
                        supportingText = {
                            FieldSupportingText("示例：截图、点击 540 1200")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = config.textFieldMinLines,
                        maxLines = config.textFieldMaxLines
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(hSpacing)
                    ) {
                        CircleIconButton(
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.Image,
                                    contentDescription = "添加图片"
                                )
                            },
                            onClick = onImageClick
                        )
                        CircleIconButton(
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.Mic,
                                    contentDescription = "语音输入"
                                )
                            },
                            onClick = onVoiceClick
                        )
                        FilledTonalButton(
                            onClick = onSend,
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = if (isBusy) "处理中" else "发送")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickActionCard(
    onPing: () -> Unit,
    onRuntimeCheck: () -> Unit = {},
    onCapabilities: () -> Unit,
    onCapture: () -> Unit,
    onShell: () -> Unit,
    onSafeTapTask: () -> Unit = {},
    onHealthSweepTask: () -> Unit = {},
    onSwipeCaptureTask: () -> Unit = {},
    onEvents: () -> Unit,
    eventStreaming: Boolean,
    actionsEnabled: Boolean = true
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
            Text(text = "快捷动作", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(hSpacing),
                verticalArrangement = Arrangement.spacedBy(vSpacing)
            ) {
                AssistChip(onClick = onPing, enabled = actionsEnabled, label = { Text("Ping") })
                AssistChip(onClick = onRuntimeCheck, enabled = actionsEnabled, label = { Text("运行时检查") })
                AssistChip(onClick = onHealthSweepTask, enabled = actionsEnabled, label = { Text("运行时体检") })
                AssistChip(onClick = onCapabilities, enabled = actionsEnabled, label = { Text("Capabilities") })
                AssistChip(onClick = onCapture, enabled = actionsEnabled, label = { Text(stringResource(R.string.overview_capture_detect)) })
                AssistChip(onClick = onSwipeCaptureTask, enabled = actionsEnabled, label = { Text("滑动后截图") })
                AssistChip(onClick = onShell, enabled = actionsEnabled, label = { Text("wm size") })
                AssistChip(onClick = onSafeTapTask, enabled = actionsEnabled, label = { Text("确认后点击") })
                AssistChip(
                    onClick = onEvents,
                    enabled = actionsEnabled,
                    label = { Text(if (eventStreaming) "停止事件流" else "开始事件流") }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatTaskExecutionCard(
    taskExecution: ChatTaskExecutionState?,
    taskHistory: List<ChatTaskExecutionState>,
    taskHistoryFilter: ChatTaskHistoryFilter,
    onCancelTaskExecution: () -> Unit,
    onClearCurrentTaskExecution: () -> Unit,
    onClearTaskHistory: () -> Unit,
    onRetryTaskExecution: (ChatTaskExecutionState) -> Unit,
    onTaskHistoryFilterChange: (ChatTaskHistoryFilter) -> Unit
) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    val hSpacing = responsiveFlowHSpacing()
    val vSpacing = responsiveFlowVSpacing()
    val filteredTaskHistory = taskHistory.filter { task ->
        when (taskHistoryFilter) {
            ChatTaskHistoryFilter.All -> true
            ChatTaskHistoryFilter.Failed -> task.status == ChatTaskProgressState.Failed
            ChatTaskHistoryFilter.Cancelled -> task.status == ChatTaskProgressState.Cancelled
            ChatTaskHistoryFilter.Succeeded -> task.status == ChatTaskProgressState.Succeeded
            ChatTaskHistoryFilter.Retried -> task.retryCount > 0 || task.retryFromTaskId != null
        }
    }
    val allVisibleTasks = buildList {
        taskExecution?.let(::add)
        addAll(taskHistory)
    }.distinctBy { it.taskId }
    var selectedTaskId by remember(taskExecution?.taskId, taskHistory, taskHistoryFilter) {
        mutableStateOf(
            taskExecution?.taskId
                ?: filteredTaskHistory.firstOrNull()?.taskId
                ?: taskHistory.firstOrNull()?.taskId
        )
    }
    val detailTask = allVisibleTasks.firstOrNull { it.taskId == selectedTaskId }
        ?: taskExecution
        ?: filteredTaskHistory.firstOrNull()
        ?: taskHistory.firstOrNull()
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = "任务执行台", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "多步骤动作的任务视图",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusChip(
                    label = taskExecution?.status?.let(::taskProgressLabel) ?: "空闲",
                    tone = taskExecution?.status?.let(::toneForTaskProgress) ?: StatusTone.Neutral
                )
            }
            if (taskExecution == null) {
                ResultPanel(text = "暂无最近任务。可直接发送“检查运行时状态”或“确认页面后安全点击”开始任务。")
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(hSpacing),
                    verticalArrangement = Arrangement.spacedBy(vSpacing)
                ) {
                    if (taskExecution.status == ChatTaskProgressState.Running) {
                        AssistChip(
                            onClick = onCancelTaskExecution,
                            label = { Text("取消任务") }
                        )
                    }
                    if (taskExecution.status != ChatTaskProgressState.Running) {
                        AssistChip(
                            onClick = onClearCurrentTaskExecution,
                            label = { Text("清空当前") }
                        )
                    }
                    if (taskExecution.taskAction != null && 
                        (taskExecution.status == ChatTaskProgressState.Failed || 
                         taskExecution.status == ChatTaskProgressState.Cancelled)) {
                        AssistChip(
                            onClick = { onRetryTaskExecution(taskExecution) },
                            label = { Text("重试本任务") }
                        )
                    }
                    AssistChip(
                        onClick = { selectedTaskId = taskExecution.taskId },
                        label = {
                            Text(
                                if (detailTask?.taskId == taskExecution.taskId) {
                                    "当前详情"
                                } else {
                                    "查看详情"
                                }
                            )
                        }
                    )
                    if (taskHistory.isNotEmpty()) {
                        AssistChip(
                            onClick = onClearTaskHistory,
                            label = { Text("清空历史") }
                        )
                    }
                }
                TaskProgressBar(task = taskExecution)
                ResultPanel(
                    text = buildTaskOverviewText(taskExecution)
                )
            }
            if (taskHistory.isNotEmpty()) {
                Text(
                    text = "任务历史面板 (${filteredTaskHistory.size}/${taskHistory.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "显示历史任务，可查看步骤与重试链",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(hSpacing),
                    verticalArrangement = Arrangement.spacedBy(vSpacing)
                ) {
                    ChatTaskHistoryFilter.entries.forEach { filter ->
                        AssistChip(
                            onClick = { onTaskHistoryFilterChange(filter) },
                            label = {
                                Text(
                                    if (taskHistoryFilter == filter) {
                                        "已选 ${taskHistoryFilterLabel(filter)}"
                                    } else {
                                        taskHistoryFilterLabel(filter)
                                    }
                                )
                            }
                        )
                    }
                }
                filteredTaskHistory.forEachIndexed { index, historyItem ->
                    StatusCard(
                        title = "历史 ${index + 1} ${historyItem.title}",
                        content = buildTaskOverviewText(historyItem),
                        maxContentLines = 8
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(hSpacing),
                        verticalArrangement = Arrangement.spacedBy(vSpacing)
                    ) {
                        AssistChip(
                            onClick = { selectedTaskId = historyItem.taskId },
                            label = {
                                Text(
                                    if (detailTask?.taskId == historyItem.taskId) {
                                        "详情已打开"
                                    } else {
                                        "查看详情"
                                    }
                                )
                            }
                        )
                        AssistChip(
                            onClick = { selectedTaskId = historyItem.taskId },
                            label = { Text("切到详情面板") }
                        )
                        if (historyItem.taskAction != null &&
                            (historyItem.status == ChatTaskProgressState.Failed ||
                                historyItem.status == ChatTaskProgressState.Cancelled)
                        ) {
                            AssistChip(
                                onClick = { onRetryTaskExecution(historyItem) },
                                label = { Text("重试此任务") }
                            )
                        }
                    }
                }
            }
            detailTask?.let { task ->
                TaskDetailPanel(
                    task = task,
                    isCurrentTask = taskExecution?.taskId == task.taskId,
                    onRetryTaskExecution = onRetryTaskExecution
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatReadinessCard(
    modelLabel: String,
    aiSummary: String,
    connectionSummary: String,
    eventStreaming: Boolean
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = "AI 编排状态", style = MaterialTheme.typography.titleLarge)
                }
                HeroBadge(
                    text = if (eventStreaming) "事件活跃" else "事件空闲",
                    accent = if (eventStreaming) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(hSpacing),
                verticalArrangement = Arrangement.spacedBy(vSpacing)
            ) {
                StatusChip("模型 $modelLabel")
                StatusChip(if (aiSummary.contains("已就绪")) "AI 就绪" else "AI 待配置")
                StatusChip(if (eventStreaming) "事件订阅中" else "事件未订阅")
            }
            ResultPanel(text = aiSummary)
            ResultPanel(text = "连接摘要: $connectionSummary")
        }
    }
}

@Composable
private fun TaskProgressBar(task: ChatTaskExecutionState) {
    val total = task.steps.size.coerceAtLeast(1)
    val finished = task.steps.count {
        it.status == ChatTaskProgressState.Succeeded ||
            it.status == ChatTaskProgressState.Failed ||
            it.status == ChatTaskProgressState.Cancelled
    }
    val running = task.steps.count { it.status == ChatTaskProgressState.Running }
    val progress = when (task.status) {
        ChatTaskProgressState.Succeeded -> 1f
        ChatTaskProgressState.Failed,
        ChatTaskProgressState.Cancelled -> (finished.toFloat() / total).coerceIn(0f, 1f)
        else -> ((finished + running * 0.5f) / total).coerceIn(0f, 1f)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = buildString {
                append("进度 ${finished}/${total}")
                if (running > 0) {
                    append(" · 执行中 $running")
                }
                task.runtimeTaskId?.takeIf { it.isNotBlank() }?.let {
                    append(" · Runtime $it")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RuntimeTasksCard(
    tasks: List<ClawRuntimeTaskSnapshot>,
    status: String,
    eventStreaming: Boolean,
    onRefresh: () -> Unit,
    onCancel: (String) -> Unit
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = "Runtime 任务", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = if (eventStreaming) {
                            "事件流已开启，状态会自动刷新"
                        } else {
                            "建议开启事件流以实时更新；也可手动刷新"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AssistChip(
                    onClick = onRefresh,
                    label = { Text("刷新") }
                )
            }
            ResultPanel(text = status)
            if (tasks.isEmpty()) {
                Text(
                    text = "暂无 Runtime 任务。可在聊天中使用 /task_submit demo ping 或 task_list。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                tasks.forEach { task ->
                    val cancellable = task.state.equals("Running", ignoreCase = true) ||
                        task.state.equals("Queued", ignoreCase = true) ||
                        task.state.equals("Retrying", ignoreCase = true) ||
                        task.state.equals("WaitingSignal", ignoreCase = true)
                    StatusCard(
                        title = task.name.ifBlank { task.taskId }.ifBlank { "未命名任务" },
                        content = task.summaryLine(),
                        maxContentLines = 4
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(hSpacing),
                        verticalArrangement = Arrangement.spacedBy(vSpacing)
                    ) {
                        StatusChip(
                            label = task.state.ifBlank { "Unknown" },
                            tone = toneForRuntimeTaskState(task.state)
                        )
                        if (cancellable && task.taskId.isNotBlank()) {
                            AssistChip(
                                onClick = { onCancel(task.taskId) },
                                label = { Text("取消") }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun toneForRuntimeTaskState(state: String): StatusTone {
    return when (state.lowercase()) {
        "succeeded" -> StatusTone.Success
        "failed" -> StatusTone.Danger
        "cancelled" -> StatusTone.Warning
        "running", "queued", "retrying", "waitingsignal", "compensating" -> StatusTone.Active
        else -> StatusTone.Neutral
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskDetailPanel(
    task: ChatTaskExecutionState,
    isCurrentTask: Boolean,
    onRetryTaskExecution: (ChatTaskExecutionState) -> Unit
) {
    val hSpacing = responsiveFlowHSpacing()
    val vSpacing = responsiveFlowVSpacing()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = "任务详情", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (isCurrentTask) {
                            "完整步骤、失败边界与重试链。"
                        } else {
                            "历史任务的完整步骤与重试链。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusChip(
                    label = taskProgressLabel(task.status),
                    tone = toneForTaskProgress(task.status)
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(hSpacing),
                verticalArrangement = Arrangement.spacedBy(vSpacing)
            ) {
                StatusChip(
                    label = if (isCurrentTask) "当前任务" else "历史任务",
                    tone = if (isCurrentTask) StatusTone.Active else StatusTone.Neutral
                )
                task.taskAction?.let {
                    StatusChip(
                        label = taskActionLabel(it),
                        tone = StatusTone.Active
                    )
                }
                if (task.retryCount > 0) {
                    StatusChip(
                        label = "第 ${task.retryCount} 次重试",
                        tone = StatusTone.Warning
                    )
                }
                task.retryFromTaskId?.let {
                    StatusChip(
                        label = "来源 ${shortTaskId(it)}",
                        tone = StatusTone.Warning
                    )
                }
                task.failure?.let {
                    StatusChip(
                        label = "失败 ${it.code}",
                        tone = StatusTone.Danger
                    )
                }
            }
            ResultPanel(text = buildTaskOverviewText(task))
            if (task.taskAction != null &&
                (task.status == ChatTaskProgressState.Failed ||
                    task.status == ChatTaskProgressState.Cancelled)
            ) {
                AssistChip(
                    onClick = { onRetryTaskExecution(task) },
                    label = { Text("从详情重试") }
                )
            }
            if (task.steps.isEmpty()) {
                ResultPanel(text = "当前任务暂无步骤记录。")
            } else {
                task.steps.forEachIndexed { index, step ->
                    StatusCard(
                        title = "步骤 ${index + 1} ${step.title}",
                        content = buildTaskStepText(step),
                        maxContentLines = 6
                    )
                }
            }
        }
    }
}

private fun toneForTaskProgress(state: ChatTaskProgressState): StatusTone {
    return when (state) {
        ChatTaskProgressState.Pending -> StatusTone.Neutral
        ChatTaskProgressState.Running -> StatusTone.Active
        ChatTaskProgressState.Succeeded -> StatusTone.Success
        ChatTaskProgressState.Failed -> StatusTone.Danger
        ChatTaskProgressState.Cancelled -> StatusTone.Warning
    }
}

private fun taskProgressLabel(state: ChatTaskProgressState): String {
    return when (state) {
        ChatTaskProgressState.Pending -> "等待中"
        ChatTaskProgressState.Running -> "执行中"
        ChatTaskProgressState.Succeeded -> "已成功"
        ChatTaskProgressState.Failed -> "已失败"
        ChatTaskProgressState.Cancelled -> "已取消"
    }
}

private fun buildTaskOverviewText(task: ChatTaskExecutionState): String {
    return buildString {
        appendLine(taskProgressLabel(task.status))
        appendLine(task.summary)
        if (task.originPrompt.isNotBlank()) {
            appendLine("来源指令: ${task.originPrompt}")
        }
        if (task.taskAction != null) {
            appendLine("任务类型: ${taskActionLabel(task.taskAction)}")
        }
        task.runtimeTaskId?.takeIf { it.isNotBlank() }?.let {
            appendLine("Runtime 任务: $it")
        }
        if (task.retryCount > 0) {
            appendLine("重试次数: 第 ${task.retryCount} 次")
        }
        task.retryFromTaskId?.let {
            appendLine("重试来源: ${shortTaskId(it)}")
        }
        appendLine("开始: ${formatEpochMillis(task.startedAtEpochMs)}")
        appendLine("结束: ${formatEpochMillis(task.finishedAtEpochMs)}")
        append("耗时: ${formatTaskDuration(task.startedAtEpochMs, task.finishedAtEpochMs)}")
        if (task.failure != null) {
            val failure = task.failure
            appendLine("失败摘要: ${failure.summary}")
            appendLine("失败代码: ${failure.code}")
            append("失败明细: ${failure.rawDetail}")
        } else if (!task.failureReason.isNullOrBlank()) {
            append("\n失败原因: ${task.failureReason}")
        }
    }
}

private fun buildTaskStepText(step: ChatTaskStepState): String {
    return buildString {
        appendLine(taskProgressLabel(step.status))
        appendLine(step.detail)
        append("开始: ${formatEpochMillis(step.startedAtEpochMs)}")
        if (step.finishedAtEpochMs > 0L) {
            append("\n结束: ${formatEpochMillis(step.finishedAtEpochMs)}")
            append("\n耗时: ${formatTaskDuration(step.startedAtEpochMs, step.finishedAtEpochMs)}")
        }
    }
}

private fun formatTaskDuration(startedAtEpochMs: Long, finishedAtEpochMs: Long): String {
    if (startedAtEpochMs <= 0L) {
        return "等待开始"
    }
    if (finishedAtEpochMs <= 0L) {
        return "进行中"
    }
    val durationMs = (finishedAtEpochMs - startedAtEpochMs).coerceAtLeast(0L)
    val seconds = durationMs / 1000L
    val millis = durationMs % 1000L
    return if (seconds > 0L) {
        "${seconds}.${(millis / 100L)}s"
    } else {
        "${durationMs}ms"
    }
}

private fun taskActionLabel(action: com.clawdroid.app.chat.ChatTaskAction): String {
    return when (action) {
        com.clawdroid.app.chat.ChatTaskAction.ConfirmThenSafeTap -> "页面确认后安全点击"
        com.clawdroid.app.chat.ChatTaskAction.ProbeThenCapabilities -> "运行时状态检查"
        com.clawdroid.app.chat.ChatTaskAction.CaptureThenPreview -> "截图检测"
        com.clawdroid.app.chat.ChatTaskAction.RuntimeHealthSweep -> "运行时体检"
        com.clawdroid.app.chat.ChatTaskAction.SwipeThenCapture -> "滑动后截图"
    }
}

private fun shortTaskId(taskId: String): String {
    return if (taskId.length <= 12) taskId else taskId.takeLast(12)
}

private fun taskHistoryFilterLabel(filter: ChatTaskHistoryFilter): String {
    return when (filter) {
        ChatTaskHistoryFilter.All -> "全部"
        ChatTaskHistoryFilter.Failed -> "失败"
        ChatTaskHistoryFilter.Cancelled -> "已取消"
        ChatTaskHistoryFilter.Succeeded -> "已成功"
        ChatTaskHistoryFilter.Retried -> "重试链"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MetricsOverviewCard(
    daemonMetrics: String,
    runtimeMetrics: String,
    windowSummary: String,
    dashboardMetrics: DashboardRuntimeMetrics
) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    val hSpacing = responsiveFlowHSpacing()
    val vSpacing = responsiveFlowVSpacing()
    val anomalies = buildDashboardAnomalies(dashboardMetrics)
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = "实时仪表盘", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "App 与系统指标实时刷新",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HeroBadge(
                    text = formatEpochMillis(dashboardMetrics.sampledAtEpochMs),
                    accent = MaterialTheme.colorScheme.primary
                )
            }
            if (anomalies.isNotEmpty()) {
                Text(text = "异常与降级提示", style = MaterialTheme.typography.titleMedium)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(hSpacing),
                    verticalArrangement = Arrangement.spacedBy(vSpacing)
                ) {
                    anomalies.forEach { anomaly ->
                        StatusChip(
                            label = anomaly.label,
                            tone = anomaly.tone
                        )
                    }
                }
                ResultPanel(text = buildDashboardAnomalySummary(anomalies))
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(innerSpacing),
                verticalArrangement = Arrangement.spacedBy(innerSpacing)
            ) {
                MetricInfoCard(
                    title = "App CPU",
                    value = "${dashboardMetrics.appCpuPercent.asPercentLabel()}",
                    supporting = if (dashboardMetrics.procFsReadable) "当前前台进程" else "系统 CPU 指标受限",
                    tone = if (dashboardMetrics.procFsReadable) StatusTone.Active else StatusTone.Warning
                )
                MetricInfoCard(
                    title = "App 内存",
                    value = formatBytes(dashboardMetrics.appMemoryBytes),
                    supporting = "PSS",
                    tone = StatusTone.Neutral
                )
                MetricInfoCard(
                    title = "系统负载",
                    value = "${dashboardMetrics.loadAverage1.formatLoad()} / ${dashboardMetrics.loadAverage5.formatLoad()}",
                    supporting = "${formatBytes(dashboardMetrics.systemMemoryUsedBytes)} / ${formatBytes(dashboardMetrics.systemMemoryTotalBytes)}",
                    tone = systemLoadTone(dashboardMetrics)
                )
                MetricInfoCard(
                    title = "Root 采样",
                    value = if (dashboardMetrics.rootBacked) "已启用" else "待启用",
                    supporting = if (dashboardMetrics.procFsReadable) "进程指标已降频" else "系统 /proc 受限",
                    tone = rootSamplingTone(dashboardMetrics)
                )
            }
            ProcessMetricPanel(
                title = "ClawRuntime 进程",
                metric = dashboardMetrics.runtimeProcess,
                fallback = runtimeMetrics
            )
            ProcessMetricPanel(
                title = "Root 环境进程",
                metric = dashboardMetrics.magiskProcess,
                fallback = if (dashboardMetrics.rootBacked) {
                    "未发现 magiskd（KernelSU/APatch 等可能无此进程，属正常；状态区「Root环境」看目录/模块即可）"
                } else {
                    "Root 未就绪，暂无法读取"
                }
            )
            ResultPanel(text = "系统负载与内存: $daemonMetrics")
            ResultPanel(text = "前台窗口: $windowSummary")
            ResultPanel(
                text = "采样说明: ${dashboardMetrics.note}\nApp Java Heap: ${formatBytes(dashboardMetrics.appJavaHeapBytes)}\nApp Native Heap: ${formatBytes(dashboardMetrics.appNativeHeapBytes)}"
            )
        }
    }
}

private data class DashboardAnomalyInfo(
    val label: String,
    val detail: String,
    val tone: StatusTone
)

private fun buildDashboardAnomalies(
    dashboardMetrics: DashboardRuntimeMetrics
): List<DashboardAnomalyInfo> {
    return buildList {
        if (!dashboardMetrics.procFsReadable) {
            add(
                DashboardAnomalyInfo(
                    label = "系统指标受限",
                    detail = "当前设备限制访问 /proc，系统负载与总内存展示已进入降级模式。",
                    tone = StatusTone.Danger
                )
            )
        } else if (
            dashboardMetrics.systemMemoryTotalBytes <= 0L &&
            dashboardMetrics.loadAverage1 <= 0f &&
            dashboardMetrics.loadAverage5 <= 0f
        ) {
            add(
                DashboardAnomalyInfo(
                    label = "系统负载为空",
                    detail = "本轮采样未拿到有效的 loadavg 或内存总量，建议结合 Root 采样再次复验。",
                    tone = StatusTone.Warning
                )
            )
        }
        if (!dashboardMetrics.rootBacked) {
            add(
                DashboardAnomalyInfo(
                    label = "Root 采样未启用",
                    detail = "当前只能确认 App 与系统指标，ClawRuntime 与 Magisk 进程状态暂不可见。",
                    tone = StatusTone.Warning
                )
            )
        } else {
            if (!dashboardMetrics.runtimeProcess.present) {
                add(
                    DashboardAnomalyInfo(
                        label = "未发现 ClawRuntime",
                        detail = "Root 已可用，但暂未匹配到 clawdroid-runtime 进程。",
                        tone = StatusTone.Warning
                    )
                )
            }
            if (!dashboardMetrics.magiskProcess.present) {
                add(
                    DashboardAnomalyInfo(
                        label = "未发现 magiskd 进程",
                        detail = "属常见情况：KernelSU/APatch 通常没有 magiskd；请以状态区「Root环境 / 模块 / Runtime」为准。",
                        tone = StatusTone.Neutral
                    )
                )
            }
        }
    }
}

private fun buildDashboardAnomalySummary(
    anomalies: List<DashboardAnomalyInfo>
): String {
    return anomalies.joinToString(separator = "\n") { anomaly ->
        "${anomaly.label}: ${anomaly.detail}"
    }
}

private fun systemLoadTone(dashboardMetrics: DashboardRuntimeMetrics): StatusTone {
    return when {
        !dashboardMetrics.procFsReadable -> StatusTone.Danger
        dashboardMetrics.systemMemoryTotalBytes <= 0L &&
            dashboardMetrics.loadAverage1 <= 0f &&
            dashboardMetrics.loadAverage5 <= 0f -> StatusTone.Warning
        else -> StatusTone.Neutral
    }
}

private fun rootSamplingTone(dashboardMetrics: DashboardRuntimeMetrics): StatusTone {
    return when {
        !dashboardMetrics.rootBacked -> StatusTone.Warning
        !dashboardMetrics.runtimeProcess.present || !dashboardMetrics.magiskProcess.present -> StatusTone.Warning
        else -> StatusTone.Success
    }
}

@Composable
internal fun FieldSupportingText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = primaryButtonMinHeight)
    ) {
        Text(text = text)
    }
}

@Composable
internal fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = primaryButtonMinHeight)
    ) {
        Text(text = text)
    }
}

@Composable
internal fun ActionCard(
    title: String,
    buttonText: String,
    result: String,
    onClick: () -> Unit
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
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            PrimaryActionButton(
                text = buttonText,
                onClick = onClick,
                modifier = Modifier.fillMaxWidth()
            )
            ResultPanel(text = result)
        }
    }
}

@Composable
internal fun BasicPermissionsCard(
    status: LocalEnvironmentStatus,
    actionStatus: String,
    rememberedNotification: Boolean = false,
    rememberedWriteSettings: Boolean = false,
    rememberedAllFiles: Boolean = false,
    rememberedAccessibility: Boolean = false,
    onToggleNotification: () -> Unit,
    onToggleAccessibility: () -> Unit,
    onToggleWriteSettings: () -> Unit,
    onToggleAllFiles: () -> Unit,
    onToggleNotificationListener: () -> Unit,
    onToggleShizuku: () -> Unit,
    onRootGrantAll: () -> Unit
) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    val allCoreOk = corePermissionsGranted(status)
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "基础权限", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "开关以系统实时状态为准；曾授权过的项会标注记忆。点击开关跳转系统页，返回后自动复核。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PermissionSwitchRow(
                title = "通知权限",
                checked = status.notificationPermissionGranted,
                subtitle = permissionMemoryHint(
                    live = status.notificationPermissionGranted,
                    remembered = rememberedNotification
                ),
                onCheckedChange = { wanted ->
                    if (wanted != status.notificationPermissionGranted) onToggleNotification()
                }
            )
            PermissionSwitchRow(
                title = "无障碍服务",
                checked = status.accessibilityEnabled,
                subtitle = permissionMemoryHint(
                    live = status.accessibilityEnabled,
                    remembered = rememberedAccessibility
                ) ?: "仅手动或 Root 一键开启，启动不会自动授权",
                onCheckedChange = { wanted ->
                    if (wanted != status.accessibilityEnabled) onToggleAccessibility()
                }
            )
            PermissionSwitchRow(
                title = "修改系统设置",
                checked = status.writeSettingsGranted,
                subtitle = permissionMemoryHint(
                    live = status.writeSettingsGranted,
                    remembered = rememberedWriteSettings
                ),
                onCheckedChange = { wanted ->
                    if (wanted != status.writeSettingsGranted) onToggleWriteSettings()
                }
            )
            PermissionSwitchRow(
                title = "全部文件访问",
                checked = status.allFilesAccessGranted,
                subtitle = permissionMemoryHint(
                    live = status.allFilesAccessGranted,
                    remembered = rememberedAllFiles
                ),
                onCheckedChange = { wanted ->
                    if (wanted != status.allFilesAccessGranted) onToggleAllFiles()
                }
            )
            PermissionSwitchRow(
                title = "通知监听",
                checked = status.notificationListenerEnabled,
                onCheckedChange = { wanted ->
                    if (wanted != status.notificationListenerEnabled) onToggleNotificationListener()
                }
            )
            PermissionSwitchRow(
                title = "Shizuku",
                checked = status.shizukuPermissionGranted,
                subtitle = when {
                    status.shizukuPermissionGranted -> null
                    status.shizukuBinderAlive -> "服务已连接，点击请求授权"
                    status.shizukuManagerInstalled -> "已安装但服务未连接"
                    else -> "未安装 Shizuku"
                },
                onCheckedChange = { wanted ->
                    if (wanted != status.shizukuPermissionGranted) onToggleShizuku()
                }
            )
            PermissionSwitchRow(
                title = "Root 一键授权",
                subtitle = if (allCoreOk) {
                    "通知 / 无障碍 / 系统设置 / 全部文件均已就绪"
                } else {
                    "用 Root 一次性授予上述核心权限（含无障碍）"
                },
                checked = allCoreOk,
                enabled = !allCoreOk,
                onCheckedChange = { enable ->
                    if (enable) onRootGrantAll()
                }
            )
            val feedback = actionStatus.trim()
            if (feedback.isNotBlank() &&
                feedback != "未执行权限修复" &&
                !feedback.startsWith("Root=")
            ) {
                ResultPanel(text = feedback)
            }
        }
    }
}

private fun permissionMemoryHint(live: Boolean, remembered: Boolean): String? {
    return when {
        live && remembered -> "系统已开启（已记忆）"
        live -> "系统已开启"
        remembered -> "曾授权，当前系统未开启 — 点击重新授权"
        else -> null
    }
}

@Composable
private fun PermissionSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalEnvironmentOpsCard(
    result: String,
    eventStreaming: Boolean,
    onDetectEnvironment: () -> Unit,
    onPing: () -> Unit,
    onCapabilities: () -> Unit,
    onCapture: () -> Unit,
    onShell: () -> Unit,
    onEvents: () -> Unit
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
            Text(text = "本地环境", style = MaterialTheme.typography.titleMedium)
            PrimaryActionButton(
                text = "环境检测",
                onClick = onDetectEnvironment,
                modifier = Modifier.fillMaxWidth()
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(hSpacing),
                verticalArrangement = Arrangement.spacedBy(vSpacing)
            ) {
                AssistChip(onClick = onPing, label = { Text("Ping") })
                AssistChip(onClick = onCapabilities, label = { Text("Capabilities") })
                AssistChip(onClick = onCapture, label = { Text(stringResource(R.string.overview_capture_detect)) })
                AssistChip(onClick = onShell, label = { Text("wm size") })
                AssistChip(
                    onClick = onEvents,
                    label = { Text(if (eventStreaming) "停止事件流" else "开始事件流") }
                )
            }
            ResultPanel(text = result)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PermissionActionsCard(
    summary: String,
    result: String,
    targetPath: String,
    chmodMode: String,
    chownOwner: String,
    onTargetPathChange: (String) -> Unit,
    onChmodModeChange: (String) -> Unit,
    onChownOwnerChange: (String) -> Unit,
    onRequestNotification: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenWriteSettings: () -> Unit,
    onOpenAllFiles: () -> Unit,
    onRootGrantNotification: () -> Unit,
    onRootGrantWriteSettings: () -> Unit,
    onRootGrantAllFiles: () -> Unit,
    onRootEnableAccessibility: () -> Unit,
    onRootGrantAutomation: () -> Unit,
    onRootChmodPath: () -> Unit,
    onRootChownPath: () -> Unit
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
            Text(text = "权限与高权限修复", style = MaterialTheme.typography.titleMedium)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = "系统授权入口", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(hSpacing),
                verticalArrangement = Arrangement.spacedBy(vSpacing)
            ) {
                AssistChip(onClick = onRequestNotification, label = { Text("通知") })
                AssistChip(onClick = onOpenAccessibility, label = { Text("无障碍") })
                AssistChip(onClick = onOpenWriteSettings, label = { Text("系统设置") })
                AssistChip(onClick = onOpenAllFiles, label = { Text("全部文件") })
            }
            Text(text = "Root 辅助授权", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(hSpacing),
                verticalArrangement = Arrangement.spacedBy(vSpacing)
            ) {
                AssistChip(onClick = onRootGrantNotification, label = { Text("Root 通知") })
                AssistChip(onClick = onRootGrantWriteSettings, label = { Text("Root 系统设置") })
                AssistChip(onClick = onRootGrantAllFiles, label = { Text("Root 全部文件") })
                AssistChip(onClick = onRootEnableAccessibility, label = { Text("Root 无障碍") })
                AssistChip(onClick = onRootGrantAutomation, label = { Text("Root 一键修复") })
            }
            Text(text = "Root 文件权限修复", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = targetPath,
                onValueChange = onTargetPathChange,
                label = { Text("目标路径") },
                supportingText = { FieldSupportingText("例如 /data/adb/modules/clawruntime 或 /data/local/tmp/clawdroid") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = chmodMode,
                onValueChange = onChmodModeChange,
                label = { Text("chmod 模式") },
                supportingText = { FieldSupportingText("例如 0755、0644") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = chownOwner,
                onValueChange = onChownOwnerChange,
                label = { Text("chown 属主") },
                supportingText = { FieldSupportingText("例如 0:0、1000:1000") },
                modifier = Modifier.fillMaxWidth()
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(hSpacing),
                verticalArrangement = Arrangement.spacedBy(vSpacing)
            ) {
                AssistChip(onClick = onRootChmodPath, label = { Text("Root chmod") })
                AssistChip(onClick = onRootChownPath, label = { Text("Root chown") })
            }
            ResultPanel(text = result)
        }
    }
}

@Composable
internal fun PageConfirmationCard(
    expectedPackage: String,
    expectedText: String,
    expectedViewId: String,
    result: String,
    onExpectedPackageChange: (String) -> Unit,
    onExpectedTextChange: (String) -> Unit,
    onExpectedViewIdChange: (String) -> Unit,
    onConfirm: () -> Unit
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
            Text(text = "页面确认", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = expectedPackage,
                onValueChange = onExpectedPackageChange,
                label = { Text("期望包名") },
                supportingText = { FieldSupportingText("可留空，例如 com.tencent.mm") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = expectedText,
                onValueChange = onExpectedTextChange,
                label = { Text("期望文本") },
                supportingText = { FieldSupportingText("可留空") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = expectedViewId,
                onValueChange = onExpectedViewIdChange,
                label = { Text("视图ID") },
                supportingText = { FieldSupportingText("可留空") },
                modifier = Modifier.fillMaxWidth()
            )
            PrimaryActionButton(
                text = "执行页面确认",
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth()
            )
            ResultPanel(text = result)
        }
    }
}

@Composable
internal fun ClickPrecheckCard(
    expectedPackage: String,
    targetText: String,
    targetViewId: String,
    result: String,
    executeResult: String,
    onExpectedPackageChange: (String) -> Unit,
    onTargetTextChange: (String) -> Unit,
    onTargetViewIdChange: (String) -> Unit,
    onPrecheck: () -> Unit,
    onExecuteTap: () -> Unit
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
            Text(text = "点击前检查", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = expectedPackage,
                onValueChange = onExpectedPackageChange,
                label = { Text("期望包名") },
                supportingText = { FieldSupportingText("可留空，使用当前包名") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = targetText,
                onValueChange = onTargetTextChange,
                label = { Text("目标文本") },
                supportingText = { FieldSupportingText("例如 发送、登录") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = targetViewId,
                onValueChange = onTargetViewIdChange,
                label = { Text("目标视图ID") },
                supportingText = { FieldSupportingText("可与文本二选一") },
                modifier = Modifier.fillMaxWidth()
            )
            PrimaryActionButton(
                text = "执行检查",
                onClick = onPrecheck,
                modifier = Modifier.fillMaxWidth()
            )
            SecondaryActionButton(
                text = "执行安全点击",
                onClick = onExecuteTap,
                modifier = Modifier.fillMaxWidth()
            )
            ResultPanel(text = result)
            ResultPanel(text = executeResult)
        }
    }
}

@Composable
internal fun EventSubscriptionCard(
    title: String,
    result: String,
    streaming: Boolean,
    recentEvents: List<String> = emptyList(),
    onStart: () -> Unit,
    onStop: () -> Unit
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
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "事件流会持续接收 Runtime 推送的窗口、任务与健康变化。需要实时跟踪界面与守护状态时再开启订阅；平时可保持关闭以节省电量。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PrimaryActionButton(
                text = "开始订阅",
                onClick = onStart,
                enabled = !streaming,
                modifier = Modifier.fillMaxWidth()
            )
            SecondaryActionButton(
                text = "停止订阅",
                onClick = onStop,
                enabled = streaming,
                modifier = Modifier.fillMaxWidth()
            )
            ResultPanel(text = result)
            if (recentEvents.isNotEmpty()) {
                Text(
                    text = "最近事件",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ResultPanel(text = recentEvents.takeLast(12).joinToString("\n"))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShellCard(
    selectedCommand: String,
    commands: List<String>,
    expanded: Boolean,
    result: String,
    output: String,
    onExpandedChange: (Boolean) -> Unit,
    onCommandSelected: (String) -> Unit,
    onExecute: () -> Unit
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
            Text(text = "受限 Shell", style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = onExpandedChange
            ) {
                OutlinedTextField(
                    value = selectedCommand,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("命令模板") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { onExpandedChange(false) }
                ) {
                    commands.forEach { command ->
                        DropdownMenuItem(
                            text = { Text(command) },
                            onClick = { onCommandSelected(command) }
                        )
                    }
                }
            }
            PrimaryActionButton(
                text = "执行命令",
                onClick = onExecute,
                modifier = Modifier.fillMaxWidth()
            )
            ResultPanel(text = result)
            Text(text = "命令输出", style = MaterialTheme.typography.titleSmall)
            OutputPanel(text = output)
        }
    }
}

@Composable
internal fun PreviewCard(imageBitmap: androidx.compose.ui.graphics.ImageBitmap?) {
    if (imageBitmap == null) return
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "截图预览", style = MaterialTheme.typography.titleMedium)
            Image(
                bitmap = imageBitmap,
                contentDescription = "截图预览",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
internal fun LogCard(title: String, content: String) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            OutputPanel(text = content)
        }
    }
}

@Composable
internal fun ResultPanel(text: String) {
    val tone = toneForMessage(text)
    val config = rememberAdaptableLayoutConfig()
    Surface(
        color = tone.container(MaterialTheme.colorScheme).copy(alpha = 0.55f),
        tonalElevation = 0.dp,
        shape = CardDefaults.outlinedShape
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = tone.content(MaterialTheme.colorScheme),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = config.resultPanelMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        )
    }
}

@Composable
internal fun OutputPanel(text: String) {
    val config = rememberAdaptableLayoutConfig()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        tonalElevation = 0.dp,
        shape = CardDefaults.outlinedShape
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = config.outputPanelMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        )
    }
}

@Composable
internal fun ChatBubble(
    message: ChatMessage,
    showSenderLabel: Boolean = true
) {
    val isUser = message.role == ChatRole.User
    val context = LocalContext.current
    val timeText = remember(message.createdAtEpochMs) {
        formatChatBubbleTime(message.createdAtEpochMs)
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val selectionColors = remember(isUser) {
        if (isUser) {
            TextSelectionColors(
                handleColor = Color.White,
                backgroundColor = Color.White.copy(alpha = 0.35f)
            )
        } else {
            TextSelectionColors(
                handleColor = Color(0xFF1565C0),
                backgroundColor = Color(0xFF1565C0).copy(alpha = 0.28f)
            )
        }
    }
    val useRich = !isUser && (
        message.state == ChatMessageState.Final ||
            message.state == ChatMessageState.Terminated ||
            message.content.length > 80 ||
            message.content.contains("```") ||
            message.content.contains('|')
        )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 18.dp
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
            },
            tonalElevation = if (isUser) 0.dp else 1.dp,
            modifier = if (isUser) {
                Modifier.widthIn(max = 320.dp)
            } else {
                Modifier.fillMaxWidth(0.92f)
            }
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (showSenderLabel) {
                    Text(
                        text = if (isUser) "你" else "Clawdroid",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                message.attachmentLabel?.let { label ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AttachFile,
                            contentDescription = null,
                            tint = if (isUser) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                            } else {
                                MaterialTheme.colorScheme.secondary
                            },
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                            } else {
                                MaterialTheme.colorScheme.secondary
                            }
                        )
                    }
                }
                if (message.hasMedia) {
                    ChatMediaContent(
                        media = message.media,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                // Selection only — no long-press menu competing with drag handles.
                CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                    SelectionContainer {
                        if (useRich) {
                            com.clawdroid.app.ui.rich.RichMessageContent(
                                content = message.content,
                                contentColor = contentColor,
                                streaming = message.state == ChatMessageState.Streaming
                            )
                        } else {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = timeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        if (message.state == ChatMessageState.Streaming) {
                            ChatStreamingDots(
                                color = if (isUser) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        } else if (message.state == ChatMessageState.Terminated) {
                            Text(
                                text = "已终止",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isUser) {
                                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }
                    }
                    IconButton(
                        onClick = { copyChatText(context, message.content) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "复制全文",
                            tint = if (isUser) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun copyChatText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("clawdroid-chat", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

@Composable
private fun ChatStreamingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "stream-dots")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "stream-alpha"
    )
    Text(
        text = "输入中",
        style = MaterialTheme.typography.labelSmall,
        color = color.copy(alpha = alpha)
    )
}

@Composable
internal fun CircleIconButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        shape = CircleShape
    ) {
        icon()
    }
}

@Composable
internal fun StatusChip(label: String) {
    StatusChip(label = label, tone = toneForMessage(label))
}

@Composable
private fun StatusChip(label: String, tone: StatusTone) {
    val colorScheme = MaterialTheme.colorScheme
    AssistChip(
        onClick = {},
        border = BorderStroke(1.dp, tone.border(colorScheme)),
        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
            containerColor = tone.container(colorScheme).copy(alpha = 0.28f),
            labelColor = tone.content(colorScheme)
        ),
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

@Composable
internal fun ModernCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
    ) {
        content()
    }
}

@Composable
private fun MetricInfoCard(
    title: String,
    value: String,
    supporting: String,
    tone: StatusTone = StatusTone.Neutral
) {
    val config = rememberAdaptableLayoutConfig()
    Surface(
        modifier = Modifier.widthIn(min = config.metricCardMinWidth, max = config.metricCardMaxWidth),
        color = tone.container(MaterialTheme.colorScheme).copy(alpha = 0.20f),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = tone.content(MaterialTheme.colorScheme),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProcessMetricPanel(
    title: String,
    metric: ProcessDashboardMetric,
    fallback: String
) {
    val hSpacing = responsiveFlowHSpacing()
    val vSpacing = responsiveFlowVSpacing()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (metric.present) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(hSpacing),
                    verticalArrangement = Arrangement.spacedBy(vSpacing)
                ) {
                    StatusChip("PID ${metric.pid}", tone = StatusTone.Active)
                    StatusChip("CPU ${metric.cpuPercent.asPercentLabel()}", tone = StatusTone.Success)
                    StatusChip("RSS ${formatBytes(metric.rssBytes)}", tone = StatusTone.Warning)
                    StatusChip("状态 ${metric.state}", tone = StatusTone.Neutral)
                }
                Text(
                    text = "命令: ${metric.command}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ResultPanel(text = fallback)
            }
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun HeroBadge(text: String, accent: Color) {
    Surface(
        color = accent.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            color = accent,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun HeroInfoTile(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    accent: Color
) {
    Surface(
        modifier = modifier,
        color = accent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FloatingNavButton(
    modifier: Modifier = Modifier,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        Color.Transparent
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier
            .clickable(onClick = onClick),
        color = containerColor,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun toneForConnectionState(state: ClawRuntimeConnectionState): StatusTone {
    return when (state) {
        ClawRuntimeConnectionState.Ready,
        ClawRuntimeConnectionState.CapabilitySynced,
        ClawRuntimeConnectionState.Authenticated -> StatusTone.Success
        ClawRuntimeConnectionState.Degraded,
        ClawRuntimeConnectionState.ChallengeIssued,
        ClawRuntimeConnectionState.PeerVerified,
        ClawRuntimeConnectionState.SocketConnected -> StatusTone.Warning
        ClawRuntimeConnectionState.Closed,
        ClawRuntimeConnectionState.Disconnected -> StatusTone.Danger
    }
}

private fun toneForRootState(granted: Boolean?): StatusTone {
    return when (granted) {
        true -> StatusTone.Success
        false -> StatusTone.Danger
        null -> StatusTone.Warning
    }
}

private fun toneForBoolean(value: Boolean): StatusTone {
    return if (value) StatusTone.Success else StatusTone.Danger
}

private fun toneForLsposed(status: LocalEnvironmentStatus): StatusTone {
    return when {
        status.xposedInjected -> StatusTone.Success
        status.lsposedManagerInstalled -> StatusTone.Warning
        else -> StatusTone.Danger
    }
}

private fun toneForMessage(text: String): StatusTone {
    val normalized = text.lowercase()
    return when {
        "成功" in text || "ready" in normalized || "running" in normalized || "active" in normalized || "ok" in normalized -> StatusTone.Success
        "失败" in text || "error" in normalized || "denied" in normalized || "closed" in normalized || "不可用" in text -> StatusTone.Danger
        "请求中" in text || "执行中" in text || "检测中" in text || "测试中" in text || "协商中" in text || "订阅中" in text -> StatusTone.Active
        "未" in text || "等待" in text || "idle" in normalized || "空闲" in text || "unknown" in normalized -> StatusTone.Warning
        else -> StatusTone.Neutral
    }
}

private fun StatusTone.container(colorScheme: androidx.compose.material3.ColorScheme): Color {
    return when (this) {
        StatusTone.Neutral -> colorScheme.surfaceVariant
        StatusTone.Active -> colorScheme.primary
        StatusTone.Success -> colorScheme.secondary
        StatusTone.Warning -> colorScheme.tertiary
        StatusTone.Danger -> colorScheme.error
    }
}

private fun StatusTone.content(colorScheme: androidx.compose.material3.ColorScheme): Color {
    return when (this) {
        StatusTone.Neutral -> colorScheme.onSurface
        StatusTone.Active -> colorScheme.onPrimary
        StatusTone.Success -> colorScheme.onSecondary
        StatusTone.Warning -> colorScheme.onTertiary
        StatusTone.Danger -> colorScheme.onError
    }
}

private fun StatusTone.border(colorScheme: androidx.compose.material3.ColorScheme): Color {
    return container(colorScheme).copy(alpha = 0.40f)
}

private fun Float.asPercentLabel(): String = String.format("%.1f%%", this)

private fun Float.formatLoad(): String = String.format("%.2f", this)
