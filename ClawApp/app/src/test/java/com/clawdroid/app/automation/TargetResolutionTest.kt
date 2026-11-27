package com.clawdroid.app.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetResolutionTest {
    @Test
    fun duplicateTargetsAreAmbiguous() {
        val result = TargetResolver.resolve(snapshotWithTargets(2), targetText = "继续")

        assertTrue(result is TargetResolution.Ambiguous)
    }

    @Test
    fun tokenExpiresWhenGenerationChanges() {
        val snapshot = snapshotWithTargets(1)
        val resolution = TargetResolver.resolve(snapshot, targetText = "继续") as TargetResolution.Resolved

        assertFalse(TargetResolver.validate(resolution, snapshot.copy(generation = resolution.snapshotGeneration + 1), 100_000))
    }

    @Test
    fun tokenIsValidForLabelOnlyNode() {
        val snapshot = snapshotWithTargets(1).let {
            it.copy(nodes = it.nodes.map { node -> node.copy(id = "", viewId = "", label = "Continue") })
        }
        val resolution = TargetResolver.resolve(snapshot, targetText = "continue") as TargetResolution.Resolved

        assertTrue(TargetResolver.validate(resolution, snapshot, 100_000))
    }

    @Test
    fun tokenRejectsPackageMismatch() {
        val snapshot = snapshotWithTargets(1)
        val resolution = TargetResolver.resolve(snapshot, targetText = "继续") as TargetResolution.Resolved

        assertFalse(TargetResolver.validate(resolution, snapshot.copy(packageName = "com.other"), 100_000))
    }

    private fun snapshotWithTargets(count: Int) = ObservationSnapshot(
        source = ObservationSource.Accessibility,
        packageName = "com.example.app",
        generation = 1,
        timestampEpochMs = 99_000,
        nodes = List(count) { index ->
            ObservedNode(
                id = "continue-$index",
                label = "继续",
                viewId = "continue-$index",
                className = "android.widget.Button",
                clickable = true,
                enabled = true,
                bounds = ObservedBounds(10, 20, 110, 80)
            )
        }
    )
}
