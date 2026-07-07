package com.clawdroid.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawdroid.app.BuildConfig
import com.clawdroid.app.env.AppPermissionManager
import com.clawdroid.app.env.LocalEnvironmentProbe
import com.clawdroid.app.env.LocalEnvironmentStatus
import com.clawdroid.app.env.StartupAutoGrantPlan
import com.clawdroid.app.env.StartupPermissionStore
import com.clawdroid.app.env.buildLocalEnvironmentDiagnosis
import com.clawdroid.app.env.buildStartupAutoGrantPlan
import com.clawdroid.app.env.shouldRememberRootPromptResult
import com.clawdroid.app.env.shouldAutoRequestRootOnStartup
import com.clawdroid.app.runtime.ClawRuntimeClient
import com.clawdroid.app.runtime.ClawRuntimeConnectionState
import com.clawdroid.app.runtime.ClawRuntimeEventFrame
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolExecutor
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

internal class OverviewController(
    private val appContext: Context,
    private val runtimeClient: ClawRuntimeClient,
    private val toolExecutor: ClawToolExecutor,
    private val previewLimitBytes: Int
) : ViewModel() {
    private val _uiState = MutableStateFlow(createInitialOverviewUiState(runtimeClient))
    val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()
    private val _dashboardMetrics = MutableStateFlow(DashboardRuntimeMetrics())
    val dashboardMetrics: StateFlow<DashboardRuntimeMetrics> = _dashboardMetrics.asStateFlow()
    private val _latestCapturePreview = MutableStateFlow<ImageBitmap?>(null)
    val latestCapturePreview: StateFlow<ImageBitmap?> = _latestCapturePreview.asStateFlow()

    val automationController = AutomationController(
        toolExecutor = toolExecutor,
        scope = viewModelScope
    )

    private var eventSubscriptionJob: Job? = null
    private var dashboardSamplingJob: Job? = null

    init {
        viewModelScope.launch {
            performStartupChecks()
        }
    }

    fun refreshLocalEnvironment() {
        viewModelScope.launch {
            refreshLocalEnvironmentState(includeRootCheck = true)
        }
    }

    suspend fun refreshLocalEnvironmentState(includeRootCheck: Boolean) {
        updatePermissionState { it.copy(localEnvironmentSummary = "检测中...") }
        val status = LocalEnvironmentProbe.probe(
            appContext,
            includeRootCheck = includeRootCheck
        )

        StartupPermissionStore.rememberGrantedPermissions(appContext, status)
        updatePermissionState {
            it.copy(
                localEnvironmentStatus = status,
                localEnvironmentSummary = buildLocalEnvironmentSummary(status),
                localEnvironmentDiagnosis = buildLocalEnvironmentDiagnosis(status).asMultilineString(),
                permissionSummary = buildPermissionSummary(status)
            )
        }
    }

    fun handleSystemSettingsReturned() {
        viewModelScope.launch {
            refreshLocalEnvironmentState(includeRootCheck = true)
            updatePermissionState { it.copy(permissionActionStatus = "已从系统设置返回，请查看最新权限状态") }
        }
    }

    fun handleNotificationPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            refreshLocalEnvironmentState(includeRootCheck = true)
            if (granted) {
                StartupPermissionStore.rememberNotificationGrant(appContext)
            }
            updatePermissionState {
                it.copy(
                    permissionActionStatus = if (granted) {
                        "通知权限已授予"
                    } else {
                        "通知权限未授予，可改用系统设置或 Root 辅助授权"
                    }
                )
            }
        }
    }

    fun markNotificationPermissionNotRequired() {
        updatePermissionState { it.copy(permissionActionStatus = "当前系统无需单独申请通知权限") }
    }

    fun markOpeningAccessibilitySettings() {
        updatePermissionState { it.copy(permissionActionStatus = "正在打开无障碍设置...") }
    }

    fun markOpeningWriteSettings() {
        updatePermissionState { it.copy(permissionActionStatus = "正在打开修改系统设置授权页...") }
    }

    fun markOpeningAllFilesAccess() {
        updatePermissionState { it.copy(permissionActionStatus = "正在打开全部文件访问授权页...") }
    }

    fun updatePermissionTargetPath(value: String) {
        updatePermissionState { it.copy(permissionTargetPath = value) }
    }

    fun updatePermissionChmodMode(value: String) {
        updatePermissionState { it.copy(permissionChmodMode = value) }
    }

    fun updatePermissionChownOwner(value: String) {
        updatePermissionState { it.copy(permissionChownOwner = value) }
    }

    fun grantNotificationPermissionViaRoot() {
        runRootPermissionAction(
            action = { AppPermissionManager.grantNotificationViaRoot(appContext) },
            onSuccess = { StartupPermissionStore.rememberNotificationGrant(appContext) }
        )
    }

    fun grantWriteSettingsViaRoot() {
        runRootPermissionAction(
            action = { AppPermissionManager.grantWriteSettingsViaRoot(appContext) },
            onSuccess = { StartupPermissionStore.rememberWriteSettingsGrant(appContext) }
        )
    }

    fun grantAllFilesAccessViaRoot() {
        runRootPermissionAction(
            action = { AppPermissionManager.grantAllFilesAccessViaRoot(appContext) },
            onSuccess = { StartupPermissionStore.rememberAllFilesGrant(appContext) }
        )
    }

    fun enableAccessibilityViaRoot() {
        runRootPermissionAction(
            action = { AppPermissionManager.enableAccessibilityViaRoot(appContext) },
            onSuccess = { StartupPermissionStore.rememberAccessibilityGrant(appContext) }
        )
    }

    fun grantAutomationPermissionsViaRoot() {
        runRootPermissionAction(
            action = { AppPermissionManager.grantAutomationPermissionsViaRoot(appContext) },
            onSuccess = { StartupPermissionStore.rememberAutomationGrant(appContext) }
        )
    }

    fun chmodPermissionTargetPathViaRoot() {
        val permissionState = uiState.value.permissionState
        runRootPermissionAction(
            action = {
                AppPermissionManager.chmodPathViaRoot(
                    path = permissionState.permissionTargetPath,
                    mode = permissionState.permissionChmodMode
                )
            }
        )
    }

    fun chownPermissionTargetPathViaRoot() {
        val permissionState = uiState.value.permissionState
        runRootPermissionAction(
            action = {
                AppPermissionManager.chownPathViaRoot(
                    path = permissionState.permissionTargetPath,
                    ownerSpec = permissionState.permissionChownOwner
                )
            }
        )
    }

    private fun runRootPermissionAction(
        action: suspend () -> com.clawdroid.app.env.RootActionResult,
        onSuccess: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            updatePermissionState { it.copy(permissionActionStatus = "执行中...") }
            val result = action()
            if (result.success) {
                StartupPermissionStore.markRootPromptResult(appContext, granted = true)
                onSuccess?.invoke()
            }
            refreshLocalEnvironmentState(includeRootCheck = true)
            updatePermissionState {
                it.copy(
                    permissionActionStatus = if (result.success) {
                        "成功: ${result.output}"
                    } else {
                        "失败: ${result.output}"
                    }
                )
            }
        }
    }

    private suspend fun performStartupChecks() {
        updatePermissionState { it.copy(permissionActionStatus = "启动自检中...") }
        val remembered = StartupPermissionStore.load(appContext)
        val shouldRequestRoot = shouldAutoRequestRootOnStartup(remembered)
        val rootResult = if (shouldRequestRoot) {
            AppPermissionManager.requestRootAccess()
        } else {
            com.clawdroid.app.env.RootActionResult(
                success = false,
                output = "首次启动尚未获得 Root 授权，保持手动授权模式"
            )
        }
        if (shouldRequestRoot && shouldRememberRootPromptResult(rootResult)) {
            StartupPermissionStore.markRootPromptResult(appContext, granted = rootResult.success)
        }

        val initialStatus = LocalEnvironmentProbe.probe(
            context = appContext,
            includeRootCheck = rootResult.success
        )
        StartupPermissionStore.rememberGrantedPermissions(appContext, initialStatus)

        val autoGrantMessages = mutableListOf<String>()
        if (rootResult.success) {
            val plan = buildStartupAutoGrantPlan(
                state = StartupPermissionStore.load(appContext),
                status = initialStatus
            )
            if (plan.hasWork) {
                autoGrantMessages += applyStartupAutoGrantPlan(plan)
            }
        }

        refreshLocalEnvironmentState(includeRootCheck = rootResult.success)
        val finalStatus = uiState.value.permissionState.localEnvironmentStatus
        updatePermissionState {
            it.copy(
                permissionActionStatus = buildStartupCheckSummary(
                    rootRequested = shouldRequestRoot,
                    rootResult = rootResult,
                    finalStatus = finalStatus,
                    autoGrantMessages = autoGrantMessages
                )
            )
        }
        runStartupRuntimeProbe()
    }

    private suspend fun applyStartupAutoGrantPlan(plan: StartupAutoGrantPlan): List<String> {
        val messages = mutableListOf<String>()
        if (plan.useAutomationGrant) {
            val result = AppPermissionManager.grantAutomationPermissionsViaRoot(appContext)
            if (result.success) {
                StartupPermissionStore.rememberAutomationGrant(appContext)
                messages += "自动恢复一键权限"
            } else {
                messages += "自动恢复一键权限失败: ${result.output}"
            }
            return messages
        }

        if (plan.grantNotification) {
            val result = AppPermissionManager.grantNotificationViaRoot(appContext)
            messages += if (result.success) {
                StartupPermissionStore.rememberNotificationGrant(appContext)
                "自动恢复通知权限"
            } else {
                "自动恢复通知权限失败: ${result.output}"
            }
        }
        if (plan.grantWriteSettings) {
            val result = AppPermissionManager.grantWriteSettingsViaRoot(appContext)
            messages += if (result.success) {
                StartupPermissionStore.rememberWriteSettingsGrant(appContext)
                "自动恢复系统设置权限"
            } else {
                "自动恢复系统设置权限失败: ${result.output}"
            }
        }
        if (plan.grantAllFiles) {
            val result = AppPermissionManager.grantAllFilesAccessViaRoot(appContext)
            messages += if (result.success) {
                StartupPermissionStore.rememberAllFilesGrant(appContext)
                "自动恢复全部文件访问"
            } else {
                "自动恢复全部文件访问失败: ${result.output}"
            }
        }
        if (plan.grantAccessibility) {
            val result = AppPermissionManager.enableAccessibilityViaRoot(appContext)
            messages += if (result.success) {
                StartupPermissionStore.rememberAccessibilityGrant(appContext)
                "自动恢复无障碍"
            } else {
                "自动恢复无障碍失败: ${result.output}"
            }
        }
        return messages
    }

    fun updateShellDropdownExpanded(expanded: Boolean) {
        updateRuntimeState {
            it.copy(shell = it.shell.copy(dropdownExpanded = expanded))
        }
    }

    fun selectShellCommand(command: String) {
        updateRuntimeState {
            it.copy(
                shell = it.shell.copy(
                    selectedCommand = command,
                    dropdownExpanded = false
                )
            )
        }
    }

    fun setOverviewActive(active: Boolean) {
        if (!active) {
            dashboardSamplingJob?.cancel()
            dashboardSamplingJob = null
            return
        }
        if (dashboardSamplingJob != null) {
            return
        }
        dashboardSamplingJob = viewModelScope.launch {
            while (true) {
                val rootAvailable = uiState.value.permissionState.localEnvironmentStatus.rootGranted == true
                val sampledMetrics = DashboardMetricsCollector.sample(rootAvailable = rootAvailable)
                _dashboardMetrics.value = sampledMetrics
                delay(1000)
            }
        }
    }

    fun applyToolSideEffects(result: ClawToolCallResult) {
        updateRuntimeState { current ->
            current.copy(
                shell = current.shell.copy(
                    output = result.shellOutput ?: current.shell.output
                )
            )
        }
        result.captureArtifact?.let { artifact ->
            _latestCapturePreview.value = null
            updateRuntimeState {
                it.copy(
                    capture = it.capture.copy(
                        latestPath = artifact.imagePath,
                        latestFormat = artifact.format,
                        latestDimensions = "${artifact.width}x${artifact.height}",
                        latestFileSize = artifact.fileSize
                    )
                )
            }
        }
        result.sessionSnapshot?.let { snapshot ->
            updateRuntimeState {
                it.copy(
                    session = it.session.copy(
                        state = snapshot.sessionState,
                        trace = snapshot.sessionTrace,
                        authMode = snapshot.authMode,
                        runtimeLoaded = snapshot.runtimeLoaded ?: it.session.runtimeLoaded,
                        runtimeProcess = snapshot.runtimeProcess.ifBlank { it.session.runtimeProcess },
                        runtimeLoadedAtEpochMs = snapshot.runtimeLoadedAtEpochMs.takeIf { value -> value > 0L }
                            ?: it.session.runtimeLoadedAtEpochMs,
                        degradedReason = snapshot.degradedReason.ifBlank { it.session.degradedReason }
                    )
                )
            }
        }
    }

    suspend fun readLatestCaptureForChat(): String {
        updateRuntimeState { it.copy(readFileStatus = "请求中...") }
        val runtimeState = uiState.value.runtimeState
        val result = toolExecutor.readLatestCapture(
            latestCapturePath = runtimeState.capture.latestPath,
            latestCaptureFormat = runtimeState.capture.latestFormat,
            latestCaptureFileSize = runtimeState.capture.latestFileSize,
            previewLimitBytes = previewLimitBytes
        )
        val rendered = renderReadPreviewResult(result)
        updateRuntimeState { it.copy(readFileStatus = rendered) }
        return rendered
    }

    suspend fun captureScreenForChat(includePreview: Boolean): String {
        updateRuntimeState { it.copy(captureStatus = "请求中...") }
        val captureResult = toolExecutor.captureScreen(includeShaPreview = false)
        applyToolSideEffects(captureResult)
        if (!captureResult.success) {
            _latestCapturePreview.value = null
        }
        updateRuntimeState { it.copy(captureStatus = captureResult.output) }
        if (!captureResult.success || !includePreview) {
            return captureResult.output
        }

        val previewStatus = readLatestCaptureForChat()
        return buildString {
            append(captureResult.output)
            append('\n')
            append(previewStatus)
        }
    }

    suspend fun pingForChat(): String {
        val result = toolExecutor.pingForChat()
        applyToolSideEffects(result)
        updateRuntimeState { it.copy(pingStatus = result.output) }
        return result.output
    }

    suspend fun getVersionForChat(): String {
        val result = toolExecutor.getVersion()
        applyToolSideEffects(result)
        updateRuntimeState { it.copy(versionStatus = result.output) }
        return result.output
    }

    suspend fun getHealthForChat(): String {
        val result = toolExecutor.getHealth()
        applyToolSideEffects(result)
        updateRuntimeState { it.copy(healthStatus = result.output) }
        return result.output
    }

    suspend fun getLastErrorForChat(): String {
        val result = toolExecutor.getLastError()
        applyToolSideEffects(result)
        updateRuntimeState { it.copy(lastErrorStatus = result.output) }
        return result.output
    }

    suspend fun probeSessionForChat(): String {
        val result = toolExecutor.probeSession()
        applyToolSideEffects(result)
        updateRuntimeState { it.copy(session = it.session.copy(summary = result.output)) }
        return result.output
    }

    suspend fun getCapabilitiesForChat(): String {
        val result = toolExecutor.getCapabilities()
        applyToolSideEffects(result)
        updateRuntimeState { it.copy(capabilityStatus = result.output) }
        return result.output
    }

    suspend fun injectTapForChat(x: Int, y: Int, displayId: Int): String {
        val result = toolExecutor.injectTap(x = x, y = y, displayId = displayId)
        applyToolSideEffects(result)
        updateRuntimeState { it.copy(tapStatus = result.output) }
        return result.output
    }

    suspend fun injectSwipeForChat(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
        displayId: Int
    ): String {
        val result = toolExecutor.injectSwipe(
            x1 = x1,
            y1 = y1,
            x2 = x2,
            y2 = y2,
            durationMs = durationMs,
            displayId = displayId
        )
        applyToolSideEffects(result)
        updateRuntimeState { it.copy(swipeStatus = result.output) }
        return result.output
    }

    suspend fun executeShellForChat(command: String): String {
        val result = toolExecutor.execShellLimited(command = command, timeoutMs = 3000)
        applyToolSideEffects(result)
        updateRuntimeState { it.copy(shell = it.shell.copy(status = result.output)) }
        return if (result.success) {
            result.output.replaceFirst("成功:", "Shell 执行完成，")
        } else {
            "Shell 执行失败：${result.error ?: result.output}"
        }
    }

    suspend fun readScreenSizeForChat(): String {
        val result = toolExecutor.readScreenSize()
        applyToolSideEffects(result)
        return result.output
    }

    fun ping() {
        viewModelScope.launch {
            updateRuntimeState { it.copy(pingStatus = "请求中...") }
            val result = toolExecutor.ping()
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(pingStatus = result.output) }
        }
    }

    fun getVersion() {
        viewModelScope.launch {
            updateRuntimeState { it.copy(versionStatus = "请求中...") }
            val result = toolExecutor.getVersion()
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(versionStatus = result.output) }
        }
    }

    fun getHealth() {
        viewModelScope.launch {
            updateRuntimeState { it.copy(healthStatus = "请求中...") }
            val result = toolExecutor.getHealth()
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(healthStatus = result.output) }
        }
    }

    fun getLastError() {
        viewModelScope.launch {
            updateRuntimeState { it.copy(lastErrorStatus = "请求中...") }
            val result = toolExecutor.getLastError()
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(lastErrorStatus = result.output) }
        }
    }

    fun probeSession() {
        viewModelScope.launch {
            updateRuntimeState {
                it.copy(
                    session = it.session.copy(
                        state = ClawRuntimeConnectionState.Disconnected,
                        trace = "Disconnected",
                        authMode = "协商中...",
                        summary = "请求中...",
                        runtimeLoaded = null,
                        runtimeProcess = "",
                        runtimeLoadedAtEpochMs = 0L,
                        degradedReason = ""
                    )
                )
            }
            val result = toolExecutor.probeSession()
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(session = it.session.copy(summary = result.output)) }
        }
    }

    private suspend fun runStartupRuntimeProbe() {
        updateRuntimeState {
            it.copy(
                session = it.session.copy(
                    state = ClawRuntimeConnectionState.Disconnected,
                    trace = "Disconnected",
                    authMode = "启动自检中...",
                    summary = "启动后自动执行 Runtime Probe...",
                    runtimeLoaded = null,
                    runtimeProcess = "",
                    runtimeLoadedAtEpochMs = 0L,
                    degradedReason = ""
                )
            )
        }
        val result = toolExecutor.probeSession()
        applyToolSideEffects(result)
        updateRuntimeState { it.copy(session = it.session.copy(summary = result.output)) }
    }

    fun getCapabilities() {
        viewModelScope.launch {
            updateRuntimeState { it.copy(capabilityStatus = "请求中...") }
            val result = toolExecutor.getCapabilities()
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(capabilityStatus = result.output) }
        }
    }

    fun captureScreen() {
        viewModelScope.launch {
            updateRuntimeState { it.copy(captureStatus = "请求中...") }
            val result = toolExecutor.captureScreen(includeShaPreview = true)
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(captureStatus = result.output) }
        }
    }

    fun readLatestCapture() {
        viewModelScope.launch {
            updateRuntimeState { it.copy(readFileStatus = "请求中...") }
            val runtimeState = uiState.value.runtimeState
            val result = toolExecutor.readLatestCapture(
                latestCapturePath = runtimeState.capture.latestPath,
                latestCaptureFormat = runtimeState.capture.latestFormat,
                latestCaptureFileSize = runtimeState.capture.latestFileSize,
                previewLimitBytes = previewLimitBytes
            )
            val rendered = renderReadPreviewResult(result)
            updateRuntimeState { it.copy(readFileStatus = rendered) }
        }
    }

    fun injectTap() {
        viewModelScope.launch {
            updateRuntimeState { it.copy(tapStatus = "请求中...") }
            val result = toolExecutor.injectTap(x = 540, y = 1200)
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(tapStatus = result.output) }
        }
    }

    fun injectSwipe() {
        viewModelScope.launch {
            updateRuntimeState { it.copy(swipeStatus = "请求中...") }
            val result = toolExecutor.injectSwipe(
                x1 = 540,
                y1 = 1800,
                x2 = 540,
                y2 = 400,
                durationMs = 350
            )
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(swipeStatus = result.output) }
        }
    }

    fun executeShell() {
        viewModelScope.launch {
            updateRuntimeState {
                it.copy(
                    shell = it.shell.copy(
                        status = "请求中...",
                        output = "正在执行..."
                    )
                )
            }
            val result = toolExecutor.execShellLimited(
                command = uiState.value.runtimeState.shell.selectedCommand,
                timeoutMs = 3000
            )
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(shell = it.shell.copy(status = result.output)) }
        }
    }

    fun startContinuousSubscription(
        onStarted: ((String) -> Unit)? = null,
        onClosed: ((String) -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            eventSubscriptionJob?.cancelAndJoin()
            runtimeClient.stopEventSubscription()
            updateEventState {
                it.copy(
                    eventLines = emptyList(),
                    eventStatus = "连接中...",
                    eventStreaming = true,
                    eventLogVersion = it.eventLogVersion + 1
                )
            }
            eventSubscriptionJob = launch {
                runCatching {
                    runtimeClient.startEventSubscription(
                        events = listOf(
                            "daemon_status_changed",
                            "capability_changed",
                            "window_changed",
                            "task_state_changed"
                        ),
                        onStarted = { started ->
                            updateEventState {
                                it.copy(
                                    eventStatus = "订阅中: mode=${started.streamMode}, interval=${started.pollIntervalMs}ms, events=${started.subscribed.joinToString()}"
                                )
                            }
                            onStarted?.invoke("事件流已连接，轮询间隔 ${started.pollIntervalMs}ms。")
                        },
                        onEvent = { frame ->
                            refreshEventDerivedState(frame)
                            val line = "${formatEpochSeconds(frame.timestamp)}  ${summarizeEventFrame(frame)}"
                            updateEventState { current ->
                                current.copy(
                                    eventLines = (listOf(line) + current.eventLines).take(24),
                                    eventLogVersion = current.eventLogVersion + 1
                                )
                            }
                        },
                        onClosed = { reason ->
                            updateEventState {
                                it.copy(
                                    eventStreaming = false,
                                    eventStatus = "已停止: $reason"
                                )
                            }
                            onClosed?.invoke(reason)
                        }
                    )
                }.onFailure { error ->
                    val message = error.message ?: error::class.java.simpleName
                    updateEventState {
                        it.copy(
                            eventStreaming = false,
                            eventStatus = "失败: $message"
                        )
                    }
                    onFailure?.invoke(message)
                }
            }
        }
    }

    fun stopContinuousSubscription(onStopped: (() -> Unit)? = null) {
        viewModelScope.launch {
            runtimeClient.stopEventSubscription()
            eventSubscriptionJob?.cancelAndJoin()
            eventSubscriptionJob = null
            updateEventState {
                it.copy(
                    eventStreaming = false,
                    eventStatus = "已手动停止"
                )
            }
            onStopped?.invoke()
        }
    }

    fun applyDebugLongOverviewSeed() {
        if (!BuildConfig.DEBUG) {
            return
        }
        updateRuntimeState {
            it.copy(
                healthStatus = buildString {
                    appendLine("健康摘要: daemon=ok, ipc=connected, queue=stable, screenshots=enabled")
                    appendLine("最近窗口: com.clawdroid.app.debug/.MainActivity")
                    appendLine("长文本压测: 这一段用于确认概览页状态卡在多行场景下仍能完整滚动、换行和阅读，不会被底部悬浮导航或卡片边界裁剪。")
                    append("附加说明: diagnostics-mode=seeded, scenario=deep-scroll-overview, density=400, viewport=1800x2880, expectation=stable-wrapping-and-scroll")
                },
                lastErrorStatus = buildString {
                    appendLine("最近错误: connection handling failed: write unix @clawdroid_secure_ipc->@: write: broken pipe")
                    appendLine("错误时间: 2026-07-07 05:20:17")
                    appendLine("最近限流: none")
                    appendLine("限流时间: unknown")
                    appendLine("限流次数: 0")
                    append("只读白名单: /data/local/tmp/clawdroid, /data/local/tmp/clawdroid/captures, /data/local/tmp/clawdroid/audit, /sdcard/Pictures, /sdcard/Download")
                },
                shell = it.shell.copy(
                    status = "成功: template=debug.seed.long_overview, exit=0, duration=42ms, stdout_truncated=false, stderr_truncated=false",
                    output = buildString {
                        appendLine("stdout:")
                        repeat(10) { index ->
                            appendLine("line-${index + 1}: overview-seed keeps the result panel tall enough to test internal scrolling and dense multi-line rendering.")
                        }
                        append("stderr: none")
                    }
                )
            )
        }
        updateEventState { current ->
            val seedLines = List(12) { index ->
                "2026-07-07 05:2${index}  daemon_status_changed last_error=broken pipe sample=${index + 1} note=debug-seed-long-event-log-for-real-device-review"
            }
            current.copy(
                eventLines = seedLines,
                eventStatus = "订阅中: mode=continuous, interval=2000ms, events=daemon_status_changed, capability_changed, window_changed",
                eventStreaming = true,
                eventLogVersion = current.eventLogVersion + 1
            )
        }
    }

    override fun onCleared() {
        runtimeClient.stopEventSubscription()
        eventSubscriptionJob?.cancel()
        dashboardSamplingJob?.cancel()
        super.onCleared()
    }

    private fun renderReadPreviewResult(result: ClawToolCallResult): String {
        applyToolSideEffects(result)
        val bytes = result.previewBytes
        if (bytes == null) {
            _latestCapturePreview.value = null
            return result.output
        }
        val decoded = runCatching { decodeCapturePreview(bytes) }.getOrNull()
        val runtimeState = uiState.value.runtimeState
        return if (decoded != null) {
            _latestCapturePreview.value = decoded.image
            "成功: decoded=${decoded.width}x${decoded.height}, sample=${decoded.sampleSize}, bytes=${formatBytes(bytes.size.toLong())}, capture_size=${formatBytes(runtimeState.capture.latestFileSize)}, format=${runtimeState.capture.latestFormat}"
        } else {
            _latestCapturePreview.value = null
            "失败: 图片解码失败，read_bytes=${formatBytes(bytes.size.toLong())}, capture_size=${formatBytes(runtimeState.capture.latestFileSize)}, format=${runtimeState.capture.latestFormat}，可能是格式不匹配、尺寸异常或原图过大"
        }
    }

    private fun refreshEventDerivedState(frame: ClawRuntimeEventFrame) {
        when (frame.event) {
            "window_changed" -> {
                updateRuntimeState {
                    it.copy(
                        latestWindowSummary = parseFocusedWindowSummary(frame.data["focused_window"]?.toString())
                    )
                }
            }

            "daemon_status_changed" -> {
                val lastError = frame.data["last_error"]?.toString().orEmpty().ifBlank { "none" }
                val lastErrorAt = (frame.data["last_error_at"] as? Number)?.toLong() ?: 0L
                val lastRateLimit = frame.data["last_rate_limit"]?.toString().orEmpty().ifBlank { "none" }
                val lastRateLimitAt = (frame.data["last_rate_limit_at"] as? Number)?.toLong() ?: 0L
                val rateLimitHits = (frame.data["rate_limit_hits"] as? Number)?.toInt() ?: 0
                val rateLimitPerMinute = (frame.data["rate_limit_per_minute"] as? Number)?.toInt() ?: 0
                val readonlyWhitelist = (frame.data["readonly_whitelist"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                updateRuntimeState {
                    it.copy(
                        latestDaemonMetrics = buildDaemonMetricsSummary(frame.data),
                        latestRuntimeProcessMetrics = buildRuntimeProcessSummary(frame.data),
                        lastErrorStatus = "最近错误: $lastError\n错误时间: ${formatEpochSeconds(lastErrorAt)}\n最近限流: $lastRateLimit\n限流时间: ${formatEpochSeconds(lastRateLimitAt)}\n限流次数: $rateLimitHits\n只读白名单: ${readonlyWhitelist.joinToString()}"
                    )
                }
            }
        }
    }

    private fun updatePermissionState(transform: (OverviewPermissionState) -> OverviewPermissionState) {
        _uiState.update { current ->
            current.copy(permissionState = transform(current.permissionState))
        }
    }

    private fun updateRuntimeState(transform: (OverviewRuntimeState) -> OverviewRuntimeState) {
        _uiState.update { current ->
            current.copy(runtimeState = transform(current.runtimeState))
        }
    }

    private fun updateEventState(transform: (OverviewEventState) -> OverviewEventState) {
        _uiState.update { current ->
            current.copy(eventState = transform(current.eventState))
        }
    }

    private data class DecodedPreview(
        val image: ImageBitmap,
        val width: Int,
        val height: Int,
        val sampleSize: Int
    )

    private fun decodeCapturePreview(bytes: ByteArray, maxPreviewDimension: Int = 2048): DecodedPreview? {
        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return null
        }

        var sampleSize = 1
        while (max(sourceWidth / sampleSize, sourceHeight / sampleSize) > maxPreviewDimension) {
            sampleSize *= 2
        }

        val decodeOptions = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null
        return DecodedPreview(
            image = bitmap.asImageBitmap(),
            width = bitmap.width,
            height = bitmap.height,
            sampleSize = sampleSize
        )
    }

    companion object {
        fun provideFactory(
            appContext: Context,
            runtimeClient: ClawRuntimeClient,
            toolExecutor: ClawToolExecutor,
            previewLimitBytes: Int
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(OverviewController::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    return OverviewController(
                        appContext = appContext,
                        runtimeClient = runtimeClient,
                        toolExecutor = toolExecutor,
                        previewLimitBytes = previewLimitBytes
                    ) as T
                }
            }
        }
    }
}

@Composable
internal fun rememberOverviewController(
    context: Context,
    runtimeClient: ClawRuntimeClient,
    toolExecutor: ClawToolExecutor,
    previewLimitBytes: Int
): OverviewController {
    val factory = remember(context, runtimeClient, toolExecutor, previewLimitBytes) {
        OverviewController.provideFactory(
            appContext = context.applicationContext,
            runtimeClient = runtimeClient,
            toolExecutor = toolExecutor,
            previewLimitBytes = previewLimitBytes
        )
    }
    return viewModel(factory = factory)
}

private fun buildPermissionSummary(status: LocalEnvironmentStatus): String {
    return "通知=${permissionGrantedLabel(status.notificationPermissionGranted)}，无障碍=${booleanStatusLabel(status.accessibilityEnabled)}，系统设置=${permissionGrantedLabel(status.writeSettingsGranted)}，全部文件=${permissionGrantedLabel(status.allFilesAccessGranted)}"
}

private fun buildStartupCheckSummary(
    rootRequested: Boolean,
    rootResult: com.clawdroid.app.env.RootActionResult,
    finalStatus: LocalEnvironmentStatus,
    autoGrantMessages: List<String>
): String {
    val diagnosis = buildLocalEnvironmentDiagnosis(finalStatus)
    val rootSummary = when {
        rootRequested && rootResult.success -> "启动自检: Root 会话正常"
        rootRequested && rootResult.timedOut -> "启动自检: 等待 Magisk Root 授权超时，下次启动会继续自动请求"
        rootRequested -> "启动自检: Root 会话失败(${rootResult.output})"
        else -> "启动自检: 首次 Root 自动申请已跳过，等待手动授权"
    }
    val magiskSummary = "Magisk=${magiskStatusLabel(finalStatus)}, Runtime守护=${booleanStatusLabel(finalStatus.runtimeDaemonRunning)}"
    val permissionSummary = "通知=${permissionGrantedLabel(finalStatus.notificationPermissionGranted)}，无障碍=${booleanStatusLabel(finalStatus.accessibilityEnabled)}，系统设置=${permissionGrantedLabel(finalStatus.writeSettingsGranted)}，全部文件=${permissionGrantedLabel(finalStatus.allFilesAccessGranted)}"
    val restoreSummary = autoGrantMessages.takeIf { it.isNotEmpty() }?.joinToString("；") ?: "无需自动恢复授权"
    return "$rootSummary\n环境诊断: ${diagnosis.title}\n${diagnosis.actionHint}\n$magiskSummary\n$permissionSummary\n$restoreSummary"
}

private fun createInitialOverviewUiState(
    runtimeClient: ClawRuntimeClient
): OverviewUiState {
    val initialLocalEnvironmentStatus = LocalEnvironmentStatus(
        rootGranted = null,
        accessibilityEnabled = false,
        notificationPermissionGranted = false,
        writeSettingsGranted = false,
        allFilesAccessGranted = false,
        magiskDaemonRunning = false,
        magiskModuleInstalled = false,
        magiskModuleEnabled = false,
        runtimeDaemonRunning = false,
        lsposedManagerInstalled = false,
        xposedInjected = false
    )
    return OverviewUiState(
        permissionState = OverviewPermissionState(
            localEnvironmentStatus = initialLocalEnvironmentStatus,
            localEnvironmentSummary = "尚未检测本地环境",
            localEnvironmentDiagnosis = buildLocalEnvironmentDiagnosis(initialLocalEnvironmentStatus).asMultilineString(),
            permissionSummary = buildPermissionSummary(initialLocalEnvironmentStatus),
            permissionTargetPath = "/data/adb/modules/clawruntime",
            permissionChmodMode = "0755",
            permissionChownOwner = "0:0",
            permissionActionStatus = "未执行权限修复"
        ),
        runtimeState = OverviewRuntimeState(
            latestDaemonMetrics = "等待事件流",
            latestRuntimeProcessMetrics = "等待事件流",
            latestWindowSummary = "unknown",
            session = SessionInfo(
                state = ClawRuntimeConnectionState.Disconnected,
                trace = "Disconnected",
                authMode = "未协商",
                summary = "尚未建立会话",
                runtimeLoaded = null,
                runtimeProcess = "",
                runtimeLoadedAtEpochMs = 0L,
                degradedReason = "",
                runtimeSocketDisplay = runtimeClient.socketDisplayName(),
                packageDisplay = runtimeClient.packageDisplayName(),
                signatureDigestDisplay = runtimeClient.signatureDigestDisplay()
            ),
            shell = ShellState(
                status = "未执行 Shell",
                output = "暂无输出",
                selectedCommand = defaultShellCommandOptions().first(),
                commandOptions = defaultShellCommandOptions(),
                dropdownExpanded = false
            ),
            capture = CaptureState(
                latestPath = "",
                latestFormat = "unknown",
                latestDimensions = "unknown",
                latestFileSize = 0L
            ),
            pingStatus = "未检测",
            versionStatus = "未读取版本信息",
            healthStatus = "未读取健康状态",
            lastErrorStatus = "未读取最近错误",
            runtimeConfigSummary = "未读取 ClawRuntime 配置摘要",
            capabilityStatus = "未读取",
            captureStatus = "未截图",
            readFileStatus = "未读取文件",
            tapStatus = "未执行点击",
            swipeStatus = "未执行滑动"
        ),
        eventState = OverviewEventState(
            eventStatus = "未订阅事件",
            eventStreaming = false,
            eventLines = emptyList(),
            eventLogVersion = 0L
        )
    )
}
