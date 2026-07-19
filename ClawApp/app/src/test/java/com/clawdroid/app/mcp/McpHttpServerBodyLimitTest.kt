package com.clawdroid.app.mcp

import com.clawdroid.app.tools.ClawToolDispatcher
import com.clawdroid.app.tools.ClawToolExecutor
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class McpHttpServerBodyLimitTest {
    private fun server(): McpHttpServer {
        val executor = mockk<ClawToolExecutor>(relaxed = true)
        val handler = McpJsonRpcHandler(ClawToolDispatcher(executor))
        return McpHttpServer(handler = handler, port = 0, authToken = "test-token")
    }

    @Test
    fun rejectsOversizedContentLengthBeforeAllocatingBody() {
        val header = buildString {
            append("POST /mcp HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            append("Content-Length: 999999999\r\n")
            append("\r\n")
        }
        val input = ByteArrayInputStream(header.toByteArray())
        try {
            server().readHttpRequestForTest(input, maxBodyBytes = 1024)
            fail("expected PayloadTooLargeException")
        } catch (error: PayloadTooLargeException) {
            assertEquals(999999999L, error.contentLength)
            assertEquals(1024, error.maxBytes)
        }
    }

    @Test
    fun acceptsBodyWithinLimit() {
        val payload = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        val header = buildString {
            append("POST /mcp HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            append("Content-Length: ${payload.toByteArray().size}\r\n")
            append("\r\n")
            append(payload)
        }
        val request = server().readHttpRequestForTest(
            ByteArrayInputStream(header.toByteArray()),
            maxBodyBytes = McpHttpServer.MAX_BODY_BYTES
        )
        assertEquals("POST", request.method)
        assertEquals("/mcp", request.path)
        assertTrue(String(request.body).contains("ping"))
    }
}
