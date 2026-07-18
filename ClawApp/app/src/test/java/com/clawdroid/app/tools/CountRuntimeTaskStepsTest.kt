package com.clawdroid.app.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class CountRuntimeTaskStepsTest {
    @Test
    fun countsListSteps() {
        val count = countRuntimeTaskSteps(
            mapOf(
                "task_id" to "t1",
                "steps" to listOf(
                    mapOf("action" to "ping"),
                    mapOf("action" to "get_capabilities")
                )
            )
        )
        assertEquals(2, count)
    }

    @Test
    fun countsJsonArrayStringSteps() {
        val count = countRuntimeTaskSteps(
            mapOf(
                "steps" to """[{"action":"ping"},{"action":"get_runtime_status"},{"action":"get_capabilities"}]"""
            )
        )
        assertEquals(3, count)
    }

    @Test
    fun missingStepsReturnsZero() {
        assertEquals(0, countRuntimeTaskSteps(mapOf("task_id" to "t1")))
    }
}
