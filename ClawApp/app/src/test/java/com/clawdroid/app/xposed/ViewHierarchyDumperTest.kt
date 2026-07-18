package com.clawdroid.app.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class ViewHierarchyDumperTest {
    @Test
    fun flattenRespectsMaxNodesAndDepth() {
        val deepChild = ViewHierarchyDumper.NodeSpec(
            className = "Leaf",
            depth = 5,
            childCount = 0
        )
        val mid = ViewHierarchyDumper.NodeSpec(
            className = "Mid",
            depth = 1,
            childCount = 1,
            children = listOf(deepChild)
        )
        val root = ViewHierarchyDumper.NodeSpec(
            className = "Root",
            depth = 0,
            childCount = 1,
            children = listOf(mid)
        )
        val flat = ViewHierarchyDumper.flatten(root, maxDepth = 4, maxNodes = 32)
        assertTrue(flat.any { it.className == "Root" })
        assertTrue(flat.any { it.className == "Mid" })
        assertFalse(flat.any { it.className == "Leaf" && it.depth > 4 })

        val manyChildren = ViewHierarchyDumper.NodeSpec(
            className = "Root",
            depth = 0,
            childCount = 40,
            children = (0 until 40).map {
                ViewHierarchyDumper.NodeSpec(className = "C$it", depth = 1, childCount = 0)
            }
        )
        val capped = ViewHierarchyDumper.flatten(manyChildren, maxDepth = 4, maxNodes = 10)
        assertEquals(10, capped.size)
    }

    @Test
    fun buildPayloadAndSummary() {
        val nodes = listOf(
            ViewHierarchyDumper.NodeSpec(
                className = "DecorView",
                id = "content",
                text = "Hello",
                bounds = "0,0,100,200",
                depth = 0,
                childCount = 1
            ),
            ViewHierarchyDumper.NodeSpec(
                className = "TextView",
                text = "Wifi",
                depth = 1,
                childCount = 0
            )
        )
        val payload = ViewHierarchyDumper.buildPayload(
            packageName = "com.android.settings",
            activityClass = "com.android.settings.Settings",
            adapterId = "settings_detail",
            loadedAtEpochMs = 7L,
            nodes = nodes,
            truncated = false
        )
        assertTrue(payload.contains("\"schema_version\":1"))
        assertTrue(payload.contains("\"node_count\":2"))
        assertTrue(payload.contains("Wifi"))
        val summary = ViewHierarchyDumper.formatSummary(payload)
        assertTrue(summary.contains("com.android.settings/com.android.settings.Settings"))
        assertTrue(summary.contains("nodes=2"))
        assertTrue(summary.contains("[settings_detail]"))
    }

    @Test
    fun detectsComposeSurface() {
        assertTrue(ViewHierarchyDumper.isComposeClass("AndroidComposeView"))
        assertTrue(ViewHierarchyDumper.isComposeClass("androidx.compose.ui.platform.ComposeView"))
        assertFalse(ViewHierarchyDumper.isComposeClass("TextView"))
        val payload = ViewHierarchyDumper.buildPayload(
            packageName = "com.example",
            activityClass = "Main",
            adapterId = "settings_detail",
            loadedAtEpochMs = 1L,
            nodes = listOf(
                ViewHierarchyDumper.NodeSpec(className = "AndroidComposeView", depth = 0, childCount = 0)
            ),
            truncated = false
        )
        assertTrue(payload.contains("\"compose_surface\":true"))
        assertTrue(payload.contains("\"compose\":true"))
        assertTrue(ViewHierarchyDumper.formatSummary(payload).contains("compose"))
    }

    @Test
    fun viewSnapshotStoreWritesLatest() {
        val tempDir = createTempDirectory(prefix = "claw-view-").toFile()
        try {
            XposedViewSnapshotStore.snapshotDirOverride = tempDir
            val payload = ViewHierarchyDumper.buildPayload(
                packageName = "com.android.settings",
                activityClass = "com.android.settings.Settings",
                adapterId = "settings_detail",
                loadedAtEpochMs = 1L,
                nodes = listOf(
                    ViewHierarchyDumper.NodeSpec(className = "Root", depth = 0, childCount = 0)
                ),
                truncated = false
            )
            val file = tempDir.resolve(XposedViewSnapshotStore.LATEST_FILE)
            file.writeText(payload)
            assertTrue(Files.exists(file.toPath()))
            assertTrue(XposedViewSnapshotStore.summaryForProbe().contains("nodes=1"))
        } finally {
            XposedViewSnapshotStore.snapshotDirOverride = null
            tempDir.deleteRecursively()
        }
    }
}
