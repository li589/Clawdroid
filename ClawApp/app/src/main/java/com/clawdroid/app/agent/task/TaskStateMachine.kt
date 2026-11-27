package com.clawdroid.app.agent.task

object TaskStateMachine {
    private val transitions: Map<TaskState, Set<TaskState>> = mapOf(
        TaskState.Created to setOf(TaskState.Queued, TaskState.Cancelled),
        TaskState.Queued to setOf(TaskState.Running, TaskState.Paused, TaskState.Cancelled),
        TaskState.Running to setOf(
            TaskState.WaitingSignal,
            TaskState.Retrying,
            TaskState.Paused,
            TaskState.Succeeded,
            TaskState.Failed,
            TaskState.Cancelled,
            TaskState.Compensating
        ),
        TaskState.WaitingSignal to setOf(
            TaskState.Running,
            TaskState.Retrying,
            TaskState.Paused,
            TaskState.Cancelled
        ),
        TaskState.Retrying to setOf(
            TaskState.Running,
            TaskState.Failed,
            TaskState.Paused,
            TaskState.Cancelled
        ),
        TaskState.Paused to setOf(TaskState.Running, TaskState.Cancelled),
        TaskState.Compensating to setOf(TaskState.Succeeded, TaskState.Failed),
        TaskState.Succeeded to emptySet(),
        TaskState.Failed to emptySet(),
        TaskState.Cancelled to emptySet()
    )

    fun canTransition(from: TaskState, to: TaskState): Boolean = to in transitions[from].orEmpty()

    fun isTerminal(state: TaskState): Boolean = transitions[state].isNullOrEmpty()
}
