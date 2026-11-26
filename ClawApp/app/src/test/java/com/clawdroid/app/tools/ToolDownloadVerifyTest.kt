package com.clawdroid.app.tools

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ToolDownloadVerifyTest {
    @Test
    fun verifyRejectsPathOutsideAppSandbox() {
        val sandbox = createTempDirectory("claw-dl-test").toFile()
        val outside = createTempDirectory("claw-dl-outside").toFile()
        val file = File(outside, "blob.bin")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val context = mockk<Context>()
        every { context.cacheDir } returns sandbox
        every { context.filesDir } returns sandbox
        every { context.getExternalFilesDir(null) } returns null
        every { context.externalCacheDir } returns null
        val service = ToolDownloadService(context)
        assertFalse(service.verify(file.absolutePath, LocalFileToolService.sha256File(file)).success)
        sandbox.deleteRecursively()
        outside.deleteRecursively()
    }

    @Test
    fun verifyMatchesExpectedHash() {
        val dir = createTempDirectory("claw-dl-test").toFile()
        val file = File(dir, "blob.bin")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val hash = LocalFileToolService.sha256File(file)
        val context = mockk<Context>()
        every { context.cacheDir } returns dir
        every { context.filesDir } returns dir
        every { context.getExternalFilesDir(null) } returns null
        every { context.externalCacheDir } returns null
        val service = ToolDownloadService(context)
        val ok = service.verify(file.absolutePath, hash)
        assertTrue(ok.success)
        val bad = service.verify(file.absolutePath, "0".repeat(64))
        assertFalse(bad.success)
        dir.deleteRecursively()
    }
}
