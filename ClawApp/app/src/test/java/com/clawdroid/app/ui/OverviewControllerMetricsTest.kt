package com.clawdroid.app.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class OverviewControllerMetricsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun setOverviewActiveStartsSamplingAndUsesRootFlag() = runTest(timeout = 10.seconds) {
        val metricsSampler = FakeOverviewMetricsSampler()
        val environmentGateway = FakeOverviewEnvironmentGateway(
            initialProbeStatus = overviewEnvironmentStatus(rootGranted = true)
        )
        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = createRuntimeClientMock(),
            toolExecutor = createToolExecutorMock(),
            previewLimitBytes = 1024,
            environmentGateway = environmentGateway,
            metricsSampler = metricsSampler,
            autoStart = false
        )

        try {
            controller.refreshLocalEnvironmentState(includeRootCheck = true)
            controller.setOverviewActive(true)
            runCurrent()
            advanceTimeBy(2001)

            assertEquals(3, metricsSampler.sampleCount)
            assertEquals(listOf(true, true, true), metricsSampler.requestedRootFlags)
            assertEquals("sample-3", controller.dashboardMetrics.value.note)
        } finally {
            controller.setOverviewActive(false)
            runCurrent()
        }
    }

    @Test
    fun setOverviewActiveTwiceDoesNotStartDuplicateJob() = runTest(timeout = 10.seconds) {
        val metricsSampler = FakeOverviewMetricsSampler()
        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = createRuntimeClientMock(),
            toolExecutor = createToolExecutorMock(),
            previewLimitBytes = 1024,
            metricsSampler = metricsSampler,
            autoStart = false
        )

        try {
            controller.setOverviewActive(true)
            controller.setOverviewActive(true)
            runCurrent()
            advanceTimeBy(1001)

            assertEquals(2, metricsSampler.sampleCount)
        } finally {
            controller.setOverviewActive(false)
            runCurrent()
        }
    }

    @Test
    fun setOverviewActiveFalseStopsSampling() = runTest(timeout = 10.seconds) {
        val metricsSampler = FakeOverviewMetricsSampler()
        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = createRuntimeClientMock(),
            toolExecutor = createToolExecutorMock(),
            previewLimitBytes = 1024,
            metricsSampler = metricsSampler,
            autoStart = false
        )

        controller.setOverviewActive(true)
        runCurrent()
        advanceTimeBy(1001)
        controller.setOverviewActive(false)
        runCurrent()
        val countAfterStop = metricsSampler.sampleCount
        advanceTimeBy(3000)

        assertEquals(countAfterStop, metricsSampler.sampleCount)
    }
}
