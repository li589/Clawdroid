package com.clawdroid.app.xposed

import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Target-app adapter contract for LSPosed injection.
 * Each adapter is package-scoped, independently switchable, and should fail closed.
 */
internal interface TargetAppAdapter {
    val adapterId: String
    val enabled: Boolean

    fun matches(lpparam: XC_LoadPackage.LoadPackageParam): Boolean

    fun install(lpparam: XC_LoadPackage.LoadPackageParam)
}
