package com.clawdroid.app.tools.adapters

import com.clawdroid.app.agent.task.ExecutionStep
import com.clawdroid.app.agent.task.StepKind
import com.clawdroid.app.agent.task.TaskCheckpoint
import com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolDispatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.json.JSONArray
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTaskAdapterTest {
    @Test
    fun submitsRuntimeSequenceAndWaitsForTaskId() = runTest {
        val dispatcher = mockk<ClawToolDispatcher>()
        val submitted = slot<Map<String, Any?>>()
        coEvery { dispatcher.execute(ClawTool.TASK_SUBMIT, capture(submitted)) } returns ClawToolCallResult(
            success = true,
            output = "submitted",
            runtimeTaskId = "t1:runtime-sequence"
        )
        val adapter = RuntimeTaskAdapter(dispatcher)

        val result = adapter.execute(
            ExecutionStep("runtime-sequence", "runtime", StepKind.RuntimeTask, payload = "ping"),
            TaskExecutionContext("t1", 1, TaskCheckpoint())
        )

        assertFalse(result.success)
        assertEquals("t1:runtime-sequence", result.runtimeTaskId)
        assertEquals("runtime_task", result.waitReason)
        coVerify(exactly = 1) { dispatcher.execute(ClawTool.TASK_SUBMIT, any()) }
        assertEquals("t1:runtime-sequence", submitted.captured["task_id"])
        val steps = JSONArray(submitted.captured["steps_json"] as String)
        assertEquals("ping", steps.getJSONObject(0).getString("action"))
    }

    @Test
    fun resyncFallsBackToTaskList() = runTest {
        val dispatcher = mockk<ClawToolDispatcher>()
        coEvery { dispatcher.execute(ClawTool.TASK_GET, any()) } returns ClawToolCallResult(false, "missing")
        coEvery { dispatcher.execute(ClawTool.TASK_LIST, any()) } returns ClawToolCallResult(
            success = true,
            output = "tasks",
            taskSnapshots = listOf(ClawRuntimeTaskSnapshot(taskId = "rt-1", state = "Succeeded"))
        )
        val adapter = RuntimeTaskAdapter(dispatcher)

        val snapshot = adapter.resync("rt-1")

        assertEquals("Succeeded", snapshot?.state)
    }

    @Test
    fun cancelDelegatesToDispatcher() = runTest {
        val dispatcher = mockk<ClawToolDispatcher>()
        coEvery { dispatcher.execute(ClawTool.TASK_CANCEL, any()) } returns ClawToolCallResult(true, "cancelled")
        val adapter = RuntimeTaskAdapter(dispatcher)

        val result = adapter.cancel("rt-1")

        assertTrue(result.success)
        coVerify(exactly = 1) { dispatcher.execute(ClawTool.TASK_CANCEL, mapOf("task_id" to "rt-1")) }
    }
}
