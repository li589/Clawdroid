package com.clawdroid.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class SandboxShellServiceTest {
    private fun engine(): SandboxShellEngine {
        val dir = createTempDirectory("claw-sandbox").toFile()
        return SandboxShellEngine(dir)
    }

    @Test
    fun allowlist_accepts_safe_commands() {
        val engine = engine()
        assertNotNull(engine.parseAllowlisted("pwd"))
        assertNotNull(engine.parseAllowlisted("id"))
        assertNotNull(engine.parseAllowlisted("ls"))
        assertEquals(listOf("ls", "-la", "--", "notes"), engine.parseAllowlisted("ls notes"))
        assertEquals(listOf("cat", "--", "notes/a.txt"), engine.parseAllowlisted("cat notes/a.txt"))
        assertNotNull(engine.parseAllowlisted("mkdir -p work/tmp"))
        assertNotNull(engine.parseAllowlisted("echo hello"))
        assertNotNull(engine.parseAllowlisted("head -n 5 notes/a.txt"))
    }

    @Test
    fun allowlist_rejects_metachar_and_escape() {
        val engine = engine()
        assertNull(engine.parseAllowlisted("id; rm -rf /"))
        assertNull(engine.parseAllowlisted("cat ../escape.txt"))
        assertNull(engine.parseAllowlisted("ls /data"))
        assertNull(engine.parseAllowlisted("curl http://evil"))
        assertNull(engine.parseAllowlisted("echo hi | cat"))
        assertFalse(engine.isCommandAllowed("sh -c id"))
    }

    @Test
    fun exec_rejects_disallowed_without_spawning() {
        val result = engine().exec("curl http://evil")
        assertFalse(result.success)
        assertEquals("sandbox_command_not_allowlisted", result.error)
    }

    @Test
    fun result_codec_roundtrip() {
        val original = ClawToolCallResult(
            success = true,
            output = "ok",
            shellOutput = "ok"
        )
        val encoded = SandboxShellEngine.encodeResult(original)
        val decoded = SandboxShellEngine.decodeResult(encoded)
        assertEquals(original.success, decoded.success)
        assertEquals(original.output, decoded.output)
        assertEquals(original.shellOutput, decoded.shellOutput)
    }
}
