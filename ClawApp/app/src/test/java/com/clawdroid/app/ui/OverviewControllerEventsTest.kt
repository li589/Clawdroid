package com.clawdroid.app.ui

import com.clawdroid.app.runtime.ClawRuntimeEventFrame
import com.clawdroid.app.runtime.ClawRuntimeEventSubscriptionStarted
import com.clawdroid.app.runtime.RuntimeEventService
import com.clawdroid.app.tools.LiveToolCapabilityStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverviewControllerEventsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun clearLiveCaps() {
        LiveToolCapabilityStore.clear()
    }

    @Test
    fun capabilityChangedEventUpdatesLiveCapabilityStore() = runTest {
        val runtimeClient = createRuntimeClientMock()
        val toolExecutor = createToolExecutorMock()
        val onEventSlot = slot<(ClawRuntimeEventFrame) -> Unit>()
        coEvery {
            runtimeClient.startEventSubscription(any(), any(), capture(onEventSlot), any())
        } coAnswers {
            onEventSlot.captured.invoke(
                eventFrame(
                    event = "capability_changed",
                    data = mapOf(
                        "root" to true,
                        "accessibility" to true,
                        "lsposed_runtime_loaded" to true,
                        "capabilities" to listOf("screen.capture", "event.subscribe")
                    )
                )
            )
        }

        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = runtimeClient,
            toolExecutor = toolExecutor,
            previewLimitBytes = 1024,
            autoStart = false,
            // 注入与 Main 同一 TestDispatcher 的 eventService，
            // 让 fan-out 协程可被 advanceUntilIdle() 推进到 listener 回调。
            eventService = RuntimeEventService(
                runtimeClient = runtimeClient,
                scope = backgroundScope,
                fanoutDispatcher = mainDispatcherRule.dispatcher
            )
        )
        controller.startContinuousSubscription()
        advanceUntilIdle()

        assertEquals(
            setOf("screen.capture", "event.subscribe"),
            LiveToolCapabilityStore.snapshot()
        )
        assertTrue(controller.uiState.value.runtimeState.capabilityStatus.contains("capability_changed"))
        assertTrue(controller.uiState.value.runtimeState.capabilityStatus.contains("screen.capture"))

        controller.stopContinuousSubscription()
        advanceUntilIdle()
    }

    @Test
    fun startContinuousSubscriptionClearsSeedAndCapsEventLog() = runTest {
        val runtimeClient = createRuntimeClientMock()
        val toolExecutor = createToolExecutorMock()
        val onStartedSlot = slot<(ClawRuntimeEventSubscriptionStarted) -> Unit>()
        val onEventSlot = slot<(ClawRuntimeEventFrame) -> Unit>()
        val onClosedSlot = slot<(String) -> Unit>()
        coEvery {
            runtimeClient.startEventSubscription(
                any(),
                capture(onStartedSlot),
                capture(onEventSlot),
                capture(onClosedSlot)
            )
        } coAnswers {
            onStartedSlot.captured.invoke(eventSubscriptionStarted(subscribed = listOf("window_changed")))
            // 生产环境 Runtime 事件以秒级间隔到达；fanout 是 CONFLATED 通道，
            // 同步连发 25 帧只会保留最后一帧。用 yield() 让消费者在每帧之间
            // 有机会取出并派发给 listener，从而真实模拟事件流并验证 24 行截断逻辑。
            repeat(25) { index ->
                onEventSlot.captured.invoke(
                    eventFrame(
                        event = "window_changed",
                        timestamp = 1_720_000_000L + index,
                        data = mapOf(
                            "focused_window" to """{"summary":"Window-${index + 1}","source":"wm"}"""
                        )
                    )
                )
                yield()
            }
        }

        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = runtimeClient,
            toolExecutor = toolExecutor,
            previewLimitBytes = 1024,
            autoStart = false,
            eventService = RuntimeEventService(
                runtimeClient = runtimeClient,
                scope = backgroundScope,
                fanoutDispatcher = mainDispatcherRule.dispatcher
            )
        )
        controller.applyDebugLongOverviewSeed()

        controller.startContinuousSubscription()
        advanceUntilIdle()

        val eventState = controller.uiState.value.eventState
        assertEquals(
            "status=${eventState.eventStatus}, streaming=${eventState.eventStreaming}, lines=${eventState.eventLines.size}",
            24,
            eventState.eventLines.size
        )
        assertTrue(
            "status=${eventState.eventStatus}",
            eventState.eventStatus.contains("订阅中")
        )
        assertTrue(
            "status=${eventState.eventStatus}, streaming=${eventState.eventStreaming}",
            eventState.eventStreaming
        )
        assertTrue(eventState.eventLines.first().contains("window=Window-25@wm"))
        assertFalse(eventState.eventLines.any { it.contains("debug-seed-long-event-log") })
        assertEquals("Window-25@wm", controller.uiState.value.runtimeState.latestWindowSummary)

        controller.stopContinuousSubscription()
        advanceUntilIdle()
    }

    @Test
    fun daemonStatusEventUpdatesMetricsAndErrorSummary() = runTest {
        val runtimeClient = createRuntimeClientMock()
        val toolExecutor = createToolExecutorMock()
        val onEventSlot = slot<(ClawRuntimeEventFrame) -> Unit>()
        coEvery {
            runtimeClient.startEventSubscription(any(), any(), capture(onEventSlot), any())
        } coAnswers {
            onEventSlot.captured.invoke(
                eventFrame(
                    event = "daemon_status_changed",
                    data = mapOf(
                        "load_1" to 1.25,
                        "load_5" to 2.5,
                        "mem_total_kb" to 4096,
                        "mem_available_kb" to 1024,
                        "runtime_pid" to 4321,
                        "runtime_rss_kb" to 512,
                        "last_error" to "broken pipe",
                        "last_error_at" to 1700000000,
                        "last_rate_limit" to "none",
                        "last_rate_limit_at" to 0,
                        "rate_limit_hits" to 3,
                        "readonly_whitelist" to listOf("/data/local/tmp/clawdroid")
                    )
                )
            )
        }

        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = runtimeClient,
            toolExecutor = toolExecutor,
            previewLimitBytes = 1024,
            autoStart = false,
            eventService = RuntimeEventService(
                runtimeClient = runtimeClient,
                scope = backgroundScope,
                fanoutDispatcher = mainDispatcherRule.dispatcher
            )
        )

        controller.startContinuousSubscription()
        advanceUntilIdle()

        assertEquals("load=1.25/2.50, mem=3.00MB/4.00MB", controller.uiState.value.runtimeState.latestDaemonMetrics)
        assertEquals("pid=4321, rss=512.0KB", controller.uiState.value.runtimeState.latestRuntimeProcessMetrics)
        assertTrue(controller.uiState.value.runtimeState.lastErrorStatus.contains("broken pipe"))
        assertTrue(controller.uiState.value.runtimeState.lastErrorStatus.contains("限流次数: 3"))

        controller.stopContinuousSubscription()
        advanceUntilIdle()
    }

    @Test
    fun subscriptionFailureSetsFailedStatus() = runTest {
        val runtimeClient = createRuntimeClientMock()
        val toolExecutor = createToolExecutorMock()
        coEvery { runtimeClient.startEventSubscription(any(), any(), any(), any()) } throws IllegalStateException("boom")

        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = runtimeClient,
            toolExecutor = toolExecutor,
            previewLimitBytes = 1024,
            autoStart = false
        )

        controller.startContinuousSubscription()
        advanceUntilIdle()

        assertFalse(controller.uiState.value.eventState.eventStreaming)
        assertEquals("失败: boom", controller.uiState.value.eventState.eventStatus)

        controller.stopContinuousSubscription()
        advanceUntilIdle()
    }

    @Test
    fun stopContinuousSubscriptionSetsManualStopStatus() = runTest {
        val runtimeClient = createRuntimeClientMock()
        val toolExecutor = createToolExecutorMock()
        val onStartedSlot = slot<(ClawRuntimeEventSubscriptionStarted) -> Unit>()
        coEvery {
            runtimeClient.startEventSubscription(any(), capture(onStartedSlot), any(), any())
        } coAnswers {
            onStartedSlot.captured.invoke(eventSubscriptionStarted())
        }

        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = runtimeClient,
            toolExecutor = toolExecutor,
            previewLimitBytes = 1024,
            autoStart = false
        )

        controller.startContinuousSubscription()
        advanceUntilIdle()
        controller.stopContinuousSubscription()
        advanceUntilIdle()

        assertFalse(controller.uiState.value.eventState.eventStreaming)
        assertEquals("已手动停止", controller.uiState.value.eventState.eventStatus)
        coVerify(atLeast = 2) { runtimeClient.stopEventSubscription() }
    }
}
