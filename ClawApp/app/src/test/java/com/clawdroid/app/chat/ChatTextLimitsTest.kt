package com.clawdroid.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTextLimitsTest {
    @Test
    fun windowMessagesKeepsLastN() {
        val messages = (1..100).map { it }
        assertEquals((41..100).toList(), ChatTextLimits.windowMessages(messages))
    }

    @Test
    fun truncateForDisplayCapsLength() {
        val huge = "x".repeat(ChatTextLimits.MAX_CONTENT_CHARS + 100)
        val out = ChatTextLimits.truncateForDisplay(huge)
        assertTrue(out.length < huge.length)
        assertTrue(out.endsWith("...(truncated)"))
    }

    @Test
    fun truncateForContextUsesSharedLimit() {
        val out = ChatTextLimits.truncateForContext("a".repeat(5000))
        assertTrue(out.length <= ChatTextLimits.MAX_CONTEXT_CHARS + 20)
    }
}
