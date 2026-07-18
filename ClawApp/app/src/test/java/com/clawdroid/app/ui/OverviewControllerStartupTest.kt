package com.clawdroid.app.ui

import com.clawdroid.app.env.StartupPermissionState
import com.clawdroid.app.runtime.ClawRuntimeConnectionState
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverviewControllerStartupTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun autoStartFalseDoesNotRunStartupChecks() = runTest {
        val runtimeClient = createRuntimeClientMock()
        val toolExecutor = createToolExecutorMock()
        val environmentGateway = FakeOverviewEnvironmentGateway()

        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = runtimeClient,
            toolExecutor = toolExecutor,
            previewLimitBytes = 1024,
            environmentGateway = environmentGateway,
            autoStart = false
        )

        advanceUntilIdle()

        assertEquals("未执行权限修复", controller.uiState.value.permissionState.permissionActionStatus)
        assertTrue(environmentGateway.calls.isEmpty())
        coVerify(exactly = 0) { toolExecutor.probeSession() }
    }

    @Test
    fun startupRootSuccessAppliesAutomationGrantAndRunsProbe() = runTest {
        val runtimeClient = createRuntimeClientMock()
        val toolExecutor = createToolExecutorMock()
        val environmentGateway = FakeOverviewEnvironmentGateway(
            initialStartupState = StartupPermissionState(
                automationGrantRemembered = true,
                notificationGrantRemembered = true,
                writeSettingsGrantRemembered = true,
                allFilesGrantRemembered = true,
                accessibilityGrantRemembered = true
            )
        ).apply {
            requestRootAccessResult = rootActionResult(success = true, output = "root-ok")
            enqueueProbeStatus(
                overviewEnvironmentStatus(
                    rootGranted = true,
                    notificationPermissionGranted = false,
                    writeSettingsGranted = false,
                    allFilesAccessGranted = false,
                    accessibilityEnabled = false,
                    magiskDaemonRunning = true,
                    magiskModuleInstalled = true,
                    magiskModuleEnabled = true
                )
            )
            enqueueProbeStatus(
                overviewEnvironmentStatus(
                    rootGranted = true,
                    notificationPermissionGranted = true,
                    writeSettingsGranted = true,
                    allFilesAccessGranted = true,
                    accessibilityEnabled = true,
                    magiskDaemonRunning = true,
                    magiskModuleInstalled = true,
                    magiskModuleEnabled = true,
                    runtimeDaemonRunning = true
                )
            )
        }
        coEvery { toolExecutor.probeSession() } returns toolCallResult(
            output = "Runtime Probe 成功",
            sessionSnapshot = sessionSnapshot(
                sessionState = ClawRuntimeConnectionState.Ready,
                sessionTrace = "Disconnected -> Ready",
                authMode = "token"
            )
        )
        coEvery { toolExecutor.getCapabilities() } returns toolCallResult(
            output = "capabilities synced"
        )
        coEvery {
            runtimeClient.startEventSubscription(any(), any(), any(), any())
        } coAnswers {
            val onStarted = arg<(com.clawdroid.app.runtime.ClawRuntimeEventSubscriptionStarted) -> Unit>(1)
            onStarted(eventSubscriptionStarted())
        }

        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = runtimeClient,
            toolExecutor = toolExecutor,
            previewLimitBytes = 1024,
            environmentGateway = environmentGateway
        )

        advanceUntilIdle()

        assertTrue(environmentGateway.calls.indexOf("requestRootAccess") >= 0)
        assertTrue(environmentGateway.calls.indexOf("grantAutomationPermissionsViaRoot") > environmentGateway.calls.indexOf("requestRootAccess"))
        assertTrue(controller.uiState.value.permissionState.permissionActionStatus.contains("自动恢复一键权限"))
        assertTrue(controller.uiState.value.runtimeState.session.summary.contains("Runtime Probe 成功"))
        assertTrue(controller.uiState.value.runtimeState.session.summary.contains("Root/Magisk 检查通过"))
        assertEquals(ClawRuntimeConnectionState.Ready, controller.uiState.value.runtimeState.session.state)
        assertEquals("capabilities synced", controller.uiState.value.runtimeState.capabilityStatus)
        assertTrue(controller.uiState.value.eventState.eventStreaming)
        assertTrue(controller.uiState.value.eventState.eventStatus.contains("自动订阅"))
        assertTrue(environmentGateway.startupState.rootGrantedEver)
        coVerify(exactly = 1) { toolExecutor.probeSession() }
        coVerify(exactly = 1) { toolExecutor.getCapabilities() }
        coVerify(atLeast = 1) { runtimeClient.startEventSubscription(any(), any(), any(), any()) }
    }

    @Test
    fun startupSkipsProbeWhenRootOkButDaemonNotRunning() = runTest {
        val toolExecutor = createToolExecutorMock()
        val environmentGateway = FakeOverviewEnvironmentGateway(
            initialProbeStatus = overviewEnvironmentStatus(
                rootGranted = true,
                magiskDaemonRunning = true,
                magiskModuleInstalled = true,
                magiskModuleEnabled = true,
                runtimeDaemonRunning = false
            )
        ).apply {
            requestRootAccessResult = rootActionResult(success = true, output = "root-ok")
        }

        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = createRuntimeClientMock(),
            toolExecutor = toolExecutor,
            previewLimitBytes = 1024,
            environmentGateway = environmentGateway
        )

        advanceUntilIdle()

        assertTrue(controller.uiState.value.runtimeState.session.summary.contains("跳过自动 Probe"))
        assertEquals(ClawRuntimeConnectionState.Disconnected, controller.uiState.value.runtimeState.session.state)
        assertTrue(controller.uiState.value.runtimeState.capabilityStatus.contains("Runtime 守护未运行"))
        coVerify(exactly = 0) { toolExecutor.probeSession() }
        coVerify(exactly = 0) { toolExecutor.getCapabilities() }
    }

    @Test
    fun startupRootTimeoutDoesNotRememberNegativePromptResult() = runTest {
        val runtimeClient = createRuntimeClientMock()
        val toolExecutor = createToolExecutorMock()
        val environmentGateway = FakeOverviewEnvironmentGateway(
            initialProbeStatus = overviewEnvironmentStatus(
                rootGranted = false,
                magiskDaemonRunning = true,
                magiskModuleInstalled = true,
                magiskModuleEnabled = true
            )
        ).apply {
            requestRootAccessResult = rootActionResult(
                success = false,
                output = "timed-out",
                timedOut = true
            )
        }
        coEvery { toolExecutor.probeSession() } returns toolCallResult(
            output = "probe after timeout",
            sessionSnapshot = sessionSnapshot(sessionState = ClawRuntimeConnectionState.Closed, runtimeLoaded = false)
        )

        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = runtimeClient,
            toolExecutor = toolExecutor,
            previewLimitBytes = 1024,
            environmentGateway = environmentGateway
        )

        advanceUntilIdle()

        assertFalse(environmentGateway.startupState.rootPromptRequestedOnce)
        assertTrue(controller.uiState.value.permissionState.permissionActionStatus.contains("超时"))
        assertFalse(environmentGateway.calls.contains("grantAutomationPermissionsViaRoot"))
        coVerify(exactly = 1) { toolExecutor.probeSession() }
    }

    @Test
    fun startupRootDeniedNonTimeoutRemembersResultAndSkipsAutoGrant() = runTest {
        val runtimeClient = createRuntimeClientMock()
        val toolExecutor = createToolExecutorMock()
        val environmentGateway = FakeOverviewEnvironmentGateway(
            initialStartupState = StartupPermissionState(automationGrantRemembered = true),
            initialProbeStatus = overviewEnvironmentStatus(rootGranted = false)
        ).apply {
            requestRootAccessResult = rootActionResult(
                success = false,
                output = "permission denied"
            )
        }
        coEvery { toolExecutor.probeSession() } returns toolCallResult(
            output = "probe after deny",
            sessionSnapshot = sessionSnapshot(sessionState = ClawRuntimeConnectionState.Closed, runtimeLoaded = false)
        )

        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = runtimeClient,
            toolExecutor = toolExecutor,
            previewLimitBytes = 1024,
            environmentGateway = environmentGateway
        )

        advanceUntilIdle()

        assertTrue(environmentGateway.startupState.rootPromptRequestedOnce)
        assertFalse(environmentGateway.calls.contains("grantAutomationPermissionsViaRoot"))
        assertTrue(controller.uiState.value.permissionState.permissionActionStatus.contains("Root 会话失败(permission denied)"))
        coVerify(exactly = 1) { toolExecutor.probeSession() }
    }

    @Test
    fun refreshConnectionIfStaleReprobesWhenSessionClosed() = runTest {
        val runtimeClient = createRuntimeClientMock()
        val toolExecutor = createToolExecutorMock()
        val environmentGateway = FakeOverviewEnvironmentGateway(
            initialStartupState = StartupPermissionState(rootGrantedEver = true),
            initialProbeStatus = overviewEnvironmentStatus(
                rootGranted = true,
                magiskDaemonRunning = true,
                magiskModuleInstalled = true,
                magiskModuleEnabled = true,
                runtimeDaemonRunning = true
            )
        )
        coEvery { toolExecutor.probeSession() } returns toolCallResult(
            output = "resume probe ok",
            sessionSnapshot = sessionSnapshot(
                sessionState = ClawRuntimeConnectionState.Ready,
                sessionTrace = "Disconnected -> Ready",
                authMode = "token"
            )
        )
        coEvery { toolExecutor.getCapabilities() } returns toolCallResult(output = "resume capabilities")
        coEvery {
            runtimeClient.startEventSubscription(any(), any(), any(), any())
        } coAnswers {
            val onStarted = arg<(com.clawdroid.app.runtime.ClawRuntimeEventSubscriptionStarted) -> Unit>(1)
            onStarted(eventSubscriptionStarted())
        }

        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = runtimeClient,
            toolExecutor = toolExecutor,
            previewLimitBytes = 1024,
            environmentGateway = environmentGateway,
            autoStart = false
        )
        controller.refreshConnectionIfStale(force = true)
        advanceUntilIdle()

        assertEquals(ClawRuntimeConnectionState.Ready, controller.uiState.value.runtimeState.session.state)
        assertEquals("resume capabilities", controller.uiState.value.runtimeState.capabilityStatus)
        assertTrue(controller.uiState.value.runtimeState.session.summary.contains("前台恢复"))
        assertTrue(controller.uiState.value.eventState.eventStreaming)
        coVerify(exactly = 1) { toolExecutor.probeSession() }
        coVerify(exactly = 1) { toolExecutor.getCapabilities() }
    }

    @Test
    fun refreshConnectionIfStaleNoopsWhenAlreadyReady() = runTest {
        val runtimeClient = createRuntimeClientMock()
        val toolExecutor = createToolExecutorMock()
        val environmentGateway = FakeOverviewEnvironmentGateway(
            initialProbeStatus = overviewEnvironmentStatus(
                rootGranted = true,
                magiskDaemonRunning = true,
                magiskModuleInstalled = true,
                magiskModuleEnabled = true,
                runtimeDaemonRunning = true
            )
        )
        coEvery { toolExecutor.probeSession() } returns toolCallResult(
            output = "probe",
            sessionSnapshot = sessionSnapshot(sessionState = ClawRuntimeConnectionState.Ready)
        )
        coEvery { toolExecutor.getCapabilities() } returns toolCallResult(output = "caps")
        coEvery {
            runtimeClient.startEventSubscription(any(), any(), any(), any())
        } coAnswers {
            val onStarted = arg<(com.clawdroid.app.runtime.ClawRuntimeEventSubscriptionStarted) -> Unit>(1)
            onStarted(eventSubscriptionStarted())
        }

        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = runtimeClient,
            toolExecutor = toolExecutor,
            previewLimitBytes = 1024,
            environmentGateway = environmentGateway,
            autoStart = false
        )
        controller.refreshConnectionIfStale(force = true)
        advanceUntilIdle()
        coVerify(exactly = 1) { toolExecutor.probeSession() }

        controller.refreshConnectionIfStale(force = false)
        advanceUntilIdle()

        coVerify(exactly = 1) { toolExecutor.probeSession() }
        coVerify(exactly = 1) { toolExecutor.getCapabilities() }
    }
}
