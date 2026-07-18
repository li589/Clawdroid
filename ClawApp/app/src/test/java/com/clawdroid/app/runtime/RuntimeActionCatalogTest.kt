package com.clawdroid.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeActionCatalogTest {
    @Test
    fun catalogCoversCoreAndExtendedActions() {
        assertEquals(
            RuntimeActionCatalog.CAPABILITY_SYSTEM_INSPECT,
            RuntimeActionCatalog.capabilityFor(RuntimeActionCatalog.GET_RUNTIME_STATUS)
        )
        assertEquals(
            RuntimeActionCatalog.CAPABILITY_INPUT_INJECT,
            RuntimeActionCatalog.capabilityFor(RuntimeActionCatalog.INJECT_KEYEVENT)
        )
        assertTrue(RuntimeActionCatalog.actionToCapability.containsKey(RuntimeActionCatalog.TASK_SUBMIT))
        assertEquals(18, RuntimeActionCatalog.actionToCapability.size)
        assertEquals(
            RuntimeActionCatalog.CAPABILITY_EVENT_REPORT,
            RuntimeActionCatalog.capabilityFor(RuntimeActionCatalog.REPORT_XPOSED_FOCUS)
        )
        assertEquals(
            RuntimeActionCatalog.CAPABILITY_EVENT_REPORT,
            RuntimeActionCatalog.capabilityFor(RuntimeActionCatalog.REPORT_XPOSED_VIEW)
        )
        assertEquals(
            RuntimeActionCatalog.CAPABILITY_FILE_WRITE_LIMITED,
            RuntimeActionCatalog.capabilityFor(RuntimeActionCatalog.WRITE_FILE_LIMITED)
        )
    }
}
