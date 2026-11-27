package com.clawdroid.app.data

import com.clawdroid.app.agent.AgentGoal
import com.clawdroid.app.agent.AgentPlan
import com.clawdroid.app.agent.AgentRun
import com.clawdroid.app.agent.AgentRunPhase
import com.clawdroid.app.agent.MemoryBundle
import com.clawdroid.app.agent.WorldSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRunStoreCodecTest {
    @Test
    fun executionTaskIdRoundTrips() {
        val run = AgentRun(
            id = "run-1",
            sessionId = "session-1",
            goal = AgentGoal("goal-1", "inspect"),
            world = WorldSnapshot(sessionSummary = "ready"),
            memory = MemoryBundle(workingSummary = "working"),
            plan = AgentPlan(),
            phase = AgentRunPhase.AwaitRuntime,
            runtimeTaskId = "runtime-1",
            executionTaskId = "execution-1"
        )

        val restored = AgentRunStore.deserializeAgentRun(AgentRunStore.serializeAgentRun(run))

        assertEquals(run, restored)
    }
}
