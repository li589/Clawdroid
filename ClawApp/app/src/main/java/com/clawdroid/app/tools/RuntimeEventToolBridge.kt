package com.clawdroid.app.tools

import com.clawdroid.app.runtime.RuntimeEventService
import kotlin.coroutines.resume
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * MCP / tool-facing bridge over [RuntimeEventService].
 * Start is idempotent when the stream is already live; late [onClosed] after a
 * successful start must not flip a successful tool result into failure.
 */
class RuntimeEventToolBridge(
    private val eventService: RuntimeEventService,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) : ClawToolDispatcher.EventBridge {
    override suspend fun handle(operation: String): ClawToolCallResult {
        return try {
            withTimeout(timeoutMs) {
                when (operation.lowercase()) {
                    "stop" -> suspendCancellableCoroutine { continuation ->
                        eventService.stop {
                            if (continuation.isActive) {
                                continuation.resume(
                                    ClawToolCallResult(success = true, output = "事件流已停止")
                                )
                            }
                        }
                    }
                    else -> suspendCancellableCoroutine { continuation ->
                        var started = false
                        eventService.start(
                            forceRestart = false,
                            onStarted = { message ->
                                started = true
                                if (continuation.isActive) {
                                    continuation.resume(
                                        ClawToolCallResult(success = true, output = message)
                                    )
                                }
                            },
                            onClosed = { reason ->
                                // Only treat close as start failure when we never connected.
                                if (!started && continuation.isActive) {
                                    continuation.resume(
                                        ClawToolCallResult(
                                            success = false,
                                            output = "事件流已关闭：$reason",
                                            error = reason
                                        )
                                    )
                                }
                            },
                            onFailure = { message ->
                                if (!started && continuation.isActive) {
                                    continuation.resume(
                                        ClawToolCallResult(
                                            success = false,
                                            output = "事件订阅失败：$message",
                                            error = message
                                        )
                                    )
                                }
                            }
                        )
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            ClawToolCallResult(
                success = false,
                output = "事件桥接超时（${timeoutMs}ms）",
                error = "event_bridge_timeout"
            )
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
