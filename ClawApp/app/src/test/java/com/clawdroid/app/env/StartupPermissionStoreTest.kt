package com.clawdroid.app.env

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupPermissionStoreTest {
    @Test
    fun shouldAutoRequestRootOnStartupRequestsOnFirstLaunch() {
        assertTrue(
            shouldAutoRequestRootOnStartup(
                StartupPermissionState(
                    rootPromptRequestedOnce = false,
                    rootGrantedEver = false
                )
            )
        )
    }

    @Test
    fun shouldAutoRequestRootOnStartupKeepsRetryingAfterInitialDenial() {
        assertTrue(
            shouldAutoRequestRootOnStartup(
                StartupPermissionState(
                    rootPromptRequestedOnce = true,
                    rootGrantedEver = false
                )
            )
        )
    }

    @Test
    fun shouldRememberRootPromptResultKeepsRetryingAfterTimeout() {
        assertFalse(
            shouldRememberRootPromptResult(
                RootActionResult(
                    success = false,
                    output = "Root 命令执行超时",
                    timedOut = true
                )
            )
        )
    }

    @Test
    fun shouldRememberRootPromptResultPersistsDecisionAfterNonTimeoutFailure() {
        assertTrue(
            shouldRememberRootPromptResult(
                RootActionResult(
                    success = false,
                    output = "permission denied"
                )
            )
        )
    }

    @Test
    fun buildStartupAutoGrantPlanPrefersAutomationGrantWhenRemembered() {
        val plan = buildStartupAutoGrantPlan(
            state = StartupPermissionState(
                automationGrantRemembered = true,
                notificationGrantRemembered = true,
                writeSettingsGrantRemembered = true
            ),
            status = emptyEnvironmentStatus()
        )

        assertTrue(plan.useAutomationGrant)
        assertFalse(plan.grantNotification)
    }

    @Test
    fun buildStartupAutoGrantPlanRestoresRememberedIndividualPermissions() {
        val plan = buildStartupAutoGrantPlan(
            state = StartupPermissionState(
                notificationGrantRemembered = true,
                writeSettingsGrantRemembered = true,
                allFilesGrantRemembered = false,
                accessibilityGrantRemembered = true
            ),
            status = emptyEnvironmentStatus().copy(
                notificationPermissionGranted = false,
                writeSettingsGranted = true,
                accessibilityEnabled = false
            )
        )

        assertTrue(plan.grantNotification)
        assertFalse(plan.grantWriteSettings)
        assertTrue(plan.grantAccessibility)
        assertFalse(plan.grantAllFiles)
    }

    @Test
    fun parseMagiskModuleStatusReadsStructuredFlags() {
        val status = parseMagiskModuleStatus(
            "magisk=1|installed=1|enabled=0|runtime=1|path=/data/adb/modules/clawruntime"
        )

        assertTrue(status.magiskDaemonRunning)
        assertTrue(status.moduleInstalled)
        assertFalse(status.moduleEnabled)
        assertTrue(status.runtimeDaemonRunning)
    }
}
