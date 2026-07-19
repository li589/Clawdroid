package com.clawdroid.app.chat

/**
 * Shared truncation / window limits for chat memory, prefs, and model context.
 */
object ChatTextLimits {
    const val MAX_MESSAGES = 60
    const val MAX_CONTENT_CHARS = 32 * 1024
    const val MAX_PERSIST_FIELD_CHARS = 8 * 1024
    const val MAX_CONTEXT_CHARS = 1_800
    const val MAX_HISTORY_TURN_CHARS = 400

    fun truncateForDisplay(text: String, maxChars: Int = MAX_CONTENT_CHARS): String {
        return truncate(text, maxChars)
    }

    fun truncateForContext(text: String, maxChars: Int = MAX_CONTEXT_CHARS): String {
        return truncate(text, maxChars)
    }

    fun truncateForPersist(text: String, maxChars: Int = MAX_PERSIST_FIELD_CHARS): String {
        return truncate(text, maxChars)
    }

    fun <T> windowMessages(messages: List<T>, max: Int = MAX_MESSAGES): List<T> {
        return if (messages.size <= max) messages else messages.takeLast(max)
    }

    private fun truncate(text: String, maxChars: Int): String {
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) {
            return trimmed
        }
        return trimmed.take(maxChars) + "\n...(truncated)"
    }
}
