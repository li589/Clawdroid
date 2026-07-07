package com.clawdroid.app.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.graphics.ImageBitmap

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
    debugHighlightLongContent: Boolean = false
) {
    if (debugHighlightLongContent) {
        item { SectionTitle("长内容压测") }
        item {
            ActionCard(
                title = "最近错误",
                buttonText = "获取 Last Error",
                result = runtimeState.lastErrorStatus,
                onClick = runtimeActions.onGetLastError
            )
        }
        item {
            EventSubscriptionCard(
                title = "事件订阅",
                result = eventState.eventStatus,
                streaming = eventState.eventStreaming,
                onStart = eventActions.onStartEvents,
                onStop = eventActions.onStopEvents
            )
        }
        if (eventState.eventLines.isNotEmpty()) {
            item {
                LogCard(
                    title = "事件流日志",
                    content = eventState.eventLines.joinToString("\n")
                )
            }
        }
        item {
            ShellCard(
                selectedCommand = runtimeState.shell.selectedCommand,
                commands = runtimeState.shell.commandOptions,
                expanded = runtimeState.shell.dropdownExpanded,
                result = runtimeState.shell.status,
                output = runtimeState.shell.output,
                onExpandedChange = runtimeActions.onShellExpandedChange,
                onCommandSelected = runtimeActions.onShellCommandSelected,
                onExecute = runtimeActions.onExecuteShell
            )
        }
    }
    item { SectionTitle("运行总览") }
    item {
        OverviewHeroCard(
            sessionState = runtimeState.session.state,
            localEnvironmentStatus = permissionState.localEnvironmentStatus,
            eventStreaming = eventState.eventStreaming,
            daemonMetrics = runtimeState.latestDaemonMetrics,
            runtimeMetrics = runtimeState.latestRuntimeProcessMetrics,
            windowSummary = runtimeState.latestWindowSummary,
            runtimeLoaded = runtimeState.session.runtimeLoaded,
            runtimeProcess = runtimeState.session.runtimeProcess,
            degradedReason = runtimeState.session.degradedReason
        )
    }
    item {
        MetricsOverviewCard(
            daemonMetrics = runtimeState.latestDaemonMetrics,
            runtimeMetrics = runtimeState.latestRuntimeProcessMetrics,
            windowSummary = runtimeState.latestWindowSummary,
            dashboardMetrics = dashboardMetrics
        )
    }
    item {
        QuickActionCard(
            onPing = runtimeActions.onPing,
            onCapabilities = runtimeActions.onGetCapabilities,
            onCapture = runtimeActions.onCaptureScreen,
            onShell = runtimeActions.onExecuteShell,
            onEvents = if (eventState.eventStreaming) eventActions.onStopEvents else eventActions.onStartEvents,
            eventStreaming = eventState.eventStreaming
        )
    }
    item {
        StatusCard(
            title = "连接配置",
            content = "本地 Socket: ${runtimeState.session.runtimeSocketDisplay} (Abstract Namespace)\n包名: ${runtimeState.session.packageDisplay}\n签名摘要: ${runtimeState.session.signatureDigestDisplay}"
        )
    }
    item { SectionTitle("本地状态") }
    item {
        ActionCard(
            title = "本地环境检测",
            buttonText = "刷新本地状态",
            result = permissionState.localEnvironmentSummary,
            onClick = permissionActions.onRefreshLocalEnvironment
        )
    }
    item {
        StatusCard(
            title = "本地环境诊断",
            content = permissionState.localEnvironmentDiagnosis,
            maxContentLines = 8
        )
    }
    item {
        StatusCard(
            title = "本地环境详情",
            content = "Root: ${rootStatusLabel(permissionState.localEnvironmentStatus.rootGranted)}\nMagisk 守护: ${magiskDaemonStatusLabel(permissionState.localEnvironmentStatus)}\nClawRuntime 模块: ${moduleStatusLabel(permissionState.localEnvironmentStatus)}\nRuntime 守护: ${runtimeDaemonStatusLabel(permissionState.localEnvironmentStatus)}\nLSPosed: ${lsposedStatusLabel(permissionState.localEnvironmentStatus)}\nLSPosed 进程: ${permissionState.localEnvironmentStatus.xposedProcessName.ifBlank { "unknown" }}\nLSPosed 时间: ${formatEpochMillis(permissionState.localEnvironmentStatus.xposedLoadedAtEpochMs)}\nAccessibility: ${booleanStatusLabel(permissionState.localEnvironmentStatus.accessibilityEnabled)}\n通知权限: ${permissionGrantedLabel(permissionState.localEnvironmentStatus.notificationPermissionGranted)}\n修改系统设置: ${permissionGrantedLabel(permissionState.localEnvironmentStatus.writeSettingsGranted)}\n全部文件访问: ${permissionGrantedLabel(permissionState.localEnvironmentStatus.allFilesAccessGranted)}"
        )
    }
    item {
        PermissionActionsCard(
            summary = permissionState.permissionSummary,
            result = permissionState.permissionActionStatus,
            targetPath = permissionState.permissionTargetPath,
            chmodMode = permissionState.permissionChmodMode,
            chownOwner = permissionState.permissionChownOwner,
            onTargetPathChange = permissionActions.onPermissionTargetPathChange,
            onChmodModeChange = permissionActions.onPermissionChmodModeChange,
            onChownOwnerChange = permissionActions.onPermissionChownOwnerChange,
            onRequestNotification = permissionActions.onRequestNotificationPermission,
            onOpenAccessibility = permissionActions.onOpenAccessibilitySettings,
            onOpenWriteSettings = permissionActions.onOpenWriteSettings,
            onOpenAllFiles = permissionActions.onOpenAllFilesAccess,
            onRootGrantNotification = permissionActions.onRootGrantNotificationPermission,
            onRootGrantWriteSettings = permissionActions.onRootGrantWriteSettings,
            onRootGrantAllFiles = permissionActions.onRootGrantAllFilesAccess,
            onRootEnableAccessibility = permissionActions.onRootEnableAccessibility,
            onRootGrantAutomation = permissionActions.onRootGrantAutomationPermissions,
            onRootChmodPath = permissionActions.onRootChmodPath,
            onRootChownPath = permissionActions.onRootChownPath
        )
    }
    item {
        StatusCard(
            title = "Runtime 连接诊断",
            content = buildRuntimeConnectionDiagnosis(
                localStatus = permissionState.localEnvironmentStatus,
                runtimeState = runtimeState
            ),
            maxContentLines = 10
        )
    }
    item {
        StatusCard(
            title = "连接状态机",
            content = "当前状态: ${runtimeState.session.state}\n状态轨迹: ${runtimeState.session.trace}\n鉴权模式: ${runtimeState.session.authMode}\n会话摘要: ${runtimeState.session.summary}\nRuntime 注入: ${runtimeState.session.runtimeLoaded?.let { if (it) "已加载" else "未加载" } ?: "unknown"}\nRuntime 进程: ${runtimeState.session.runtimeProcess.ifBlank { "unknown" }}\nRuntime 时间: ${formatEpochMillis(runtimeState.session.runtimeLoadedAtEpochMs)}\nDegraded: ${runtimeState.session.degradedReason.ifBlank { "none" }}\nCapabilities: ${runtimeState.capabilityStatus}"
        )
    }
    item { SectionTitle("自动化闭环") }
    item {
        StatusCard(
            title = "无障碍感知快照",
            content = automationState.accessibilitySnapshotStatus,
            maxContentLines = 12
        )
    }
    item {
        StatusCard(
            title = "任务状态",
            content = "任务状态: ${automationState.taskState}\n任务详情: ${automationState.taskSummary}"
        )
    }
    item {
        PageConfirmationCard(
            expectedPackage = automationState.pageConfirmPackage,
            expectedText = automationState.pageConfirmText,
            expectedViewId = automationState.pageConfirmViewId,
            result = automationState.pageConfirmStatus,
            onExpectedPackageChange = automationActions.onPageConfirmPackageChange,
            onExpectedTextChange = automationActions.onPageConfirmTextChange,
            onExpectedViewIdChange = automationActions.onPageConfirmViewIdChange,
            onConfirm = automationActions.onConfirmPage
        )
    }
    item {
        ClickPrecheckCard(
            expectedPackage = automationState.clickPrecheckPackage,
            targetText = automationState.clickPrecheckText,
            targetViewId = automationState.clickPrecheckViewId,
            result = automationState.clickPrecheckStatus,
            executeResult = automationState.safeTapStatus,
            onExpectedPackageChange = automationActions.onClickPrecheckPackageChange,
            onTargetTextChange = automationActions.onClickPrecheckTextChange,
            onTargetViewIdChange = automationActions.onClickPrecheckViewIdChange,
            onPrecheck = automationActions.onClickPrecheck,
            onExecuteTap = automationActions.onExecuteSafeTap
        )
    }
    item { SectionTitle("ClawRuntime 控制") }
    item {
        ActionCard(
            title = "ClawRuntime 联通测试",
            buttonText = "发送 Ping",
            result = runtimeState.pingStatus,
            onClick = runtimeActions.onPing
        )
    }
    item {
        ActionCard(
            title = "版本信息",
            buttonText = "获取 Version",
            result = runtimeState.versionStatus,
            onClick = runtimeActions.onGetVersion
        )
    }
    item {
        ActionCard(
            title = "健康状态",
            buttonText = "获取 Health",
            result = runtimeState.healthStatus,
            onClick = runtimeActions.onGetHealth
        )
    }
    item {
        ActionCard(
            title = "最近错误",
            buttonText = "获取 Last Error",
            result = runtimeState.lastErrorStatus,
            onClick = runtimeActions.onGetLastError
        )
    }
    item {
        ActionCard(
            title = "会话探测",
            buttonText = "执行 Runtime Probe",
            result = "用于一次性完成连接、挑战鉴权骨架、能力同步和最终状态判定。",
            onClick = runtimeActions.onSessionProbe
        )
    }
    item {
        ActionCard(
            title = "ClawRuntime 能力读取",
            buttonText = "获取 Capabilities",
            result = runtimeState.capabilityStatus,
            onClick = runtimeActions.onGetCapabilities
        )
    }
    item { SectionTitle("能力动作") }
    item {
        ActionCard(
            title = "屏幕截图",
            buttonText = "执行 Capture Screen",
            result = runtimeState.captureStatus,
            onClick = runtimeActions.onCaptureScreen
        )
    }
    item {
        ActionCard(
            title = "读取文件",
            buttonText = "读取并预览最近截图",
            result = runtimeState.readFileStatus,
            onClick = runtimeActions.onReadLatestCapture
        )
    }
    item { SectionTitle("自动化输入") }
    item {
        ActionCard(
            title = "输入注入",
            buttonText = "执行 Tap(540,1200)",
            result = runtimeState.tapStatus,
            onClick = runtimeActions.onInjectTap
        )
    }
    item {
        ActionCard(
            title = "滑动注入",
            buttonText = "执行 Swipe(540,1800 -> 540,400)",
            result = runtimeState.swipeStatus,
            onClick = runtimeActions.onInjectSwipe
        )
    }
    item { SectionTitle("诊断与事件") }
    item {
        ShellCard(
            selectedCommand = runtimeState.shell.selectedCommand,
            commands = runtimeState.shell.commandOptions,
            expanded = runtimeState.shell.dropdownExpanded,
            result = runtimeState.shell.status,
            output = runtimeState.shell.output,
            onExpandedChange = runtimeActions.onShellExpandedChange,
            onCommandSelected = runtimeActions.onShellCommandSelected,
            onExecute = runtimeActions.onExecuteShell
        )
    }
    item {
        EventSubscriptionCard(
            title = "事件订阅",
            result = eventState.eventStatus,
            streaming = eventState.eventStreaming,
            onStart = eventActions.onStartEvents,
            onStop = eventActions.onStopEvents
        )
    }
    if (eventState.eventLines.isNotEmpty()) {
        item {
            LogCard(
                title = "事件流日志",
                content = eventState.eventLines.joinToString("\n")
            )
        }
    }
    latestCapturePreview?.let { preview ->
        item {
            PreviewCard(preview)
        }
    }
}
