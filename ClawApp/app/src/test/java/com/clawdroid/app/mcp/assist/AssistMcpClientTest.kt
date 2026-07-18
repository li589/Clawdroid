package com.clawdroid.app.mcp.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistMcpClientTest {
    @Test
    fun normalizeMcpUrlAppendsMcp() {
        assertEquals(
            "http://127.0.0.1:8766/mcp",
            AssistMcpClient.normalizeMcpUrl("http://127.0.0.1:8766")
        )
    }

    @Test
    fun normalizeMcpUrlRewritesSse() {
        assertEquals(
            "http://127.0.0.1:8766/mcp",
            AssistMcpClient.normalizeMcpUrl("http://127.0.0.1:8766/sse")
        )
    }

    @Test
    fun disabledClientReturnsDisabledCode() {
        val client = AssistMcpClient {
            AssistMcpConfig(enabled = false, hostUrl = "http://127.0.0.1:9/mcp")
        }
        val result = client.ping()
        assertEquals(AssistErrorCode.DISABLED, result.errorCode)
        assertTrue(!result.ok)
    }
}
