package com.clawdroid.app.focus

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.clawdroid.app.xposed.XposedAdapterConfig

/**
 * Receives focus/view snapshots from LSPosed hooks in whitelist target processes.
 * Callers must be this app or an enabled adapter package.
 */
class ClawFocusContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle()
        if (!authorizeCaller()) {
            result.putBoolean(KEY_OK, false)
            result.putString(KEY_ERROR, "caller_not_allowed")
            return result
        }
        when (method) {
            METHOD_FOCUS_SNAPSHOT -> {
                val json = extras?.getString(EXTRA_JSON).orEmpty()
                val ok = LiveXposedFocusStore.updateFromJson(json)
                result.putBoolean(KEY_OK, ok)
                if (ok) {
                    result.putLong(KEY_UPDATED_AT, LiveXposedFocusStore.updatedAtMs())
                }
            }
            METHOD_VIEW_SNAPSHOT -> {
                val json = extras?.getString(EXTRA_JSON).orEmpty()
                val ok = LiveXposedViewStore.updateFromJson(json)
                result.putBoolean(KEY_OK, ok)
                if (ok) {
                    result.putLong(KEY_UPDATED_AT, LiveXposedViewStore.updatedAtMs())
                }
            }
            else -> result.putBoolean(KEY_OK, false)
        }
        return result
    }

    private fun authorizeCaller(): Boolean {
        val ctx = context ?: return false
        val self = ctx.packageName
        val uid = Binder.getCallingUid()
        val packages = ctx.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        if (packages.isEmpty()) {
            return FocusCallerGate.isAllowedCaller(null, self, emptySet())
        }
        val allowed = FocusCallerGate.allowedPackagesFromConfig(XposedAdapterConfig.load())
        return packages.any { pkg ->
            FocusCallerGate.isAllowedCaller(pkg, self, allowed)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val METHOD_FOCUS_SNAPSHOT = "focus_snapshot"
        const val METHOD_VIEW_SNAPSHOT = "view_snapshot"
        const val EXTRA_JSON = "json"
        const val KEY_OK = "ok"
        const val KEY_UPDATED_AT = "updated_at_epoch_ms"
        const val KEY_ERROR = "error"

        fun authority(applicationId: String): String = "$applicationId.focus"
    }
}
