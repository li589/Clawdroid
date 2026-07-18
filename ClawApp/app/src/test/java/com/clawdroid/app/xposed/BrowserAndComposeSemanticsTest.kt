package com.clawdroid.app.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUrlExtrasTest {
    @Test
    fun fromRawPrefersUrlAndKeepsPlainText() {
        val url = BrowserUrlExtras.fromRaw(
            dataUri = "https://example.com/path",
            action = IntentAction.VIEW
        )
        assertEquals("https://example.com/path", url["browser_url"])
        assertEquals(IntentAction.VIEW, url["browser_action"])

        val text = BrowserUrlExtras.fromRaw(extraText = "hello world")
        assertEquals("hello world", text["browser_text"])
        assertFalse(text.containsKey("browser_url"))

        assertTrue(BrowserUrlExtras.looksLikeUrlOrQuery("https://a.b"))
        assertFalse(BrowserUrlExtras.looksLikeUrlOrQuery("not a url"))
    }

    private object IntentAction {
        const val VIEW = "android.intent.action.VIEW"
    }
}

class ComposeSemanticsProbeTest {
    @Test
    fun mergeUniqueCapsEntries() {
        val merged = ComposeSemanticsProbe.mergeUnique(
            mapOf("a11y_text" to "Hello"),
            mapOf("sem_Text" to "World", "a11y_text" to "ignored")
        )
        assertEquals("Hello", merged["a11y_text"])
        assertEquals("World", merged["sem_Text"])
        assertEquals("a11y_text=Hello;sem_Text=World", ComposeSemanticsProbe.summarize(merged))
    }
}
