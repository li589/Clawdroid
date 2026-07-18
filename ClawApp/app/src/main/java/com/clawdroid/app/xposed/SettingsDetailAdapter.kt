package com.clawdroid.app.xposed

import android.app.Activity
import android.content.Intent

/**
 * Settings-targeted adapter with structured fragment extras + shallow View dump on resume.
 */
internal class SettingsDetailAdapter(
    targetPackages: Set<String>
) : FocusSnapshotAdapter(
    id = "settings_detail",
    targetPackages = targetPackages,
    targetLabel = "settings"
) {
    override fun extraFields(activity: Activity, intent: Intent?): Map<String, String> {
        return super.extraFields(activity, intent) + SettingsFragmentExtras.fromIntent(intent)
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
