package com.clawdroid.app.ui

import com.clawdroid.app.ai.AiAgentOrchestrator

internal data class ChatConsoleState(
    val messages: List<ChatMessage>,
    val input: String,
    val pendingImageLabel: String?,
    val chatBusy: Boolean,
    val taskExecution: ChatTaskExecutionState?,
    val taskHistory: List<ChatTaskExecutionState>,
    val taskHistoryFilter: ChatTaskHistoryFilter,
    val eventStreaming: Boolean,
    val modelLabel: String,
    val aiSummary: String,
    val connectionSummary: String
)

internal data class ChatConsoleActions(
    val onInputChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onVoiceClick: () -> Unit,
    val onImageClick: () -> Unit,
    val onQuickPing: () -> Unit,
    val onQuickRuntimeCheck: () -> Unit,
    val onQuickCapabilities: () -> Unit,
    val onQuickCapture: () -> Unit,
    val onQuickShell: () -> Unit,
    val onQuickSafeTapTask: () -> Unit,
    val onQuickHealthSweepTask: () -> Unit,
    val onQuickSwipeCaptureTask: () -> Unit,
    val onQuickToggleEvents: () -> Unit,
    val onClearHistory: () -> Unit,
    val onCancelTaskExecution: () -> Unit,
    val onClearCurrentTaskExecution: () -> Unit,
    val onClearTaskHistory: () -> Unit,
    val onRetryTaskExecution: (ChatTaskExecutionState) -> Unit,
    val onTaskHistoryFilterChange: (ChatTaskHistoryFilter) -> Unit
)

internal fun buildChatConsoleState(
    chatState: ChatUiState,
    modelSettings: ModelSettings,
    eventStreaming: Boolean,
    connectionSummary: String
): ChatConsoleState {
    return ChatConsoleState(
        messages = chatState.messages,
        input = chatState.input,
        pendingImageLabel = chatState.pendingImageLabel,
        chatBusy = chatState.chatBusy,
        taskExecution = chatState.taskExecution,
        taskHistory = chatState.taskHistory,
        taskHistoryFilter = chatState.taskHistoryFilter,
        eventStreaming = eventStreaming,
        modelLabel = modelSettings.provider.name,
        aiSummary = AiAgentOrchestrator.readinessSummary(modelSettings) + "\n最近状态: ${chatState.latestAiStatus}",
        connectionSummary = connectionSummary
    )
}

internal fun ChatViewModel.buildChatConsoleActions(
    modelSettings: ModelSettings,
    eventStreaming: Boolean,
    onModelCallSuccess: () -> Unit,
    onVoiceClick: () -> Unit,
    onImageClick: () -> Unit
): ChatConsoleActions {
    return ChatConsoleActions(
        onInputChange = ::updateInput,
        onSend = { submitCurrentInput(modelSettings, onModelCallSuccess) },
        onVoiceClick = onVoiceClick,
        onImageClick = onImageClick,
        onQuickPing = { submitPrompt("ping ClawRuntime", modelSettings, onModelCallSuccess) },
        onQuickRuntimeCheck = { submitPrompt("检查运行时状态", modelSettings, onModelCallSuccess) },
        onQuickCapabilities = { submitPrompt("获取能力", modelSettings, onModelCallSuccess) },
        onQuickCapture = { submitPrompt("截图并预览", modelSettings, onModelCallSuccess) },
        onQuickShell = { submitPrompt("/shell wm size", modelSettings, onModelCallSuccess) },
        onQuickSafeTapTask = { submitPrompt("确认页面后安全点击", modelSettings, onModelCallSuccess) },
        onQuickHealthSweepTask = { submitPrompt("运行时体检", modelSettings, onModelCallSuccess) },
        onQuickSwipeCaptureTask = { submitPrompt("滑动后截图", modelSettings, onModelCallSuccess) },
        onQuickToggleEvents = {
            submitPrompt(
                if (eventStreaming) "停止事件订阅" else "开始事件订阅",
                modelSettings,
                onModelCallSuccess
            )
        },
        onClearHistory = { clearHistory() },
        onCancelTaskExecution = { cancelCurrentTaskExecution() },
        onClearCurrentTaskExecution = { clearCurrentTaskExecution() },
        onClearTaskHistory = { clearTaskHistory() },
        onRetryTaskExecution = { task -> retryTask(task) },
        onTaskHistoryFilterChange = { filter -> setTaskHistoryFilter(filter) }
    )
}
