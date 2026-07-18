package com.clawdroid.app.focus

import com.clawdroid.app.xposed.XposedAdapterConfig

/**
 * Caller allowlist for cross-process focus/view ContentProvider pushes.
 */
internal object FocusCallerGate {
    fun isAllowedCaller(
        callingPackage: String?,
        selfPackage: String,
        allowedPackages: Set<String>
    ): Boolean {
        val caller = callingPackage?.trim().orEmpty()
        if (caller.isBlank()) return false
        if (caller == selfPackage) return true
        return caller in allowedPackages
    }

    fun allowedPackagesFromConfig(config: XposedAdapterConfig = XposedAdapterConfig.load()): Set<String> {
        return buildSet {
            if (config.isAdapterEnabled("activity_focus")) {
                addAll(config.activityFocusPackages)
            }
            if (config.isAdapterEnabled("settings_detail")) {
                addAll(config.settingsPackages)
            }
            if (config.isAdapterEnabled("launcher_focus")) {
                addAll(config.launcherPackages)
            }
            if (config.isAdapterEnabled("browser_detail")) {
                addAll(config.browserPackages)
            }
            if (config.isAdapterEnabled("wechat_detail")) {
                addAll(config.wechatPackages)
            }
        }
    }
}
