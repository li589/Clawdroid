package com.clawdroid.app.xposed

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.clawdroid.app.BuildConfig
import com.clawdroid.app.focus.ClawFocusContentProvider

/**
 * Best-effort push of view hierarchy JSON into Clawdroid's ContentProvider.
 */
internal object XposedViewPush {
    @Volatile
    internal var modulePackageOverride: String? = null

    fun notify(context: Context, payloadJson: String): Boolean {
        val packageName = modulePackageOverride ?: BuildConfig.APPLICATION_ID
        return runCatching {
            val authority = ClawFocusContentProvider.authority(packageName)
            val extras = Bundle().apply {
                putString(ClawFocusContentProvider.EXTRA_JSON, payloadJson)
            }
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.call(
                    Uri.parse("content://$authority"),
                    ClawFocusContentProvider.METHOD_VIEW_SNAPSHOT,
                    null,
                    extras
                )
            } else {
                @Suppress("DEPRECATION")
                context.contentResolver.call(
                    authority,
                    ClawFocusContentProvider.METHOD_VIEW_SNAPSHOT,
                    null,
                    extras
                )
            }
            result?.getBoolean(ClawFocusContentProvider.KEY_OK) == true
        }.onFailure { error ->
            de.robv.android.xposed.XposedBridge.log(
                "Clawdroid/view_push failed: ${error.message ?: error::class.java.simpleName}"
            )
        }.getOrDefault(false)
    }
}
