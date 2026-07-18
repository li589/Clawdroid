package com.clawdroid.app.xposed

import android.content.Intent
import android.os.Bundle

/**
 * Structured Settings Intent fields for focus extras.
 */
internal object SettingsFragmentExtras {
    private const val EXTRA_SHOW_FRAGMENT = ":settings:show_fragment"
    private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_EMBEDDED_DEEP_LINK =
        "android.provider.extra.SETTINGS_EMBEDDED_DEEP_LINK_INTENT_URI"

    fun fromIntent(intent: Intent?, maxChars: Int = 160): Map<String, String> {
        if (intent == null) return emptyMap()
        val out = linkedMapOf<String, String>()
        val fragment = intent.getStringExtra(EXTRA_SHOW_FRAGMENT)
            ?: intent.getStringExtra("show_fragment")
            ?: intent.getStringExtra("android:settings:show_fragment")
        if (!fragment.isNullOrBlank()) {
            out["settings_fragment"] = fragment.trim().take(maxChars)
        }
        val argKey = intent.getStringExtra(EXTRA_FRAGMENT_ARG_KEY)
            ?: intent.getStringExtra("settings:fragment_args_key")
        if (!argKey.isNullOrBlank()) {
            out["settings_fragment_args_key"] = argKey.trim().take(64)
        }
        val args = intent.getBundleExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS)
            ?: intent.getBundleExtra("show_fragment_args")
        val argsSummary = summarizeBundle(args, maxChars)
        if (argsSummary.isNotBlank()) {
            out["settings_arguments"] = argsSummary
        }
        val deepLink = intent.getStringExtra(EXTRA_EMBEDDED_DEEP_LINK)
        if (!deepLink.isNullOrBlank()) {
            out["settings_deep_link"] = deepLink.trim().take(maxChars)
        }
        return out
    }

    /** Pure helper for unit tests (no Android Intent). */
    fun fromRaw(
        showFragment: String? = null,
        fragmentArgsKey: String? = null,
        argumentsSummary: String? = null,
        deepLink: String? = null,
        maxChars: Int = 160
    ): Map<String, String> {
        val out = linkedMapOf<String, String>()
        if (!showFragment.isNullOrBlank()) {
            out["settings_fragment"] = showFragment.trim().take(maxChars)
        }
        if (!fragmentArgsKey.isNullOrBlank()) {
            out["settings_fragment_args_key"] = fragmentArgsKey.trim().take(64)
        }
        if (!argumentsSummary.isNullOrBlank()) {
            out["settings_arguments"] = argumentsSummary.trim().take(maxChars)
        }
        if (!deepLink.isNullOrBlank()) {
            out["settings_deep_link"] = deepLink.trim().take(maxChars)
        }
        return out
    }

    private fun summarizeBundle(bundle: Bundle?, maxChars: Int): String {
        if (bundle == null || bundle.isEmpty) return ""
        val parts = mutableListOf<String>()
        for (key in bundle.keySet().take(6)) {
            if (key.isNullOrBlank()) continue
            val value = runCatching {
                bundle.get(key)?.toString().orEmpty()
            }.getOrDefault("")
            if (value.isBlank()) continue
            parts += "${key.take(32)}=${value.take(40)}"
        }
        return parts.joinToString(";").take(maxChars)
    }
}
