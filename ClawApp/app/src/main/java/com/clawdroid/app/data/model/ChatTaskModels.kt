package com.clawdroid.app.data.model

import com.clawdroid.app.chat.ChatTaskAction

internal enum class ChatTaskProgressState {
    Pending,
    Running,
    Succeeded,
    Failed,
    Cancelled
}

internal data class ChatTaskFailureState(
    val code: String,
    val summary: String,
    val rawDetail: String
)

internal data class ChatTaskStepState(
    val title: String,
    val status: ChatTaskProgressState = ChatTaskProgressState.Pending,
    val detail: String = "等待执行",
    val startedAtEpochMs: Long = 0L,
    val finishedAtEpochMs: Long = 0L
)

internal data class ChatTaskExecutionState(
    val taskId: String,
    val title: String,
    val summary: String,
    val status: ChatTaskProgressState,
    val steps: List<ChatTaskStepState>,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long = 0L,
    val taskAction: ChatTaskAction? = null,
    val runtimeTaskId: String? = null,
    val failureReason: String? = null,
    val originPrompt: String = "",
    val retryCount: Int = 0,
    val retryFromTaskId: String? = null,
    val failure: ChatTaskFailureState? = null
)
