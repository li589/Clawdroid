package com.clawdroid.app.ui

import com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRuntimeTaskSyncTest {
    @Test
    fun withRuntimeSnapshotMapsRunningProgress() {
        val base = ChatTaskExecutionState(
            taskId = "chat-1",
            title = "AI 工具循环",
            summary = "running",
            status = ChatTaskProgressState.Running,
            steps = listOf(
                ChatTaskStepState(title = "步骤 1"),
                ChatTaskStepState(title = "步骤 2"),
                ChatTaskStepState(title = "步骤 3")
            ),
            startedAtEpochMs = 1L,
            runtimeTaskId = "task-1"
        )
        val updated = base.withRuntimeSnapshot(
            ClawRuntimeTaskSnapshot(
                taskId = "task-1",
                state = "Running",
                currentStep = 1,
                totalSteps = 3,
                completedSteps = 1,
                name = "demo"
            ),
            nowEpochMs = 10L
        )

        assertEquals(ChatTaskProgressState.Running, updated.status)
        assertEquals(ChatTaskProgressState.Succeeded, updated.steps[0].status)
        assertEquals(ChatTaskProgressState.Running, updated.steps[1].status)
        assertEquals(ChatTaskProgressState.Pending, updated.steps[2].status)
        assertTrue(updated.summary.contains("task_id=task-1"))
    }

    @Test
    fun withRuntimeSnapshotMarksSucceededTerminal() {
        val base = ChatTaskExecutionState(
            taskId = "chat-2",
            title = "t",
            summary = "s",
            status = ChatTaskProgressState.Running,
            steps = listOf(ChatTaskStepState(title = "a"), ChatTaskStepState(title = "b")),
            startedAtEpochMs = 1L
        )
        val updated = base.withRuntimeSnapshot(
            ClawRuntimeTaskSnapshot(
                taskId = "task-2",
                state = "Succeeded",
                currentStep = 1,
                totalSteps = 2,
                completedSteps = 2
            ),
            nowEpochMs = 20L
        )
        assertEquals(ChatTaskProgressState.Succeeded, updated.status)
        assertTrue(updated.steps.all { it.status == ChatTaskProgressState.Succeeded })
        assertEquals(20L, updated.finishedAtEpochMs)
    }

    @Test
    fun withRuntimeSnapshotMapsFailedAndCancelled() {
        val base = ChatTaskExecutionState(
            taskId = "chat-3",
            title = "t",
            summary = "s",
            status = ChatTaskProgressState.Running,
            steps = listOf(
                ChatTaskStepState(title = "a"),
                ChatTaskStepState(title = "b"),
                ChatTaskStepState(title = "c")
            ),
            startedAtEpochMs = 1L,
            runtimeTaskId = "task-3"
        )
        val failed = base.withRuntimeSnapshot(
            ClawRuntimeTaskSnapshot(
                taskId = "task-3",
                state = "Failed",
                currentStep = 1,
                totalSteps = 3,
                completedSteps = 1,
                error = "boom",
                errorCode = 7003
            ),
            nowEpochMs = 30L
        )
        assertEquals(ChatTaskProgressState.Failed, failed.status)
        assertEquals(ChatTaskProgressState.Succeeded, failed.steps[0].status)
        assertEquals(ChatTaskProgressState.Failed, failed.steps[1].status)
        assertEquals("runtime_7003", failed.failure?.code)

        val cancelled = base.withRuntimeSnapshot(
            ClawRuntimeTaskSnapshot(
                taskId = "task-3",
                state = "Cancelled",
                currentStep = 0,
                totalSteps = 3
            ),
            nowEpochMs = 40L
        )
        assertEquals(ChatTaskProgressState.Cancelled, cancelled.status)
        assertEquals(ChatTaskProgressState.Cancelled, cancelled.steps[0].status)
    }

    @Test
    fun withRuntimeSnapshotKeepsExistingStepsWhenTotalStepsMissing() {
        val base = ChatTaskExecutionState(
            taskId = "chat-4",
            title = "运行时体检",
            summary = "s",
            status = ChatTaskProgressState.Running,
            steps = listOf(
                ChatTaskStepState(title = "Ping"),
                ChatTaskStepState(title = "Runtime Status"),
                ChatTaskStepState(title = "获取能力")
            ),
            startedAtEpochMs = 1L,
            runtimeTaskId = "task-4"
        )
        val updated = base.withRuntimeSnapshot(
            ClawRuntimeTaskSnapshot(
                taskId = "task-4",
                state = "Running",
                totalSteps = 0,
                name = "运行时体检"
            ),
            nowEpochMs = 50L
        )
        assertEquals(3, updated.steps.size)
        assertEquals("Ping", updated.steps[0].title)
        assertEquals("获取能力", updated.steps[2].title)
    }
}
