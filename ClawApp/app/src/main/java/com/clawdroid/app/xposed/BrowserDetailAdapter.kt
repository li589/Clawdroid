package com.clawdroid.app.xposed

import android.app.Activity
import android.content.Intent

/**
 * Browser/Chrome adapter: URL intent semantics + shallow view dump (Compose-aware).
 */
internal class BrowserDetailAdapter(
    targetPackages: Set<String>
) : FocusSnapshotAdapter(
    id = "browser_detail",
    targetPackages = targetPackages,
    targetLabel = "browser"
) {
    override fun extraFields(activity: Activity, intent: Intent?): Map<String, String> {
        return super.extraFields(activity, intent) + BrowserUrlExtras.fromIntent(intent)
    }

    override fun afterPersist(
        activity: Activity,
        processName: String,
        active: Boolean,
        focusPayload: String?
    ) {
        if (!active) return
        val written = XposedViewSnapshotStore.writeFromActivity(
            activity = activity,
            adapterId = adapterId
        )
        if (written == null) {
            AdapterFuse.recordViewFailure(adapterId)
            de.robv.android.xposed.XposedBridge.log(
                "Clawdroid/$adapterId: failed to persist view dump for ${activity.javaClass.name}"
            )
            return
        }
        XposedViewPush.notify(activity.applicationContext, written)
    }
}
