package com.clawdroid.app.agent.task

enum class TaskState {
    Created,
    Queued,
    Running,
    WaitingSignal,
    Retrying,
    Paused,
    Succeeded,
    Failed,
    Cancelled,
    Compensating
}

enum class StepKind {
    Tool,
    RuntimeTask,
    ObservePage,
    ConfirmPage,
    ResolveTarget,
    SafeTap,
    AwaitUser
}

data class StepRetryPolicy(
    val maxAttempts: Int = 0,
    val initialDelayMs: Long = 500,
    val maxDelayMs: Long = 5_000
)

data class ExecutionStep(
    val id: String,
    val title: String,
    val kind: StepKind,
    val payload: String = "",
    val retry: StepRetryPolicy = StepRetryPolicy(),
    val timeoutMs: Long = 30_000,
    val sideEffectKey: String? = null
)

data class TaskCheckpoint(
    val nextStepIndex: Int = 0,
    val completedStepIds: Set<String> = emptySet(),
    val stepAttempts: Map<String, Int> = emptyMap(),
    val runtimeTaskId: String? = null,
    val waitingReason: String? = null
)

data class TaskFailure(
    val code: String,
    val summary: String,
    val detail: String = "",
    val retryable: Boolean = false,
    val source: String = ""
)

data class ExecutionTask(
    val id: String,
    val title: String,
    val originPrompt: String,
    val steps: List<ExecutionStep>,
    val state: TaskState = TaskState.Created,
    val attempt: Int = 1,
    val retryOf: String? = null,
    val checkpoint: TaskCheckpoint = TaskCheckpoint(),
    val failure: TaskFailure? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
