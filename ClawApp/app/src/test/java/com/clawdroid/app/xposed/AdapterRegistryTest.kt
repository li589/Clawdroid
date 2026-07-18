package com.clawdroid.app.xposed

import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class AdapterRegistryTest {
    @Before
    fun resetFuse() {
        AdapterFuse.clearForTests()
        AdapterRegistry.clearForTests()
        ActivityLifecycleHook.resetForTests()
    }

    @Test
    fun createDefaultAdaptersIncludesSelfFocusSettingsAndLauncher() {
        val adapters = AdapterRegistry.createDefaultAdapters(
            modulePackageName = "com.clawdroid.app.debug",
            config = XposedAdapterConfig.defaults()
        )
        assertEquals(
            listOf(
                "self_runtime_marker",
                "activity_focus",
                "settings_detail",
                "launcher_focus",
                "browser_detail"
            ),
            adapters.map { it.adapterId }
        )
        assertTrue(adapters.all { it.enabled })
    }

    @Test
    fun createDefaultAdaptersRespectsEnabledFlags() {
        val adapters = AdapterRegistry.createDefaultAdapters(
            modulePackageName = "com.clawdroid.app.debug",
            config = XposedAdapterConfig(
                enabledAdapters = setOf("self_runtime_marker", "launcher_focus"),
                launcherPackages = setOf("com.miui.home")
            )
        )
        assertEquals(listOf("self_runtime_marker", "launcher_focus"), adapters.map { it.adapterId })
    }

    @Test
    fun packageMatchingPrefersSpecializedOverGenericFocus() {
        val adapters = AdapterRegistry.createDefaultAdapters(
            modulePackageName = "com.clawdroid.app.debug",
            config = XposedAdapterConfig.defaults()
        )
        val self = adapters.first { it.adapterId == "self_runtime_marker" }
        val focus = adapters.first { it.adapterId == "activity_focus" }
        val settings = adapters.first { it.adapterId == "settings_detail" }
        val launcher = adapters.first { it.adapterId == "launcher_focus" }
        val browser = adapters.first { it.adapterId == "browser_detail" }

        assertTrue(self.matches(loadPackage("com.clawdroid.app.debug")))
        assertFalse(self.matches(loadPackage("com.android.settings")))
        assertFalse(focus.matches(loadPackage("com.android.settings")))
        assertTrue(focus.matches(loadPackage("com.android.systemui")))
        assertFalse(focus.matches(loadPackage("com.miui.home")))
        assertTrue(settings.matches(loadPackage("com.android.settings")))
        assertTrue(launcher.matches(loadPackage("com.miui.home")))
        assertFalse(launcher.matches(loadPackage("com.android.settings")))
        assertTrue(browser.matches(loadPackage("com.android.chrome")))
        assertFalse(focus.matches(loadPackage("com.android.chrome")))
    }

    @Test
    fun activityFocusKeepsSettingsWhenSettingsDetailDisabled() {
        val adapters = AdapterRegistry.createDefaultAdapters(
            modulePackageName = "com.clawdroid.app.debug",
            config = XposedAdapterConfig(
                enabledAdapters = setOf("self_runtime_marker", "activity_focus"),
                activityFocusPackages = setOf("com.android.settings", "com.android.systemui")
            )
        )
        val focus = adapters.first { it.adapterId == "activity_focus" }
        assertTrue(focus.matches(loadPackage("com.android.settings")))
        assertTrue(focus.matches(loadPackage("com.android.systemui")))
    }

    @Test
    fun installMatchingAdaptersInvokesInstallForMatchedPackage() {
        var installedCount = 0
        val adapter = object : TargetAppAdapter {
            override val adapterId: String = "probe_adapter"
            override val enabled: Boolean = true
            override fun matches(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
                return lpparam.packageName == "com.example.target"
            }
            override fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
                installedCount += 1
            }
        }
        val installed = AdapterRegistry.installMatchingAdapters(
            lpparam = loadPackage("com.example.target"),
            adapters = listOf(adapter),
            config = XposedAdapterConfig.defaults()
        )
        assertEquals(listOf("probe_adapter"), installed)
        assertEquals(1, installedCount)

        val skipped = AdapterRegistry.installMatchingAdapters(
            lpparam = loadPackage("com.other.app"),
            adapters = listOf(adapter),
            config = XposedAdapterConfig.defaults()
        )
        assertTrue(skipped.isEmpty())
        assertEquals(1, installedCount)
    }

    @Test
    fun installMatchingAdaptersSkipsBlownFuse() {
        val tempDir = createTempDirectory(prefix = "claw-fuse-").toFile()
        try {
            AdapterFuse.dirOverride = tempDir
            AdapterFuse.thresholdOverride = 1
            AdapterFuse.recordFailure("settings_detail")
            assertTrue(AdapterFuse.isBlown("settings_detail"))
            val adapters = AdapterRegistry.createDefaultAdapters(
                modulePackageName = "com.clawdroid.app.debug",
                config = XposedAdapterConfig.defaults()
            )
            val installed = AdapterRegistry.installMatchingAdapters(
                lpparam = loadPackage("com.android.settings"),
                adapters = adapters,
                config = XposedAdapterConfig.defaults()
            )
            assertFalse(installed.contains("settings_detail"))
        } finally {
            AdapterFuse.clearForTests()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun fuseMarkerFileAloneDoesNotBlockInstall() {
        val tempDir = createTempDirectory(prefix = "claw-fuse-file-").toFile()
        try {
            AdapterFuse.dirOverride = tempDir
            AdapterFuse.blow("settings_detail", 99)
            assertFalse(AdapterFuse.isBlown("settings_detail"))
            val adapters = AdapterRegistry.createDefaultAdapters(
                modulePackageName = "com.clawdroid.app.debug",
                config = XposedAdapterConfig.defaults()
            )
            val installed = AdapterRegistry.installMatchingAdapters(
                lpparam = loadPackage("com.android.settings"),
                adapters = adapters,
                config = XposedAdapterConfig.defaults()
            )
            assertTrue(installed.contains("settings_detail"))
        } finally {
            AdapterFuse.clearForTests()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun viewFuseDoesNotBlockMainAdapterInstall() {
        val tempDir = createTempDirectory(prefix = "claw-view-fuse-").toFile()
        try {
            AdapterFuse.dirOverride = tempDir
            AdapterFuse.thresholdOverride = 1
            AdapterFuse.recordViewFailure("settings_detail")
            assertTrue(AdapterFuse.isViewBlown("settings_detail"))
            assertFalse(AdapterFuse.isBlown("settings_detail"))
            val adapters = AdapterRegistry.createDefaultAdapters(
                modulePackageName = "com.clawdroid.app.debug",
                config = XposedAdapterConfig.defaults()
            )
            val installed = AdapterRegistry.installMatchingAdapters(
                lpparam = loadPackage("com.android.settings"),
                adapters = adapters,
                config = XposedAdapterConfig.defaults()
            )
            assertTrue(installed.contains("settings_detail"))
        } finally {
            AdapterFuse.clearForTests()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun focusSnapshotStoreWritesLatestAndNestedExtras() {
        val tempDir = createTempDirectory(prefix = "clawdroid-xposed-").toFile()
        try {
            XposedFocusSnapshotStore.snapshotDirOverride = tempDir
            val payload = XposedFocusSnapshotStore.write(
                packageName = "com.android.settings",
                processName = "com.android.settings",
                activityClass = "com.android.settings.Settings",
                adapterId = "settings_detail",
                loadedAtEpochMs = 42L,
                activityTitle = "Network",
                intentAction = "android.settings.WIRELESS_SETTINGS",
                extras = mapOf("settings_fragment" to "WifiSettings", "target" to "settings")
            )
            assertTrue(!payload.isNullOrBlank())
            val summary = XposedFocusSnapshotStore.summaryForProbe()
            assertTrue(summary.contains("com.android.settings/com.android.settings.Settings"))
            assertTrue(summary.contains("\"Network\""))
            assertTrue(summary.contains("action=android.settings.WIRELESS_SETTINGS"))
            assertTrue(summary.contains("fragment=WifiSettings"))
            assertTrue(summary.contains("[settings_detail]"))
            assertTrue(Files.exists(tempDir.toPath().resolve("focus_latest.json")))
            val ringFiles = tempDir.resolve("focus_ring").listFiles()?.filter {
                it.name.startsWith("focus_com.android.settings_") && it.name.endsWith(".json")
            }.orEmpty()
            assertEquals(1, ringFiles.size)
            assertTrue(ringFiles.first().name.contains("_42.json"))
            val raw = XposedFocusSnapshotStore.readRaw().orEmpty()
            assertTrue(raw.contains("\"schema_version\":2"))
            assertTrue(raw.contains("\"extras\""))
            assertTrue(raw.contains("settings_fragment"))
            assertFalse(raw.contains("\"settings_fragment\":\"WifiSettings\",\"package_name\""))
        } finally {
            XposedFocusSnapshotStore.snapshotDirOverride = null
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun versionGateRejectsOutOfRange() {
        val gate = AdapterVersionGate(minVersionCode = 10, maxVersionCode = 20)
        assertTrue(gate.allows(15))
        assertFalse(gate.allows(9))
        assertFalse(gate.allows(21))
        assertTrue(gate.allows(null))
    }

    @Test
    fun installMatchingAdaptersSkipsVersionGate() {
        AdapterRegistry.versionCodeOverrideForTests = 5L
        val adapters = AdapterRegistry.createDefaultAdapters(
            modulePackageName = "com.clawdroid.app.debug",
            config = XposedAdapterConfig(
                enabledAdapters = setOf("settings_detail"),
                settingsPackages = setOf("com.android.settings"),
                versionGates = mapOf(
                    "settings_detail" to AdapterVersionGate(minVersionCode = 10L, maxVersionCode = 99L)
                )
            )
        )
        val installed = AdapterRegistry.installMatchingAdapters(
            lpparam = loadPackage("com.android.settings"),
            adapters = adapters,
            config = XposedAdapterConfig(
                enabledAdapters = setOf("settings_detail"),
                settingsPackages = setOf("com.android.settings"),
                versionGates = mapOf(
                    "settings_detail" to AdapterVersionGate(minVersionCode = 10L, maxVersionCode = 99L)
                )
            )
        )
        assertTrue(installed.isEmpty())
    }

    @Test
    fun defaultBrowserPackagesIncludeOemBrowsers() {
        val browsers = XposedAdapterConfig.DEFAULT_BROWSER_PACKAGES
        assertTrue(browsers.contains("com.microsoft.emmx"))
        assertTrue(browsers.contains("org.mozilla.firefox"))
        assertTrue(browsers.contains("com.heytap.browser"))
        assertTrue(browsers.contains("com.tencent.mtt"))
        val adapters = AdapterRegistry.createDefaultAdapters(
            modulePackageName = "com.clawdroid.app.debug",
            config = XposedAdapterConfig.defaults()
        )
        val browser = adapters.first { it.adapterId == "browser_detail" }
        assertTrue(browser.matches(loadPackage("com.microsoft.emmx")))
        assertTrue(browser.matches(loadPackage("com.vivo.browser")))
    }

    private fun loadPackage(packageName: String): XC_LoadPackage.LoadPackageParam {
        return XC_LoadPackage.LoadPackageParam().apply {
            this.packageName = packageName
            this.processName = packageName
        }
    }
}
