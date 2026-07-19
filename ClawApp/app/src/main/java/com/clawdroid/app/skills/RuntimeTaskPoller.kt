package com.clawdroid.app.skills

import com.clawdroid.app.runtime.ClawRuntimeTaskSnapshot
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Shared poll helper for waiting on Runtime `task_*` until a terminal state
 * (or poll budget exhausted → detach to event tracking).
 */
object RuntimeTaskPoller {
    const val ERROR_DETACHED = ClawAgentRunner.ERROR_RUNTIME_TASK_DETACHED

    data class AwaitResult(
        val success: Boolean,
        val detached: Boolean,
        val output: String,
        val snapshot: ClawRuntimeTaskSnapshot?,
        val error: String? = null
    )

    suspend fun awaitTerminal(
        dispatcher: ClawToolDispatcher,
        taskId: String,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        pollAttempts: Int = DEFAULT_POLL_ATTEMPTS,
        onSnapshot: ((ClawRuntimeTaskSnapshot) -> Unit)? = null
    ): AwaitResult {
        val id = taskId.trim()
        if (id.isEmpty()) {
            return AwaitResult(
                success = false,
                detached = false,
                output = "失败: task_id 为空",
                snapshot = null,
                error = "missing_task_id"
            )
        }

        var finalSnapshot: ClawRuntimeTaskSnapshot? = null

        repeat(pollAttempts) {
            currentCoroutineContext().ensureActive()
            delay(pollIntervalMs)
            val getResult = dispatcher.execute(
                ClawTool.TASK_GET,
                mapOf("task_id" to id)
            )
            val snapshot = getResult.taskSnapshot ?: return@repeat
            finalSnapshot = snapshot
            onSnapshot?.invoke(snapshot)
            if (snapshot.state.lowercase() in TERMINAL_STATES) {
                val ok = snapshot.state.equals("succeeded", ignoreCase = true)
                return AwaitResult(
                    success = ok,
                    detached = false,
                    output = snapshot.summaryLine(),
                    snapshot = snapshot,
                    error = if (ok) {
                        null
                    } else {
                        snapshot.error.takeIf { it.isNotBlank() } ?: snapshot.state
                    }
                )
            }
        }

        val tracking = finalSnapshot ?: ClawRuntimeTaskSnapshot(
            taskId = id,
            state = "Running"
        )
        onSnapshot?.invoke(tracking)
        // 任务在 Runtime 侧可能仍在运行，本地轮询超时不能等价于成功，
        // 否则只检查 success 的调用方会误判任务已完成。detached=true 与
        // ERROR_DETACHED 仍可供需要继续事件跟踪的调用方区分这种状态。
        return AwaitResult(
            success = false,
            detached = true,
            output = "本地轮询超时，已改为事件跟踪 Runtime 任务 $id（状态=${tracking.state}）。",
            snapshot = tracking,
            error = ERROR_DETACHED
        )
    }

    fun toToolResult(awaited: AwaitResult, runtimeTaskId: String): ClawToolCallResult {
        return ClawToolCallResult(
            success = awaited.success,
            output = awaited.output,
            error = awaited.error,
            runtimeTaskId = runtimeTaskId,
            taskSnapshot = awaited.snapshot
        )
    }

    private const val DEFAULT_POLL_INTERVAL_MS = 500L
    private const val DEFAULT_POLL_ATTEMPTS = 120
    private val TERMINAL_STATES = setOf("succeeded", "failed", "cancelled")
}
