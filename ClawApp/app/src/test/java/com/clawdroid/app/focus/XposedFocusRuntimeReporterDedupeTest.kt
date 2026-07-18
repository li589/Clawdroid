package com.clawdroid.app.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class XposedFocusRuntimeReporterDedupeTest {
    @Before
    fun clear() {
        LiveXposedFocusStore.clearForTests()
    }

    @Test
    fun storeListenerReceivesSummaryOnUpdate() {
        val count = AtomicInteger(0)
        val listener: (String) -> Unit = { count.incrementAndGet() }
        LiveXposedFocusStore.addListener(listener)
        try {
            assertTrue(
                LiveXposedFocusStore.updateFromJson(
                    """{"package_name":"a","activity_class":"b","loaded_at_epoch_ms":1,"extras":{}}"""
                )
            )
            assertEquals(1, count.get())
            // Same content still notifies listeners; reporter dedupes IPC separately.
            assertTrue(
                LiveXposedFocusStore.updateFromJson(
                    """{"package_name":"a","activity_class":"b","loaded_at_epoch_ms":1,"extras":{}}"""
                )
            )
            assertEquals(2, count.get())
        } finally {
            LiveXposedFocusStore.removeListener(listener)
            LiveXposedFocusStore.clearForTests()
        }
    }
}
