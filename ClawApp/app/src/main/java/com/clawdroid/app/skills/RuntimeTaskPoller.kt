package com.clawdroid.app.skills

import com.clawdroid.app.runtime.ClawRuntimeTaskSnapshot
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolDispatcher
import kotlinx.coroutines.CancellationException
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
        timeoutMs: Long? = null,
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

        val effectiveAttempts = if (timeoutMs != null && timeoutMs > 0L) {
            // Adaptive backoff averages ~1s/attempt over long waits; keep a floor of 4 polls.
            ((timeoutMs / pollIntervalMs.coerceAtLeast(100L)).toInt()).coerceIn(4, 1_200)
        } else {
            pollAttempts
        }

        var finalSnapshot: ClawRuntimeTaskSnapshot? = null
        var consecutiveMisses = 0

        try {
            repeat(effectiveAttempts) { attempt ->
                currentCoroutineContext().ensureActive()
                val waitMs = when {
                    attempt < 20 -> pollIntervalMs
                    attempt < 60 -> pollIntervalMs * 2
                    else -> pollIntervalMs * 4
                }
                delay(waitMs)
                val getResult = runCatching {
                    dispatcher.execute(ClawTool.TASK_GET, mapOf("task_id" to id))
                }.getOrNull()
                val snapshot = getResult?.taskSnapshot
                if (snapshot == null) {
                    consecutiveMisses += 1
                    // After several misses, try task_list as a fallback lookup.
                    if (consecutiveMisses >= 3 && consecutiveMisses % 3 == 0) {
                        val listed = runCatching {
                            dispatcher.execute(ClawTool.TASK_LIST)
                        }.getOrNull()?.taskSnapshots?.firstOrNull { it.taskId == id }
                        if (listed != null) {
                            consecutiveMisses = 0
                            finalSnapshot = listed
                            onSnapshot?.invoke(listed)
                            if (listed.state.lowercase() in TERMINAL_STATES) {
                                return terminalResult(listed)
                            }
                        }
                    }
                    return@repeat
                }
                consecutiveMisses = 0
                finalSnapshot = snapshot
                onSnapshot?.invoke(snapshot)
                if (snapshot.state.lowercase() in TERMINAL_STATES) {
                    return terminalResult(snapshot)
                }
            }
        } catch (cancelled: CancellationException) {
            runCatching {
                dispatcher.execute(ClawTool.TASK_CANCEL, mapOf("task_id" to id))
            }
            throw cancelled
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
            output = "本地轮询超时，已改为事件跟踪 Runtime 任务 $id（状态=${tracking.state}）。请保持事件订阅以接收终态。",
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

    /** Prefer explicit runtimeTaskId, then snapshot, then parse from submit output. */
    fun resolveTaskId(submitResult: ClawToolCallResult): String? {
        submitResult.runtimeTaskId?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        submitResult.taskSnapshot?.taskId?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val output = submitResult.output
        val patterns = listOf(
            Regex("""task[_ ]?id["'\s:=]+([A-Za-z0-9_.:-]+)""", RegexOption.IGNORE_CASE),
            Regex("""Runtime 任务\s+([A-Za-z0-9_.:-]+)"""),
            Regex("""\btask[-_]([A-Za-z0-9_.:-]+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(output)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (match.isNotBlank()) return match
        }
        return null
    }

    private fun terminalResult(snapshot: ClawRuntimeTaskSnapshot): AwaitResult {
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

    // ~3 minutes with adaptive backoff (500ms → 1s → 2s).
    private const val DEFAULT_POLL_INTERVAL_MS = 500L
    private const val DEFAULT_POLL_ATTEMPTS = 180
    private val TERMINAL_STATES = setOf("succeeded", "failed", "cancelled")
}
