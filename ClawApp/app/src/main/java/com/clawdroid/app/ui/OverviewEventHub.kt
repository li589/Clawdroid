package com.clawdroid.app.ui

import com.clawdroid.app.fault.FaultIsolation
import com.clawdroid.app.fault.safeLaunch
import com.clawdroid.app.runtime.ClawRuntimeEventFrame
import com.clawdroid.app.runtime.RuntimeEventService
import kotlinx.coroutines.CoroutineScope

/**
 * Overview UI adapter over [RuntimeEventService].
 * Does not own the Runtime subscription socket.
 */
internal class OverviewEventHub(
    private val eventService: RuntimeEventService,
    private val scope: CoroutineScope,
    private val updateEventState: ((OverviewEventState) -> OverviewEventState) -> Unit,
    private val onEventFrame: (ClawRuntimeEventFrame) -> Unit,
    private val onUnhandledError: (tag: String, message: String) -> Unit = { _, _ -> }
) {
    private val frameListener = RuntimeEventService.FrameListener { frame ->
        onEventFrame(frame)
        val line = "${formatEpochSeconds(frame.timestamp)}  ${summarizeEventFrame(frame)}"
        updateEventState { current ->
            current.copy(
                eventLines = (listOf(line) + current.eventLines).take(24),
                eventLogVersion = current.eventLogVersion + 1,
                eventStreaming = true
            )
        }
    }

    init {
        eventService.addListener(frameListener)
    }

    fun start(
        onStarted: ((String) -> Unit)? = null,
        onClosed: ((String) -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null
    ) {
        scope.safeLaunch(
            "overview:events-start",
            onError = { error ->
                val message = FaultIsolation.formatIsolatedError("overview:events-start", error)
                updateEventState { it.copy(eventStatus = message, eventStreaming = false) }
                onUnhandledError("overview:events-start", message)
            }
        ) {
            updateEventState {
                it.copy(
                    eventLines = emptyList(),
                    eventStatus = "连接中...",
                    eventStreaming = true,
                    eventLogVersion = it.eventLogVersion + 1
                )
            }
            eventService.start(
                onStarted = { message ->
                    updateEventState {
                        it.copy(
                            eventStreaming = true,
                            eventStatus = eventService.statusText
                        )
                    }
                    onStarted?.invoke(message)
                },
                onClosed = { reason ->
                    updateEventState {
                        it.copy(
                            eventStreaming = false,
                            eventStatus = "已停止: $reason"
                        )
                    }
                    onClosed?.invoke(reason)
                },
                onFailure = { message ->
                    updateEventState {
                        it.copy(
                            eventStreaming = false,
                            eventStatus = "失败: $message"
                        )
                    }
                    onFailure?.invoke(message)
                }
            )
        }
    }

    fun stop(onStopped: (() -> Unit)? = null) {
        scope.safeLaunch(
            "overview:events-stop",
            onError = { error ->
                val message = FaultIsolation.formatIsolatedError("overview:events-stop", error)
                updateEventState { it.copy(eventStatus = message, eventStreaming = false) }
                onUnhandledError("overview:events-stop", message)
            }
        ) {
            eventService.stop {
                updateEventState {
                    it.copy(
                        eventStreaming = false,
                        eventStatus = "已手动停止"
                    )
                }
                onStopped?.invoke()
            }
        }
    }

    /** Unregister UI listener only; does not stop the process-scoped subscription. */
    fun detach() {
        eventService.removeListener(frameListener)
    }
}
