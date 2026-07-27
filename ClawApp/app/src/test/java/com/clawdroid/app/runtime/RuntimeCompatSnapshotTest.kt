package com.clawdroid.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCompatSnapshotTest {
    @Test
    fun okWhenActionsMatch() {
        val snap = RuntimeCompatSnapshot.evaluate(
            daemonVersion = "0.2.0",
            protocolVersion = RuntimeActionCatalog.EXPECTED_PROTOCOL_VERSION,
            actions = RuntimeActionCatalog.expectedActions
        )
        assertEquals(RuntimeCompatSnapshot.Status.Ok, snap.status)
        assertTrue(snap.missingActions.isEmpty())
    }

    @Test
    fun staleWhenActionMissing() {
        val snap = RuntimeCompatSnapshot.evaluate(
            daemonVersion = "0.1.0",
            protocolVersion = 1,
            actions = setOf("ping", "get_capabilities")
        )
        assertEquals(RuntimeCompatSnapshot.Status.ModuleStale, snap.status)
        assertTrue(snap.missingActions.contains("list_dir_limited"))
    }

    @Test
    fun protocolMismatch() {
        val snap = RuntimeCompatSnapshot.evaluate(
            daemonVersion = "9.9.9",
            protocolVersion = 99,
            actions = RuntimeActionCatalog.expectedActions
        )
        assertEquals(RuntimeCompatSnapshot.Status.ProtocolMismatch, snap.status)
    }
}
