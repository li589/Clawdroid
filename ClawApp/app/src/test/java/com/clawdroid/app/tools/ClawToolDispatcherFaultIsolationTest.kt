package com.clawdroid.app.tools

import com.clawdroid.app.fault.FaultCodes
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClawToolDispatcherFaultIsolationTest {
    @Test
    fun executeCatchesUncaughtToolExceptions() = runBlocking {
        val executor = mockk<ClawToolExecutor>()
        coEvery { executor.ping() } throws RuntimeException("boom-from-executor")
        val dispatcher = ClawToolDispatcher(executor)

        val result = dispatcher.execute(ClawTool.RUNTIME_PING)

        assertFalse(result.success)
        assertEquals(FaultCodes.TOOL_UNCAUGHT, result.error)
        assertTrue(result.output.contains("boom-from-executor") || result.output.contains("内部错误已隔离"))
    }
}
