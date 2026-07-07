package com.clawdroid.app.ui
import com.clawdroid.app.automation.AutomationTaskState
import com.clawdroid.app.env.LocalEnvironmentStatus
import com.clawdroid.app.runtime.ClawRuntimeConnectionState

internal data class OverviewPermissionState(
    val localEnvironmentStatus: LocalEnvironmentStatus,
    val localEnvironmentSummary: String,
    val localEnvironmentDiagnosis: String,
    val permissionSummary: String,
    val permissionTargetPath: String,
    val permissionChmodMode: String,
    val permissionChownOwner: String,
    val permissionActionStatus: String
)

internal data class OverviewPermissionActions(
    val onRefreshLocalEnvironment: () -> Unit,
    val onPermissionTargetPathChange: (String) -> Unit,
    val onPermissionChmodModeChange: (String) -> Unit,
    val onPermissionChownOwnerChange: (String) -> Unit,
    val onRequestNotificationPermission: () -> Unit,
    val onOpenAccessibilitySettings: () -> Unit,
    val onOpenWriteSettings: () -> Unit,
    val onOpenAllFilesAccess: () -> Unit,
    val onRootGrantNotificationPermission: () -> Unit,
    val onRootGrantWriteSettings: () -> Unit,
    val onRootGrantAllFilesAccess: () -> Unit,
    val onRootEnableAccessibility: () -> Unit,
    val onRootGrantAutomationPermissions: () -> Unit,
    val onRootChmodPath: () -> Unit,
    val onRootChownPath: () -> Unit
)

internal data class OverviewAutomationState(
    val accessibilitySnapshotStatus: String,
    val taskState: AutomationTaskState,
    val taskSummary: String,
    val pageConfirmPackage: String,
    val pageConfirmText: String,
    val pageConfirmViewId: String,
    val pageConfirmStatus: String,
    val clickPrecheckPackage: String,
    val clickPrecheckText: String,
    val clickPrecheckViewId: String,
    val clickPrecheckStatus: String,
    val safeTapStatus: String
)

internal data class OverviewAutomationActions(
    val onPageConfirmPackageChange: (String) -> Unit,
    val onPageConfirmTextChange: (String) -> Unit,
    val onPageConfirmViewIdChange: (String) -> Unit,
    val onConfirmPage: () -> Unit,
    val onClickPrecheckPackageChange: (String) -> Unit,
    val onClickPrecheckTextChange: (String) -> Unit,
    val onClickPrecheckViewIdChange: (String) -> Unit,
    val onClickPrecheck: () -> Unit,
    val onExecuteSafeTap: () -> Unit
)

internal data class OverviewRuntimeState(
    val latestDaemonMetrics: String,
    val latestRuntimeProcessMetrics: String,
    val latestWindowSummary: String,
    val session: SessionInfo,
    val shell: ShellState,
    val capture: CaptureState,
    val pingStatus: String,
    val versionStatus: String,
    val healthStatus: String,
    val lastErrorStatus: String,
    val runtimeConfigSummary: String,
    val capabilityStatus: String,
    val captureStatus: String,
    val readFileStatus: String,
    val tapStatus: String,
    val swipeStatus: String
)

internal data class SessionInfo(
    val state: ClawRuntimeConnectionState,
    val trace: String,
    val authMode: String,
    val summary: String,
    val runtimeLoaded: Boolean?,
    val runtimeProcess: String,
    val runtimeLoadedAtEpochMs: Long,
    val degradedReason: String,
    val runtimeSocketDisplay: String,
    val packageDisplay: String,
    val signatureDigestDisplay: String
)

internal data class ShellState(
    val status: String,
    val output: String,
    val selectedCommand: String,
    val commandOptions: List<String>,
    val dropdownExpanded: Boolean
)

internal data class CaptureState(
    val latestPath: String,
    val latestFormat: String,
    val latestDimensions: String,
    val latestFileSize: Long
)

internal data class OverviewRuntimeActions(
    val onPing: () -> Unit,
    val onGetVersion: () -> Unit,
    val onGetHealth: () -> Unit,
    val onGetLastError: () -> Unit,
    val onSessionProbe: () -> Unit,
    val onGetCapabilities: () -> Unit,
    val onCaptureScreen: () -> Unit,
    val onReadLatestCapture: () -> Unit,
    val onInjectTap: () -> Unit,
    val onInjectSwipe: () -> Unit,
    val onShellExpandedChange: (Boolean) -> Unit,
    val onShellCommandSelected: (String) -> Unit,
    val onExecuteShell: () -> Unit
)

internal data class OverviewEventState(
    val eventStatus: String,
    val eventStreaming: Boolean,
    val eventLines: List<String>,
    val eventLogVersion: Long
)

internal data class OverviewEventActions(
    val onStartEvents: () -> Unit,
    val onStopEvents: () -> Unit
)

internal data class OverviewUiState(
    val permissionState: OverviewPermissionState,
    val runtimeState: OverviewRuntimeState,
    val eventState: OverviewEventState
) {
    val connectionSummary: String
        get() = "session=${runtimeState.session.state}, auth=${runtimeState.session.authMode}, events=${if (eventState.eventStreaming) "running" else "idle"}"
}

internal fun OverviewController.buildPermissionActions(
    onRequestNotificationPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenWriteSettings: () -> Unit,
    onOpenAllFilesAccess: () -> Unit
): OverviewPermissionActions {
    return OverviewPermissionActions(
        onRefreshLocalEnvironment = ::refreshLocalEnvironment,
        onPermissionTargetPathChange = ::updatePermissionTargetPath,
        onPermissionChmodModeChange = ::updatePermissionChmodMode,
        onPermissionChownOwnerChange = ::updatePermissionChownOwner,
        onRequestNotificationPermission = onRequestNotificationPermission,
        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
        onOpenWriteSettings = onOpenWriteSettings,
        onOpenAllFilesAccess = onOpenAllFilesAccess,
        onRootGrantNotificationPermission = ::grantNotificationPermissionViaRoot,
        onRootGrantWriteSettings = ::grantWriteSettingsViaRoot,
        onRootGrantAllFilesAccess = ::grantAllFilesAccessViaRoot,
        onRootEnableAccessibility = ::enableAccessibilityViaRoot,
        onRootGrantAutomationPermissions = ::grantAutomationPermissionsViaRoot,
        onRootChmodPath = ::chmodPermissionTargetPathViaRoot,
        onRootChownPath = ::chownPermissionTargetPathViaRoot
    )
}

internal fun OverviewController.buildRuntimeActions(): OverviewRuntimeActions {
    return OverviewRuntimeActions(
        onPing = ::ping,
        onGetVersion = ::getVersion,
        onGetHealth = ::getHealth,
        onGetLastError = ::getLastError,
        onSessionProbe = ::probeSession,
        onGetCapabilities = ::getCapabilities,
        onCaptureScreen = ::captureScreen,
        onReadLatestCapture = ::readLatestCapture,
        onInjectTap = ::injectTap,
        onInjectSwipe = ::injectSwipe,
        onShellExpandedChange = ::updateShellDropdownExpanded,
        onShellCommandSelected = ::selectShellCommand,
        onExecuteShell = ::executeShell
    )
}

internal fun OverviewController.buildEventActions(): OverviewEventActions {
    return OverviewEventActions(
        onStartEvents = { startContinuousSubscription() },
        onStopEvents = { stopContinuousSubscription() }
    )
}

internal fun AutomationController.buildOverviewAutomationActions(): OverviewAutomationActions {
    return OverviewAutomationActions(
        onPageConfirmPackageChange = ::updatePageConfirmPackage,
        onPageConfirmTextChange = ::updatePageConfirmText,
        onPageConfirmViewIdChange = ::updatePageConfirmViewId,
        onConfirmPage = ::confirmPage,
        onClickPrecheckPackageChange = ::updateClickPrecheckPackage,
        onClickPrecheckTextChange = ::updateClickPrecheckText,
        onClickPrecheckViewIdChange = ::updateClickPrecheckViewId,
        onClickPrecheck = ::precheckClickTarget,
        onExecuteSafeTap = { launchSafeTap() }
    )
}
