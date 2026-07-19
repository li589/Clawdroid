package com.clawdroid.app.env

import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityResetScriptTest {
    @Test
    fun resetScriptWaitsForBootThenClearsBeforeRebind() {
        val script = AppPermissionManager.buildAccessibilityResetAndEnableScript(
            packageName = "com.clawdroid.app.debug",
            component = "com.clawdroid.app.debug/com.clawdroid.app.service.ClawAccessibilityService",
            shortComponent = "com.clawdroid.app.debug/.service.ClawAccessibilityService"
        )
        assertTrue(script.contains("sys.boot_completed"))
        assertTrue(script.contains("accessibility_enabled 0"))
        assertTrue(script.contains("accessibility_enabled 1"))
        assertTrue(script.contains("com.clawdroid.app.debug/com.clawdroid.app.service.ClawAccessibilityService"))
    }
}
