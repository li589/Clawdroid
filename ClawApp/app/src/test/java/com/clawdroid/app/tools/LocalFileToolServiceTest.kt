package com.clawdroid.app.tools

import android.content.Context
import com.clawdroid.app.runtime.ClawRuntimeClient
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class LocalFileToolServiceTest {
    @Test
    fun sha256Stable() {
        val dir = createTempDirectory("claw-file-test").toFile()
        val file = File(dir, "a.txt")
        file.writeText("hello")
        val hash = LocalFileToolService.sha256File(file)
        assertEquals(64, hash.length)
        assertEquals(hash, LocalFileToolService.sha256File(file))
        dir.deleteRecursively()
    }

    @Test
    fun sha256BytesMatches() {
        val bytes = "hello".toByteArray()
        assertTrue(LocalFileToolService.sha256Bytes(bytes).startsWith("2cf24dba"))
    }

    @Test
    fun replaceRejectsEmptyFind() {
        runBlocking {
            val dir = createTempDirectory("claw-replace").toFile()
            val service = serviceFor(dir)
            val result = service.replace(
                path = "x.txt",
                find = "",
                replace = "a",
                regex = false,
                lineStart = null,
                lineEnd = null
            )
            assertFalse(result.success)
            assertEquals("empty_find", result.error)
            dir.deleteRecursively()
        }
    }

    @Test
    fun replaceRejectsBadRegex() {
        runBlocking {
            val dir = createTempDirectory("claw-replace2").toFile()
            File(dir, "x.txt").writeText("abc")
            val service = serviceFor(dir)
            val result = service.replace(
                path = "x.txt",
                find = "(",
                replace = "a",
                regex = true,
                lineStart = null,
                lineEnd = null
            )
            assertFalse(result.success)
            assertEquals("invalid_regex", result.error)
            dir.deleteRecursively()
        }
    }

    @Test
    fun replaceRejectsInvalidLineRange() {
        runBlocking {
            val dir = createTempDirectory("claw-replace3").toFile()
            File(dir, "x.txt").writeText("hello")
            val service = serviceFor(dir)
            val result = service.replace(
                path = "x.txt",
                find = "h",
                replace = "H",
                regex = false,
                lineStart = 3,
                lineEnd = 1
            )
            assertFalse(result.success)
            assertEquals("invalid_line_range", result.error)
            dir.deleteRecursively()
        }
    }

    private fun serviceFor(dir: File): LocalFileToolService {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.filesDir } returns dir
        every { ctx.cacheDir } returns dir
        every { ctx.getExternalFilesDir(null) } returns dir
        every { ctx.externalCacheDir } returns dir
        return LocalFileToolService(ctx, mockk<ClawRuntimeClient>(relaxed = true))
    }
}
