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

class FtpTransferServiceTest {
    private fun service(): FtpTransferService {
        val dir = createTempDirectory("claw-ftp").toFile()
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns dir
        every { context.cacheDir } returns dir.resolve("cache").also { it.mkdirs() }
        every { context.getExternalFilesDir(null) } returns null
        every { context.externalCacheDir } returns null
        return FtpTransferService(context)
    }

    @Test
    fun sandbox_accepts_relative_and_rejects_escape() {
        val svc = service()
        val ok = svc.resolveSandboxFile("ftp/a.bin", forWrite = true)
        assertNotNull(ok)
        assertTrue(svc.isSandboxPath(ok!!))
        assertNull(svc.resolveSandboxFile("/data/local/tmp/x", forWrite = true))
        assertNull(svc.resolveSandboxFile("../escape.bin", forWrite = true))
    }

    @Test
    fun invalid_op_rejected_without_network() {
        val result = service().execute(op = "delete", host = "127.0.0.1")
        assertFalse(result.success)
        assertEquals("invalid_op", result.error)
    }

    @Test
    fun empty_host_rejected() {
        val result = service().execute(op = "list", host = "  ")
        assertFalse(result.success)
        assertEquals("invalid_host", result.error)
    }

    @Test
    fun invalid_protocol_rejected() {
        val result = service().execute(op = "list", host = "127.0.0.1", protocol = "ftps")
        assertFalse(result.success)
        assertEquals("invalid_protocol", result.error)
    }

    @Test
    fun ftp_tool_registered() {
        assertNotNull(ClawTool.byToolId("ftp_transfer"))
        assertTrue(ToolPermissionGrant.INTERNET in ClawToolDefinitions.spec(ClawTool.FTP_TRANSFER).grants)
        val keys = ClawToolCatalog.allowedArgumentKeys(ClawTool.FTP_TRANSFER)
        assertNotNull(keys)
        assertTrue(keys!!.contains("protocol"))
    }
}
