package com.clawdroid.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPermissionGateTest {
    @Test
    fun capabilityMissingWhenLiveCapsKnown() {
        LiveToolCapabilityStore.update(listOf("system.ping"))
        val gate = ToolPermissionGate(
            context = null,
            knownCapabilities = { LiveToolCapabilityStore.snapshot() }
        )
        val decision = gate.evaluate(ClawToolDefinitions.spec(ClawTool.CAPTURE_SCREEN))
        assertFalse(decision.allowed)
        assertEquals("capability_missing", decision.errorCode)
        LiveToolCapabilityStore.clear()
    }

    @Test
    fun capabilityOkWhenPresent() {
        LiveToolCapabilityStore.update(
            listOf(
                RuntimeActionCatalogLike.SCREEN_CAPTURE
            )
        )
        // Use real catalog constant via string to avoid coupling if renamed in gate path
        LiveToolCapabilityStore.update(listOf("screen.capture"))
        val gate = ToolPermissionGate(
            context = null,
            knownCapabilities = { LiveToolCapabilityStore.snapshot() }
        )
        val decision = gate.evaluate(ClawToolDefinitions.spec(ClawTool.CAPTURE_SCREEN))
        assertTrue(decision.allowed)
        LiveToolCapabilityStore.clear()
    }

    @Test
    fun emptyLiveCapsSoftAllowRequiredCaps() {
        LiveToolCapabilityStore.clear()
        val gate = ToolPermissionGate(
            context = null,
            knownCapabilities = { emptySet() }
        )
        val decision = gate.evaluate(ClawToolDefinitions.spec(ClawTool.CAPTURE_SCREEN))
        assertTrue(decision.allowed)
    }

    @Test
    fun denyWhenToolNotAllowed() {
        val gate = ToolPermissionGate(
            context = null,
            isToolAllowed = { it != "sandbox_shell" }
        )
        val decision = gate.evaluate(ClawToolDefinitions.spec(ClawTool.SANDBOX_SHELL))
        assertFalse(decision.allowed)
        assertEquals("tool_not_allowed", decision.errorCode)
    }

    @Test
    fun denyAgentCallsWhenDisabled() {
        val gate = ToolPermissionGate(
            context = null,
            agentCallsEnabled = { false }
        )
        val decision = gate.evaluate(ClawToolDefinitions.spec(ClawTool.RUN_AGENT))
        assertFalse(decision.allowed)
        assertEquals("agent_calls_disabled", decision.errorCode)
    }

    private object RuntimeActionCatalogLike {
        const val SCREEN_CAPTURE = "screen.capture"
    }
}
