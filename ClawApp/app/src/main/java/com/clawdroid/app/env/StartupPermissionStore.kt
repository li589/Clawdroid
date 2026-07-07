package com.clawdroid.app.env

import android.content.Context

internal data class StartupPermissionState(
    val rootPromptRequestedOnce: Boolean = false,
    val rootGrantedEver: Boolean = false,
    val automationGrantRemembered: Boolean = false,
    val notificationGrantRemembered: Boolean = false,
    val writeSettingsGrantRemembered: Boolean = false,
    val allFilesGrantRemembered: Boolean = false,
    val accessibilityGrantRemembered: Boolean = false
)

internal data class StartupAutoGrantPlan(
    val useAutomationGrant: Boolean = false,
    val grantNotification: Boolean = false,
    val grantWriteSettings: Boolean = false,
    val grantAllFiles: Boolean = false,
    val grantAccessibility: Boolean = false
) {
    val hasWork: Boolean
        get() = useAutomationGrant ||
            grantNotification ||
            grantWriteSettings ||
            grantAllFiles ||
            grantAccessibility
}

internal object StartupPermissionStore {
    private const val prefsName = "clawdroid_startup_permission_state"
    private const val keyRootPromptRequestedOnce = "root_prompt_requested_once"
    private const val keyRootGrantedEver = "root_granted_ever"
    private const val keyAutomationGrantRemembered = "automation_grant_remembered"
    private const val keyNotificationGrantRemembered = "notification_grant_remembered"
    private const val keyWriteSettingsGrantRemembered = "write_settings_grant_remembered"
    private const val keyAllFilesGrantRemembered = "all_files_grant_remembered"
    private const val keyAccessibilityGrantRemembered = "accessibility_grant_remembered"

    fun load(context: Context): StartupPermissionState {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        return StartupPermissionState(
            rootPromptRequestedOnce = prefs.getBoolean(keyRootPromptRequestedOnce, false),
            rootGrantedEver = prefs.getBoolean(keyRootGrantedEver, false),
            automationGrantRemembered = prefs.getBoolean(keyAutomationGrantRemembered, false),
            notificationGrantRemembered = prefs.getBoolean(keyNotificationGrantRemembered, false),
            writeSettingsGrantRemembered = prefs.getBoolean(keyWriteSettingsGrantRemembered, false),
            allFilesGrantRemembered = prefs.getBoolean(keyAllFilesGrantRemembered, false),
            accessibilityGrantRemembered = prefs.getBoolean(keyAccessibilityGrantRemembered, false)
        )
    }

    fun markRootPromptResult(context: Context, granted: Boolean) {
        update(context) { current ->
            current.copy(
                rootPromptRequestedOnce = true,
                rootGrantedEver = current.rootGrantedEver || granted
            )
        }
    }

    fun rememberNotificationGrant(context: Context) {
        update(context) { it.copy(notificationGrantRemembered = true) }
    }

    fun rememberWriteSettingsGrant(context: Context) {
        update(context) { it.copy(writeSettingsGrantRemembered = true) }
    }

    fun rememberAllFilesGrant(context: Context) {
        update(context) { it.copy(allFilesGrantRemembered = true) }
    }

    fun rememberAccessibilityGrant(context: Context) {
        update(context) { it.copy(accessibilityGrantRemembered = true) }
    }

    fun rememberAutomationGrant(context: Context) {
        update(context) {
            it.copy(
                automationGrantRemembered = true,
                notificationGrantRemembered = true,
                writeSettingsGrantRemembered = true,
                allFilesGrantRemembered = true,
                accessibilityGrantRemembered = true
            )
        }
    }

    fun rememberGrantedPermissions(context: Context, status: LocalEnvironmentStatus) {
        update(context) { current ->
            current.copy(
                rootGrantedEver = current.rootGrantedEver || (status.rootGranted == true),
                notificationGrantRemembered = current.notificationGrantRemembered || status.notificationPermissionGranted,
                writeSettingsGrantRemembered = current.writeSettingsGrantRemembered || status.writeSettingsGranted,
                allFilesGrantRemembered = current.allFilesGrantRemembered || status.allFilesAccessGranted,
                accessibilityGrantRemembered = current.accessibilityGrantRemembered || status.accessibilityEnabled
            )
        }
    }

    private fun update(context: Context, transform: (StartupPermissionState) -> StartupPermissionState) {
        val next = transform(load(context))
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(keyRootPromptRequestedOnce, next.rootPromptRequestedOnce)
            .putBoolean(keyRootGrantedEver, next.rootGrantedEver)
            .putBoolean(keyAutomationGrantRemembered, next.automationGrantRemembered)
            .putBoolean(keyNotificationGrantRemembered, next.notificationGrantRemembered)
            .putBoolean(keyWriteSettingsGrantRemembered, next.writeSettingsGrantRemembered)
            .putBoolean(keyAllFilesGrantRemembered, next.allFilesGrantRemembered)
            .putBoolean(keyAccessibilityGrantRemembered, next.accessibilityGrantRemembered)
            .apply()
    }
}

internal fun shouldAutoRequestRootOnStartup(@Suppress("UNUSED_PARAMETER") state: StartupPermissionState): Boolean {
    return true
}

internal fun shouldRememberRootPromptResult(result: RootActionResult): Boolean {
    return result.success || !result.timedOut
}

internal fun buildStartupAutoGrantPlan(
    state: StartupPermissionState,
    status: LocalEnvironmentStatus
): StartupAutoGrantPlan {
    val missingNotification = !status.notificationPermissionGranted
    val missingWriteSettings = !status.writeSettingsGranted
    val missingAllFiles = !status.allFilesAccessGranted
    val missingAccessibility = !status.accessibilityEnabled
    val missingCoreGrant = missingNotification || missingWriteSettings || missingAllFiles || missingAccessibility

    if (state.automationGrantRemembered && missingCoreGrant) {
        return StartupAutoGrantPlan(useAutomationGrant = true)
    }

    return StartupAutoGrantPlan(
        grantNotification = state.notificationGrantRemembered && missingNotification,
        grantWriteSettings = state.writeSettingsGrantRemembered && missingWriteSettings,
        grantAllFiles = state.allFilesGrantRemembered && missingAllFiles,
        grantAccessibility = state.accessibilityGrantRemembered && missingAccessibility
    )
}
