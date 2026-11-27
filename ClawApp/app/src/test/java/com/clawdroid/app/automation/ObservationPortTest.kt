package com.clawdroid.app.automation

import android.graphics.Rect
import com.clawdroid.app.focus.LiveXposedFocusStore
import com.clawdroid.app.focus.LiveXposedViewStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationPortTest {
    @Test
    fun staleLsposedFallsBackToAccessibility() {
        val now = 100_000L
        val port = ObservationPort(
            lsposedFocus = fakeProvider(snapshot(ObservationSource.LsposedFocus, now - 20_000)),
            lsposedView = fakeProvider(snapshot(ObservationSource.LsposedView, now - 20_000)),
            accessibility = fakeProvider(snapshot(ObservationSource.Accessibility, now - 100)),
            now = { now }
        )

        assertEquals(ObservationSource.Accessibility, port.current("com.example.app")?.source)
    }

    @Test
    fun packageMismatchIsRejected() {
        val now = 100_000L
        val port = ObservationPort(
            lsposedFocus = fakeProvider(snapshot(ObservationSource.LsposedFocus, now, packageName = "com.other")),
            lsposedView = fakeProvider(null),
            accessibility = fakeProvider(null),
            now = { now }
        )

        assertNull(port.current("com.example.app"))
    }

    @Test
    fun lsposedFocusSourceMapsIdentityAndGeneration() {
        LiveXposedFocusStore.clearForTests()
        LiveXposedFocusStore.updateFromJson(
            """{"schema_version":2,"package_name":"com.example.app","activity_class":".MainActivity","loaded_at_epoch_ms":100}"""
        )

        val snapshot = LsposedFocusObservationSource().snapshot("com.example.app")

        assertEquals(ObservationSource.LsposedFocus, snapshot?.source)
        assertEquals(".MainActivity", snapshot?.activityClass)
        assertTrue((snapshot?.generation ?: 0) > 0)
    }

    @Test
    fun lsposedViewSourceMapsNodesAndBounds() {
        LiveXposedViewStore.clearForTests()
        LiveXposedViewStore.updateFromJson(
            """{"schema_version":1,"package_name":"com.example.app","activity_class":".MainActivity","loaded_at_epoch_ms":100,"node_count":1,"nodes":[{"class":"android.widget.Button","id":"continue","text":"继续","bounds":"[10,20][110,80]","visibility":"visible"}]}"""
        )

        val snapshot = LsposedViewObservationSource().snapshot("com.example.app")

        assertEquals(1, snapshot?.nodes?.size)
        assertEquals("继续", snapshot?.nodes?.single()?.label)
        assertEquals(110, snapshot?.nodes?.single()?.bounds?.right)
    }

    @Test
    fun accessibilitySourceMapsClickableNodesAndGeneration() {
        val source = AccessibilityObservationSource(
            snapshotProvider = {
                AccessibilitySnapshot(
                    serviceConnected = true,
                    packageName = "com.example.app",
                    className = ".MainActivity",
                    updatedAtEpochMs = 100
                )
            },
            nodesProvider = {
                listOf(
                    NodeSemantic(
                        text = "继续",
                        viewId = "continue",
                        className = "android.widget.Button",
                        clickable = true,
                        enabled = true,
                        bounds = Rect(10, 20, 110, 80)
                    )
                )
            },
            generationProvider = { 7 }
        )

        val snapshot = source.snapshot("com.example.app")

        assertEquals(ObservationSource.Accessibility, snapshot?.source)
        assertEquals(7L, snapshot?.generation)
        assertTrue(snapshot?.nodes?.single()?.clickable == true)
    }

    private fun snapshot(
        source: ObservationSource,
        timestamp: Long,
        packageName: String = "com.example.app"
    ) = ObservationSnapshot(
        source = source,
        packageName = packageName,
        generation = 1,
        timestampEpochMs = timestamp
    )

    private fun fakeProvider(value: ObservationSnapshot?) = object : ObservationSourceProvider {
        override fun snapshot(packageName: String): ObservationSnapshot? = value
    }
}
