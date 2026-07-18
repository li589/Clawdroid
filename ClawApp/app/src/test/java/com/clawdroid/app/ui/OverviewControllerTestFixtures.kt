package com.clawdroid.app.ui

import android.content.Context
import com.clawdroid.app.env.LocalEnvironmentStatus
import com.clawdroid.app.env.RootActionResult
import com.clawdroid.app.env.StartupPermissionState
import com.clawdroid.app.runtime.ClawRuntimeClient
import com.clawdroid.app.runtime.ClawRuntimeConnectionState
import com.clawdroid.app.runtime.ClawRuntimeEventFrame
import com.clawdroid.app.runtime.ClawRuntimeEventSubscriptionStarted
import com.clawdroid.app.tools.ClawCaptureArtifact
import com.clawdroid.app.tools.ClawSessionSnapshot
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolExecutor
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs

internal fun overviewEnvironmentStatus(
    rootGranted: Boolean? = null,
    accessibilityEnabled: Boolean = false,
    notificationPermissionGranted: Boolean = false,
    writeSettingsGranted: Boolean = false,
    allFilesAccessGranted: Boolean = false,
    magiskDaemonRunning: Boolean = false,
    magiskModuleInstalled: Boolean = false,
    magiskModuleEnabled: Boolean = false,
    runtimeDaemonRunning: Boolean = false,
    lsposedManagerInstalled: Boolean = false,
    xposedInjected: Boolean = false
): LocalEnvironmentStatus {
    return LocalEnvironmentStatus(
        rootGranted = rootGranted,
        accessibilityEnabled = accessibilityEnabled,
        notificationPermissionGranted = notificationPermissionGranted,
        writeSettingsGranted = writeSettingsGranted,
        allFilesAccessGranted = allFilesAccessGranted,
        magiskDaemonRunning = magiskDaemonRunning,
        magiskModuleInstalled = magiskModuleInstalled,
        magiskModuleEnabled = magiskModuleEnabled,
        runtimeDaemonRunning = runtimeDaemonRunning,
        lsposedManagerInstalled = lsposedManagerInstalled,
        xposedInjected = xposedInjected
    )
}

internal fun rootActionResult(
    success: Boolean,
    output: String,
    timedOut: Boolean = false
): RootActionResult {
    return RootActionResult(success = success, output = output, timedOut = timedOut)
}

internal fun toolCallResult(
    success: Boolean = true,
    output: String = "ok",
    error: String? = null,
    runtimeConfigSummary: String? = null,
    shellOutput: String? = null,
    captureArtifact: ClawCaptureArtifact? = null,
    previewBytes: ByteArray? = null,
    sessionSnapshot: ClawSessionSnapshot? = null
): ClawToolCallResult {
    return ClawToolCallResult(
        success = success,
        output = output,
        error = error,
        runtimeConfigSummary = runtimeConfigSummary,
        shellOutput = shellOutput,
        captureArtifact = captureArtifact,
        previewBytes = previewBytes,
        sessionSnapshot = sessionSnapshot
    )
}

internal fun sessionSnapshot(
    sessionState: ClawRuntimeConnectionState = ClawRuntimeConnectionState.Ready,
    sessionTrace: String = "Disconnected -> Ready",
    authMode: String = "token",
    runtimeLoaded: Boolean? = true,
    runtimeProcess: String = "zygote64",
    runtimeLoadedAtEpochMs: Long = 123L,
    degradedReason: String = ""
): ClawSessionSnapshot {
    return ClawSessionSnapshot(
        sessionState = sessionState,
        sessionTrace = sessionTrace,
        authMode = authMode,
        runtimeLoaded = runtimeLoaded,
        runtimeProcess = runtimeProcess,
        runtimeLoadedAtEpochMs = runtimeLoadedAtEpochMs,
        degradedReason = degradedReason
    )
}

internal fun captureArtifact(
    imagePath: String = "/data/local/tmp/clawdroid/capture.png",
    format: String = "png",
    width: Int = 1440,
    height: Int = 3200,
    fileSize: Long = 4096L,
    sha256: String = "abcd1234"
): ClawCaptureArtifact {
    return ClawCaptureArtifact(
        imagePath = imagePath,
        format = format,
        width = width,
        height = height,
        fileSize = fileSize,
        sha256 = sha256
    )
}

internal fun eventFrame(
    event: String,
    timestamp: Long = 1_720_000_000L,
    data: Map<String, Any?> = emptyMap()
): ClawRuntimeEventFrame {
    return ClawRuntimeEventFrame(event = event, timestamp = timestamp, data = data)
}

internal fun eventSubscriptionStarted(
    subscribed: List<String> = listOf("daemon_status_changed"),
    streamMode: String = "continuous",
    pollIntervalMs: Long = 2000L
): ClawRuntimeEventSubscriptionStarted {
    return ClawRuntimeEventSubscriptionStarted(
        subscribed = subscribed,
        streamMode = streamMode,
        pollIntervalMs = pollIntervalMs
    )
}

internal fun createRuntimeClientMock(): ClawRuntimeClient {
    return mockk(relaxed = true) {
        every { socketDisplayName() } returns "clawdroid_secure_ipc"
        every { packageDisplayName() } returns "com.clawdroid.app.debug"
        every { signatureDigestDisplay() } returns "sha256:test"
        every { stopEventSubscription() } just runs
    }
}

internal fun createToolExecutorMock(): ClawToolExecutor {
    return mockk(relaxed = true)
}

internal fun createAppContextMock(): Context {
    return mockk(relaxed = true)
}

internal class FakeOverviewEnvironmentGateway(
    initialStartupState: StartupPermissionState = StartupPermissionState(),
    initialProbeStatus: LocalEnvironmentStatus = overviewEnvironmentStatus()
) : OverviewEnvironmentGateway {
    val calls = mutableListOf<String>()
    var startupState: StartupPermissionState = initialStartupState
    var requestRootAccessResult: RootActionResult = rootActionResult(success = false, output = "root-not-configured")
    var grantNotificationResult: RootActionResult = rootActionResult(success = true, output = "grant-notification")
    var grantWriteSettingsResult: RootActionResult = rootActionResult(success = true, output = "grant-write-settings")
    var grantAllFilesResult: RootActionResult = rootActionResult(success = true, output = "grant-all-files")
    var grantAccessibilityResult: RootActionResult = rootActionResult(success = true, output = "grant-accessibility")
    var grantAutomationResult: RootActionResult = rootActionResult(success = true, output = "grant-automation")
    var chmodResult: RootActionResult = rootActionResult(success = true, output = "chmod")
    var chownResult: RootActionResult = rootActionResult(success = true, output = "chown")
    var defaultProbeStatus: LocalEnvironmentStatus = initialProbeStatus
    private val queuedProbeStatuses = ArrayDeque<LocalEnvironmentStatus>()

    fun enqueueProbeStatus(status: LocalEnvironmentStatus) {
        queuedProbeStatuses.addLast(status)
    }

    override fun loadStartupPermissionState(): StartupPermissionState {
        calls += "loadStartupPermissionState"
        return startupState
    }

    override fun markRootPromptResult(granted: Boolean) {
        calls += "markRootPromptResult:$granted"
        startupState = startupState.copy(
            rootPromptRequestedOnce = true,
            rootGrantedEver = startupState.rootGrantedEver || granted
        )
    }

    override fun rememberNotificationGrant() {
        calls += "rememberNotificationGrant"
        startupState = startupState.copy(notificationGrantRemembered = true)
    }

    override fun rememberWriteSettingsGrant() {
        calls += "rememberWriteSettingsGrant"
        startupState = startupState.copy(writeSettingsGrantRemembered = true)
    }

    override fun rememberAllFilesGrant() {
        calls += "rememberAllFilesGrant"
        startupState = startupState.copy(allFilesGrantRemembered = true)
    }

    override fun rememberAccessibilityGrant() {
        calls += "rememberAccessibilityGrant"
        startupState = startupState.copy(accessibilityGrantRemembered = true)
    }

    override fun rememberAutomationGrant() {
        calls += "rememberAutomationGrant"
        startupState = startupState.copy(
            automationGrantRemembered = true,
            notificationGrantRemembered = true,
            writeSettingsGrantRemembered = true,
            allFilesGrantRemembered = true,
            accessibilityGrantRemembered = true
        )
    }

    override fun rememberGrantedPermissions(status: LocalEnvironmentStatus) {
        calls += "rememberGrantedPermissions"
        defaultProbeStatus = status
        startupState = startupState.copy(
            rootGrantedEver = startupState.rootGrantedEver || (status.rootGranted == true),
            notificationGrantRemembered = startupState.notificationGrantRemembered || status.notificationPermissionGranted,
            writeSettingsGrantRemembered = startupState.writeSettingsGrantRemembered || status.writeSettingsGranted,
            allFilesGrantRemembered = startupState.allFilesGrantRemembered || status.allFilesAccessGranted,
            accessibilityGrantRemembered = startupState.accessibilityGrantRemembered || status.accessibilityEnabled
        )
    }

    override suspend fun probeLocalEnvironment(includeRootCheck: Boolean): LocalEnvironmentStatus {
        calls += "probeLocalEnvironment:$includeRootCheck"
        return queuedProbeStatuses.removeFirstOrNull() ?: defaultProbeStatus
    }

    override suspend fun requestRootAccess(): RootActionResult {
        calls += "requestRootAccess"
        return requestRootAccessResult
    }

    override suspend fun grantNotificationViaRoot(): RootActionResult {
        calls += "grantNotificationViaRoot"
        return grantNotificationResult
    }

    override suspend fun grantWriteSettingsViaRoot(): RootActionResult {
        calls += "grantWriteSettingsViaRoot"
        return grantWriteSettingsResult
    }

    override suspend fun grantAllFilesAccessViaRoot(): RootActionResult {
        calls += "grantAllFilesAccessViaRoot"
        return grantAllFilesResult
    }

    override suspend fun enableAccessibilityViaRoot(): RootActionResult {
        calls += "enableAccessibilityViaRoot"
        return grantAccessibilityResult
    }

    override suspend fun grantAutomationPermissionsViaRoot(): RootActionResult {
        calls += "grantAutomationPermissionsViaRoot"
        return grantAutomationResult
    }

    override suspend fun chmodPathViaRoot(path: String, mode: String): RootActionResult {
        calls += "chmodPathViaRoot:$path:$mode"
        return chmodResult
    }

    override suspend fun chownPathViaRoot(path: String, ownerSpec: String): RootActionResult {
        calls += "chownPathViaRoot:$path:$ownerSpec"
        return chownResult
    }
}

internal class FakeOverviewMetricsSampler(
    private val metricsSequence: MutableList<DashboardRuntimeMetrics> = mutableListOf(
        DashboardRuntimeMetrics(sampledAtEpochMs = 1L, note = "sample-1")
    )
) : OverviewMetricsSampler {
    val requestedRootFlags = mutableListOf<Boolean>()
    var sampleCount: Int = 0

    override suspend fun sample(rootAvailable: Boolean): DashboardRuntimeMetrics {
        requestedRootFlags += rootAvailable
        sampleCount += 1
        return if (metricsSequence.isEmpty()) {
            DashboardRuntimeMetrics(sampledAtEpochMs = sampleCount.toLong(), note = "sample-$sampleCount")
        } else {
            metricsSequence.removeAt(0)
        }
    }
}
