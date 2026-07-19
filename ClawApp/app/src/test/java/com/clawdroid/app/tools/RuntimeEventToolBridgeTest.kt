package com.clawdroid.app.tools

import com.clawdroid.app.runtime.RuntimeEventService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEventToolBridgeTest {
    @Test
    fun startSucceedsWhenServiceReportsStarted() = runBlocking {
        val service = mockk<RuntimeEventService>()
        val onStarted = slot<(String) -> Unit>()
        every {
            service.start(
                forceRestart = false,
                onStarted = capture(onStarted),
                onClosed = any(),
                onFailure = any()
            )
        } answers {
            onStarted.captured.invoke("事件流已连接，轮询间隔 1000ms。")
        }

        val bridge = RuntimeEventToolBridge(service, timeoutMs = 2_000L)
        val result = bridge.handle("start")
        assertTrue(result.success)
        assertTrue(result.output.contains("事件流已连接"))
    }

    @Test
    fun lateOnClosedAfterStartDoesNotFailToolResult() = runBlocking {
        val service = mockk<RuntimeEventService>()
        val onStarted = slot<(String) -> Unit>()
        val onClosed = slot<(String) -> Unit>()
        every {
            service.start(
                forceRestart = false,
                onStarted = capture(onStarted),
                onClosed = capture(onClosed),
                onFailure = any()
            )
        } answers {
            onStarted.captured.invoke("事件流已连接")
            onClosed.captured.invoke("connection_error: ")
        }

        val bridge = RuntimeEventToolBridge(service, timeoutMs = 2_000L)
        val result = bridge.handle("start")
        assertTrue(result.success)
        assertFalse(result.output.contains("事件流已关闭"))
    }

    @Test
    fun onClosedBeforeStartIsReportedAsFailure() = runBlocking {
        val service = mockk<RuntimeEventService>()
        val onClosed = slot<(String) -> Unit>()
        every {
            service.start(
                forceRestart = false,
                onStarted = any(),
                onClosed = capture(onClosed),
                onFailure = any()
            )
        } answers {
            onClosed.captured.invoke("connection_error: boom")
        }

        val bridge = RuntimeEventToolBridge(service, timeoutMs = 2_000L)
        val result = bridge.handle("start")
        assertFalse(result.success)
        assertEquals("connection_error: boom", result.error)
    }

    @Test
    fun stopDelegatesToService() = runBlocking {
        val service = mockk<RuntimeEventService>()
        val onStopped = slot<() -> Unit>()
        every { service.stop(capture(onStopped)) } answers { onStopped.captured.invoke() }

        val bridge = RuntimeEventToolBridge(service, timeoutMs = 2_000L)
        val result = bridge.handle("stop")
        assertTrue(result.success)
        verify(exactly = 1) { service.stop(any()) }
    }
}
