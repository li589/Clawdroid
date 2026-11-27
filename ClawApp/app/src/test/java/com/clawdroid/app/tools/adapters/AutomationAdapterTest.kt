package com.clawdroid.app.tools.adapters

import com.clawdroid.app.automation.ObservationPort
import com.clawdroid.app.automation.ObservationSnapshot
import com.clawdroid.app.automation.ObservationSource
import com.clawdroid.app.automation.ObservationSourceProvider
import com.clawdroid.app.automation.ObservedBounds
import com.clawdroid.app.automation.ObservedNode
import com.clawdroid.app.automation.TargetResolution
import com.clawdroid.app.agent.task.ExecutionStep
import com.clawdroid.app.agent.task.StepKind
import com.clawdroid.app.agent.task.TaskCheckpoint
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolDispatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationAdapterTest {
    @Test
    fun confirmPageReportsPackageMismatch() = runTest {
        val dispatcher = mockk<ClawToolDispatcher>()
        val port = ObservationPort(
            lsposedFocus = provider(null),
            lsposedView = provider(null),
            accessibility = provider(snapshot(generation = 1).copy(packageName = "com.other")),
            clock = { 100_000 }
        )
        val adapter = AutomationAdapter(port, dispatcher)

        val result = adapter.execute(
            ExecutionStep(
                "confirm",
                "confirm",
                StepKind.ConfirmPage,
                payload = """{"expected_package":"com.example.app"}"""
            ),
            TaskExecutionContext("t1", 1, TaskCheckpoint())
        )

        assertEquals("package_mismatch", result.failure?.code)
    }

    @Test
    fun safeTapRejectsStaleResolution() = runTest {
        val dispatcher = mockk<ClawToolDispatcher>()
        val snapshot = snapshot(generation = 2)
        val port = ObservationPort(
            lsposedFocus = provider(null),
            lsposedView = provider(null),
            accessibility = provider(snapshot),
            clock = { 100_000 }
        )
        val adapter = AutomationAdapter(port, dispatcher)
        val oldResolution = TargetResolution.Resolved(
            token = "old-token",
            nodeKey = "continue",
            label = "继续",
            bounds = ObservedBounds(10, 20, 110, 80),
            packageName = "com.example.app",
            snapshotGeneration = 1,
            source = ObservationSource.Accessibility,
            resolvedAtEpochMs = 99_000
        )

        val result = adapter.execute(
            ExecutionStep(
                "tap",
                "tap",
                StepKind.SafeTap,
                payload = """{"expected_package":"com.example.app"}"""
            ),
            TaskExecutionContext(
                "t1",
                1,
                TaskCheckpoint(data = mapOf("target_resolution" to resolutionJson(oldResolution)))
            )
        )

        assertFalse(result.success)
        assertEquals("snapshot_stale", result.failure?.code)
        coVerify(exactly = 0) { dispatcher.execute(ClawTool.INJECT_TAP, any()) }
    }

    @Test
    fun safeTapUsesResolvedBoundsCenter() = runTest {
        val dispatcher = mockk<ClawToolDispatcher>()
        coEvery { dispatcher.execute(ClawTool.INJECT_TAP, any()) } returns ClawToolCallResult(true, "accepted")
        val snapshot = snapshot(generation = 1)
        val port = ObservationPort(
            lsposedFocus = provider(null),
            lsposedView = provider(null),
            accessibility = provider(snapshot),
            clock = { 100_000 }
        )
        val adapter = AutomationAdapter(port, dispatcher)
        val resolution = TargetResolution.Resolved(
            token = "token",
            nodeKey = "continue",
            label = "继续",
            bounds = ObservedBounds(10, 20, 110, 80),
            packageName = "com.example.app",
            snapshotGeneration = 1,
            source = ObservationSource.Accessibility,
            resolvedAtEpochMs = 99_000
        )

        val result = adapter.execute(
            ExecutionStep(
                "tap",
                "tap",
                StepKind.SafeTap,
                payload = """{"expected_package":"com.example.app"}"""
            ),
            TaskExecutionContext(
                "t1",
                1,
                TaskCheckpoint(data = mapOf("target_resolution" to resolutionJson(resolution)))
            )
        )

        assertTrue(result.summary, result.success)
        coVerify(exactly = 1) {
            dispatcher.execute(ClawTool.INJECT_TAP, match { it["x"] == 60 && it["y"] == 50 })
        }
    }

    private fun snapshot(generation: Long) = ObservationSnapshot(
        source = ObservationSource.Accessibility,
        packageName = "com.example.app",
        generation = generation,
        timestampEpochMs = 99_000,
        nodes = listOf(
            ObservedNode(
                id = "continue",
                label = "继续",
                viewId = "continue",
                className = "android.widget.Button",
                clickable = true,
                enabled = true,
                bounds = ObservedBounds(10, 20, 110, 80)
            )
        )
    )

    private fun provider(value: ObservationSnapshot?) = object : ObservationSourceProvider {
        override fun snapshot(packageName: String): ObservationSnapshot? = value
    }

    private fun resolutionJson(resolution: TargetResolution.Resolved): String = buildString {
        append("{")
        append("\"token\":\"").append(resolution.token).append("\",")
        append("\"nodeKey\":\"").append(resolution.nodeKey).append("\",")
        append("\"label\":\"").append(resolution.label).append("\",")
        append("\"left\":").append(resolution.bounds.left).append(",")
        append("\"top\":").append(resolution.bounds.top).append(",")
        append("\"right\":").append(resolution.bounds.right).append(",")
        append("\"bottom\":").append(resolution.bounds.bottom).append(",")
        append("\"packageName\":\"").append(resolution.packageName).append("\",")
        append("\"snapshotGeneration\":").append(resolution.snapshotGeneration).append(",")
        append("\"source\":\"").append(resolution.source.name).append("\",")
        append("\"resolvedAtEpochMs\":").append(resolution.resolvedAtEpochMs)
        append("}")
    }
}
