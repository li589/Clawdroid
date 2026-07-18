package com.clawdroid.app.xposed

import android.app.Activity
import android.content.Intent
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Shared focus-snapshot adapter: resume writes active snapshot; pause marks inactive.
 */
internal abstract class FocusSnapshotAdapter(
    private val id: String,
    private val targetPackages: Set<String>,
    private val targetLabel: String? = null
) : TargetAppAdapter {
    override val adapterId: String = id
    override val enabled: Boolean = targetPackages.isNotEmpty()

    override fun matches(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        return enabled && lpparam.packageName in targetPackages
    }

    override fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        ActivityLifecycleHook.installOnce(
            lpparam = lpparam,
            adapterId = adapterId,
            onResume = { activity, processName ->
                persist(activity, processName, active = true)
            },
            onPause = { activity, processName ->
                persist(activity, processName, active = false)
            }
        )
    }

    protected open fun extraFields(activity: Activity, intent: Intent?): Map<String, String> {
        val base = linkedMapOf<String, String>()
        if (!targetLabel.isNullOrBlank()) {
            base["target"] = targetLabel
        }
        base["task_root"] = activity.isTaskRoot.toString()
        base.putAll(FocusIntentExtras.safeExtras(intent))
        return base
    }

    private fun persist(activity: Activity, processName: String, active: Boolean) {
        val component = activity.componentName
            ?: android.content.ComponentName(activity.packageName, activity.javaClass.name)
        val intent = activity.intent
        val payload = XposedFocusSnapshotStore.write(
            packageName = activity.packageName,
            processName = processName,
            activityClass = component.className,
            adapterId = adapterId,
            activityTitle = activity.title?.toString()?.trim().orEmpty(),
            intentAction = intent?.action.orEmpty(),
            intentData = intent?.dataString.orEmpty(),
            extras = extraFields(activity, intent),
            active = active
        )
        if (payload == null) {
            AdapterFuse.recordFailure(adapterId)
            de.robv.android.xposed.XposedBridge.log(
                "Clawdroid/$adapterId: failed to persist focus for ${component.flattenToShortString()}"
            )
            return
        }
        // Best-effort IPC into Clawdroid; file bridge remains source of truth on failure.
        XposedFocusPush.notify(activity.applicationContext, payload)
        afterPersist(activity, processName, active, payload)
    }

    /** Hook for adapters that need extra side effects (e.g. Settings view dump). */
    protected open fun afterPersist(
        activity: Activity,
        processName: String,
        active: Boolean,
        focusPayload: String?
    ) {
    }
}
