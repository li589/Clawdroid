package com.clawdroid.app.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

class McpHttpServerQueryTest {
    @Test
    fun decodeQueryComponentSupportsLegacyAndroidRuntime() {
        assertEquals("hello world", decodeQueryComponent("hello+world"))
        assertEquals("密钥", decodeQueryComponent("%E5%AF%86%E9%92%A5"))
    }
}
