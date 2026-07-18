package com.clawdroid.app.runtime

import com.clawdroid.app.fault.FaultIsolation
import com.clawdroid.app.fault.safeLaunch
import com.clawdroid.app.tools.LiveToolCapabilityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-scoped Runtime event subscription.
 * Owns the single [ClawRuntimeClient.startEventSubscription] job and fans frames to listeners.
 * Updates [LiveToolCapabilityStore] on `capability_changed` before fan-out.
 */
class RuntimeEventService(
    private val runtimeClient: ClawRuntimeClient,
    private val scope: CoroutineScope
) {
    fun interface FrameListener {
        fun onFrame(frame: ClawRuntimeEventFrame)
    }

    private val listeners = CopyOnWriteArraySet<FrameListener>()
    private var subscriptionJob: Job? = null

    @Volatile
    var streaming: Boolean = false
        private set

    @Volatile
    var statusText: String = "未订阅"
        private set

    fun addListener(listener: FrameListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: FrameListener) {
        listeners.remove(listener)
    }

    fun start(
        onStarted: ((String) -> Unit)? = null,
        onClosed: ((String) -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null
    ) {
        scope.safeLaunch(
            "runtime:events-start",
            onError = { error ->
                val message = FaultIsolation.formatIsolatedError("runtime:events-start", error)
                streaming = false
                statusText = message
                onFailure?.invoke(message)
            }
        ) {
            subscriptionJob?.cancelAndJoin()
            runtimeClient.stopEventSubscription()
            streaming = true
            statusText = "连接中..."
            subscriptionJob = scope.launch {
                try {
                    runtimeClient.startEventSubscription(
                        events = DEFAULT_EVENTS,
                        onStarted = { started ->
                            streaming = true
                            statusText =
                                "订阅中: mode=${started.streamMode}, interval=${started.pollIntervalMs}ms, events=${started.subscribed.joinToString()}"
                            onStarted?.invoke(
                                "事件流已连接，轮询间隔 ${started.pollIntervalMs}ms。"
                            )
                        },
                        onEvent = { frame ->
                            if (frame.event == "capability_changed") {
                                LiveToolCapabilityStore.updateFromEventData(frame.data)
                            }
                            listeners.forEach { listener ->
                                runCatching { listener.onFrame(frame) }
                            }
                        },
                        onClosed = { reason ->
                            streaming = false
                            statusText = "已停止: $reason"
                            onClosed?.invoke(reason)
                        }
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    val message = error.message ?: error::class.java.simpleName
                    streaming = false
                    statusText = "失败: $message"
                    onFailure?.invoke(message)
                }
            }
        }
    }

    fun stop(onStopped: (() -> Unit)? = null) {
        scope.safeLaunch(
            "runtime:events-stop",
            onError = { error ->
                val message = FaultIsolation.formatIsolatedError("runtime:events-stop", error)
                streaming = false
                statusText = message
                onStopped?.invoke()
            }
        ) {
            runtimeClient.stopEventSubscription()
            subscriptionJob?.cancelAndJoin()
            subscriptionJob = null
            streaming = false
            statusText = "已手动停止"
            onStopped?.invoke()
        }
    }

    /** Tear down without UI callbacks (process / composition dispose). */
    fun shutdown() {
        runtimeClient.stopEventSubscription()
        subscriptionJob?.cancel()
        subscriptionJob = null
        streaming = false
        statusText = "未订阅"
        listeners.clear()
    }

    companion object {
        val DEFAULT_EVENTS = listOf(
            "daemon_status_changed",
            "capability_changed",
            "window_changed",
            "task_state_changed",
            "xposed_focus_changed",
            "xposed_view_changed"
        )
    }
}
