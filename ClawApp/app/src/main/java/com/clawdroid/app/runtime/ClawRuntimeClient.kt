package com.clawdroid.app.runtime

import android.content.Context
import com.clawdroid.app.ipc.ClawRuntimeCapabilities as IpcClawRuntimeCapabilities
import com.clawdroid.app.ipc.CaptureScreenResult
import com.clawdroid.app.ipc.ClawRuntimeConnectionState as IpcClawRuntimeConnectionState
import com.clawdroid.app.ipc.ClawRuntimeEventFrame as IpcClawRuntimeEventFrame
import com.clawdroid.app.ipc.ClawRuntimeEventSubscriptionStarted as IpcClawRuntimeEventSubscriptionStarted
import com.clawdroid.app.ipc.ClawRuntimeHandshakeResult as IpcClawRuntimeHandshakeResult
import com.clawdroid.app.ipc.ClawRuntimeHealthResult as IpcClawRuntimeHealthResult
import com.clawdroid.app.ipc.ClawRuntimeIpcClient
import com.clawdroid.app.ipc.ClawRuntimeLastErrorResult as IpcClawRuntimeLastErrorResult
import com.clawdroid.app.ipc.ClawRuntimePingResult as IpcClawRuntimePingResult
import com.clawdroid.app.ipc.ClawRuntimeSessionProbeResult as IpcClawRuntimeSessionProbeResult
import com.clawdroid.app.ipc.ClawRuntimeSubscribeEventsResult as IpcClawRuntimeSubscribeEventsResult
import com.clawdroid.app.ipc.ClawRuntimeVersionResult as IpcClawRuntimeVersionResult
import com.clawdroid.app.ipc.ExecShellLimitedResult
import com.clawdroid.app.ipc.InjectKeyeventResult
import com.clawdroid.app.ipc.InjectSwipeResult
import com.clawdroid.app.ipc.InjectTapResult
import com.clawdroid.app.ipc.ReadFileLimitedResult
typealias ClawRuntimeConnectionState = IpcClawRuntimeConnectionState
typealias ClawRuntimeEventFrame = IpcClawRuntimeEventFrame
typealias ClawRuntimePingResult = IpcClawRuntimePingResult
typealias ClawRuntimeVersionResult = IpcClawRuntimeVersionResult
typealias ClawRuntimeHealthResult = IpcClawRuntimeHealthResult
typealias ClawRuntimeLastErrorResult = IpcClawRuntimeLastErrorResult
typealias ClawRuntimeCapabilities = IpcClawRuntimeCapabilities
typealias ClawRuntimeSessionProbeResult = IpcClawRuntimeSessionProbeResult
typealias ClawRuntimeHandshakeResult = IpcClawRuntimeHandshakeResult
typealias ClawRuntimeCaptureScreenResult = CaptureScreenResult
typealias ClawRuntimeReadFileResult = ReadFileLimitedResult
typealias ClawRuntimeInjectTapResult = InjectTapResult
typealias ClawRuntimeInjectSwipeResult = InjectSwipeResult
typealias ClawRuntimeInjectKeyeventResult = InjectKeyeventResult
typealias ClawRuntimeExecShellResult = ExecShellLimitedResult
typealias ClawRuntimeSubscribeEventsResult = IpcClawRuntimeSubscribeEventsResult
typealias ClawRuntimeEventSubscriptionStarted = IpcClawRuntimeEventSubscriptionStarted
typealias ClawRuntimeStatusResult = com.clawdroid.app.ipc.ClawRuntimeStatusResult
typealias ClawRuntimeModuleStatus = com.clawdroid.app.ipc.ClawRuntimeModuleStatus
typealias ClawRuntimeTaskSnapshot = com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot
typealias ClawRuntimeTaskSubmitResult = com.clawdroid.app.ipc.ClawRuntimeTaskSubmitResult
typealias ClawRuntimeTaskListResult = com.clawdroid.app.ipc.ClawRuntimeTaskListResult
typealias ClawRuntimeTaskCancelResult = com.clawdroid.app.ipc.ClawRuntimeTaskCancelResult

class ClawRuntimeClient(
    socketName: String = "clawdroid_secure_ipc",
    packageName: String,
    sharedSecret: String,
    signatureDigest: String
) {
    private val delegate = ClawRuntimeIpcClient(
        socketName = socketName,
        packageName = packageName,
        sharedSecret = sharedSecret,
        signatureDigest = signatureDigest
    )

    fun packageDisplayName(): String = delegate.packageDisplayName()

    fun signatureDigestDisplay(): String = delegate.signatureDigestDisplay()

    fun socketDisplayName(): String = delegate.socketDisplayName()

    suspend fun ping(): Result<ClawRuntimePingResult> = delegate.ping()

    suspend fun reportXposedFocus(focusJson: String) = delegate.reportXposedFocus(focusJson)

    suspend fun reportXposedView(viewJson: String) = delegate.reportXposedView(viewJson)

    suspend fun getVersion(): Result<ClawRuntimeVersionResult> = delegate.getVersion()

    suspend fun getHealth(): Result<ClawRuntimeHealthResult> = delegate.getHealth()

    suspend fun getLastError(): Result<ClawRuntimeLastErrorResult> = delegate.getLastError()

    suspend fun getCapabilities(): Result<ClawRuntimeCapabilities> = delegate.getCapabilities()

    suspend fun getRuntimeStatus(): Result<ClawRuntimeStatusResult> = delegate.getRuntimeStatus()

    suspend fun taskSubmit(task: Map<String, Any?>): Result<ClawRuntimeTaskSubmitResult> =
        delegate.taskSubmit(task)

    suspend fun taskGet(taskId: String): Result<ClawRuntimeTaskSnapshot> = delegate.taskGet(taskId)

    suspend fun taskList(): Result<ClawRuntimeTaskListResult> = delegate.taskList()

    suspend fun taskCancel(taskId: String): Result<ClawRuntimeTaskCancelResult> =
        delegate.taskCancel(taskId)

    suspend fun captureScreen(): Result<ClawRuntimeCaptureScreenResult> = delegate.captureScreen()

    suspend fun readFileLimited(
        path: String,
        offset: Long = 0,
		maxBytes: Int = ClawRuntimeIpcClient.SAFE_FILE_CHUNK_BYTES
    ): Result<ClawRuntimeReadFileResult> = delegate.readFileLimited(path, offset, maxBytes)

    suspend fun readFileFullyLimited(
        path: String,
        chunkSize: Int = ClawRuntimeIpcClient.SAFE_FILE_CHUNK_BYTES,
        maxTotalBytes: Int = 8388608
    ): Result<ByteArray> = delegate.readFileFullyLimited(path, chunkSize, maxTotalBytes)

    suspend fun writeFileLimited(
        path: String,
        content: String,
        append: Boolean = false
    ) = delegate.writeFileLimited(path, content, append)

    suspend fun statFileLimited(
        path: String,
        computeHash: Boolean = true
    ) = delegate.statFileLimited(path, computeHash)

    suspend fun injectTap(
        x: Int,
        y: Int,
        displayId: Int = 0
    ): Result<ClawRuntimeInjectTapResult> = delegate.injectTap(x, y, displayId)

    suspend fun injectKeyevent(
        key: String? = null,
        keyCode: Int? = null,
        displayId: Int = 0
    ): Result<ClawRuntimeInjectKeyeventResult> = delegate.injectKeyevent(key, keyCode, displayId)

    suspend fun injectSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int = 350,
        displayId: Int = 0
    ): Result<ClawRuntimeInjectSwipeResult> = delegate.injectSwipe(x1, y1, x2, y2, durationMs, displayId)

    suspend fun execShellLimited(
        command: String,
        timeoutMs: Int = 3000
    ): Result<ClawRuntimeExecShellResult> = delegate.execShellLimited(command, timeoutMs)

    suspend fun startEventSubscription(
        events: List<String>,
        onStarted: (ClawRuntimeEventSubscriptionStarted) -> Unit,
        onEvent: (ClawRuntimeEventFrame) -> Unit,
        onClosed: (String) -> Unit
    ) {
        delegate.startEventSubscription(events, onStarted, onEvent, onClosed)
    }

    fun stopEventSubscription() = delegate.stopEventSubscription()

    suspend fun probeSession(): Result<ClawRuntimeSessionProbeResult> = delegate.probeSession()

    companion object {
        fun resolveSignatureDigest(context: Context, packageName: String): String {
            return ClawRuntimeIpcClient.resolveSignatureDigest(context, packageName)
        }
    }
}
