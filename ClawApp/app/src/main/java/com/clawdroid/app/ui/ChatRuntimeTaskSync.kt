package com.clawdroid.app.ui

import com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot

internal fun ChatTaskExecutionState.withRuntimeSnapshot(
    snapshot: ClawRuntimeTaskSnapshot,
    nowEpochMs: Long = System.currentTimeMillis()
): ChatTaskExecutionState {
    val terminalStatus = when (snapshot.state.lowercase()) {
        "succeeded" -> ChatTaskProgressState.Succeeded
        "failed" -> ChatTaskProgressState.Failed
        "cancelled" -> ChatTaskProgressState.Cancelled
        else -> null
    }
    // Prefer Runtime totalSteps; if submit ack omits it (0), keep existing chat step slots.
    val existingSteps = this.steps
    val totalSteps = when {
        snapshot.totalSteps > 0 -> snapshot.totalSteps
        existingSteps.isNotEmpty() -> existingSteps.size
        else -> 1
    }
    val steps = existingSteps.toMutableList()
    while (steps.size < totalSteps) {
        steps += ChatTaskStepState(title = "步骤 ${steps.size + 1}")
    }
    val completed = snapshot.completedSteps.coerceIn(0, totalSteps)
    val currentStep = snapshot.currentStep.coerceIn(0, totalSteps - 1)
    val mappedSteps = steps.take(totalSteps).mapIndexed { index, step ->
        when {
            terminalStatus == ChatTaskProgressState.Succeeded -> step.copy(
                status = ChatTaskProgressState.Succeeded,
                detail = if (index == currentStep) snapshot.summaryLine() else step.detail,
                finishedAtEpochMs = if (step.finishedAtEpochMs > 0L) step.finishedAtEpochMs else nowEpochMs
            )
            terminalStatus == ChatTaskProgressState.Failed -> {
                when {
                    index < completed -> step.copy(
                        status = ChatTaskProgressState.Succeeded,
                        finishedAtEpochMs = if (step.finishedAtEpochMs > 0L) step.finishedAtEpochMs else nowEpochMs
                    )
                    index == currentStep -> step.copy(
                        status = ChatTaskProgressState.Failed,
                        detail = snapshot.error.ifBlank { snapshot.summaryLine() },
                        finishedAtEpochMs = nowEpochMs,
                        startedAtEpochMs = if (step.startedAtEpochMs > 0L) step.startedAtEpochMs else nowEpochMs
                    )
                    else -> step.copy(status = ChatTaskProgressState.Pending)
                }
            }
            terminalStatus == ChatTaskProgressState.Cancelled -> {
                if (step.status == ChatTaskProgressState.Running || index == currentStep) {
                    step.copy(
                        status = ChatTaskProgressState.Cancelled,
                        detail = "Runtime 任务已取消",
                        finishedAtEpochMs = nowEpochMs
                    )
                } else {
                    step
                }
            }
            index < completed -> step.copy(
                status = ChatTaskProgressState.Succeeded,
                finishedAtEpochMs = if (step.finishedAtEpochMs > 0L) step.finishedAtEpochMs else nowEpochMs
            )
            index == currentStep -> step.copy(
                status = ChatTaskProgressState.Running,
                detail = snapshot.summaryLine(),
                startedAtEpochMs = if (step.startedAtEpochMs > 0L) step.startedAtEpochMs else nowEpochMs
            )
            else -> step.copy(status = ChatTaskProgressState.Pending)
        }
    }
    return copy(
        runtimeTaskId = snapshot.taskId,
        title = snapshot.name.ifBlank { title },
        summary = "Runtime: ${snapshot.summaryLine()}",
        status = terminalStatus ?: ChatTaskProgressState.Running,
        steps = mappedSteps,
        finishedAtEpochMs = if (terminalStatus != null) nowEpochMs else 0L,
        failureReason = if (terminalStatus == ChatTaskProgressState.Failed) {
            snapshot.error.ifBlank { "Runtime 任务失败" }
        } else {
            null
        },
        failure = when (terminalStatus) {
            ChatTaskProgressState.Failed -> ChatTaskFailureState(
                code = if (snapshot.errorCode != 0) {
                    "runtime_${snapshot.errorCode}"
                } else {
                    "runtime_task_failed"
                },
                summary = "Runtime 任务失败",
                rawDetail = snapshot.error.ifBlank { snapshot.summaryLine() }
            )
            ChatTaskProgressState.Cancelled -> ChatTaskFailureState(
                code = "runtime_task_cancelled",
                summary = "Runtime 任务已取消",
                rawDetail = snapshot.summaryLine()
            )
            else -> null
        }
    )
}
