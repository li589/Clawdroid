package com.clawdroid.app.skills

import com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolDispatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTaskPollerTest {
    @Test
    fun awaitTerminalReturnsSuccessOnSucceeded() = runBlocking {
        val dispatcher = mockk<ClawToolDispatcher>()
        coEvery {
            dispatcher.execute(ClawTool.TASK_GET, any())
        } returns ClawToolCallResult(
            success = true,
            output = "ok",
            taskSnapshot = ClawRuntimeTaskSnapshot(
                taskId = "t1",
                state = "Succeeded",
                name = "demo",
                totalSteps = 1,
                completedSteps = 1
            )
        )

        val result = RuntimeTaskPoller.awaitTerminal(
            dispatcher = dispatcher,
            taskId = "t1",
            pollIntervalMs = 1L,
            pollAttempts = 3
        )
        assertTrue(result.success)
        assertFalse(result.detached)
        assertEquals("t1", result.snapshot?.taskId)
    }

    @Test
    fun awaitTerminalDetachesWhenStillRunning() = runBlocking {
        val dispatcher = mockk<ClawToolDispatcher>()
        coEvery {
            dispatcher.execute(ClawTool.TASK_GET, any())
        } returns ClawToolCallResult(
            success = true,
            output = "running",
            taskSnapshot = ClawRuntimeTaskSnapshot(
                taskId = "t2",
                state = "Running",
                name = "demo"
            )
        )

        val result = RuntimeTaskPoller.awaitTerminal(
            dispatcher = dispatcher,
            taskId = "t2",
            pollIntervalMs = 1L,
            pollAttempts = 2
        )
        // 轮询超时只意味着本地放弃了等待，并不等价于任务成功完成。
        // detached=true 与 ERROR_DETACHED 仍可供调用方区分"仍在 Runtime 侧运行"。
        assertFalse(result.success)
        assertTrue(result.detached)
        assertEquals(RuntimeTaskPoller.ERROR_DETACHED, result.error)
    }

    @Test
    fun awaitTerminalCancelsRuntimeTaskOnCoroutineCancel() = runBlocking {
        val dispatcher = mockk<ClawToolDispatcher>()
        coEvery {
            dispatcher.execute(ClawTool.TASK_GET, any())
        } returns ClawToolCallResult(
            success = true,
            output = "running",
            taskSnapshot = ClawRuntimeTaskSnapshot(
                taskId = "t-cancel",
                state = "Running",
                name = "demo"
            )
        )
        coEvery {
            dispatcher.execute(ClawTool.TASK_CANCEL, any())
        } returns ClawToolCallResult(success = true, output = "cancelled")

        val job = launch {
            RuntimeTaskPoller.awaitTerminal(
                dispatcher = dispatcher,
                taskId = "t-cancel",
                pollIntervalMs = 50L,
                pollAttempts = 100
            )
        }
        delay(80L)
        job.cancel()
        job.join()
        coVerify(atLeast = 1) {
            dispatcher.execute(ClawTool.TASK_CANCEL, match { it["task_id"] == "t-cancel" })
        }
    }

    @Test
    fun resolveTaskIdPrefersExplicitThenParsesOutput() {
        val fromField = RuntimeTaskPoller.resolveTaskId(
            ClawToolCallResult(success = true, output = "x", runtimeTaskId = " explicit ")
        )
        assertEquals("explicit", fromField)

        val fromOutput = RuntimeTaskPoller.resolveTaskId(
            ClawToolCallResult(
                success = true,
                output = """提交成功 task_id="task-abc-1" """
            )
        )
        assertEquals("task-abc-1", fromOutput)
    }
}
