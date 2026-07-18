package com.clawdroid.app.env

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuSupportTest {
    @Test
    fun allowlist_accepts_fixed_and_parameterized() {
        assertTrue(ShizukuSupport.isCommandAllowed("id"))
        assertTrue(ShizukuSupport.isCommandAllowed("  whoami  "))
        assertTrue(ShizukuSupport.isCommandAllowed("pm path com.android.settings"))
        assertTrue(ShizukuSupport.isCommandAllowed("dumpsys package com.clawdroid.app"))
        assertTrue(ShizukuSupport.isCommandAllowed("pidof com.android.systemui"))
    }

    @Test
    fun allowlist_rejects_shell_metacharacters() {
        assertFalse(ShizukuSupport.isCommandAllowed("id; rm -rf /"))
        assertFalse(ShizukuSupport.isCommandAllowed("id && reboot"))
        assertFalse(ShizukuSupport.isCommandAllowed("pm path com.a | cat"))
        assertFalse(ShizukuSupport.isCommandAllowed("curl http://evil"))
        assertFalse(ShizukuSupport.isCommandAllowed(""))
    }
}
