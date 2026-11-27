package com.clawdroid.app.tools.adapters

import com.clawdroid.app.agent.task.ExecutionStep
import com.clawdroid.app.agent.task.StepKind
import com.clawdroid.app.agent.task.TaskCheckpoint
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolDispatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolAdapterTest {
    @Test
    fun mapsSuccessfulToolResult() = runTest {
        val dispatcher = mockk<ClawToolDispatcher>()
        coEvery { dispatcher.execute(ClawTool.RUNTIME_PING, any()) } returns ClawToolCallResult(true, "ok")
        val adapter = LocalToolAdapter(dispatcher)

        val result = adapter.execute(
            ExecutionStep("s1", "ping", StepKind.Tool, payload = "runtime_ping"),
            TaskExecutionContext("t1", 1, TaskCheckpoint())
        )

        assertTrue(result.success)
        assertEquals("ok", result.summary)
    }

    @Test
    fun jsonPayloadPassesToolArguments() = runTest {
        val dispatcher = mockk<ClawToolDispatcher>()
        coEvery { dispatcher.execute(ClawTool.INJECT_SWIPE, any()) } returns ClawToolCallResult(true, "ok")
        val adapter = LocalToolAdapter(dispatcher)
        val payload = """{"tool":"inject_swipe","arguments":{"x1":100,"y1":200,"x2":300,"y2":400}}"""

        val result = adapter.execute(
            ExecutionStep("s1", "swipe", StepKind.Tool, payload = payload),
            TaskExecutionContext("t1", 1, TaskCheckpoint())
        )

        assertTrue(result.success)
        coVerify {
            dispatcher.execute(ClawTool.INJECT_SWIPE, match {
                it["x1"] == 100 && it["y1"] == 200 && it["x2"] == 300 && it["y2"] == 400
            })
        }
    }

    @Test
    fun rejectsUnknownToolWithoutCallingDispatcher() = runTest {
        val dispatcher = mockk<ClawToolDispatcher>()
        val adapter = LocalToolAdapter(dispatcher)

        val result = adapter.execute(
            ExecutionStep("s1", "unknown", StepKind.Tool, payload = "not_a_tool"),
            TaskExecutionContext("t1", 1, TaskCheckpoint())
        )

        assertFalse(result.success)
        assertEquals("unknown_tool", result.failure?.code)
    }
}
