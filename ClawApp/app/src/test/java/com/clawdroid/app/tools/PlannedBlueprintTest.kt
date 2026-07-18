package com.clawdroid.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannedBlueprintTest {
    @Test
    fun describePlannedBlueprintJsonHelper() {
        // Without Android assets Context, planned list is empty; JSON helper still works.
        val json = ClawToolCatalog.plannedBlueprintJson(
            PlannedToolBlueprint(
                id = "web_render",
                tier = "Basic",
                domain = "network",
                summary = "完整网页渲染（蓝图）"
            )
        )
        assertEquals("web_render", json.getString("name"))
        assertEquals(false, json.getJSONObject("clawdroid").getBoolean("available"))
        assertEquals("Planned", json.getJSONObject("clawdroid").getString("status"))
    }

    @Test
    fun availabilityMarksMissingCaps() {
        LiveToolCapabilityStore.update(listOf("system.ping"))
        val availability = ClawToolCatalog.availabilityFor(
            ClawToolDefinitions.spec(ClawTool.INJECT_TAP)
        )
        assertTrue(!availability.available)
        assertTrue(availability.reason.startsWith("capability_missing"))
        LiveToolCapabilityStore.clear()
    }

    @Test
    fun availabilityUnknownWhenCapsEmptyButRequired() {
        LiveToolCapabilityStore.clear()
        val availability = ClawToolCatalog.availabilityFor(
            ClawToolDefinitions.spec(ClawTool.INJECT_TAP),
            capabilities = emptySet()
        )
        assertTrue(!availability.available)
        assertEquals("capabilities_unknown", availability.reason)
    }

    @Test
    fun liveStoreParsesBracketList() {
        LiveToolCapabilityStore.clear()
        LiveToolCapabilityStore.updateFromToolOutput(
            "Capabilities 成功\ncapabilities=[system.ping, file.read.limited]\ndegraded=none"
        )
        assertTrue(LiveToolCapabilityStore.snapshot().contains("system.ping"))
        assertTrue(LiveToolCapabilityStore.snapshot().contains("file.read.limited"))
        assertNotNull(LiveToolCapabilityStore.updatedAtMs())
        LiveToolCapabilityStore.clear()
    }
}
