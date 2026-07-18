package com.clawdroid.app.xposed

import android.content.Intent

/**
 * Browser / Chrome Intent URL fields for focus extras.
 */
internal object BrowserUrlExtras {
    fun fromIntent(intent: Intent?, maxChars: Int = 200): Map<String, String> {
        if (intent == null) return emptyMap()
        val out = linkedMapOf<String, String>()
        val data = intent.dataString?.trim().orEmpty()
        if (data.isNotBlank()) {
            out["browser_url"] = data.take(maxChars)
        }
        val candidates = listOf(
            Intent.EXTRA_TEXT,
            "android.intent.extra.ORIGINATING_URI",
            "url",
            "URL",
            "query"
        )
        for (key in candidates) {
            val value = intent.getStringExtra(key)?.trim().orEmpty()
            if (value.isBlank()) continue
            if (key == Intent.EXTRA_TEXT && !looksLikeUrlOrQuery(value)) {
                out.putIfAbsent("browser_text", value.take(maxChars))
                continue
            }
            out.putIfAbsent("browser_url", value.take(maxChars))
        }
        val appId = intent.getStringExtra("com.android.browser.application_id")?.trim().orEmpty()
        if (appId.isNotBlank()) {
            out["browser_app_id"] = appId.take(64)
        }
        val action = intent.action.orEmpty()
        if (action.isNotBlank()) {
            out["browser_action"] = action.take(64)
        }
        return out
    }

    /** Pure helper for tests. */
    fun fromRaw(
        dataUri: String? = null,
        extraText: String? = null,
        action: String? = null,
        maxChars: Int = 200
    ): Map<String, String> {
        val out = linkedMapOf<String, String>()
        if (!dataUri.isNullOrBlank()) {
            out["browser_url"] = dataUri.trim().take(maxChars)
        }
        if (!extraText.isNullOrBlank()) {
            val text = extraText.trim()
            if (looksLikeUrlOrQuery(text)) {
                out.putIfAbsent("browser_url", text.take(maxChars))
            } else {
                out["browser_text"] = text.take(maxChars)
            }
        }
        if (!action.isNullOrBlank()) {
            out["browser_action"] = action.trim().take(64)
        }
        return out
    }

    fun looksLikeUrlOrQuery(value: String): Boolean {
        val v = value.lowercase()
        return v.startsWith("http://") ||
            v.startsWith("https://") ||
            v.startsWith("www.") ||
            v.contains("://")
    }
}
