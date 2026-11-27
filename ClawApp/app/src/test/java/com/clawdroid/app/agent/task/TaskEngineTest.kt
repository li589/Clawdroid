package com.clawdroid.app.agent.task

import com.clawdroid.app.data.InMemoryTaskStore
import com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot
import com.clawdroid.app.tools.adapters.TaskExecutionContext
import com.clawdroid.app.tools.adapters.TaskStepAdapter
import com.clawdroid.app.tools.adapters.TaskStepResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskEngineTest {
    @Test
    fun executesStepsInOrderAndPersistsCompletion() = runTest {
        val store = InMemoryTaskStore()
        val calls = mutableListOf<String>()
        val adapter = object : TaskStepAdapter {
            override suspend fun execute(
                step: ExecutionStep,
                context: TaskExecutionContext
            ): TaskStepResult {
                calls += step.id
                return TaskStepResult(true, step.title)
            }
        }
        val engine = TaskEngine(store, mapOf(StepKind.Tool to adapter), this)

        engine.submit(
            ExecutionTask(
                id = "t1",
                title = "demo",
                originPrompt = "run",
                steps = listOf(
                    ExecutionStep("s1", "one", StepKind.Tool),
                    ExecutionStep("s2", "two", StepKind.Tool)
                )
            )
        )
        advanceUntilIdle()

        assertEquals(listOf("s1", "s2"), calls)
        assertEquals(TaskState.Succeeded, store.load().single().state)
        assertEquals(setOf("s1", "s2"), store.load().single().checkpoint.completedStepIds)
    }

    @Test
    fun waitingStepPersistsRuntimeTaskId() = runTest {
        val store = InMemoryTaskStore()
        val adapter = object : TaskStepAdapter {
            override suspend fun execute(
                step: ExecutionStep,
                context: TaskExecutionContext
            ): TaskStepResult = TaskStepResult(
                success = false,
                summary = "submitted",
                runtimeTaskId = "rt-1",
                waitReason = "runtime_task"
            )
        }
        val engine = TaskEngine(store, mapOf(StepKind.RuntimeTask to adapter), this)

        engine.submit(
            ExecutionTask("t1", "demo", "run", listOf(ExecutionStep("s1", "runtime", StepKind.RuntimeTask)))
        )
        advanceUntilIdle()

        val task = store.load().single()
        assertEquals(TaskState.WaitingSignal, task.state)
        assertEquals("rt-1", task.checkpoint.runtimeTaskId)
    }

    @Test
    fun pauseStopsBeforeNextStepAndResumeContinues() = runTest {
        val store = InMemoryTaskStore()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val adapter = object : TaskStepAdapter {
            override suspend fun execute(
                step: ExecutionStep,
                context: TaskExecutionContext
            ): TaskStepResult {
                if (step.id == "s1") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                } else {
                    secondStarted.complete(Unit)
                }
                return TaskStepResult(true, step.title)
            }
        }
        val engine = TaskEngine(store, mapOf(StepKind.Tool to adapter), this)
        engine.submit(
            ExecutionTask(
                "t1",
                "demo",
                "run",
                listOf(
                    ExecutionStep("s1", "one", StepKind.Tool),
                    ExecutionStep("s2", "two", StepKind.Tool)
                )
            )
        )
        runCurrent()
        assertTrue(firstStarted.isCompleted)

        engine.pause("t1")
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertFalse(secondStarted.isCompleted)
        assertEquals(TaskState.Paused, store.load().single().state)
        engine.resume("t1")
        advanceUntilIdle()
        assertTrue(secondStarted.isCompleted)
        assertEquals(TaskState.Succeeded, store.load().single().state)
    }

    @Test
    fun olderRuntimeEventCannotRegressSucceededTask() = runTest {
        val store = InMemoryTaskStore()
        val engine = TaskEngine(store, emptyMap(), this)
        engine.restore(ExecutionTask("t1", "demo", "run", emptyList(), TaskState.Succeeded))

        engine.applyRuntimeSnapshot(ClawRuntimeTaskSnapshot(taskId = "t1", state = "Running"))

        assertEquals(TaskState.Succeeded, store.load().single().state)
    }

    @Test
    fun runtimeSuccessAdvancesMixedTask() = runTest {
        val store = InMemoryTaskStore()
        val localCalls = mutableListOf<String>()
        val local = object : TaskStepAdapter {
            override suspend fun execute(
                step: ExecutionStep,
                context: TaskExecutionContext
            ): TaskStepResult {
                localCalls += step.id
                return TaskStepResult(true, step.title)
            }
        }
        val engine = TaskEngine(store, mapOf(StepKind.Tool to local), this)
        engine.restore(
            ExecutionTask(
                id = "t1",
                title = "mixed",
                originPrompt = "run",
                steps = listOf(
                    ExecutionStep("runtime", "runtime", StepKind.RuntimeTask),
                    ExecutionStep("local", "local", StepKind.Tool)
                ),
                state = TaskState.WaitingSignal,
                checkpoint = TaskCheckpoint(runtimeTaskId = "rt-1", waitingReason = "runtime_task")
            )
        )

        engine.applyRuntimeSnapshot(ClawRuntimeTaskSnapshot(taskId = "rt-1", state = "Succeeded"))
        advanceUntilIdle()

        assertEquals(listOf("local"), localCalls)
        assertEquals(TaskState.Succeeded, store.load().single().state)
        assertEquals(setOf("runtime", "local"), store.load().single().checkpoint.completedStepIds)
    }

    @Test
    fun rejectsDuplicateActiveTaskId() = runTest {
        val gate = CompletableDeferred<Unit>()
        val adapter = object : TaskStepAdapter {
            override suspend fun execute(
                step: ExecutionStep,
                context: TaskExecutionContext
            ): TaskStepResult {
                gate.await()
                return TaskStepResult(true, step.title)
            }
        }
        val engine = TaskEngine(InMemoryTaskStore(), mapOf(StepKind.Tool to adapter), this)
        val task = ExecutionTask("t1", "demo", "run", listOf(ExecutionStep("s1", "one", StepKind.Tool)))

        engine.submit(task)
        runCurrent()
        try {
            engine.submit(task)
            fail("duplicate active task id was accepted")
        } catch (_: IllegalArgumentException) {
            assertTrue(true)
        }
        gate.complete(Unit)
        advanceUntilIdle()
    }
}
