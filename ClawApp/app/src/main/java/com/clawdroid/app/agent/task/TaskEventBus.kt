package com.clawdroid.app.agent.task

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

data class TaskEvent(
    val taskId: String,
    val state: TaskState,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

interface TaskEventBus {
    fun publish(event: TaskEvent)
    fun events(): SharedFlow<TaskEvent>
}

class MutableTaskEventBus : TaskEventBus {
    private val flow = MutableSharedFlow<TaskEvent>(extraBufferCapacity = 64)

    override fun publish(event: TaskEvent) {
        flow.tryEmit(event)
    }

    override fun events(): SharedFlow<TaskEvent> = flow
}
