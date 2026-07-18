package com.clawdroid.app.focus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveXposedViewStoreTest {
    @Before
    fun clear() {
        LiveXposedViewStore.clearForTests()
    }

    @Test
    fun updateFromJsonCachesSummary() {
        assertTrue(
            LiveXposedViewStore.updateFromJson(
                """
                {
                  "schema_version":1,
                  "adapter_id":"settings_detail",
                  "package_name":"com.android.settings",
                  "activity_class":"com.android.settings.Settings",
                  "node_count":2,
                  "compose_surface":false,
                  "nodes":[]
                }
                """.trimIndent()
            )
        )
        assertTrue(LiveXposedViewStore.hasLive())
        assertTrue(LiveXposedViewStore.summaryForProbe().contains("nodes=2"))
        assertFalse(LiveXposedViewStore.updateFromJson("""{"package_name":"only"}"""))
    }
}
