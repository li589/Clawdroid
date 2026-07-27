package com.clawdroid.app.termux

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxBridgeParseTest {
    private val bridge = TermuxBridge(mockk<Context>(relaxed = true))

    @Test
    fun allowlistsProotDistro() {
        val parsed = bridge.parseAllowlisted("proot-distro list")
        assertNotNull(parsed)
        assertTrue(parsed!!.executablePath.endsWith("/proot-distro"))
        assertEquals(listOf("list"), parsed.arguments)
    }

    @Test
    fun allowsBashLcScript() {
        val parsed = bridge.parseAllowlisted("bash -lc 'proot-distro install ubuntu'")
        assertNotNull(parsed)
        assertEquals(listOf("-lc", "proot-distro install ubuntu"), parsed!!.arguments)
    }

    @Test
    fun rejectsUnknownBinary() {
        assertNull(bridge.parseAllowlisted("nmap -sV"))
    }

    @Test
    fun longInstallGetsExtendedTimeout() {
        val ms = TermuxBridge.resolveTimeoutMs(
            "bash -lc 'proot-distro install ubuntu'",
            requestedMs = 30_000L
        )
        assertTrue(ms >= 480_000L)
    }

    @Test
    fun timeoutTipDoesNotLookLikeAllowExternalBlock() {
        val timedOut = com.clawdroid.app.tools.ClawToolCallResult(
            success = false,
            output = "失败: Timed out waiting for 120000 ms。确认 allow-external-apps=true 后再试",
            error = "termux_timeout"
        )
        assertNull(
            // should not treat as allow-external block
            if (TermuxBridge.isAllowExternalAppsBlocked(timedOut)) "blocked" else null
        )
    }
}
