package com.clawdroid.app.tools.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPayloadResolverTest {

    @Test
    fun taskJsonWithoutTaskIdGetsAutoId() {
        val payload = resolveTaskPayload(
            mapOf(
                "task_json" to """{"name":"reboot_system","steps":[{"id":"s1","action":"exec_shell_limited","args":{"command":"reboot"}}]}"""
            )
        )
        assertNotNull(payload)
        val taskId = payload!!["task_id"]?.toString().orEmpty()
        assertTrue(taskId.isNotBlank())
        assertTrue(taskId.startsWith("app-task-"))
    }

    @Test
    fun topLevelTaskIdMergedIntoTaskJson() {
        val payload = resolveTaskPayload(
            mapOf(
                "task_id" to "reboot_system",
                "task_json" to """{"name":"reboot","steps":[{"id":"s1","action":"exec_shell_limited","args":{"command":"reboot"}}]}"""
            )
        )
        assertNotNull(payload)
        assertEquals("reboot_system", payload!!["task_id"])
    }

    @Test
    fun stepsJsonPathStillWorks() {
        val payload = resolveTaskPayload(
            mapOf(
                "task_id" to "t1",
                "steps_json" to """[{"id":"s1","action":"runtime_ping"}]"""
            )
        )
        assertNotNull(payload)
        assertEquals("t1", payload!!["task_id"])
    }
}
