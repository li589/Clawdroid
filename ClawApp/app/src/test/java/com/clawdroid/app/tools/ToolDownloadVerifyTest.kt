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
    fun verifyMatchesExpectedHash() {
        val dir = createTempDirectory("claw-dl-test").toFile()
        val file = File(dir, "blob.bin")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val hash = LocalFileToolService.sha256File(file)
        val context = mockk<Context>()
        every { context.cacheDir } returns dir
        val service = ToolDownloadService(context)
        val ok = service.verify(file.absolutePath, hash)
        assertTrue(ok.success)
        val bad = service.verify(file.absolutePath, "0".repeat(64))
        assertFalse(bad.success)
        dir.deleteRecursively()
    }
}
