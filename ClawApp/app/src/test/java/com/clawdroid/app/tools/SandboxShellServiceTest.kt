package com.clawdroid.app.tools

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class SandboxShellServiceTest {
    private fun service(): SandboxShellService {
        val dir = createTempDirectory("claw-sandbox").toFile()
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns dir
        return SandboxShellService(context)
    }

    @Test
    fun allowlist_accepts_safe_commands() {
        val service = service()
        assertNotNull(service.parseAllowlisted("pwd"))
        assertNotNull(service.parseAllowlisted("id"))
        assertNotNull(service.parseAllowlisted("ls"))
        assertEquals(listOf("ls", "-la", "--", "notes"), service.parseAllowlisted("ls notes"))
        assertEquals(listOf("cat", "--", "notes/a.txt"), service.parseAllowlisted("cat notes/a.txt"))
        assertNotNull(service.parseAllowlisted("mkdir -p work/tmp"))
        assertNotNull(service.parseAllowlisted("echo hello"))
        assertNotNull(service.parseAllowlisted("head -n 5 notes/a.txt"))
    }

    @Test
    fun allowlist_rejects_metachar_and_escape() {
        val service = service()
        assertNull(service.parseAllowlisted("id; rm -rf /"))
        assertNull(service.parseAllowlisted("cat ../escape.txt"))
        assertNull(service.parseAllowlisted("ls /data"))
        assertNull(service.parseAllowlisted("curl http://evil"))
        assertNull(service.parseAllowlisted("echo hi | cat"))
        assertFalse(service.isCommandAllowed("sh -c id"))
    }

    @Test
    fun exec_rejects_disallowed_without_spawning() {
        val result = service().exec("curl http://evil")
        assertFalse(result.success)
        assertEquals("sandbox_command_not_allowlisted", result.error)
    }
}
