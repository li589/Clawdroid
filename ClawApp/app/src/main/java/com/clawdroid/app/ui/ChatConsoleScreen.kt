package com.clawdroid.app.ui

import com.clawdroid.app.data.ChatSessionSummary
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawdroid.app.tools.ClawAssetPromptStore

/**
 * 现代聊天页：消息区占主视口，工具区可折叠，输入栏固定底部。
 */
@Composable
internal fun ChatPage(
    state: ChatConsoleState,
    actions: ChatConsoleActions,
    modifier: Modifier = Modifier,
    onScrollTowardBottom: () -> Unit = {},
    onScrollTowardTop: () -> Unit = {},
    onComposerInteract: () -> Unit = {}
) {
    var toolsExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val suggestionPrompts = remember(context) {
        ClawAssetPromptStore.chatSuggestions(context).ifEmpty {
            listOf(
                "ping ClawRuntime",
                "获取能力",
                "截图并预览",
                "运行时体检"
            )
        }
    }
    val latestScrollDown = rememberUpdatedState(onScrollTowardBottom)
    val latestScrollUp = rememberUpdatedState(onScrollTowardTop)
    val nestedScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                when {
                    available.y < -6f -> latestScrollDown.value()
                    available.y > 6f -> latestScrollUp.value()
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content, state.messages.lastOrNull()?.state) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .nestedScroll(nestedScroll)
    ) {
        ChatTopBar(
            sessionTitle = state.activeSessionTitle,
            modelLabel = state.modelLabel,
            connectionSummary = state.connectionSummary,
            chatBusy = state.chatBusy,
            toolsExpanded = toolsExpanded,
            sessionSummaries = state.sessionSummaries,
            activeSessionId = state.activeSessionId,
            onToggleTools = { toolsExpanded = !toolsExpanded },
            onCreateSession = actions.onCreateSession,
            onDeleteCurrentSession = actions.onDeleteCurrentSession,
            onSelectSession = actions.onSelectSession
        )

        AnimatedVisibility(
            visible = toolsExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChatReadinessCard(
                    modelLabel = state.modelLabel,
                    aiSummary = state.aiSummary,
                    connectionSummary = state.connectionSummary,
                    eventStreaming = state.eventStreaming
                )
                QuickActionCard(
                    onPing = actions.onQuickPing,
                    onRuntimeCheck = actions.onQuickRuntimeCheck,
                    onCapabilities = actions.onQuickCapabilities,
                    onCapture = actions.onQuickCapture,
                    onShell = actions.onQuickShell,
                    onSafeTapTask = actions.onQuickSafeTapTask,
                    onHealthSweepTask = actions.onQuickHealthSweepTask,
                    onSwipeCaptureTask = actions.onQuickSwipeCaptureTask,
                    onEvents = actions.onQuickToggleEvents,
                    eventStreaming = state.eventStreaming,
                    actionsEnabled = !state.chatBusy
                )
                ChatTaskExecutionCard(
                    taskExecution = state.taskExecution,
                    taskHistory = state.taskHistory,
                    taskHistoryFilter = state.taskHistoryFilter,
                    onCancelTaskExecution = actions.onCancelTaskExecution,
                    onClearCurrentTaskExecution = actions.onClearCurrentTaskExecution,
                    onClearTaskHistory = actions.onClearTaskHistory,
                    onRetryTaskExecution = actions.onRetryTaskExecution,
                    onTaskHistoryFilterChange = actions.onTaskHistoryFilterChange
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        if (state.taskExecution != null && !toolsExpanded) {
            ChatInlineTaskBanner(
                task = state.taskExecution,
                onExpand = { toolsExpanded = true },
                onCancel = actions.onCancelTaskExecution
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 8.dp,
                    bottom = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.messages.isEmpty()) {
                    item(key = "chat-empty") {
                        ChatEmptyState(
                            suggestions = suggestionPrompts,
                            enabled = !state.chatBusy,
                            onSuggestion = actions.onSubmitPrompt
                        )
                    }
                } else {
                    items(
                        items = state.messages,
                        key = { it.id },
                        contentType = { it.role }
                    ) { message ->
                        ChatBubble(
                            message = message,
                            showSenderLabel = false
                        )
                    }
                    if (state.agentEvents.isNotEmpty() || state.awaitingBudgetContinue) {
                        item(key = "agent-timeline") {
                            AgentActivityTimeline(
                                events = state.agentEvents,
                                expanded = state.agentTimelineExpanded,
                                apiCallsRemaining = state.apiCallsRemaining,
                                awaitingBudgetContinue = state.awaitingBudgetContinue,
                                onToggle = actions.onToggleAgentTimeline
                            )
                        }
                    }
                    state.pendingCommandReview?.let { review ->
                        item(key = "command-review") {
                            CommandReviewCard(
                                review = review,
                                onApprove = actions.onApproveCommandReview,
                                onDeny = actions.onDenyCommandReview
                            )
                        }
                    }
                    if (isMostlyWelcome(state.messages)) {
                        item(key = "chat-suggestions") {
                            ChatSuggestionRow(
                                suggestions = suggestionPrompts,
                                enabled = !state.chatBusy,
                                onSuggestion = actions.onSubmitPrompt
                            )
                        }
                    }
                }
            }
        }

        ChatComposerBar(
            input = state.input,
            pendingImageLabel = state.pendingImageLabel,
            isBusy = state.chatBusy,
            onInputChange = {
                onComposerInteract()
                actions.onInputChange(it)
            },
            onSend = {
                onComposerInteract()
                actions.onSend()
            },
            onStop = {
                onComposerInteract()
                actions.onInterruptGeneration()
            },
            onVoiceClick = {
                onComposerInteract()
                actions.onVoiceClick()
            },
            onImageClick = {
                onComposerInteract()
                actions.onImageClick()
            },
            onClearPendingImage = {
                onComposerInteract()
                actions.onClearPendingImage()
            }
        )
    }
}

/** 兼容旧 LazyListScope 入口（已弃用，保留避免外部误用编译失败）。 */
@Deprecated("Use ChatPage instead", ReplaceWith("ChatPage(state, actions)"))
internal fun LazyListScope.chatConsoleScreen(
    state: ChatConsoleState,
    actions: ChatConsoleActions
) {
    item(key = "chat-page-legacy") {
        ChatPage(
            state = state,
            actions = actions,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 480.dp)
        )
    }
}

@Composable
private fun ChatTopBar(
    sessionTitle: String,
    modelLabel: String,
    connectionSummary: String,
    chatBusy: Boolean,
    toolsExpanded: Boolean,
    sessionSummaries: List<ChatSessionSummary>,
    activeSessionId: String,
    onToggleTools: () -> Unit,
    onCreateSession: () -> Unit,
    onDeleteCurrentSession: () -> Unit,
    onSelectSession: (String) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showSessionPicker by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = sessionTitle.ifBlank { "新对话" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(modelLabel)
                            if (chatBusy) append(" · 处理中")
                            append(" · ")
                            append(connectionSummary.take(28))
                            if (connectionSummary.length > 28) append("…")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onToggleTools) {
                    Text(if (toolsExpanded) "收起" else "工具")
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "对话菜单"
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("新建对话") },
                            onClick = {
                                menuExpanded = false
                                onCreateSession()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除当前对话") },
                            onClick = {
                                menuExpanded = false
                                onDeleteCurrentSession()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("选择对话") },
                            onClick = {
                                menuExpanded = false
                                showSessionPicker = true
                            }
                        )
                    }
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }

    if (showSessionPicker) {
        AlertDialog(
            onDismissRequest = { showSessionPicker = false },
            title = { Text("选择对话") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (sessionSummaries.isEmpty()) {
                        Text(
                            text = "暂无历史对话",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        sessionSummaries.forEach { session ->
                            val selected = session.id == activeSessionId
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showSessionPicker = false
                                        onSelectSession(session.id)
                                    },
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = session.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (session.preview.isNotBlank()) {
                                        Text(
                                            text = session.preview,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSessionPicker = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
private fun ChatInlineTaskBanner(
    task: ChatTaskExecutionState,
    onExpand: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = task.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onExpand) { Text("详情") }
            if (task.status == ChatTaskProgressState.Running) {
                TextButton(onClick = onCancel) { Text("取消") }
            }
        }
    }
}

@Composable
private fun ChatEmptyState(
    suggestions: List<String>,
    enabled: Boolean,
    onSuggestion: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "开始对话",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "用自然语言控制 Runtime：探测、截图、任务与快捷动作。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ChatSuggestionRow(
            suggestions = suggestions,
            enabled = enabled,
            onSuggestion = onSuggestion
        )
    }
}

@Composable
private fun ChatSuggestionRow(
    suggestions: List<String>,
    enabled: Boolean,
    onSuggestion: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        suggestions.forEach { prompt ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onSuggestion(prompt) },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ) {
                Text(
                    text = prompt,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun ChatComposerBar(
    input: String,
    pendingImageLabel: String?,
    isBusy: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoiceClick: () -> Unit,
    onImageClick: () -> Unit,
    onClearPendingImage: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            pendingImageLabel?.let { label ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "附件 · $label",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onClearPendingImage,
                        enabled = !isBusy
                    ) {
                        Text("清除")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalIconButton(
                    onClick = onImageClick,
                    enabled = !isBusy
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Image,
                        contentDescription = "添加图片"
                    )
                }
                FilledTonalIconButton(
                    onClick = onVoiceClick,
                    enabled = !isBusy
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = "语音输入"
                    )
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 5,
                        enabled = !isBusy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (!isBusy && input.isNotBlank()) onSend()
                            }
                        ),
                        decorationBox = { inner ->
                            Box {
                                if (input.isBlank()) {
                                    Text(
                                        text = if (isBusy) "处理中，可点停止打断…" else "发消息或指令…",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }
                FilledIconButton(
                    onClick = if (isBusy) onStop else onSend,
                    enabled = isBusy || input.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isBusy) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        contentColor = if (isBusy) {
                            MaterialTheme.colorScheme.onError
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isBusy) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (isBusy) "停止" else "发送",
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

private fun isMostlyWelcome(messages: List<ChatMessage>): Boolean {
    if (messages.isEmpty()) return true
    if (messages.size > 2) return false
    return messages.all { it.role == ChatRole.Assistant && it.state == ChatMessageState.Final }
}

@Composable
private fun CommandReviewCard(
    review: PendingCommandReview,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "命令审查：${review.toolDisplayName}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = review.argumentsPreview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onDeny) {
                    Text("拒绝")
                }
                TextButton(onClick = onApprove) {
                    Text("批准执行")
                }
            }
        }
    }
}
