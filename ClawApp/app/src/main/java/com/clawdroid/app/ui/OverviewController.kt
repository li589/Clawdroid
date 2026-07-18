package com.clawdroid.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawdroid.app.BuildConfig
import com.clawdroid.app.env.LocalEnvironmentStatus
import com.clawdroid.app.env.RootActionResult
import com.clawdroid.app.env.StartupAutoGrantPlan
import com.clawdroid.app.env.buildLocalEnvironmentDiagnosis
import com.clawdroid.app.env.buildStartupAutoGrantPlan
import com.clawdroid.app.env.shouldRememberRootPromptResult
import com.clawdroid.app.env.shouldAutoRequestRootOnStartup
import com.clawdroid.app.runtime.ClawRuntimeClient
import com.clawdroid.app.runtime.ClawRuntimeConnectionState
import com.clawdroid.app.runtime.ClawRuntimeEventFrame
import com.clawdroid.app.runtime.RuntimeEventService
import com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot
import com.clawdroid.app.tools.ClawCaptureArtifact
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.fault.FaultIsolation
import com.clawdroid.app.fault.safeLaunch
import com.clawdroid.app.tools.ClawToolExecutor
import com.clawdroid.app.focus.LiveXposedFocusStore
import com.clawdroid.app.focus.LiveXposedViewStore
import com.clawdroid.app.tools.LiveToolCapabilityStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class OverviewController(
    appContext: Context,
    private val runtimeClient: ClawRuntimeClient,
    private val toolExecutor: ClawToolExecutor,
    private val previewLimitBytes: Int,
    private val environmentGateway: OverviewEnvironmentGateway = DefaultOverviewEnvironmentGateway(appContext),
    private val metricsSampler: OverviewMetricsSampler = DefaultOverviewMetricsSampler,
    private val autoStart: Boolean = true,
    eventService: RuntimeEventService? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(createInitialOverviewUiState(runtimeClient))
    val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()
    private val _dashboardMetrics = MutableStateFlow(DashboardRuntimeMetrics())
    val dashboardMetrics: StateFlow<DashboardRuntimeMetrics> = _dashboardMetrics.asStateFlow()
    private val capturePreviewStore = CapturePreviewStore()
    val latestCapturePreview: StateFlow<ImageBitmap?> = capturePreviewStore.preview

    val automationController = AutomationController(
        toolExecutor = toolExecutor,
        scope = viewModelScope
    )

    private val ownsEventService = eventService == null
    private val runtimeEventService =
        eventService ?: RuntimeEventService(runtimeClient, viewModelScope)

    private val eventHub = OverviewEventHub(
        eventService = runtimeEventService,
        scope = viewModelScope,
        updateEventState = ::updateEventState,
        onEventFrame = ::refreshEventDerivedState,
        onUnhandledError = { _, message ->
            updateRuntimeState { it.copy(lastErrorStatus = message) }
        }
    )

    private var dashboardSamplingJob: Job? = null
    private var initialStartupCompleted: Boolean = false
    private var connectionRefreshInFlight: Boolean = false
    private var lastHostStartedRefreshAtMs: Long = 0L

    @Volatile
    private var captureArtifactListener: ((ClawCaptureArtifact) -> Unit)? = null

    @Volatile
    private var runtimeTaskEventListener: ((ClawRuntimeTaskSnapshot) -> Unit)? = null

    private val xposedFocusListener: (String) -> Unit = { summary ->
        viewModelScope.launch {
            updatePermissionState { state ->
                state.copy(
                    localEnvironmentStatus = state.localEnvironmentStatus.copy(
                        xposedFocusSummary = summary
                    )
                )
            }
        }
    }

    private val xposedViewListener: (String) -> Unit = { summary ->
        viewModelScope.launch {
            updatePermissionState { state ->
                state.copy(
                    localEnvironmentStatus = state.localEnvironmentStatus.copy(
                        xposedViewSummary = summary
                    )
                )
            }
        }
    }

    private fun onOverviewError(
        tag: String,
        setStatus: ((String) -> Unit)? = null
    ): (Throwable) -> Unit = { error ->
        val message = FaultIsolation.formatIsolatedError(tag, error)
        setStatus?.invoke(message)
        updateRuntimeState { it.copy(lastErrorStatus = message) }
    }

    fun setRuntimeTaskEventListener(listener: ((ClawRuntimeTaskSnapshot) -> Unit)?) {
        runtimeTaskEventListener = listener
    }

    fun setCaptureArtifactListener(listener: ((ClawCaptureArtifact) -> Unit)?) {
        captureArtifactListener = listener
        val capture = uiState.value.runtimeState.capture
        val path = capture.latestPath.trim()
        if (listener != null && path.isNotEmpty()) {
            val dimensions = capture.latestDimensions.split('x')
            listener(
                ClawCaptureArtifact(
                    imagePath = path,
                    format = capture.latestFormat,
                    width = dimensions.getOrNull(0)?.toIntOrNull() ?: 0,
                    height = dimensions.getOrNull(1)?.toIntOrNull() ?: 0,
                    fileSize = capture.latestFileSize,
                    sha256 = ""
                )
            )
        }
    }

    init {
        LiveXposedFocusStore.addListener(xposedFocusListener)
        LiveXposedViewStore.addListener(xposedViewListener)
        if (autoStart) {
            safeLaunch("overview:startup", onError = onOverviewError("overview:startup") { msg ->
                updatePermissionState { it.copy(permissionActionStatus = msg) }
            }) {
                performStartupChecks()
            }
        } else {
            initialStartupCompleted = true
        }
    }

    fun refreshLocalEnvironment() {
        safeLaunch("overview:env", onError = onOverviewError("overview:env") { msg ->
            updatePermissionState { it.copy(permissionActionStatus = msg) }
        }) {
            refreshLocalEnvironmentState(includeRootCheck = true)
        }
    }

    suspend fun refreshLocalEnvironmentState(includeRootCheck: Boolean) {
        updatePermissionState { it.copy(localEnvironmentSummary = "检测中...") }
        val status = environmentGateway.probeLocalEnvironment(includeRootCheck = includeRootCheck)

        environmentGateway.rememberGrantedPermissions(status)
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
        safeLaunch("overview:settings-return", onError = onOverviewError("overview:settings-return") { msg ->
            updatePermissionState { it.copy(permissionActionStatus = msg) }
        }) {
            refreshLocalEnvironmentState(includeRootCheck = true)
            updatePermissionState { it.copy(permissionActionStatus = "已从系统设置返回，请查看最新权限状态") }
        }
    }

    fun handleNotificationPermissionResult(granted: Boolean) {
        safeLaunch("overview:notification", onError = onOverviewError("overview:notification") { msg ->
            updatePermissionState { it.copy(permissionActionStatus = msg) }
        }) {
            refreshLocalEnvironmentState(includeRootCheck = true)
            if (granted) {
                environmentGateway.rememberNotificationGrant()
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
            action = environmentGateway::grantNotificationViaRoot,
            onSuccess = environmentGateway::rememberNotificationGrant
        )
    }

    fun grantWriteSettingsViaRoot() {
        runRootPermissionAction(
            action = environmentGateway::grantWriteSettingsViaRoot,
            onSuccess = environmentGateway::rememberWriteSettingsGrant
        )
    }

    fun grantAllFilesAccessViaRoot() {
        runRootPermissionAction(
            action = environmentGateway::grantAllFilesAccessViaRoot,
            onSuccess = environmentGateway::rememberAllFilesGrant
        )
    }

    fun enableAccessibilityViaRoot() {
        runRootPermissionAction(
            action = environmentGateway::enableAccessibilityViaRoot,
            onSuccess = environmentGateway::rememberAccessibilityGrant
        )
    }

    fun grantAutomationPermissionsViaRoot() {
        runRootPermissionAction(
            action = environmentGateway::grantAutomationPermissionsViaRoot,
            onSuccess = environmentGateway::rememberAutomationGrant
        )
    }

    fun chmodPermissionTargetPathViaRoot() {
        val permissionState = uiState.value.permissionState
        runRootPermissionAction(
            action = {
                environmentGateway.chmodPathViaRoot(
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
                environmentGateway.chownPathViaRoot(
                    path = permissionState.permissionTargetPath,
                    ownerSpec = permissionState.permissionChownOwner
                )
            }
        )
    }

    private fun runRootPermissionAction(
        action: suspend () -> RootActionResult,
        onSuccess: (() -> Unit)? = null
    ) {
        safeLaunch("overview:root-action", onError = onOverviewError("overview:root-action") { msg ->
            updatePermissionState { it.copy(permissionActionStatus = msg) }
        }) {
            updatePermissionState { it.copy(permissionActionStatus = "执行中...") }
            val result = action()
            if (result.success) {
                environmentGateway.markRootPromptResult(granted = true)
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
        val remembered = environmentGateway.loadStartupPermissionState()
        val shouldRequestRoot = shouldAutoRequestRootOnStartup(remembered)
        val rootResult = if (shouldRequestRoot) {
            environmentGateway.requestRootAccess()
        } else {
            RootActionResult(
                success = false,
                output = "首次启动尚未获得 Root 授权，保持手动授权模式"
            )
        }
        if (shouldRequestRoot && shouldRememberRootPromptResult(rootResult)) {
            environmentGateway.markRootPromptResult(granted = rootResult.success)
        }

        val includeRootCheck = rootResult.success || remembered.rootGrantedEver
        val initialStatus = environmentGateway.probeLocalEnvironment(includeRootCheck = includeRootCheck)
        environmentGateway.rememberGrantedPermissions(initialStatus)

        val autoGrantMessages = mutableListOf<String>()
        if (rootResult.success) {
            val plan = buildStartupAutoGrantPlan(
                state = environmentGateway.loadStartupPermissionState(),
                status = initialStatus
            )
            if (plan.hasWork) {
                autoGrantMessages += applyStartupAutoGrantPlan(plan)
            }
        }

        refreshLocalEnvironmentState(includeRootCheck = includeRootCheck)
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
        connectRuntimeAfterEnvironmentCheck(reason = "启动自检")
        initialStartupCompleted = true
        lastHostStartedRefreshAtMs = System.currentTimeMillis()
    }

    fun onHostStarted() {
        if (!initialStartupCompleted || connectionRefreshInFlight) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastHostStartedRefreshAtMs < FOREGROUND_REFRESH_MIN_INTERVAL_MS) {
            return
        }
        lastHostStartedRefreshAtMs = now
        safeLaunch("overview:host-started", onError = onOverviewError("overview:host-started")) {
            refreshConnectionIfStale()
        }
    }

    suspend fun refreshConnectionIfStale(force: Boolean = false) {
        if (connectionRefreshInFlight) {
            return
        }
        connectionRefreshInFlight = true
        try {
            val sessionState = uiState.value.runtimeState.session.state
            val alreadyConnected = sessionState == ClawRuntimeConnectionState.Ready ||
                sessionState == ClawRuntimeConnectionState.Degraded
            if (!force && alreadyConnected) {
                return
            }
            val remembered = environmentGateway.loadStartupPermissionState()
            val includeRootCheck = remembered.rootGrantedEver ||
                uiState.value.permissionState.localEnvironmentStatus.rootGranted == true
            refreshLocalEnvironmentState(includeRootCheck = includeRootCheck)
            connectRuntimeAfterEnvironmentCheck(reason = "前台恢复")
        } finally {
            connectionRefreshInFlight = false
        }
    }

    private suspend fun applyStartupAutoGrantPlan(plan: StartupAutoGrantPlan): List<String> {
        val messages = mutableListOf<String>()
        if (plan.useAutomationGrant) {
            val result = environmentGateway.grantAutomationPermissionsViaRoot()
            if (result.success) {
                environmentGateway.rememberAutomationGrant()
                messages += "自动恢复一键权限"
            } else {
                messages += "自动恢复一键权限失败: ${result.output}"
            }
            return messages
        }

        if (plan.grantNotification) {
            val result = environmentGateway.grantNotificationViaRoot()
            messages += if (result.success) {
                environmentGateway.rememberNotificationGrant()
                "自动恢复通知权限"
            } else {
                "自动恢复通知权限失败: ${result.output}"
            }
        }
        if (plan.grantWriteSettings) {
            val result = environmentGateway.grantWriteSettingsViaRoot()
            messages += if (result.success) {
                environmentGateway.rememberWriteSettingsGrant()
                "自动恢复系统设置权限"
            } else {
                "自动恢复系统设置权限失败: ${result.output}"
            }
        }
        if (plan.grantAllFiles) {
            val result = environmentGateway.grantAllFilesAccessViaRoot()
            messages += if (result.success) {
                environmentGateway.rememberAllFilesGrant()
                "自动恢复全部文件访问"
            } else {
                "自动恢复全部文件访问失败: ${result.output}"
            }
        }
        if (plan.grantAccessibility) {
            val result = environmentGateway.enableAccessibilityViaRoot()
            messages += if (result.success) {
                environmentGateway.rememberAccessibilityGrant()
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
        dashboardSamplingJob = safeLaunch("overview:dashboard", onError = onOverviewError("overview:dashboard")) {
            while (true) {
                val rootAvailable = uiState.value.permissionState.localEnvironmentStatus.rootGranted == true
                val sampledMetrics = metricsSampler.sample(rootAvailable = rootAvailable)
                _dashboardMetrics.value = sampledMetrics
                delay(1000)
            }
        }
    }

    fun applyToolSideEffects(result: ClawToolCallResult) {
        applyNonPreviewToolSideEffects(result)
        // Decode preview here only — never call renderReadPreviewResult (avoids recursion).
        result.previewBytes?.takeIf { it.isNotEmpty() }?.let { bytes ->
            capturePreviewStore.publishDecoded(bytes)
        }
    }

    private fun applyNonPreviewToolSideEffects(result: ClawToolCallResult) {
        updateRuntimeState { current ->
            current.copy(
                shell = current.shell.copy(
                    output = result.shellOutput ?: current.shell.output
                )
            )
        }
        result.captureArtifact?.let { artifact ->
            capturePreviewStore.clear()
            captureArtifactListener?.invoke(artifact)
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
        val snapshots = result.taskSnapshots
            ?: listOfNotNull(result.taskSnapshot)
        if (snapshots.isNotEmpty()) {
            updateRuntimeState { current ->
                val byId = linkedMapOf<String, ClawRuntimeTaskSnapshot>()
                snapshots.forEach { snapshot ->
                    if (snapshot.taskId.isNotBlank()) {
                        byId[snapshot.taskId] = snapshot
                    }
                }
                current.runtimeTasks.forEach { existing ->
                    if (existing.taskId.isNotBlank() && existing.taskId !in byId) {
                        byId[existing.taskId] = existing
                    }
                }
                val merged = byId.values
                    .sortedByDescending { task ->
                        task.startedAt.takeIf { value -> value > 0L } ?: task.createdAt
                    }
                    .take(MAX_RUNTIME_TASKS_VISIBLE)
                current.copy(
                    runtimeTasks = merged,
                    runtimeTasksStatus = "已同步 ${merged.size} 个任务"
                )
            }
        }
    }

    /**
     * Apply capture/session/task sync plus per-tool Overview status fields after Dispatcher execution.
     */
    fun applyChatToolEffects(
        tool: ClawTool,
        arguments: Map<String, String>,
        result: ClawToolCallResult
    ) {
        applyToolSideEffects(result)
        when (tool) {
            ClawTool.RUNTIME_PING -> updateRuntimeState { it.copy(pingStatus = result.output) }
            ClawTool.GET_VERSION -> updateRuntimeState { it.copy(versionStatus = result.output) }
            ClawTool.GET_HEALTH -> updateRuntimeState { it.copy(healthStatus = result.output) }
            ClawTool.GET_RUNTIME_STATUS -> applyRuntimeStatusSideEffects(result)
            ClawTool.GET_LAST_ERROR -> updateRuntimeState { it.copy(lastErrorStatus = result.output) }
            ClawTool.PROBE_SESSION -> updateRuntimeState {
                it.copy(session = it.session.copy(summary = result.output))
            }
            ClawTool.GET_CAPABILITIES -> updateRuntimeState { it.copy(capabilityStatus = result.output) }
            ClawTool.CAPTURE_SCREEN -> updateRuntimeState { it.copy(captureStatus = result.output) }
            ClawTool.READ_LATEST_CAPTURE -> updateRuntimeState { it.copy(readFileStatus = result.output) }
            ClawTool.INJECT_TAP -> updateRuntimeState { it.copy(tapStatus = result.output) }
            ClawTool.INJECT_KEYEVENT -> updateRuntimeState { it.copy(keyeventStatus = result.output) }
            ClawTool.INJECT_SWIPE -> updateRuntimeState { it.copy(swipeStatus = result.output) }
            ClawTool.EXECUTE_SHELL_LIMITED -> updateRuntimeState {
                it.copy(shell = it.shell.copy(status = result.output))
            }
            ClawTool.PAGE_CONFIRM,
            ClawTool.CLICK_PRECHECK,
            ClawTool.SAFE_TAP -> {
                automationController.applyDispatchedToolEffects(tool, arguments, result)
            }
            else -> Unit
        }
    }

    private fun applyRuntimeStatusSideEffects(result: ClawToolCallResult) {
        updateRuntimeState { current ->
            val syncedCommands = mergeShellCommandOptions(
                current = current.shell.commandOptions,
                remote = result.allowedShellCommands.orEmpty()
            )
            current.copy(
                runtimeStatus = result.output,
                runtimeConfigSummary = result.runtimeConfigSummary ?: current.runtimeConfigSummary,
                shell = current.shell.copy(
                    commandOptions = syncedCommands,
                    selectedCommand = when {
                        current.shell.selectedCommand in syncedCommands -> current.shell.selectedCommand
                        syncedCommands.isNotEmpty() -> syncedCommands.first()
                        else -> current.shell.selectedCommand
                    }
                )
            )
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
            capturePreviewStore.clear()
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

    suspend fun getRuntimeStatusForChat(): String {
        val result = toolExecutor.getRuntimeStatus()
        applyToolSideEffects(result)
        applyRuntimeStatusSideEffects(result)
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

    suspend fun injectKeyeventForChat(key: String? = null, keyCode: Int? = null, displayId: Int = 0): String {
        val result = toolExecutor.injectKeyevent(key = key, keyCode = keyCode, displayId = displayId)
        applyToolSideEffects(result)
        updateRuntimeState { it.copy(keyeventStatus = result.output) }
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

    fun refreshRuntimeTasks() {
        safeLaunch("overview:tasks", onError = onOverviewError("overview:tasks") { msg ->
            updateRuntimeState { it.copy(runtimeTasksStatus = msg) }
        }) {
            updateRuntimeState { it.copy(runtimeTasksStatus = "刷新中...") }
            runtimeClient.taskList().fold(
                onSuccess = { result ->
                    val listed = result.tasks
                        .sortedByDescending { task ->
                            task.startedAt.takeIf { value -> value > 0L } ?: task.createdAt
                        }
                        .take(MAX_RUNTIME_TASKS_VISIBLE)
                    updateRuntimeState {
                        it.copy(
                            runtimeTasks = listed,
                            runtimeTasksStatus = if (listed.isEmpty()) {
                                "当前会话暂无 Runtime 任务"
                            } else {
                                "已同步 ${listed.size} 个任务"
                            }
                        )
                    }
                },
                onFailure = { error ->
                    updateRuntimeState {
                        it.copy(
                            runtimeTasksStatus = "失败: ${error.message ?: error::class.java.simpleName}"
                        )
                    }
                }
            )
        }
    }

    fun cancelRuntimeTask(taskId: String) {
        val normalizedId = taskId.trim()
        if (normalizedId.isBlank()) {
            return
        }
        safeLaunch("overview:task-cancel", onError = onOverviewError("overview:task-cancel") { msg ->
            updateRuntimeState { it.copy(runtimeTasksStatus = msg) }
        }) {
            updateRuntimeState { it.copy(runtimeTasksStatus = "正在取消 $normalizedId ...") }
            val result = toolExecutor.taskCancel(normalizedId)
            updateRuntimeState { it.copy(runtimeTasksStatus = result.output) }
            if (result.success) {
                refreshRuntimeTasks()
            }
        }
    }

    private fun upsertRuntimeTask(snapshot: ClawRuntimeTaskSnapshot) {
        updateRuntimeState { current ->
            val merged = buildList {
                add(snapshot)
                current.runtimeTasks.forEach { existing ->
                    if (existing.taskId != snapshot.taskId) {
                        add(existing)
                    }
                }
            }.take(MAX_RUNTIME_TASKS_VISIBLE)
            current.copy(
                runtimeTasks = merged,
                runtimeTasksStatus = "事件更新: ${snapshot.summaryLine()}"
            )
        }
    }

    fun ping() {
        safeLaunch("overview:ping", onError = onOverviewError("overview:ping") { msg ->
            updateRuntimeState { it.copy(pingStatus = msg) }
        }) {
            updateRuntimeState { it.copy(pingStatus = "请求中...") }
            val result = toolExecutor.ping()
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(pingStatus = result.output) }
        }
    }

    fun getVersion() {
        safeLaunch("overview:version", onError = onOverviewError("overview:version") { msg ->
            updateRuntimeState { it.copy(versionStatus = msg) }
        }) {
            updateRuntimeState { it.copy(versionStatus = "请求中...") }
            val result = toolExecutor.getVersion()
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(versionStatus = result.output) }
        }
    }

    fun getHealth() {
        safeLaunch("overview:health", onError = onOverviewError("overview:health") { msg ->
            updateRuntimeState { it.copy(healthStatus = msg) }
        }) {
            updateRuntimeState { it.copy(healthStatus = "请求中...") }
            val result = toolExecutor.getHealth()
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(healthStatus = result.output) }
        }
    }

    fun getRuntimeStatus() {
        safeLaunch("overview:runtime-status", onError = onOverviewError("overview:runtime-status") { msg ->
            updateRuntimeState { it.copy(runtimeStatus = msg) }
        }) {
            updateRuntimeState { it.copy(runtimeStatus = "请求中...") }
            val result = toolExecutor.getRuntimeStatus()
            applyToolSideEffects(result)
            applyRuntimeStatusSideEffects(result)
        }
    }

    fun getLastError() {
        safeLaunch("overview:last-error", onError = onOverviewError("overview:last-error") { msg ->
            updateRuntimeState { it.copy(lastErrorStatus = msg) }
        }) {
            updateRuntimeState { it.copy(lastErrorStatus = "请求中...") }
            val result = toolExecutor.getLastError()
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(lastErrorStatus = result.output) }
        }
    }

    fun probeSession() {
        safeLaunch("overview:probe", onError = onOverviewError("overview:probe") { msg ->
            updateRuntimeState { state ->
                state.copy(session = state.session.copy(summary = msg))
            }
        }) {
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

    private suspend fun connectRuntimeAfterEnvironmentCheck(reason: String) {
        val env = uiState.value.permissionState.localEnvironmentStatus
        val precheckHint = when {
            env.rootGranted == true && !env.runtimeDaemonRunning ->
                "$reason: 已确认 Root，但 Runtime 守护未运行，跳过自动 Probe"
            env.rootGranted != true ->
                "$reason: Root 未就绪，仍尝试连接 Runtime"
            !env.magiskModuleInstalled ->
                "$reason: Magisk 模块未安装，仍尝试连接 Runtime"
            !env.magiskModuleEnabled ->
                "$reason: Magisk 模块未启用，仍尝试连接 Runtime"
            !env.runtimeDaemonRunning ->
                "$reason: Runtime 守护未检测到，仍尝试连接 Runtime"
            else ->
                "$reason: Root/Magisk 检查通过，自动连接 Runtime"
        }

        if (env.rootGranted == true && !env.runtimeDaemonRunning) {
            updateRuntimeState {
                it.copy(
                    session = it.session.copy(
                        state = ClawRuntimeConnectionState.Disconnected,
                        trace = "Disconnected",
                        authMode = "未协商",
                        summary = buildString {
                            appendLine(precheckHint)
                            append("建议检查 Magisk 模块 service.sh / verify.sh，确认 clawdroid-runtime 已拉起后再手动 Probe。")
                        }.trim(),
                        runtimeLoaded = null,
                        runtimeProcess = "",
                        runtimeLoadedAtEpochMs = 0L,
                        degradedReason = "runtime_daemon_not_running"
                    ),
                    capabilityStatus = "未同步：Runtime 守护未运行"
                )
            }
            return
        }

        updateRuntimeState {
            it.copy(
                session = it.session.copy(
                    state = ClawRuntimeConnectionState.Disconnected,
                    trace = "Disconnected",
                    authMode = "$reason 中...",
                    summary = "$precheckHint\n正在执行 Runtime Probe...",
                    runtimeLoaded = null,
                    runtimeProcess = "",
                    runtimeLoadedAtEpochMs = 0L,
                    degradedReason = ""
                )
            )
        }
        val probeResult = toolExecutor.probeSession()
        applyToolSideEffects(probeResult)
        updateRuntimeState {
            it.copy(
                session = it.session.copy(
                    summary = buildString {
                        appendLine(precheckHint)
                        append(probeResult.output)
                    }.trim()
                )
            )
        }

        val sessionState = uiState.value.runtimeState.session.state
        val shouldSyncCapabilities = probeResult.success &&
            (sessionState == ClawRuntimeConnectionState.Ready ||
                sessionState == ClawRuntimeConnectionState.Degraded)
        if (!shouldSyncCapabilities) {
            if (!probeResult.success) {
                updateRuntimeState {
                    it.copy(capabilityStatus = "未同步：Runtime Probe 未成功")
                }
            }
            return
        }

        updateRuntimeState { it.copy(capabilityStatus = "同步中...") }
        val capabilitiesResult = toolExecutor.getCapabilities()
        applyToolSideEffects(capabilitiesResult)
        updateRuntimeState { it.copy(capabilityStatus = capabilitiesResult.output) }
        ensureAutoEventSubscription(reason = reason)
        refreshRuntimeTasks()
    }

    private fun ensureAutoEventSubscription(reason: String) {
        if (uiState.value.eventState.eventStreaming) {
            return
        }
        startContinuousSubscription(
            onStarted = {
                updateEventState { current ->
                    current.copy(
                        eventStatus = "${current.eventStatus}（$reason 自动订阅）"
                    )
                }
            },
            onFailure = { message ->
                updateEventState { current ->
                    current.copy(
                        eventStatus = "自动订阅失败($reason): $message"
                    )
                }
            }
        )
    }

    fun getCapabilities() {
        safeLaunch("overview:capabilities", onError = onOverviewError("overview:capabilities") { msg ->
            updateRuntimeState { it.copy(capabilityStatus = msg) }
        }) {
            updateRuntimeState { it.copy(capabilityStatus = "请求中...") }
            val result = toolExecutor.getCapabilities()
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(capabilityStatus = result.output) }
        }
    }

    fun captureScreen() {
        safeLaunch("overview:capture", onError = onOverviewError("overview:capture") { msg ->
            updateRuntimeState { it.copy(captureStatus = msg) }
        }) {
            updateRuntimeState { it.copy(captureStatus = "请求中...") }
            val result = toolExecutor.captureScreen(includeShaPreview = true)
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(captureStatus = result.output) }
        }
    }

    fun readLatestCapture() {
        safeLaunch("overview:read-capture", onError = onOverviewError("overview:read-capture") { msg ->
            updateRuntimeState { it.copy(readFileStatus = msg) }
        }) {
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
        safeLaunch("overview:tap", onError = onOverviewError("overview:tap") { msg ->
            updateRuntimeState { it.copy(tapStatus = msg) }
        }) {
            updateRuntimeState { it.copy(tapStatus = "请求中...") }
            val result = toolExecutor.injectTap(x = 540, y = 1200)
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(tapStatus = result.output) }
        }
    }

    fun injectSwipe() {
        safeLaunch("overview:swipe", onError = onOverviewError("overview:swipe") { msg ->
            updateRuntimeState { it.copy(swipeStatus = msg) }
        }) {
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

    fun injectKeyeventBack() {
        safeLaunch("overview:keyevent", onError = onOverviewError("overview:keyevent") { msg ->
            updateRuntimeState { it.copy(keyeventStatus = msg) }
        }) {
            updateRuntimeState { it.copy(keyeventStatus = "请求中...") }
            val result = toolExecutor.injectKeyevent(key = "BACK")
            applyToolSideEffects(result)
            updateRuntimeState { it.copy(keyeventStatus = result.output) }
        }
    }

    fun executeShell() {
        safeLaunch("overview:shell", onError = onOverviewError("overview:shell") { msg ->
            updateRuntimeState {
                it.copy(shell = it.shell.copy(status = msg, output = msg))
            }
        }) {
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
        eventHub.start(onStarted = onStarted, onClosed = onClosed, onFailure = onFailure)
    }

    fun stopContinuousSubscription(onStopped: (() -> Unit)? = null) {
        eventHub.stop(onStopped = onStopped)
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
                    append("只读白名单: /data/local/tmp/clawdroid, /data/local/tmp/clawdroid/captures, /data/local/tmp/clawdroid/audit, /data/local/tmp/clawdroid/xposed, /data/adb/modules/clawruntime, /sdcard/Pictures, /sdcard/Download")
                },
                runtimeStatus = buildString {
                    appendLine("成功: version=0.2.0, uptime=120s")
                    appendLine("module=clawruntime, installed=true, enabled=true, state=running, pid=3141")
                    appendLine("verify=ok: seeded")
                    append("actions=14, shell=18, keys=11")
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
        LiveXposedFocusStore.removeListener(xposedFocusListener)
        LiveXposedViewStore.removeListener(xposedViewListener)
        eventHub.detach()
        if (ownsEventService) {
            runtimeEventService.shutdown()
        }
        dashboardSamplingJob?.cancel()
        super.onCleared()
    }

    private fun renderReadPreviewResult(result: ClawToolCallResult): String {
        applyNonPreviewToolSideEffects(result)
        val bytes = result.previewBytes
        if (bytes == null || bytes.isEmpty()) {
            capturePreviewStore.clear()
            return result.output
        }
        val decoded = capturePreviewStore.publishDecoded(bytes)
        val runtimeState = uiState.value.runtimeState
        return if (decoded != null) {
            "成功: decoded=${decoded.width}x${decoded.height}, sample=${decoded.sampleSize}, bytes=${formatBytes(bytes.size.toLong())}, capture_size=${formatBytes(runtimeState.capture.latestFileSize)}, format=${runtimeState.capture.latestFormat}"
        } else {
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

            "task_state_changed" -> {
                val snapshot = ClawRuntimeTaskSnapshot.fromData(frame.data)
                if (snapshot.taskId.isNotBlank()) {
                    upsertRuntimeTask(snapshot)
                    runtimeTaskEventListener?.invoke(snapshot)
                }
            }

            "capability_changed" -> {
                // LiveToolCapabilityStore already updated by RuntimeEventService.
                val root = frame.data["root"]?.toString().orEmpty()
                val accessibility = frame.data["accessibility"]?.toString().orEmpty()
                val loaded = frame.data["lsposed_runtime_loaded"]?.toString().orEmpty()
                val caps = LiveToolCapabilityStore.snapshot()
                updateRuntimeState {
                    it.copy(
                        capabilityStatus = buildString {
                            appendLine("事件同步: capability_changed")
                            appendLine("root=$root, accessibility=$accessibility, runtime_loaded=$loaded")
                            append("capabilities=[${caps.joinToString()}]")
                        }
                    )
                }
            }

            "daemon_status_changed" -> {
                val lastError = frame.data["last_error"]?.toString().orEmpty().ifBlank { "none" }
                val lastErrorAt = (frame.data["last_error_at"] as? Number)?.toLong() ?: 0L
                val lastRateLimit = frame.data["last_rate_limit"]?.toString().orEmpty().ifBlank { "none" }
                val lastRateLimitAt = (frame.data["last_rate_limit_at"] as? Number)?.toLong() ?: 0L
                val rateLimitHits = (frame.data["rate_limit_hits"] as? Number)?.toInt() ?: 0
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

    companion object {
        private const val FOREGROUND_REFRESH_MIN_INTERVAL_MS = 5_000L
        private const val MAX_RUNTIME_TASKS_VISIBLE = 8

        fun provideFactory(
            appContext: Context,
            runtimeClient: ClawRuntimeClient,
            toolExecutor: ClawToolExecutor,
            previewLimitBytes: Int,
            eventService: RuntimeEventService? = null
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
                        previewLimitBytes = previewLimitBytes,
                        eventService = eventService
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
    previewLimitBytes: Int,
    eventService: RuntimeEventService? = null
): OverviewController {
    val factory = remember(context, runtimeClient, toolExecutor, previewLimitBytes, eventService) {
        OverviewController.provideFactory(
            appContext = context.applicationContext,
            runtimeClient = runtimeClient,
            toolExecutor = toolExecutor,
            previewLimitBytes = previewLimitBytes,
            eventService = eventService
        )
    }
    return viewModel(factory = factory)
}

private fun buildPermissionSummary(status: LocalEnvironmentStatus): String {
    return "通知=${permissionGrantedLabel(status.notificationPermissionGranted)}，通知监听=${booleanStatusLabel(status.notificationListenerEnabled)}，无障碍=${booleanStatusLabel(status.accessibilityEnabled)}，系统设置=${permissionGrantedLabel(status.writeSettingsGranted)}，全部文件=${permissionGrantedLabel(status.allFilesAccessGranted)}，Shizuku=${shizukuStatusLabel(status)}"
}

private fun shizukuStatusLabel(status: LocalEnvironmentStatus): String {
    return when {
        status.shizukuPermissionGranted -> "已授权"
        status.shizukuBinderAlive -> "未授权"
        status.shizukuManagerInstalled -> "未连接"
        else -> "未安装"
    }
}

internal fun buildStartupCheckSummary(
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

internal fun createInitialOverviewUiState(
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
            runtimeStatus = "未读取 Runtime/模块统一状态",
            lastErrorStatus = "未读取最近错误",
            runtimeConfigSummary = "未读取 ClawRuntime 配置摘要",
            capabilityStatus = "未读取",
            captureStatus = "未截图",
            readFileStatus = "未读取文件",
            tapStatus = "未执行点击",
            swipeStatus = "未执行滑动",
            keyeventStatus = "未执行按键"
        ),
        eventState = OverviewEventState(
            eventStatus = "未订阅事件",
            eventStreaming = false,
            eventLines = emptyList(),
            eventLogVersion = 0L
        )
    )
}
