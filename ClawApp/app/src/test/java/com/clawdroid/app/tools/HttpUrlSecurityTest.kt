package com.clawdroid.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HttpUrlSecurityTest {
    @Test
    fun acceptsPublicHttpsAddress() {
        val url = validatePublicHttpUrl("https://8.8.8.8/path")
        assertEquals("https", url.protocol)
        assertEquals("8.8.8.8", url.host)
    }

    @Test
    fun rejectsLoopbackPrivateLinkLocalAndUnsupportedUrls() {
        val blocked = listOf(
            "http://127.0.0.1",
            "http://10.0.0.1",
            "http://169.254.169.254",
            "http://[::1]",
            "file:///data/local/tmp/secret",
            "ftp://example.com/file"
        )
        for (value in blocked) {
            assertThrows(IllegalArgumentException::class.java) {
                validatePublicHttpUrl(value)
            }
        }
    }

    @Test
    fun rejectsCredentialsInUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            validatePublicHttpUrl("https://user:pass@example.com")
        }
    }
}
