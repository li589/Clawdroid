package com.clawdroid.app.tools.adapters

import com.clawdroid.app.agent.task.ExecutionStep
import com.clawdroid.app.agent.task.TaskCheckpoint
import com.clawdroid.app.agent.task.TaskFailure
import com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot

data class TaskExecutionContext(
    val taskId: String,
    val attempt: Int,
    val checkpoint: TaskCheckpoint
)

data class TaskStepResult(
    val success: Boolean,
    val summary: String,
    val failure: TaskFailure? = null,
    val runtimeTaskId: String? = null,
    val waitReason: String? = null,
    val data: Map<String, String> = emptyMap()
)

interface TaskStepAdapter {
    suspend fun execute(step: ExecutionStep, context: TaskExecutionContext): TaskStepResult
}

interface TaskCancellationAdapter {
    suspend fun cancel(taskId: String): TaskStepResult
}

interface TaskResyncAdapter {
    suspend fun resync(taskId: String): ClawRuntimeTaskSnapshot?
}
