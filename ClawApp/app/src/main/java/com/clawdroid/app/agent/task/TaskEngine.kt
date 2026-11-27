package com.clawdroid.app.agent.task

import com.clawdroid.app.data.TaskStore
import com.clawdroid.app.tools.adapters.TaskExecutionContext
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
        val job = scope.launch { runTask(queued.id) }
        jobs[queued.id] = job
        job.invokeOnCompletion {
            activeTaskIds.remove(queued.id)
            jobs.remove(queued.id)
        }
    }

    fun restore(task: ExecutionTask) {
        store.save(task)
    }

    fun current(taskId: String): ExecutionTask? =
        store.load().firstOrNull { it.id == taskId }

    private suspend fun runTask(taskId: String) {
        val running = transition(taskId, TaskState.Running, "任务执行中") ?: return
        var task = running
        while (task.checkpoint.nextStepIndex < task.steps.size) {
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
                        waitingReason = null
                    ),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            } ?: return
            eventBus.publish(TaskEvent(taskId, task.state, "步骤完成: ${step.title}"))
        }
        transition(taskId, TaskState.Succeeded, "任务已完成")
        jobs.remove(taskId)
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
