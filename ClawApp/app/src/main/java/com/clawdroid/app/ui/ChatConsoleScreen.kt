package com.clawdroid.app.ui

import androidx.compose.foundation.lazy.LazyListScope

internal fun LazyListScope.chatConsoleScreen(
    state: ChatConsoleState,
    actions: ChatConsoleActions
) {
    item { SectionTitle("AI 就绪状态") }
    item {
        ChatReadinessCard(
            modelLabel = state.modelLabel,
            aiSummary = state.aiSummary,
            connectionSummary = state.connectionSummary,
            eventStreaming = state.eventStreaming
        )
    }
    item { SectionTitle("快捷动作") }
    item {
        QuickActionCard(
            onPing = actions.onQuickPing,
            onRuntimeCheck = actions.onQuickRuntimeCheck,
            onCapabilities = actions.onQuickCapabilities,
            onCapture = actions.onQuickCapture,
            onShell = actions.onQuickShell,
            onSafeTapTask = actions.onQuickSafeTapTask,
            onEvents = actions.onQuickToggleEvents,
            eventStreaming = state.eventStreaming
        )
    }
    item { SectionTitle("任务执行") }
    item {
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
    }
    item { SectionTitle("交互控制台") }
    item {
        ChatWorkspaceCard(
            messages = state.messages,
            input = state.input,
            pendingImageLabel = state.pendingImageLabel,
            isBusy = state.chatBusy,
            onInputChange = actions.onInputChange,
            onSend = actions.onSend,
            onVoiceClick = actions.onVoiceClick,
            onImageClick = actions.onImageClick,
            onClearHistory = actions.onClearHistory
        )
    }
}
