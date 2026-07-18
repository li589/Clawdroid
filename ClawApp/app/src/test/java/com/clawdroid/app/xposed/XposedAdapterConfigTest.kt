package com.clawdroid.app.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class XposedAdapterConfigTest {
    @Test
    fun parseOverridesPackageListsAndEnabledAdapters() {
        val raw = """
            {
              "version": 2,
              "enabled_adapters": ["self_runtime_marker", "activity_focus"],
              "activity_focus_packages": ["com.android.settings", "com.example.target"],
              "settings_packages": ["com.android.settings"],
              "launcher_packages": ["com.miui.home"]
            }
        """.trimIndent()

        val config = XposedAdapterConfig.parse(raw).getOrThrow()
        assertEquals(2, config.version)
        assertEquals(setOf("self_runtime_marker", "activity_focus"), config.enabledAdapters)
        assertEquals(setOf("com.android.settings", "com.example.target"), config.activityFocusPackages)
        assertEquals(setOf("com.android.settings"), config.settingsPackages)
        assertEquals(setOf("com.miui.home"), config.launcherPackages)
        assertTrue(config.isAdapterEnabled("activity_focus"))
        assertFalse(config.isAdapterEnabled("launcher_focus"))
    }

    @Test
    fun loadFallsBackToDefaultsWhenFileMissing() {
        val tempDir = createTempDirectory(prefix = "clawdroid-adapters-").toFile()
        try {
            XposedAdapterConfig.configFileOverride = tempDir.resolve("adapters.json")
            val config = XposedAdapterConfig.load()
            assertEquals(XposedAdapterConfig.DEFAULT_ENABLED_ADAPTERS, config.enabledAdapters)
            assertTrue(config.activityFocusPackages.contains("com.android.settings"))
            assertTrue(config.launcherPackages.contains("com.miui.home"))
        } finally {
            XposedAdapterConfig.configFileOverride = null
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun writeDefaultsIfMissingAndRoundTrip() {
        val tempDir = createTempDirectory(prefix = "clawdroid-adapters-write-").toFile()
        try {
            val configFile = tempDir.resolve("adapters.json")
            XposedAdapterConfig.configFileOverride = configFile
            assertTrue(XposedAdapterConfig.writeDefaultsIfMissing())
            assertFalse(XposedAdapterConfig.writeDefaultsIfMissing())
            assertTrue(configFile.exists())

            val loaded = XposedAdapterConfig.load()
            assertEquals(XposedAdapterConfig.defaults().enabledAdapters, loaded.enabledAdapters)
            assertEquals(
                XposedAdapterConfig.defaults().activityFocusPackages,
                loaded.activityFocusPackages
            )
            assertTrue(
                XposedAdapterConfig.summaryForProbe(loaded).contains("adapters=")
            )
        } finally {
            XposedAdapterConfig.configFileOverride = null
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun invalidJsonFallsBackToDefaultsOnLoad() {
        val tempDir = createTempDirectory(prefix = "clawdroid-adapters-bad-").toFile()
        try {
            val configFile = tempDir.resolve("adapters.json")
            configFile.writeText("not-json")
            XposedAdapterConfig.configFileOverride = configFile
            assertEquals(XposedAdapterConfig.defaults(), XposedAdapterConfig.load())
            assertTrue(XposedAdapterConfig.parse("not-json").isFailure)
        } finally {
            XposedAdapterConfig.configFileOverride = null
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun ensureDefaultsSeededWritesOnceAndSupportsRootFallback() {
        val tempDir = createTempDirectory(prefix = "clawdroid-adapters-seed-").toFile()
        try {
            val configFile = tempDir.resolve("adapters.json")
            XposedAdapterConfig.configFileOverride = configFile

            assertTrue(XposedAdapterConfig.ensureDefaultsSeeded(rootCommand = null))
            assertTrue(configFile.exists())
            assertFalse(XposedAdapterConfig.ensureDefaultsSeeded(rootCommand = null))
            assertTrue(XposedAdapterConfig.summaryForProbe().contains("source=file"))

            // Force direct-write failure: parent path is a regular file, not a directory.
            val parentAsFile = tempDir.resolve("parent-as-file")
            parentAsFile.writeText("not-a-directory")
            XposedAdapterConfig.configFileOverride = File(parentAsFile, "adapters.json")

            var rootCalls = 0
            assertTrue(
                XposedAdapterConfig.ensureDefaultsSeeded { command ->
                    rootCalls += 1
                    assertTrue(command.contains("mkdir -p"))
                    assertTrue(command.contains("CLAWDROID_EOF"))
                    // Simulate successful root write onto a readable path.
                    XposedAdapterConfig.configFileOverride = configFile
                    configFile.writeText(XposedAdapterConfig.defaults().toJson())
                    true
                }
            )
            assertEquals(1, rootCalls)
            assertTrue(XposedAdapterConfig.hasReadableConfig())
        } finally {
            XposedAdapterConfig.configFileOverride = null
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun parseFuseAndVersionGates() {
        val raw = """
            {
              "version": 2,
              "fuse_after_failures": 3,
              "enabled_adapters": ["settings_detail"],
              "adapter_version_gates": {
                "settings_detail": { "min_version_code": 10, "max_version_code": 99 }
              }
            }
        """.trimIndent()
        val config = XposedAdapterConfig.parse(raw).getOrThrow()
        assertEquals(3, config.fuseAfterFailures)
        val gate = config.versionGateFor("settings_detail")
        assertEquals(10L, gate?.minVersionCode)
        assertEquals(99L, gate?.maxVersionCode)
        assertTrue(gate!!.allows(50))
        assertFalse(gate.allows(5))
    }
}
