package com.clawdroid.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputGuardsTest {
    @Test
    fun sanitizePromptStripsControlsAndCapsLength() {
        val huge = "a".repeat(InputGuards.MAX_PROMPT_CHARS + 100)
        val (sanitized, warn) = InputGuards.sanitizePromptInput("ok\u0001\n$huge")
        assertTrue(sanitized.length <= InputGuards.MAX_PROMPT_CHARS)
        assertTrue(!sanitized.contains('\u0001'))
        assertNotNull(warn)
        assertEquals("truncated", warn?.code)
    }

    @Test
    fun rejectNullByteInPrompt() {
        val err = InputGuards.validatePromptForSubmit("hello\u0000world")
        assertEquals("null_byte", err?.code)
    }

    @Test
    fun shellMetacharRejected() {
        val err = InputGuards.validateShellCommand("wm size; rm -rf /")
        assertEquals("shell_metachar", err?.code)
    }

    @Test
    fun pathTraversalRejected() {
        val err = InputGuards.validatePath("../etc/passwd")
        assertEquals("path_traversal", err?.code)
    }

    @Test
    fun fileWriteSizeRejected() {
        val err = InputGuards.validateFileWriteContent("x".repeat(InputGuards.MAX_FILE_WRITE_CHARS + 1))
        assertEquals("content_too_large", err?.code)
    }

    @Test
    fun validShellPasses() {
        assertNull(InputGuards.validateShellCommand("wm size"))
    }

    @Test
    fun termuxDetectionCommandsAllowed() {
        assertNull(InputGuards.validateShellCommand("pm path com.termux"))
        assertNull(InputGuards.validateShellCommand("pm list packages com.termux"))
        assertNull(InputGuards.validateShellCommand("ls /data/data/com.termux"))
        assertNull(InputGuards.validateShellCommand("cmd package path com.termux"))
    }

    @Test
    fun termuxPipeStillRejected() {
        val err = InputGuards.validateShellCommand("pm list packages | grep termux")
        assertEquals("shell_metachar", err?.code)
    }
}
