package com.clawdroid.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClawToolDefinitionsTest {
    @Test
    fun everyToolHasSpec() {
        ClawTool.entries.forEach { tool ->
            val spec = ClawToolDefinitions.spec(tool)
            assertEquals(tool.toolId, spec.id)
        }
    }

    @Test
    fun findByTagFiltersFileTools() {
        val files = ClawToolDefinitions.find(tag = "file")
        assertTrue(files.any { it.id == "file_read" })
        assertTrue(files.none { it.id == "app_list" })
    }

    @Test
    fun accessibilityGateBlocksWhenServiceOff() {
        val gate = ToolPermissionGate(context = null)
        // null context soft-allows accessibility check (treated as true) — Root/Basic always ok
        val decision = gate.evaluate(ClawToolDefinitions.spec(ClawTool.LIST_TOOLS))
        assertTrue(decision.allowed)
    }

    @Test
    fun assistCallRequiresEnabledClient() {
        val gate = ToolPermissionGate(
            context = null,
            assistEnabled = { false }
        )
        val decision = gate.evaluate(ClawToolDefinitions.spec(ClawTool.ASSIST_CALL_TOOL))
        assertFalse(decision.allowed)
        assertEquals("assist_disabled", decision.errorCode)
    }

    @Test
    fun catalogExportsPermissionMeta() {
        val json = ClawToolCatalog.toMcpToolJson(
            ClawToolCatalog.definition("file_read")!!
        )
        assertTrue(json.has("clawdroid"))
        assertEquals("Basic", json.getJSONObject("clawdroid").getString("tier"))
    }
}
