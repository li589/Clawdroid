package com.clawdroid.app.agent.task

import com.clawdroid.app.data.TaskStore
import com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot
import com.clawdroid.app.tools.adapters.TaskCancellationAdapter
import com.clawdroid.app.tools.adapters.TaskExecutionContext
import com.clawdroid.app.tools.adapters.TaskResyncAdapter
import com.clawdroid.app.tools.adapters.TaskStepAdapter
import com.clawdroid.app.tools.adapters.TaskStepResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TaskEngine(
    private val store: TaskStore,
    private val adapters: Map<StepKind, TaskStepAdapter>,
    private val scope: CoroutineScope,
    private val eventBus: TaskEventBus = MutableTaskEventBus()
) {
    private val mutex = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val activeTaskIds = ConcurrentHashMap.newKeySet<String>()
    private val pausedTaskIds = ConcurrentHashMap.newKeySet<String>()
    private val cancelledTaskIds = ConcurrentHashMap.newKeySet<String>()

    val events: SharedFlow<TaskEvent>
        get() = eventBus.events()

    suspend fun submit(task: ExecutionTask) {
        require(task.id.isNotBlank()) { "task id is required" }
        require(task.steps.isNotEmpty()) { "task must contain at least one step" }
        require(activeTaskIds.add(task.id)) { "task ${task.id} is already active" }
        val queued = mutex.withLock {
            val queued = task.copy(
                state = TaskState.Queued,
                failure = null,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            store.save(queued)
            eventBus.publish(TaskEvent(queued.id, TaskState.Queued, "任务已排队"))
            queued
        }
        launchTask(queued.id, alreadyActive = true)
    }

    fun restore(task: ExecutionTask) {
        store.save(task)
    }

    suspend fun pause(taskId: String) {
        mutex.withLock {
            val current = current(taskId) ?: return@withLock
            if (TaskStateMachine.isTerminal(current.state)) return@withLock
            pausedTaskIds.add(taskId)
            val updated = current.copy(state = TaskState.Paused, updatedAtEpochMs = System.currentTimeMillis())
            store.save(updated)
            eventBus.publish(TaskEvent(taskId, TaskState.Paused, "任务已暂停"))
        }
    }

    suspend fun resume(taskId: String) {
        val resumed = mutex.withLock {
            pausedTaskIds.remove(taskId)
            val current = current(taskId) ?: return@withLock false
            if (current.state != TaskState.Paused) return@withLock false
            val updated = current.copy(state = TaskState.Running, updatedAtEpochMs = System.currentTimeMillis())
            store.save(updated)
            eventBus.publish(TaskEvent(taskId, TaskState.Running, "任务已恢复"))
            true
        }
        if (resumed) launchTask(taskId)
    }

    suspend fun retry(taskId: String) {
        val retried = mutex.withLock {
            val current = current(taskId) ?: return@withLock false
            if (current.state != TaskState.Failed && current.state != TaskState.Cancelled) return@withLock false
            if (jobs[taskId]?.isActive == true) return@withLock false
            cancelledTaskIds.remove(taskId)
            pausedTaskIds.remove(taskId)
            val updated = current.copy(
                state = TaskState.Queued,
                attempt = current.attempt + 1,
                retryOf = current.id,
                checkpoint = current.checkpoint.copy(
                    stepAttempts = current.checkpoint.stepAttempts.filterKeys { it in current.checkpoint.completedStepIds },
                    waitingReason = null
                ),
                failure = null,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            store.save(updated)
            eventBus.publish(TaskEvent(taskId, TaskState.Queued, "任务重试已排队"))
            activeTaskIds.add(taskId)
            true
        }
        if (retried) launchTask(taskId, alreadyActive = true)
    }

    suspend fun cancel(taskId: String) {
        if (!cancelledTaskIds.add(taskId)) return
        val runtimeTaskId = current(taskId)?.checkpoint?.runtimeTaskId
        jobs[taskId]?.cancel(CancellationException("用户取消任务: $taskId"))
        pausedTaskIds.remove(taskId)
        if (runtimeTaskId != null) {
            runCatching {
                (adapters[StepKind.RuntimeTask] as? TaskCancellationAdapter)?.cancel(runtimeTaskId)
            }
        }
        mutex.withLock {
            val current = current(taskId) ?: return@withLock
            if (TaskStateMachine.isTerminal(current.state)) return@withLock
            val updated = current.copy(
                state = TaskState.Cancelled,
                failure = TaskFailure(
                    code = "user_cancelled",
                    summary = "任务已取消",
                    source = "task_engine"
                ),
                updatedAtEpochMs = System.currentTimeMillis()
            )
            store.save(updated)
            eventBus.publish(TaskEvent(taskId, TaskState.Cancelled, "任务已取消"))
        }
    }

    suspend fun restoreAll() {
        val tasks = store.load().filterNot { TaskStateMachine.isTerminal(it.state) }
        for (task in tasks) {
            val runtimeTaskId = task.checkpoint.runtimeTaskId
            if (runtimeTaskId != null) {
                val snapshot = (adapters[StepKind.RuntimeTask] as? TaskResyncAdapter)?.resync(runtimeTaskId)
                if (snapshot != null) {
                    applyRuntimeSnapshot(snapshot)
                    continue
                }
            }
            val current = current(task.id) ?: continue
            if (current.state == TaskState.Paused || current.state == TaskState.WaitingSignal) continue
            val step = current.steps.getOrNull(current.checkpoint.nextStepIndex)
            if (step?.sideEffectKey != null && step.id !in current.checkpoint.completedStepIds) {
                update(task.id) {
                    it.copy(
                        state = TaskState.WaitingSignal,
                        checkpoint = it.checkpoint.copy(waitingReason = "resume_confirmation_required"),
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                }
                eventBus.publish(TaskEvent(task.id, TaskState.WaitingSignal, "恢复前需要确认非幂等步骤"))
                continue
            }
            val resumed = update(task.id) {
                it.copy(state = TaskState.Running, updatedAtEpochMs = System.currentTimeMillis())
            } ?: continue
            if (activeTaskIds.add(resumed.id)) launchTask(resumed.id, alreadyActive = true)
        }
    }

    fun current(taskId: String): ExecutionTask? =
        store.load().firstOrNull { it.id == taskId }

    suspend fun applyRuntimeSnapshot(snapshot: ClawRuntimeTaskSnapshot) {
        val runtimeState = snapshot.state.lowercase()
        var taskToContinue: String? = null
        mutex.withLock {
            val task = store.load().firstOrNull {
                it.id == snapshot.taskId || it.checkpoint.runtimeTaskId == snapshot.taskId
            } ?: return@withLock
            if (TaskStateMachine.isTerminal(task.state)) return@withLock
            val updated = when (runtimeState) {
                "succeeded" -> {
                    val step = task.steps.getOrNull(task.checkpoint.nextStepIndex)
                    val checkpoint = if (step?.kind == StepKind.RuntimeTask) {
                        task.checkpoint.copy(
                            nextStepIndex = task.checkpoint.nextStepIndex + 1,
                            completedStepIds = task.checkpoint.completedStepIds + step.id,
                            waitingReason = null
                        )
                    } else {
                        task.checkpoint.copy(waitingReason = null)
                    }
                    val state = if (checkpoint.nextStepIndex >= task.steps.size) {
                        TaskState.Succeeded
                    } else {
                        TaskState.Running
                    }
                    task.copy(
                        state = state,
                        checkpoint = checkpoint,
                        failure = null,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                }
                "failed" -> task.copy(
                    state = TaskState.Failed,
                    failure = TaskFailure(
                        code = if (snapshot.errorCode != 0) "runtime_${snapshot.errorCode}" else "runtime_task_failed",
                        summary = snapshot.error.ifBlank { "Runtime 任务失败" },
                        detail = snapshot.summaryLine(),
                        retryable = false,
                        source = "runtime_task"
                    ),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
                "cancelled" -> task.copy(
                    state = TaskState.Cancelled,
                    failure = TaskFailure(
                        code = "runtime_task_cancelled",
                        summary = "Runtime 任务已取消",
                        detail = snapshot.summaryLine(),
                        source = "runtime_task"
                    ),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
                else -> task.copy(
                    state = if (task.state == TaskState.Paused) TaskState.Paused else TaskState.Running,
                    checkpoint = task.checkpoint.copy(runtimeTaskId = snapshot.taskId),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            }
            store.save(updated)
            eventBus.publish(TaskEvent(updated.id, updated.state, snapshot.summaryLine()))
            if (runtimeState == "succeeded" && updated.state == TaskState.Running) {
                taskToContinue = updated.id
            }
        }
        taskToContinue?.let(::launchTask)
    }

    private suspend fun runTask(taskId: String) {
        val running = current(taskId)?.takeIf { it.state == TaskState.Running }
            ?: transition(taskId, TaskState.Running, "任务执行中")
            ?: return
        var task = running
        while (task.checkpoint.nextStepIndex < task.steps.size) {
            if (taskId in pausedTaskIds) {
                update(taskId) {
                    it.copy(state = TaskState.Paused, updatedAtEpochMs = System.currentTimeMillis())
                }
                eventBus.publish(TaskEvent(taskId, TaskState.Paused, "任务已暂停"))
                return
            }
            val step = task.steps[task.checkpoint.nextStepIndex]
            val attempts = (task.checkpoint.stepAttempts[step.id] ?: 0) + 1
            task = update(taskId) {
                it.copy(
                    state = TaskState.Running,
                    checkpoint = it.checkpoint.copy(
                        stepAttempts = it.checkpoint.stepAttempts + (step.id to attempts)
                    ),
                    failure = null,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            } ?: return
            val context = TaskExecutionContext(task.id, task.attempt, task.checkpoint)
            val result = try {
                val adapter = adapters[step.kind]
                    ?: return fail(task.id, TaskFailure("adapter_missing", "缺少步骤适配器: ${step.kind}", source = step.kind.name))
                adapter.execute(step, context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                TaskStepResult(
                    success = false,
                    summary = "步骤执行异常: ${error.message.orEmpty()}",
                    failure = TaskFailure(
                        code = "adapter_error",
                        summary = "步骤执行异常",
                        detail = error.stackTraceToString(),
                        source = step.kind.name
                    )
                )
            }

            if (result.waitReason != null) {
                update(taskId) {
                    it.copy(
                        state = TaskState.WaitingSignal,
                        checkpoint = it.checkpoint.copy(
                            runtimeTaskId = result.runtimeTaskId ?: it.checkpoint.runtimeTaskId,
                            waitingReason = result.waitReason
                        ),
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                }
                eventBus.publish(TaskEvent(taskId, TaskState.WaitingSignal, result.waitReason))
                return
            }

            if (!result.success) {
                fail(taskId, result.failure ?: TaskFailure("step_failed", result.summary, source = step.kind.name))
                return
            }

            task = update(taskId) {
                it.copy(
                    checkpoint = it.checkpoint.copy(
                        nextStepIndex = it.checkpoint.nextStepIndex + 1,
                        completedStepIds = it.checkpoint.completedStepIds + step.id,
                        runtimeTaskId = result.runtimeTaskId ?: it.checkpoint.runtimeTaskId,
                        waitingReason = null,
                        data = it.checkpoint.data + result.data
                    ),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            } ?: return
            eventBus.publish(TaskEvent(taskId, task.state, "步骤完成: ${step.title}"))
        }
        transition(taskId, TaskState.Succeeded, "任务已完成")
        jobs.remove(taskId)
    }

    private fun launchTask(taskId: String, alreadyActive: Boolean = false) {
        if (!alreadyActive && !activeTaskIds.add(taskId)) return
        val job = scope.launch { runTask(taskId) }
        jobs[taskId] = job
        job.invokeOnCompletion {
            activeTaskIds.remove(taskId)
            jobs.remove(taskId)
        }
    }

    private suspend fun fail(taskId: String, failure: TaskFailure) {
        update(taskId) {
            it.copy(
                state = TaskState.Failed,
                failure = failure,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        }
        eventBus.publish(TaskEvent(taskId, TaskState.Failed, failure.summary))
        jobs.remove(taskId)
    }

    private suspend fun transition(taskId: String, state: TaskState, message: String): ExecutionTask? =
        mutex.withLock {
            val current = current(taskId) ?: return@withLock null
            if (!TaskStateMachine.canTransition(current.state, state)) return@withLock null
            val updated = current.copy(state = state, updatedAtEpochMs = System.currentTimeMillis())
            store.save(updated)
            eventBus.publish(TaskEvent(taskId, state, message))
            updated
        }

    private suspend fun update(
        taskId: String,
        transform: (ExecutionTask) -> ExecutionTask
    ): ExecutionTask? = mutex.withLock {
        val current = current(taskId) ?: return@withLock null
        val updated = transform(current)
        store.save(updated)
        updated
    }
}
