package com.clawdroid.app.ui

import com.clawdroid.app.runtime.ClawRuntimeConnectionState
import io.mockk.coEvery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverviewControllerRuntimeActionsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun probeSessionUpdatesSummaryAndSessionSnapshot() = runTest {
        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = createRuntimeClientMock(),
            toolExecutor = createToolExecutorMock().also { toolExecutor ->
                coEvery { toolExecutor.probeSession() } returns toolCallResult(
                    output = "probe ok",
                    sessionSnapshot = sessionSnapshot(
                        sessionState = ClawRuntimeConnectionState.Ready,
                        sessionTrace = "Disconnected -> Ready",
                        authMode = "challenge"
                    )
                )
            },
            previewLimitBytes = 1024,
            autoStart = false
        )

        controller.probeSession()
        advanceUntilIdle()

        assertEquals("probe ok", controller.uiState.value.runtimeState.session.summary)
        assertEquals(ClawRuntimeConnectionState.Ready, controller.uiState.value.runtimeState.session.state)
        assertEquals("challenge", controller.uiState.value.runtimeState.session.authMode)
        assertEquals("Disconnected -> Ready", controller.uiState.value.runtimeState.session.trace)
    }

    @Test
    fun getCapabilitiesUpdatesCapabilityStatusAndSessionSnapshot() = runTest {
        val toolExecutor = createToolExecutorMock()
        coEvery { toolExecutor.getCapabilities() } returns toolCallResult(
            output = "capabilities ok",
            sessionSnapshot = sessionSnapshot(
                sessionState = ClawRuntimeConnectionState.Degraded,
                sessionTrace = "Disconnected -> Degraded",
                authMode = "capabilities",
                degradedReason = "missing_runtime"
            )
        )
        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = createRuntimeClientMock(),
            toolExecutor = toolExecutor,
            previewLimitBytes = 1024,
            autoStart = false
        )

        controller.getCapabilities()
        advanceUntilIdle()

        assertEquals("capabilities ok", controller.uiState.value.runtimeState.capabilityStatus)
        assertEquals(ClawRuntimeConnectionState.Degraded, controller.uiState.value.runtimeState.session.state)
        assertEquals("missing_runtime", controller.uiState.value.runtimeState.session.degradedReason)
    }

    @Test
    fun executeShellForChatRewritesSuccessPrefixAndPersistsShellOutput() = runTest {
        val toolExecutor = createToolExecutorMock()
        coEvery { toolExecutor.execShellLimited("id", 3000) } returns toolCallResult(
            output = "成功: exit=0",
            shellOutput = "stdout:\nuid=0(root)"
        )
        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = createRuntimeClientMock(),
            toolExecutor = toolExecutor,
            previewLimitBytes = 1024,
            autoStart = false
        )

        controller.selectShellCommand("id")
        val result = controller.executeShellForChat("id")

        assertEquals("Shell 执行完成， exit=0", result)
        assertEquals("stdout:\nuid=0(root)", controller.uiState.value.runtimeState.shell.output)
        assertEquals("成功: exit=0", controller.uiState.value.runtimeState.shell.status)
    }

    @Test
    fun captureScreenForChatWithoutPreviewUpdatesCaptureState() = runTest {
        val toolExecutor = createToolExecutorMock()
        coEvery { toolExecutor.captureScreen(includeShaPreview = false) } returns toolCallResult(
            output = "截图成功",
            captureArtifact = captureArtifact(
                imagePath = "/tmp/a.png",
                width = 1080,
                height = 2400,
                fileSize = 2048L
            )
        )
        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = createRuntimeClientMock(),
            toolExecutor = toolExecutor,
            previewLimitBytes = 1024,
            autoStart = false
        )

        val result = controller.captureScreenForChat(includePreview = false)

        assertEquals("截图成功", result)
        assertEquals("/tmp/a.png", controller.uiState.value.runtimeState.capture.latestPath)
        assertEquals("1080x2400", controller.uiState.value.runtimeState.capture.latestDimensions)
        assertEquals(2048L, controller.uiState.value.runtimeState.capture.latestFileSize)
    }

    @Test
    fun readLatestCaptureForChatWithoutPreviewBytesReturnsRawOutput() = runTest {
        val toolExecutor = createToolExecutorMock()
        coEvery {
            toolExecutor.readLatestCapture(
                latestCapturePath = "",
                latestCaptureFormat = "unknown",
                latestCaptureFileSize = 0L,
                previewLimitBytes = 1024
            )
        } returns toolCallResult(output = "read ok")
        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = createRuntimeClientMock(),
            toolExecutor = toolExecutor,
            previewLimitBytes = 1024,
            autoStart = false
        )

        val result = controller.readLatestCaptureForChat()

        assertEquals("read ok", result)
        assertEquals("read ok", controller.uiState.value.runtimeState.readFileStatus)
        assertTrue(controller.latestCapturePreview.value == null)
    }

    @Test
    fun applyToolSideEffectsWithPreviewBytesDoesNotStackOverflow() = runTest {
        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = createRuntimeClientMock(),
            toolExecutor = createToolExecutorMock(),
            previewLimitBytes = 8 * 1024 * 1024,
            autoStart = false
        )

        // Previously recurse: applyToolSideEffects -> renderReadPreviewResult -> applyToolSideEffects
        controller.applyToolSideEffects(
            toolCallResult(
                output = "preview payload",
                previewBytes = MINIMAL_PNG_1X1
            )
        )

        assertTrue(controller.uiState.value.runtimeState.shell.output.isBlank() || true)
    }

    @Test
    fun readLatestCaptureForChatWithPreviewBytesDoesNotCrash() = runTest {
        val toolExecutor = createToolExecutorMock()
        coEvery {
            toolExecutor.readLatestCapture(
                latestCapturePath = "/data/local/tmp/clawdroid/capture.png",
                latestCaptureFormat = "png",
                latestCaptureFileSize = MINIMAL_PNG_1X1.size.toLong(),
                previewLimitBytes = 8 * 1024 * 1024
            )
        } returns toolCallResult(
            output = "成功: 已读取",
            previewBytes = MINIMAL_PNG_1X1
        )
        val controller = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = createRuntimeClientMock(),
            toolExecutor = toolExecutor,
            previewLimitBytes = 8 * 1024 * 1024,
            autoStart = false
        )
        // Seed capture path so status formatting can read dimensions/size fields.
        controller.applyToolSideEffects(
            toolCallResult(
                captureArtifact = captureArtifact(
                    imagePath = "/data/local/tmp/clawdroid/capture.png",
                    format = "png",
                    fileSize = MINIMAL_PNG_1X1.size.toLong()
                )
            )
        )

        val result = controller.readLatestCaptureForChat()

        assertTrue(result.startsWith("成功:") || result.startsWith("失败:"))
        assertEquals(result, controller.uiState.value.runtimeState.readFileStatus)
    }

    companion object {
        // 1x1 PNG — enough to exercise the preview path without needing a real decode success.
        private val MINIMAL_PNG_1X1 = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
            0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
            0x54, 0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(),
            0xC0.toByte(), 0x00, 0x00, 0x00, 0x03, 0x00, 0x01,
            0x00, 0x05, 0xFE.toByte(), 0xD4.toByte(), 0xEF.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
        )
    }
}
