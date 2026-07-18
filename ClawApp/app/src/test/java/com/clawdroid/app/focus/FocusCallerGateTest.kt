package com.clawdroid.app.focus

import com.clawdroid.app.xposed.XposedAdapterConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusCallerGateTest {
    @Test
    fun allowsSelfAndWhitelistRejectsOthers() {
        val allowed = setOf("com.android.settings", "com.android.chrome")
        assertTrue(FocusCallerGate.isAllowedCaller("com.clawdroid.app", "com.clawdroid.app", allowed))
        assertTrue(FocusCallerGate.isAllowedCaller("com.android.settings", "com.clawdroid.app", allowed))
        assertFalse(FocusCallerGate.isAllowedCaller("com.evil.app", "com.clawdroid.app", allowed))
        assertFalse(FocusCallerGate.isAllowedCaller("", "com.clawdroid.app", allowed))
        assertFalse(FocusCallerGate.isAllowedCaller(null, "com.clawdroid.app", allowed))
    }

    @Test
    fun allowedPackagesRespectEnabledAdapters() {
        val config = XposedAdapterConfig(
            enabledAdapters = setOf("settings_detail", "browser_detail"),
            settingsPackages = setOf("com.android.settings"),
            browserPackages = setOf("com.android.chrome"),
            launcherPackages = setOf("com.miui.home"),
            wechatPackages = setOf("com.tencent.mm")
        )
        val packages = FocusCallerGate.allowedPackagesFromConfig(config)
        assertTrue(packages.contains("com.android.settings"))
        assertTrue(packages.contains("com.android.chrome"))
        assertFalse(packages.contains("com.miui.home"))
        assertFalse(packages.contains("com.tencent.mm"))
    }
}
