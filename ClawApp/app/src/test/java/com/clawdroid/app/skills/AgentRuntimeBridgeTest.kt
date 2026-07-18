package com.clawdroid.app.skills

import com.clawdroid.app.runtime.RuntimeActionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeBridgeTest {
    @Test
    fun runtimeHealthSweepCanSubmitAsRuntimeTask() {
        val agent = ClawAgentCatalog.byId("runtime_health_sweep")!!
        assertEquals(AgentExecutionMode.RuntimeTask, agent.executionMode)
        assertTrue(AgentRuntimeBridge.canSubmitAsRuntimeTask(agent))
        val stepsJson = AgentRuntimeBridge.buildStepsJson(agent)
        assertNotNull(stepsJson)
        assertTrue(stepsJson!!.contains(RuntimeActionCatalog.PING))
        assertTrue(stepsJson.contains(RuntimeActionCatalog.GET_RUNTIME_STATUS))
        assertTrue(stepsJson.contains(RuntimeActionCatalog.GET_CAPABILITIES))
    }

    @Test
    fun mixedAgentsStayInApp() {
        val agent = ClawAgentCatalog.byId("confirm_then_safe_tap")!!
        assertEquals(AgentExecutionMode.InApp, agent.executionMode)
        assertFalse(AgentRuntimeBridge.canSubmitAsRuntimeTask(agent))
        assertNull(AgentRuntimeBridge.runtimeActionForStep("page_confirm"))
        assertNull(AgentRuntimeBridge.runtimeActionForStep("probe_session"))
        assertNull(AgentRuntimeBridge.runtimeActionForStep("read_latest_capture"))
    }
}
