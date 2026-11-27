package com.clawdroid.app.data

import com.clawdroid.app.agent.task.ExecutionStep
import com.clawdroid.app.agent.task.ExecutionTask
import com.clawdroid.app.agent.task.StepKind
import com.clawdroid.app.agent.task.TaskCheckpoint
import com.clawdroid.app.agent.task.TaskFailure
import com.clawdroid.app.agent.task.TaskState
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskStoreTest {
    @Test
    fun jsonStoreRoundTripsCheckpoint() {
        val store = JsonTaskStore(InMemoryTaskStorage())
        val task = ExecutionTask(
            id = "t1",
            title = "demo",
            originPrompt = "run",
            steps = listOf(
                ExecutionStep(
                    id = "s1",
                    title = "ping",
                    kind = StepKind.RuntimeTask,
                    payload = "ping",
                    sideEffectKey = "effect-1"
                )
            ),
            state = TaskState.Paused,
            attempt = 2,
            retryOf = "t0",
            checkpoint = TaskCheckpoint(
                nextStepIndex = 1,
                completedStepIds = setOf("s0"),
                stepAttempts = mapOf("s1" to 2),
                runtimeTaskId = "rt-1",
                waitingReason = "page",
                data = mapOf("resolution_token" to "token-1")
            ),
            failure = TaskFailure("temp", "retry", "detail", retryable = true, source = "runtime"),
            createdAtEpochMs = 10,
            updatedAtEpochMs = 20
        )

        store.save(task)

        assertEquals(task, store.load().single())
    }

    @Test
    fun jsonStoreRoundTripsNullableFields() {
        val store = JsonTaskStore(InMemoryTaskStorage())
        val task = ExecutionTask(
            id = "t1",
            title = "demo",
            originPrompt = "run",
            steps = listOf(ExecutionStep("s1", "ping", StepKind.Tool))
        )

        store.save(task)

        assertEquals(task, store.load().single())
    }

    @Test
    fun saveReplacesTaskWithSameId() {
        val store = JsonTaskStore(InMemoryTaskStorage())
        store.save(ExecutionTask("t1", "first", "run", emptyList()))
        store.save(ExecutionTask("t1", "second", "run", emptyList()))

        assertEquals(listOf("second"), store.load().map { it.title })
    }
}
