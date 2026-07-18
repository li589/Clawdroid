package com.clawdroid.app.xposed

import android.app.Activity
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared Activity onResume/onPause hooks for package-scoped focus adapters.
 */
internal object ActivityLifecycleHook {
    private val installedKeys = ConcurrentHashMap.newKeySet<String>()

    fun installOnce(
        lpparam: XC_LoadPackage.LoadPackageParam,
        adapterId: String,
        onResume: (activity: Activity, processName: String) -> Unit,
        onPause: ((activity: Activity, processName: String) -> Unit)? = null
    ) {
        val key = "$adapterId:${lpparam.packageName}:${lpparam.processName ?: lpparam.packageName}"
        if (!installedKeys.add(key)) {
            return
        }
        val processName = lpparam.processName ?: lpparam.packageName
        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            lpparam.classLoader,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    runCatching {
                        onResume(activity, processName)
                    }.onFailure { error ->
                        AdapterFuse.recordFailure(adapterId)
                        XposedBridge.log(
                            "Clawdroid/$adapterId onResume failed: " +
                                (error.message ?: error::class.java.simpleName)
                        )
                    }
                }
            }
        )
        if (onPause != null) {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                lpparam.classLoader,
                "onPause",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        runCatching {
                            onPause(activity, processName)
                        }.onFailure { error ->
                            AdapterFuse.recordFailure(adapterId)
                            XposedBridge.log(
                                "Clawdroid/$adapterId onPause failed: " +
                                    (error.message ?: error::class.java.simpleName)
                            )
                        }
                    }
                }
            )
        }
        XposedBridge.log(
            "Clawdroid/$adapterId: lifecycle hooks for package=${lpparam.packageName} process=$processName"
        )
    }

    /** Test-only: clear install dedupe state between unit tests if needed. */
    internal fun resetForTests() {
        installedKeys.clear()
    }
}

/** @deprecated Use [ActivityLifecycleHook]. */
internal typealias ActivityResumeHook = ActivityLifecycleHook
