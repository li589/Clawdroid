package com.clawdroid.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchServiceTest {
    @Test
    fun rejects_blank_query() {
        val result = WebSearchService().search("   ")
        assertFalse(result.success)
        assertEquals("invalid_query", result.error)
    }

    @Test
    fun rejects_unknown_provider() {
        val result = WebSearchService().search("android", provider = "bing")
        assertFalse(result.success)
        assertEquals("invalid_provider", result.error)
    }

    @Test
    fun parseDuckDuckGoHtml_extracts_hits() {
        val html = """
            <div class="result">
              <a class="result__a" href="https://example.com/a">Alpha Title</a>
              <a class="result__snippet">Alpha snippet text</a>
            </div>
            <div class="result">
              <a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Fb">Beta</a>
              <a class="result__snippet">Beta snip</a>
            </div>
        """.trimIndent()
        val hits = WebSearchService().parseDuckDuckGoHtml(html, limit = 5)
        assertEquals(2, hits.size)
        assertEquals("Alpha Title", hits[0].title)
        assertEquals("https://example.com/a", hits[0].url)
        assertTrue(hits[1].url.contains("example.com/b"))
        assertEquals("ddg", hits[0].provider)
    }
}
