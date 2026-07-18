package com.clawdroid.app.xposed

import com.clawdroid.app.BuildConfig
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class ClawdroidXposedInit : IXposedHookLoadPackage {
    private val config by lazy { XposedAdapterConfig.load() }

    private val adapters by lazy {
        AdapterRegistry.createDefaultAdapters(
            modulePackageName = modulePackageName,
            config = config
        )
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val installed = AdapterRegistry.installMatchingAdapters(
            lpparam = lpparam,
            adapters = adapters
        )
        if (installed.isNotEmpty()) {
            XposedBridge.log(
                "Clawdroid Xposed adapters loaded for ${lpparam.packageName}: ${installed.joinToString()}"
            )
        }
    }

    companion object {
        private val modulePackageName: String = BuildConfig.APPLICATION_ID
    }
}
