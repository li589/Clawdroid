package com.clawdroid.app.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.graphics.ImageBitmap

internal data class AssistMcpOverviewStatus(
    val phoneServerRunning: Boolean = false,
    val phoneServerStatus: String = "未启动",
    val assistClientEnabled: Boolean = false,
    val assistClientStatus: String = "未启用",
    val assistLastError: String = "",
    val liveCapabilityCount: Int = 0
)

/**
 * Overview: status, basic permissions, local environment ops, events, capture.
 */
@Suppress("UNUSED_PARAMETER")
internal fun LazyListScope.statusOverviewScreen(
    permissionState: OverviewPermissionState,
    permissionActions: OverviewPermissionActions,
    automationState: OverviewAutomationState,
    automationActions: OverviewAutomationActions,
    runtimeState: OverviewRuntimeState,
    dashboardMetrics: DashboardRuntimeMetrics,
    latestCapturePreview: ImageBitmap?,
    runtimeActions: OverviewRuntimeActions,
    eventState: OverviewEventState,
    eventActions: OverviewEventActions,
    assistMcpStatus: AssistMcpOverviewStatus = AssistMcpOverviewStatus(),
    debugHighlightLongContent: Boolean = false
) {
    item(key = "overview-hero") {
        OverviewHeroCard(
            sessionState = runtimeState.session.state,
            localEnvironmentStatus = permissionState.localEnvironmentStatus,
            eventStreaming = eventState.eventStreaming,
            windowSummary = runtimeState.latestWindowSummary
        )
    }
    if (runtimeState.compatBanner.isNotBlank()) {
        item(key = "runtime-compat") {
            StatusCard(
                title = "App ↔ Runtime 对齐",
                content = runtimeState.compatBanner
            )
        }
    }
    item(key = "basic-permissions") {
        BasicPermissionsCard(
            status = permissionState.localEnvironmentStatus,
            actionStatus = permissionState.permissionActionStatus,
            rememberedNotification = permissionState.rememberedNotification,
            rememberedWriteSettings = permissionState.rememberedWriteSettings,
            rememberedAllFiles = permissionState.rememberedAllFiles,
            rememberedAccessibility = permissionState.rememberedAccessibility,
            onToggleNotification = permissionActions.onRequestNotificationPermission,
            onToggleAccessibility = permissionActions.onOpenAccessibilitySettings,
            onToggleWriteSettings = permissionActions.onOpenWriteSettings,
            onToggleAllFiles = permissionActions.onOpenAllFilesAccess,
            onToggleNotificationListener = permissionActions.onOpenNotificationListenerSettings,
            onToggleShizuku = permissionActions.onRequestShizukuPermission,
            onRootGrantAll = permissionActions.onRootGrantAutomationPermissions
        )
    }
    item(key = "local-environment-ops") {
        LocalEnvironmentOpsCard(
            result = buildLocalEnvironmentPanelResult(permissionState),
            eventStreaming = eventState.eventStreaming,
            onDetectEnvironment = permissionActions.onRefreshLocalEnvironment,
            onPing = runtimeActions.onPing,
            onCapabilities = runtimeActions.onGetCapabilities,
            onCapture = runtimeActions.onCaptureScreen,
            onShell = runtimeActions.onExecuteShell,
            onEvents = if (eventState.eventStreaming) {
                eventActions.onStopEvents
            } else {
                eventActions.onStartEvents
            }
        )
    }
    item(key = "event-subscription") {
        EventSubscriptionCard(
            title = "事件流",
            result = eventState.eventStatus,
            streaming = eventState.eventStreaming,
            recentEvents = eventState.eventLines,
            onStart = eventActions.onStartEvents,
            onStop = eventActions.onStopEvents
        )
    }
    item(key = "capture-preview") {
        PreviewCard(imageBitmap = latestCapturePreview)
    }
}

/**
 * 本地环境结果框：环境摘要 + 可选的操作回显（互不重复拼接）。
 */
internal fun buildLocalEnvironmentPanelResult(permissionState: OverviewPermissionState): String {
    val summary = permissionState.localEnvironmentSummary.trim()
        .ifBlank { "尚未检测本地环境" }
    val ops = permissionState.environmentOpsFeedback.trim()
    if (ops.isBlank()) return summary
    if (summary == "检测中..." || summary == "尚未检测本地环境") {
        return if (ops == "检测中..." || ops == "环境检测中...") summary else ops
    }
    if (ops == "环境检测完成" || ops == "检测中..." || ops == "环境检测中...") {
        return if (ops == "环境检测完成") "$summary\n\n$ops" else summary
    }
    if (summary.contains(ops) || ops.contains(summary)) return summary
    return "$summary\n\n$ops"
}
