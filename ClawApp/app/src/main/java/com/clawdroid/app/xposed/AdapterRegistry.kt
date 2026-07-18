package com.clawdroid.app.xposed

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Whitelist-based adapter registry.
 * Only explicitly listed packages receive non-self hooks.
 * Package lists and adapter switches come from [XposedAdapterConfig].
 */
internal object AdapterRegistry {
    @Volatile
    internal var versionCodeOverrideForTests: Long? = null

    fun createDefaultAdapters(
        modulePackageName: String,
        config: XposedAdapterConfig = XposedAdapterConfig.load()
    ): List<TargetAppAdapter> {
        val adapters = mutableListOf<TargetAppAdapter>()
        if (config.isAdapterEnabled("self_runtime_marker")) {
            adapters += SelfRuntimeMarkerAdapter(modulePackageName = modulePackageName)
        }

        val specializedPackages = buildSet {
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

        if (config.isAdapterEnabled("activity_focus")) {
            // Prefer specialized adapters for overlapping packages to avoid double hooks.
            adapters += GenericActivityFocusAdapter(
                targetPackages = config.activityFocusPackages - specializedPackages
            )
        }
        if (config.isAdapterEnabled("settings_detail")) {
            adapters += SettingsDetailAdapter(targetPackages = config.settingsPackages)
        }
        if (config.isAdapterEnabled("launcher_focus")) {
            adapters += LauncherFocusAdapter(targetPackages = config.launcherPackages)
        }
        if (config.isAdapterEnabled("browser_detail")) {
            adapters += BrowserDetailAdapter(targetPackages = config.browserPackages)
        }
        if (config.isAdapterEnabled("wechat_detail")) {
            adapters += WeChatDetailAdapter(targetPackages = config.wechatPackages)
        }
        return adapters
    }

    fun installMatchingAdapters(
        lpparam: XC_LoadPackage.LoadPackageParam,
        adapters: List<TargetAppAdapter>,
        config: XposedAdapterConfig = XposedAdapterConfig.load()
    ): List<String> {
        val installed = mutableListOf<String>()
        val versionCode = resolveVersionCode(lpparam)
        for (adapter in adapters) {
            if (!adapter.enabled || !adapter.matches(lpparam)) {
                continue
            }
            if (AdapterFuse.isBlown(adapter.adapterId, config)) {
                XposedBridge.log(
                    "Clawdroid/adapter=${adapter.adapterId} skipped (fuse blown) for ${lpparam.packageName}"
                )
                continue
            }
            val gate = config.versionGateFor(adapter.adapterId)
            if (gate != null && !gate.allows(versionCode)) {
                XposedBridge.log(
                    "Clawdroid/adapter=${adapter.adapterId} skipped (version gate) " +
                        "pkg=${lpparam.packageName} versionCode=$versionCode"
                )
                continue
            }
            runCatching {
                adapter.install(lpparam)
                installed += adapter.adapterId
            }.onFailure { error ->
                AdapterFuse.recordFailure(adapter.adapterId, config)
                XposedBridge.log(
                    "Clawdroid/adapter=${adapter.adapterId} failed for ${lpparam.packageName}: " +
                        (error.message ?: error::class.java.simpleName)
                )
            }
        }
        return installed
    }

    internal fun resolveVersionCode(lpparam: XC_LoadPackage.LoadPackageParam): Long? {
        versionCodeOverrideForTests?.let { return it }
        return runCatching {
            val field = lpparam.javaClass.getField("appInfo")
            val appInfo = field.get(lpparam) ?: return@runCatching null
            runCatching {
                appInfo.javaClass.getField("longVersionCode").getLong(appInfo)
            }.getOrElse {
                appInfo.javaClass.getField("versionCode").getInt(appInfo).toLong()
            }
        }.getOrNull()
    }

    fun clearForTests() {
        versionCodeOverrideForTests = null
    }
}
