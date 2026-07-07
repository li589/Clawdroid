package com.clawdroid.app.xposed

import android.content.Context
import com.clawdroid.app.BuildConfig
import com.clawdroid.app.env.LocalRuntimeStatus
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class ClawdroidXposedInit : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == modulePackageName) {
            installRuntimeMarkerHook(lpparam)
            return
        }
    }

    private fun installRuntimeMarkerHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val processName = lpparam.processName ?: lpparam.packageName
        XposedHelpers.findAndHookMethod(
            "android.app.Application",
            lpparam.classLoader,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = param.args.firstOrNull() as? Context ?: return
                    if (context.packageName != modulePackageName) {
                        return
                    }
                    LocalRuntimeStatus.markXposedInjected(context, processName)
                }
            }
        )
    }

    companion object {
        private val modulePackageName: String = BuildConfig.APPLICATION_ID
    }
}
