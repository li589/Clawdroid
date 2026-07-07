package com.clawdroid.app.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEnvironmentProbeTest {
    @Test
    fun parsePersistedMarkerContentReturnsParsedXposedFields() {
        val status = parsePersistedMarkerContent(
            """
            {"xposed_injected":true,"process_name":"zygote64","loaded_at_epoch_ms":123}
            """.trimIndent()
        )

        assertTrue(status.xposedInjected)
        assertEquals("zygote64", status.xposedProcessName)
        assertEquals(123L, status.xposedLoadedAtEpochMs)
        assertEquals(null, status.rootGranted)
    }

    @Test
    fun parsePersistedMarkerContentFallsBackForInvalidJson() {
        val status = parsePersistedMarkerContent("{invalid")

        assertEquals(emptyEnvironmentStatus(), status)
    }

    @Test
    fun hasAccessibilityServiceEnabledMatchesFullAndShortComponentNames() {
        val full = "com.clawdroid.app/com.clawdroid.app.service.ClawAccessibilityService"
        val short = "com.clawdroid.app/.service.ClawAccessibilityService"
        val enabledServices = "com.other/.OtherService : $short : another/.Service"

        assertTrue(
            hasAccessibilityServiceEnabled(
                enabledFlag = 1,
                enabledServices = enabledServices,
                fullComponentName = full,
                shortComponentName = short
            )
        )
    }

    @Test
    fun hasAccessibilityServiceEnabledRejectsDisabledOrBlankState() {
        val full = "com.clawdroid.app/com.clawdroid.app.service.ClawAccessibilityService"
        val short = "com.clawdroid.app/.service.ClawAccessibilityService"

        assertFalse(
            hasAccessibilityServiceEnabled(
                enabledFlag = 0,
                enabledServices = short,
                fullComponentName = full,
                shortComponentName = short
            )
        )
        assertFalse(
            hasAccessibilityServiceEnabled(
                enabledFlag = 1,
                enabledServices = "   ",
                fullComponentName = full,
                shortComponentName = short
            )
        )
    }

    @Test
    fun resolveXposedStatePrefersLiveRuntimeValues() {
        assertEquals(
            "zygote64",
            resolveXposedProcessName(
                xposedInjected = true,
                currentProcessName = "zygote64",
                persistedProcessName = "app_process"
            )
        )
        assertEquals(
            456L,
            resolveXposedLoadedAtEpochMs(
                xposedInjected = true,
                currentLoadedAtEpochMs = 456L,
                persistedLoadedAtEpochMs = 123L
            )
        )
    }

    @Test
    fun resolveXposedStateFallsBackToPersistedMarkerWhenNotInjected() {
        assertEquals(
            "app_process",
            resolveXposedProcessName(
                xposedInjected = false,
                currentProcessName = "zygote64",
                persistedProcessName = "app_process"
            )
        )
        assertEquals(
            123L,
            resolveXposedLoadedAtEpochMs(
                xposedInjected = false,
                currentLoadedAtEpochMs = 456L,
                persistedLoadedAtEpochMs = 123L
            )
        )
    }

    @Test
    fun isAppOpAllowedOutputMatchesAllowCaseInsensitively() {
        assertTrue(isAppOpAllowedOutput("WRITE_SETTINGS: Allow"))
        assertFalse(isAppOpAllowedOutput("WRITE_SETTINGS: deny"))
    }

    @Test
    fun buildLocalEnvironmentDiagnosisFlagsMissingRootAsManualAuthorizationIssue() {
        val diagnosis = buildLocalEnvironmentDiagnosis(
            emptyEnvironmentStatus().copy(rootGranted = false)
        )

        assertEquals("等待 Root 授权", diagnosis.title)
        assertTrue(diagnosis.actionHint.contains("狐狸面具"))
    }

    @Test
    fun buildLocalEnvironmentDiagnosisFlagsMissingRuntimeWhenModuleEnabled() {
        val diagnosis = buildLocalEnvironmentDiagnosis(
            emptyEnvironmentStatus().copy(
                rootGranted = true,
                magiskDaemonRunning = true,
                magiskModuleInstalled = true,
                magiskModuleEnabled = true,
                runtimeDaemonRunning = false
            )
        )

        assertEquals("模块已启用，但 Runtime 守护未运行", diagnosis.title)
        assertTrue(diagnosis.detail.contains("clawdroid-runtime"))
    }

    @Test
    fun buildLocalEnvironmentDiagnosisReturnsReadyForHealthyRootAndRuntimeChain() {
        val diagnosis = buildLocalEnvironmentDiagnosis(
            emptyEnvironmentStatus().copy(
                rootGranted = true,
                magiskDaemonRunning = true,
                magiskModuleInstalled = true,
                magiskModuleEnabled = true,
                runtimeDaemonRunning = true
            )
        )

        assertEquals("Root 与模块链路正常", diagnosis.title)
        assertTrue(diagnosis.actionHint.contains("Runtime probe"))
    }
}
