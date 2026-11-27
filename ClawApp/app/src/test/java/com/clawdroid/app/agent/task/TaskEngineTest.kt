package com.clawdroid.app.agent.task

import com.clawdroid.app.data.InMemoryTaskStore
import com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot
import com.clawdroid.app.tools.adapters.TaskCancellationAdapter
import com.clawdroid.app.tools.adapters.TaskExecutionContext
import com.clawdroid.app.tools.adapters.TaskResyncAdapter
import com.clawdroid.app.tools.adapters.TaskStepAdapter
import com.clawdroid.app.tools.adapters.TaskStepResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    fun publishesStepStartAndCompletionEvents() = runTest {
        val store = InMemoryTaskStore()
        val adapter = object : TaskStepAdapter {
            override suspend fun execute(
                step: ExecutionStep,
                context: TaskExecutionContext
            ): TaskStepResult = TaskStepResult(true, step.title)
        }
        val engine = TaskEngine(store, mapOf(StepKind.Tool to adapter), this)
        val messages = mutableListOf<String>()
        val collector = launch { engine.events.collect { messages += it.message } }

        engine.submit(
            ExecutionTask(
                "t1",
                "demo",
                "run",
                listOf(ExecutionStep("s1", "one", StepKind.Tool))
            )
        )
        advanceUntilIdle()
        collector.cancel()

        assertTrue(messages.any { it == "步骤开始: one" })
        assertTrue(messages.any { it == "步骤完成: one" })
    }

    @Test
    fun stepResultDataIsPersistedInCheckpoint() = runTest {
        val store = InMemoryTaskStore()
        val adapter = object : TaskStepAdapter {
            override suspend fun execute(
                step: ExecutionStep,
                context: TaskExecutionContext
            ): TaskStepResult = TaskStepResult(
                success = true,
                summary = "resolved",
                data = mapOf("resolution_token" to "token-1")
            )
        }
        val engine = TaskEngine(store, mapOf(StepKind.Tool to adapter), this)

        engine.submit(ExecutionTask("t1", "demo", "run", listOf(ExecutionStep("s1", "resolve", StepKind.Tool))))
        advanceUntilIdle()

        assertEquals("token-1", store.load().single().checkpoint.data["resolution_token"])
    }

    @Test
    fun retryableStepFailureRetriesWithinPolicy() = runTest {
        val store = InMemoryTaskStore()
        var calls = 0
        val adapter = object : TaskStepAdapter {
            override suspend fun execute(
                step: ExecutionStep,
                context: TaskExecutionContext
            ): TaskStepResult {
                calls += 1
                return if (calls == 1) {
                    TaskStepResult(
                        success = false,
                        summary = "temporary",
                        failure = TaskFailure("temporary", "临时失败", retryable = true)
                    )
                } else {
                    TaskStepResult(true, "ok")
                }
            }
        }
        val engine = TaskEngine(store, mapOf(StepKind.Tool to adapter), this)

        engine.submit(
            ExecutionTask(
                "t1",
                "demo",
                "run",
                listOf(
                    ExecutionStep(
                        "s1",
                        "one",
                        StepKind.Tool,
                        retry = StepRetryPolicy(maxAttempts = 2, initialDelayMs = 10)
                    )
                )
            )
        )
        advanceUntilIdle()

        assertEquals(2, calls)
        assertEquals(TaskState.Succeeded, store.load().single().state)
        assertEquals(2, store.load().single().checkpoint.stepAttempts["s1"])
    }

    @Test
    fun stepTimeoutFailsTaskWithoutLeakingActiveState() = runTest {
        val store = InMemoryTaskStore()
        val adapter = object : TaskStepAdapter {
            override suspend fun execute(
                step: ExecutionStep,
                context: TaskExecutionContext
            ): TaskStepResult {
                delay(1_000)
                return TaskStepResult(true, "too late")
            }
        }
        val engine = TaskEngine(store, mapOf(StepKind.Tool to adapter), this)

        engine.submit(
            ExecutionTask(
                "t1",
                "demo",
                "run",
                listOf(ExecutionStep("s1", "one", StepKind.Tool, timeoutMs = 10))
            )
        )
        advanceUntilIdle()

        assertEquals(TaskState.Failed, store.load().single().state)
        assertEquals("step_timeout", store.load().single().failure?.code)
    }

    @Test
    fun sideEffectingStepTimeoutDoesNotAutoRetry() = runTest {
        val store = InMemoryTaskStore()
        var calls = 0
        val adapter = object : TaskStepAdapter {
            override suspend fun execute(
                step: ExecutionStep,
                context: TaskExecutionContext
            ): TaskStepResult {
                calls += 1
                delay(1_000)
                return TaskStepResult(true, "too late")
            }
        }
        val engine = TaskEngine(store, mapOf(StepKind.SafeTap to adapter), this)

        engine.submit(
            ExecutionTask(
                "t1",
                "demo",
                "run",
                listOf(
                    ExecutionStep(
                        "s1",
                        "tap",
                        StepKind.SafeTap,
                        timeoutMs = 10,
                        sideEffectKey = "tap-1",
                        retry = StepRetryPolicy(maxAttempts = 3, initialDelayMs = 1)
                    )
                )
            )
        )
        advanceUntilIdle()

        assertEquals(1, calls)
        assertEquals(TaskState.Failed, store.load().single().state)
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
    fun restoreAllReattachesRuntimeTaskAndContinuesLocalSteps() = runTest {
        val store = InMemoryTaskStore()
        val localCalls = mutableListOf<String>()
        val runtime = object : TaskStepAdapter, TaskResyncAdapter {
            override suspend fun execute(
                step: ExecutionStep,
                context: TaskExecutionContext
            ): TaskStepResult = TaskStepResult(false, "submitted", runtimeTaskId = "rt-1", waitReason = "runtime_task")

            override suspend fun resync(taskId: String): ClawRuntimeTaskSnapshot =
                ClawRuntimeTaskSnapshot(taskId = taskId, state = "Succeeded")
        }
        val local = object : TaskStepAdapter {
            override suspend fun execute(
                step: ExecutionStep,
                context: TaskExecutionContext
            ): TaskStepResult {
                localCalls += step.id
                return TaskStepResult(true, step.title)
            }
        }
        store.save(
            ExecutionTask(
                id = "t1",
                title = "mixed",
                originPrompt = "run",
                steps = listOf(
                    ExecutionStep("runtime", "runtime", StepKind.RuntimeTask),
                    ExecutionStep("local", "local", StepKind.Tool)
                ),
                state = TaskState.Paused,
                checkpoint = TaskCheckpoint(runtimeTaskId = "rt-1")
            )
        )
        val engine = TaskEngine(
            store,
            mapOf(StepKind.RuntimeTask to runtime, StepKind.Tool to local),
            this
        )

        engine.restoreAll()
        advanceUntilIdle()

        assertEquals(listOf("local"), localCalls)
        assertEquals(TaskState.Succeeded, store.load().single().state)
    }

    @Test
    fun retryCreatesNewAttemptAndKeepsCompletedSteps() = runTest {
        val store = InMemoryTaskStore()
        val adapter = object : TaskStepAdapter {
            override suspend fun execute(
                step: ExecutionStep,
                context: TaskExecutionContext
            ): TaskStepResult = if (context.attempt == 1) {
                TaskStepResult(
                    success = false,
                    summary = "fail",
                    failure = TaskFailure("temp", "retry", retryable = true)
                )
            } else {
                TaskStepResult(true, "ok")
            }
        }
        val engine = TaskEngine(store, mapOf(StepKind.Tool to adapter), this)
        engine.submit(ExecutionTask("t1", "demo", "run", listOf(ExecutionStep("s1", "one", StepKind.Tool))))
        advanceUntilIdle()

        engine.retry("t1")
        advanceUntilIdle()

        val task = store.load().single()
        assertEquals(2, task.attempt)
        assertEquals("t1", task.retryOf)
        assertEquals(TaskState.Succeeded, task.state)
    }

    @Test
    fun cancelIsSentOnceAndTaskBecomesCancelled() = runTest {
        val store = InMemoryTaskStore()
        var cancelCalls = 0
        val adapter = object : TaskStepAdapter, TaskCancellationAdapter {
            override suspend fun execute(
                step: ExecutionStep,
                context: TaskExecutionContext
            ): TaskStepResult = TaskStepResult(
                success = false,
                summary = "submitted",
                runtimeTaskId = "rt-1",
                waitReason = "runtime_task"
            )

            override suspend fun cancel(taskId: String): TaskStepResult {
                cancelCalls++
                return TaskStepResult(true, "cancelled")
            }
        }
        val engine = TaskEngine(store, mapOf(StepKind.RuntimeTask to adapter), this)
        engine.submit(ExecutionTask("t1", "demo", "run", listOf(ExecutionStep("s1", "runtime", StepKind.RuntimeTask))))
        advanceUntilIdle()

        engine.cancel("t1")
        engine.cancel("t1")
        advanceUntilIdle()

        assertEquals(1, cancelCalls)
        assertEquals(TaskState.Cancelled, store.load().single().state)
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
    fun resumeWaitingRuntimeTaskDoesNotResubmitStep() = runTest {
        val store = InMemoryTaskStore()
        var executeCalls = 0
        var resyncCalls = 0
        val runtime = object : TaskStepAdapter, TaskResyncAdapter {
            override suspend fun execute(
                step: ExecutionStep,
                context: TaskExecutionContext
            ): TaskStepResult {
                executeCalls += 1
                return TaskStepResult(
                    success = false,
                    summary = "submitted",
                    runtimeTaskId = "rt-1",
                    waitReason = "runtime_task"
                )
            }

            override suspend fun resync(taskId: String): ClawRuntimeTaskSnapshot {
                resyncCalls += 1
                return ClawRuntimeTaskSnapshot(taskId = taskId, state = "Running")
            }
        }
        val engine = TaskEngine(store, mapOf(StepKind.RuntimeTask to runtime), this)
        engine.submit(
            ExecutionTask(
                "t1",
                "demo",
                "run",
                listOf(ExecutionStep("s1", "runtime", StepKind.RuntimeTask))
            )
        )
        advanceUntilIdle()

        engine.pause("t1")
        engine.resume("t1")
        advanceUntilIdle()

        assertEquals(1, executeCalls)
        assertEquals(1, resyncCalls)
        assertEquals(TaskState.WaitingSignal, store.load().single().state)
        assertEquals("rt-1", store.load().single().checkpoint.runtimeTaskId)
    }

    @Test
    fun explicitResumeConfirmationRunsPendingSideEffectingStep() = runTest {
        val store = InMemoryTaskStore()
        var calls = 0
        val adapter = object : TaskStepAdapter {
            override suspend fun execute(
                step: ExecutionStep,
                context: TaskExecutionContext
            ): TaskStepResult {
                calls += 1
                return TaskStepResult(true, "confirmed")
            }
        }
        store.save(
            ExecutionTask(
                id = "t1",
                title = "tap",
                originPrompt = "run",
                steps = listOf(
                    ExecutionStep("s1", "tap", StepKind.SafeTap, sideEffectKey = "tap-1")
                ),
                state = TaskState.WaitingSignal,
                checkpoint = TaskCheckpoint(waitingReason = "resume_confirmation_required")
            )
        )
        val engine = TaskEngine(store, mapOf(StepKind.SafeTap to adapter), this)

        engine.resume("t1")
        advanceUntilIdle()

        assertEquals(1, calls)
        assertEquals(TaskState.Succeeded, store.load().single().state)
    }

    @Test
    fun runtimeWaitingSignalCannotBeResumedAsLocalStep() = runTest {
        val store = InMemoryTaskStore()
        var calls = 0
        val adapter = object : TaskStepAdapter {
            override suspend fun execute(
                step: ExecutionStep,
                context: TaskExecutionContext
            ): TaskStepResult {
                calls += 1
                return TaskStepResult(true, "unexpected")
            }
        }
        store.save(
            ExecutionTask(
                id = "t1",
                title = "runtime",
                originPrompt = "run",
                steps = listOf(ExecutionStep("s1", "runtime", StepKind.RuntimeTask)),
                state = TaskState.WaitingSignal,
                checkpoint = TaskCheckpoint(runtimeTaskId = "rt-1", waitingReason = "runtime_task")
            )
        )
        val engine = TaskEngine(store, mapOf(StepKind.RuntimeTask to adapter), this)

        engine.resume("t1")
        advanceUntilIdle()

        assertEquals(0, calls)
        assertEquals(TaskState.WaitingSignal, store.load().single().state)
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
