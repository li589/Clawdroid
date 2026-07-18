package com.clawdroid.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPreviewServiceTest {
    @Test
    fun rejects_non_http_urls() {
        val result = WebPreviewService().preview("ftp://example.com/a")
        assertFalse(result.success)
        assertEquals("invalid_url", result.error)
    }

    @Test
    fun rejects_blank_scheme() {
        val result = WebPreviewService().preview("example.com")
        assertFalse(result.success)
        assertTrue(result.error == "invalid_url")
    }
}
