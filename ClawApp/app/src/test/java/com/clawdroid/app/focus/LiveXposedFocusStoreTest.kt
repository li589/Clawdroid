package com.clawdroid.app.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveXposedFocusStoreTest {
    @Before
    fun clear() {
        LiveXposedFocusStore.clearForTests()
    }

    @Test
    fun updateFromJsonCachesAndNotifies() {
        var notified = ""
        val listener: (String) -> Unit = { notified = it }
        LiveXposedFocusStore.addListener(listener)
        try {
            val ok = LiveXposedFocusStore.updateFromJson(
                """
                {
                  "schema_version":2,
                  "adapter_id":"settings_detail",
                  "package_name":"com.android.settings",
                  "process_name":"com.android.settings",
                  "activity_class":"com.android.settings.Settings",
                  "loaded_at_epoch_ms":99,
                  "active":true,
                  "extras":{"settings_fragment":"WifiSettings"}
                }
                """.trimIndent()
            )
            assertTrue(ok)
            assertTrue(LiveXposedFocusStore.hasLive())
            assertTrue(LiveXposedFocusStore.summaryForProbe().contains("com.android.settings"))
            assertTrue(LiveXposedFocusStore.summaryForProbe().contains("fragment=WifiSettings"))
            assertTrue(notified.contains("WifiSettings"))
            assertEquals(LiveXposedFocusStore.readRaw()?.contains("schema_version"), true)
        } finally {
            LiveXposedFocusStore.removeListener(listener)
            LiveXposedFocusStore.clearForTests()
        }
    }

    @Test
    fun rejectsIncompletePayload() {
        assertFalse(LiveXposedFocusStore.updateFromJson("""{"package_name":"only"}"""))
        assertFalse(LiveXposedFocusStore.hasLive())
        assertFalse(LiveXposedFocusStore.updateFromJson("not-json"))
    }
}
