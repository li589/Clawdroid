package com.clawdroid.app.runtime

import android.content.Context
import com.clawdroid.app.BuildConfig

/**
 * Resolves the Runtime IPC shared secret.
 *
 * Prefer a device-side override (for re-pairing after Magisk YAML rotation) and
 * fall back to [BuildConfig.CLAW_RUNTIME_SHARED_SECRET] so existing installs keep working.
 */
object RuntimeSecretStore {
    private const val PREFS = "claw_runtime_secret"
    private const val KEY_OVERRIDE = "shared_secret_override"

    fun resolve(context: Context): String {
        val override = getOverride(context)
        return if (!override.isNullOrBlank()) override.trim() else BuildConfig.CLAW_RUNTIME_SHARED_SECRET
    }

    fun getOverride(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OVERRIDE, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun setOverride(context: Context, secret: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        val trimmed = secret?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.remove(KEY_OVERRIDE)
        } else {
            prefs.putString(KEY_OVERRIDE, trimmed)
        }
        prefs.apply()
    }

    fun clearOverride(context: Context) {
        setOverride(context, null)
    }

    fun usingOverride(context: Context): Boolean = !getOverride(context).isNullOrBlank()
}
