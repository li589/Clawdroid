package com.clawdroid.app.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClawRuntimeTaskSnapshotTest {
    @Test
    fun fromDataParsesSnapshotFields() {
        val snapshot = ClawRuntimeTaskSnapshot.fromData(
            mapOf(
                "task_id" to "task-1",
                "session_id" to "sess-1",
                "state" to "Running",
                "current_step" to 1,
                "total_steps" to 3,
                "completed_steps" to 1,
                "created_at" to 100L,
                "name" to "health",
                "error" to "",
                "error_code" to 0
            )
        )

        assertEquals("task-1", snapshot.taskId)
        assertEquals("Running", snapshot.state)
        assertEquals(1, snapshot.currentStep)
        assertEquals(3, snapshot.totalSteps)
        assertEquals("health", snapshot.name)
        assertTrue(snapshot.summaryLine().contains("task_id=task-1"))
        assertTrue(snapshot.summaryLine().contains("step=2/3"))
    }
}
