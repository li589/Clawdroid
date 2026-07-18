package com.clawdroid.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveToolCapabilityStoreTest {
    @Before
    fun clear() {
        LiveToolCapabilityStore.clear()
    }

    @Test
    fun updateFromEventData_parses_capability_list() {
        val updated = LiveToolCapabilityStore.updateFromEventData(
            mapOf(
                "root" to true,
                "accessibility" to true,
                "capabilities" to listOf("screen.capture", "shell.exec.limited", "event.subscribe")
            )
        )
        assertTrue(updated)
        assertEquals(
            setOf("screen.capture", "shell.exec.limited", "event.subscribe"),
            LiveToolCapabilityStore.snapshot()
        )
        assertTrue(LiveToolCapabilityStore.updatedAtMs() > 0L)
    }

    @Test
    fun updateFromEventData_ignores_missing_capabilities() {
        assertFalse(
            LiveToolCapabilityStore.updateFromEventData(
                mapOf("root" to true, "accessibility" to false)
            )
        )
        assertTrue(LiveToolCapabilityStore.snapshot().isEmpty())
    }

    @Test
    fun isStale_whenEmptyOrNeverUpdated() {
        assertTrue(LiveToolCapabilityStore.isStale())
        LiveToolCapabilityStore.update(emptyList())
        assertTrue(LiveToolCapabilityStore.isStale())
    }

    @Test
    fun isStale_false_rightAfterUpdate() {
        LiveToolCapabilityStore.update(listOf("system.ping"))
        assertFalse(LiveToolCapabilityStore.isStale(maxAgeMs = 60_000L))
        assertTrue(LiveToolCapabilityStore.isStale(maxAgeMs = -1L))
    }
}
