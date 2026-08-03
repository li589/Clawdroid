package com.clawdroid.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentKernelTest {
    @Test
    fun beginTurnAllowMovesToPlan() {
        val kernel = AgentKernel()
        val turn = kernel.beginTurn(
            sessionId = "s1",
            intent = "列出 /sdcard",
            world = WorldSnapshot(rootAvailable = true),
            risk = AgentRiskLevel.Low
        )
        assertTrue(turn.policy is PolicyDecision.Allow)
        assertEquals(AgentRunPhase.Plan, turn.run.phase)
        assertEquals(turn.run.id, kernel.activeRun()?.id)
        val planned = kernel.markPlanned(turn.run.id, AgentPlan(steps = listOf(
            AgentPlanStep(id = "1", title = "file_list", kind = AgentPlanStep.Kind.Tool)
        )))
        assertNotNull(planned)
        assertEquals(AgentRunPhase.Act, planned!!.phase)
        assertEquals(1, planned.plan.steps.size)
        val done = kernel.complete(turn.run.id, success = true)
        assertEquals(AgentRunPhase.Done, done!!.phase)
        assertTrue(done.success == true)
        assertNull(kernel.activeRun())
    }

    @Test
    fun beginTurnRejectEmptyIntent() {
        val kernel = AgentKernel()
        val turn = kernel.beginTurn(
            sessionId = "s1",
            intent = "   ",
            world = WorldSnapshot()
        )
        assertTrue(turn.policy is PolicyDecision.Reject)
        assertEquals(AgentRunPhase.Done, turn.run.phase)
        assertFalse(turn.run.success == true)
        assertNull(kernel.activeRun())
    }

    @Test
    fun beginTurnRequireConfirmOnRebootHint() {
        val kernel = AgentKernel()
        val turn = kernel.beginTurn(
            sessionId = "s1",
            intent = "请重启手机",
            world = WorldSnapshot(rootAvailable = true)
        )
        assertTrue(turn.policy is PolicyDecision.RequireConfirm)
        assertEquals(AgentRunPhase.AwaitUser, turn.run.phase)
        assertEquals(turn.run.id, kernel.activeRun()?.id)
    }

    @Test
    fun markAwaitRuntimeStoresTaskId() {
        val kernel = AgentKernel()
        val turn = kernel.beginTurn(
            sessionId = "s1",
            intent = "体检",
            world = WorldSnapshot(rootAvailable = true),
            risk = AgentRiskLevel.Low
        )
        kernel.markAwaitRuntime(turn.run.id, "task-abc")
        assertEquals(AgentRunPhase.AwaitRuntime, kernel.getRun(turn.run.id)!!.phase)
        assertEquals("task-abc", kernel.getRun(turn.run.id)!!.runtimeTaskId)
    }

    @Test
    fun restoreRunReattachesActive() {
        val kernel = AgentKernel()
        val turn = kernel.beginTurn(
            sessionId = "s1",
            intent = "列出目录",
            world = WorldSnapshot(rootAvailable = true),
            risk = AgentRiskLevel.Low
        )
        kernel.markAwaitRuntime(turn.run.id, "task-1")
        val snapshot = kernel.getRun(turn.run.id)!!
        val other = AgentKernel()
        other.restoreRun(snapshot)
        assertEquals(snapshot.id, other.activeRun()?.id)
        assertEquals(AgentRunPhase.AwaitRuntime, other.activeRun()?.phase)
    }
}

class AgentPolicyTest {
    @Test
    fun rejectRootShellWithoutRoot() {
        val decision = AgentPolicy.evaluateGoal(
            AgentGoal(id = "g", intent = "execute_shell wm size"),
            WorldSnapshot(rootAvailable = false)
        )
        assertTrue(decision is PolicyDecision.Reject)
    }

    @Test
    fun rejectShellToolWithoutRoot() {
        val decision = AgentPolicy.evaluateToolHint(
            "execute_shell_limited",
            WorldSnapshot(rootAvailable = false)
        )
        assertTrue(decision is PolicyDecision.Reject)
    }

    @Test
    fun requireConfirmDestructiveTool() {
        val decision = AgentPolicy.evaluateToolHint(
            "app_stop",
            WorldSnapshot(rootAvailable = true)
        )
        assertTrue(decision is PolicyDecision.RequireConfirm)
    }
}

class MemoryBundleTest {
    @Test
    fun asRetrievedContextEmpty() {
        assertEquals("", MemoryBundle().asRetrievedContext())
    }

    @Test
    fun asRetrievedContextFormatsSections() {
        val text = MemoryBundle(
            episodicSnippets = listOf("[user] hello"),
            semanticFacts = listOf("pref: dark"),
            filePaths = listOf("/data/x")
        ).asRetrievedContext()
        assertTrue(text.contains("聊天索引："))
        assertTrue(text.contains("[user] hello"))
        assertTrue(text.contains("记忆图谱："))
        assertTrue(text.contains("文件索引："))
        assertTrue(text.contains("/data/x"))
    }
}
