package com.clawdroid.app.xposed

import android.content.Intent

/**
 * Allowlisted Intent extras for focus snapshots (length-capped, string-only).
 */
internal object FocusIntentExtras {
    private val ALLOWED_KEYS = setOf(
        "android.intent.extra.TEXT",
        "android.intent.extra.SUBJECT",
        "android.intent.extra.TITLE",
        ":settings:fragment_args_key",
        ":settings:show_fragment",
        ":settings:show_fragment_title",
        "settings:fragment_args_key",
        "show_fragment_args",
        "extra_prefs_show_button_bar"
    )

    fun safeExtras(intent: Intent?, maxValueChars: Int = 120): Map<String, String> {
        val extras = intent?.extras ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        for (key in extras.keySet()) {
            if (key.isNullOrBlank()) continue
            val allowed = key in ALLOWED_KEYS ||
                key.startsWith(":settings:") ||
                key.startsWith("settings:")
            if (!allowed) continue
            val raw = runCatching {
                extras.getCharSequence(key)?.toString()
                    ?: extras.getString(key)
                    ?: ""
            }.getOrDefault("")
            val value = raw.trim().take(maxValueChars)
            if (value.isNotBlank()) {
                out[key.take(64)] = value
            }
            if (out.size >= 8) break
        }
        return out
    }
}
