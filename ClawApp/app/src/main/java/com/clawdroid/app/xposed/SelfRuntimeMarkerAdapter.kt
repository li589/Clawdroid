package com.clawdroid.app.xposed

import android.content.Context
import com.clawdroid.app.env.LocalRuntimeStatus
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Self-process adapter: marks that Clawdroid's own process was injected by LSPosed.
 */
internal class SelfRuntimeMarkerAdapter(
    private val modulePackageName: String
) : TargetAppAdapter {
    override val adapterId: String = "self_runtime_marker"
    override val enabled: Boolean = true

    override fun matches(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        return lpparam.packageName == modulePackageName
    }

    override fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val processName = lpparam.processName ?: lpparam.packageName
        XposedHelpers.findAndHookMethod(
            "android.app.Application",
            lpparam.classLoader,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val context = param.args.firstOrNull() as? Context ?: return
                        if (context.packageName != modulePackageName) {
                            return
                        }
                        LocalRuntimeStatus.markXposedInjected(context, processName)
                    }.onFailure { error ->
                        XposedBridge.log(
                            "Clawdroid/$adapterId afterHookedMethod failed: " +
                                (error.message ?: error::class.java.simpleName)
                        )
                    }
                }
            }
        )
    }
}
