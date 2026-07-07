package com.clawdroid.app.ipc

import android.content.Context
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class ClawRuntimeRequest(
    val version: Int = 1,
    val requestId: String,
    val timestamp: Long,
    val action: String,
    val capability: String,
    val args: Map<String, Any?> = emptyMap()
)

data class ClawRuntimeResponse(
    val requestId: String,
    val ok: Boolean,
    val code: Int,
    val message: String,
    val data: Map<String, Any?> = emptyMap()
)

data class ClawRuntimePingResult(
    val daemonStatus: String,
    val daemonVersion: String,
    val latencyMs: Int
)

data class ClawRuntimeVersionResult(
    val daemonStatus: String,
    val daemonVersion: String,
    val protocolVersion: Int,
    val socketName: String,
    val logLevel: String
)

data class ClawRuntimeLastErrorResult(
    val lastError: String,
    val lastErrorAt: Long,
    val lastRateLimit: String,
    val lastRateLimitAt: Long,
    val rateLimitHits: Int,
    val rateLimitPerMinute: Int,
    val readonlyWhitelist: List<String>
)

data class ClawRuntimeHealthResult(
    val daemonStatus: String,
    val daemonVersion: String,
    val protocolVersion: Int,
    val uptimeSeconds: Long,
    val root: Boolean,
    val accessibility: Boolean,
    val lsposed: Boolean,
    val lsposedRuntimeLoaded: Boolean,
    val capabilities: List<String>,
    val auditDir: String,
    val requestTimeoutMs: Int,
    val rateLimitPerMinute: Int,
    val readonlyWhitelist: List<String>,
    val lastError: String,
    val lastErrorAt: Long,
    val rateLimitHits: Int
)

data class ClawRuntimeCapabilities(
    val root: Boolean,
    val accessibility: Boolean,
    val lsposed: Boolean,
    val lsposedRuntimeLoaded: Boolean,
    val lsposedRuntimeProcess: String,
    val lsposedRuntimeLoadedAt: Long,
    val screenshotEnabled: Boolean,
    val fileBridgeEnabled: Boolean,
    val capabilities: List<String>,
    val serverTime: Long,
    val sessionState: ClawRuntimeConnectionState = ClawRuntimeConnectionState.Disconnected,
    val stateTrace: List<ClawRuntimeConnectionState> = emptyList(),
    val degradedReason: String = ""
)

data class CaptureScreenResult(
    val displayId: Int,
    val format: String,
    val width: Int,
    val height: Int,
    val imagePath: String,
    val fileSize: Long,
    val sha256: String,
    val transport: String,
    val capturedAt: Long
)

data class ReadFileLimitedResult(
    val path: String,
    val offset: Long,
    val readBytes: Int,
    val totalSize: Long,
    val eof: Boolean,
    val contentBase64: String
) {
    fun decodeUtf8OrPlaceholder(): String {
        return runCatching {
            String(Base64.getDecoder().decode(contentBase64), Charsets.UTF_8)
        }.getOrDefault("<binary content>")
    }
}

data class InjectTapResult(
    val accepted: Boolean,
    val displayId: Int,
    val x: Int,
    val y: Int
)

data class InjectSwipeResult(
    val accepted: Boolean,
    val displayId: Int,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val durationMs: Int
)

data class ExecShellLimitedResult(
    val command: String,
    val templateName: String,
    val timeoutMs: Int,
    val durationMs: Int,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val timedOut: Boolean,
    val allowedCommands: List<String>
)

data class ClawRuntimeEventFrame(
    val event: String,
    val timestamp: Long,
    val data: Map<String, Any?> = emptyMap()
)

data class ClawRuntimeSubscribeEventsResult(
    val subscribed: List<String>,
    val streamMode: String,
    val frames: List<ClawRuntimeEventFrame>,
    val closedReason: String
)

data class ClawRuntimeEventSubscriptionStarted(
    val subscribed: List<String>,
    val streamMode: String,
    val pollIntervalMs: Long
)

enum class ClawRuntimeConnectionState {
    Disconnected,
    SocketConnected,
    PeerVerified,
    ChallengeIssued,
    Authenticated,
    CapabilitySynced,
    Ready,
    Degraded,
    Closed
}

data class ClawRuntimeSessionProbeResult(
    val sessionId: String,
    val authMode: String,
    val finalState: ClawRuntimeConnectionState,
    val stateTrace: List<ClawRuntimeConnectionState>,
    val ping: ClawRuntimePingResult,
    val capabilities: ClawRuntimeCapabilities
)

data class ClawRuntimeHandshakeResult(
    val sessionId: String,
    val authMode: String,
    val sessionState: ClawRuntimeConnectionState,
    val stateTrace: List<ClawRuntimeConnectionState>
)

class ClawRuntimeIpcClient(
    private val socketName: String = "clawdroid_secure_ipc",
    private val packageName: String,
    private val sharedSecret: String,
    private val signatureDigest: String
) {
    private val maxFrameBytes = 262144

    @Volatile
    private var activeEventSocket: LocalSocket? = null

    private val eventSocketLock = Any()

    fun packageDisplayName(): String = packageName

    fun signatureDigestDisplay(): String = signatureDigest

    fun buildPingRequest(requestId: String, timestamp: Long): ClawRuntimeRequest {
        return ClawRuntimeRequest(
            requestId = requestId,
            timestamp = timestamp,
            action = "ping",
            capability = "system.ping"
        )
    }

    suspend fun ping(): Result<ClawRuntimePingResult> = runCatching {
        val response = send(buildPingRequest(newRequestId(), nowSeconds()))
        ensureSuccess(response)

        ClawRuntimePingResult(
            daemonStatus = response.data["daemon_status"]?.toString().orEmpty(),
            daemonVersion = response.data["version"]?.toString().orEmpty(),
            latencyMs = (response.data["latency_ms"] as? Number)?.toInt() ?: -1
        )
    }

    suspend fun getVersion(): Result<ClawRuntimeVersionResult> = runCatching {
        val response = send(buildPingRequest(newRequestId(), nowSeconds()))
        ensureSuccess(response)

        ClawRuntimeVersionResult(
            daemonStatus = response.data["daemon_status"]?.toString().orEmpty(),
            daemonVersion = response.data["version"]?.toString().orEmpty(),
            protocolVersion = (response.data["protocol_version"] as? Number)?.toInt() ?: 0,
            socketName = response.data["socket_name"]?.toString().orEmpty(),
            logLevel = response.data["log_level"]?.toString().orEmpty()
        )
    }

    suspend fun getHealth(): Result<ClawRuntimeHealthResult> = runCatching {
        val response = requestCapabilitiesResponse()
        ensureSuccess(response)

        ClawRuntimeHealthResult(
            daemonStatus = response.data["daemon_status"]?.toString().orEmpty(),
            daemonVersion = response.data["version"]?.toString().orEmpty(),
            protocolVersion = (response.data["protocol_version"] as? Number)?.toInt() ?: 0,
            uptimeSeconds = (response.data["uptime_seconds"] as? Number)?.toLong() ?: 0L,
            root = response.data["root"] as? Boolean ?: false,
            accessibility = response.data["accessibility"] as? Boolean ?: false,
            lsposed = response.data["lsposed"] as? Boolean ?: false,
            lsposedRuntimeLoaded = response.data["lsposed_runtime_loaded"] as? Boolean ?: false,
            capabilities = response.data.stringList("capabilities"),
            auditDir = response.data["audit_dir"]?.toString().orEmpty(),
            requestTimeoutMs = (response.data["request_timeout_ms"] as? Number)?.toInt() ?: 0,
            rateLimitPerMinute = (response.data["rate_limit_per_minute"] as? Number)?.toInt() ?: 0,
            readonlyWhitelist = response.data.stringList("readonly_whitelist"),
            lastError = response.data["last_error"]?.toString().orEmpty(),
            lastErrorAt = (response.data["last_error_at"] as? Number)?.toLong() ?: 0L,
            rateLimitHits = (response.data["rate_limit_hits"] as? Number)?.toInt() ?: 0
        )
    }

    suspend fun getLastError(): Result<ClawRuntimeLastErrorResult> = runCatching {
        val response = requestCapabilitiesResponse()
        ensureSuccess(response)

        ClawRuntimeLastErrorResult(
            lastError = response.data["last_error"]?.toString().orEmpty(),
            lastErrorAt = (response.data["last_error_at"] as? Number)?.toLong() ?: 0L,
            lastRateLimit = response.data["last_rate_limit"]?.toString().orEmpty(),
            lastRateLimitAt = (response.data["last_rate_limit_at"] as? Number)?.toLong() ?: 0L,
            rateLimitHits = (response.data["rate_limit_hits"] as? Number)?.toInt() ?: 0,
            rateLimitPerMinute = (response.data["rate_limit_per_minute"] as? Number)?.toInt() ?: 0,
            readonlyWhitelist = response.data.stringList("readonly_whitelist")
        )
    }

    suspend fun getCapabilities(): Result<ClawRuntimeCapabilities> = runCatching {
        val response = requestCapabilitiesResponse()
        ensureSuccess(response)

        ClawRuntimeCapabilities(
            root = response.data["root"] as? Boolean ?: false,
            accessibility = response.data["accessibility"] as? Boolean ?: false,
            lsposed = response.data["lsposed"] as? Boolean ?: false,
            lsposedRuntimeLoaded = response.data["lsposed_runtime_loaded"] as? Boolean ?: false,
            lsposedRuntimeProcess = response.data["lsposed_runtime_process"]?.toString().orEmpty(),
            lsposedRuntimeLoadedAt = (response.data["lsposed_runtime_loaded_at"] as? Number)?.toLong() ?: 0L,
            screenshotEnabled = response.data["screenshot_enabled"] as? Boolean ?: false,
            fileBridgeEnabled = response.data["file_bridge_enabled"] as? Boolean ?: false,
            capabilities = response.data.stringList("capabilities"),
            serverTime = (response.data["server_time"] as? Number)?.toLong() ?: 0L,
            sessionState = response.data.connectionState("session_state"),
            stateTrace = response.data.connectionTrace(),
            degradedReason = response.data["degraded_reason"]?.toString().orEmpty()
        )
    }

    suspend fun captureScreen(
        displayId: Int = 0,
        format: String = "png",
        quality: Int = 90,
        maxWidth: Int = 1440,
        maxHeight: Int = 3200
    ): Result<CaptureScreenResult> = runCatching {
        val response = send(
            ClawRuntimeRequest(
                requestId = newRequestId(),
                timestamp = nowSeconds(),
                action = "capture_screen",
                capability = "screen.capture",
                args = mapOf(
                    "display_id" to displayId,
                    "format" to format,
                    "quality" to quality,
                    "max_width" to maxWidth,
                    "max_height" to maxHeight
                )
            )
        )
        ensureSuccess(response)

        CaptureScreenResult(
            displayId = (response.data["display_id"] as? Number)?.toInt() ?: displayId,
            format = response.data["format"]?.toString().orEmpty(),
            width = (response.data["width"] as? Number)?.toInt() ?: 0,
            height = (response.data["height"] as? Number)?.toInt() ?: 0,
            imagePath = response.data["image_path"]?.toString().orEmpty(),
            fileSize = (response.data["file_size"] as? Number)?.toLong() ?: 0L,
            sha256 = response.data["sha256"]?.toString().orEmpty(),
            transport = response.data["transport"]?.toString().orEmpty(),
            capturedAt = (response.data["captured_at"] as? Number)?.toLong() ?: 0L
        )
    }

    suspend fun readFileLimited(
        path: String,
        offset: Long = 0,
        maxBytes: Int = 65536
    ): Result<ReadFileLimitedResult> = runCatching {
        val response = send(
            ClawRuntimeRequest(
                requestId = newRequestId(),
                timestamp = nowSeconds(),
                action = "read_file_limited",
                capability = "file.read.limited",
                args = mapOf(
                    "path" to path,
                    "offset" to offset,
                    "max_bytes" to maxBytes
                )
            )
        )
        ensureSuccess(response)

        ReadFileLimitedResult(
            path = response.data["path"]?.toString().orEmpty(),
            offset = (response.data["offset"] as? Number)?.toLong() ?: offset,
            readBytes = (response.data["read_bytes"] as? Number)?.toInt() ?: 0,
            totalSize = (response.data["total_size"] as? Number)?.toLong() ?: 0L,
            eof = response.data["eof"] as? Boolean ?: false,
            contentBase64 = response.data["content_base64"]?.toString().orEmpty()
        )
    }

    suspend fun injectTap(
        x: Int,
        y: Int,
        displayId: Int = 0
    ): Result<InjectTapResult> = runCatching {
        val response = send(
            ClawRuntimeRequest(
                requestId = newRequestId(),
                timestamp = nowSeconds(),
                action = "inject_tap",
                capability = "input.inject",
                args = mapOf(
                    "x" to x,
                    "y" to y,
                    "display_id" to displayId
                )
            )
        )
        ensureSuccess(response)

        InjectTapResult(
            accepted = response.data["accepted"] as? Boolean ?: false,
            displayId = (response.data["display_id"] as? Number)?.toInt() ?: displayId,
            x = (response.data["x"] as? Number)?.toInt() ?: x,
            y = (response.data["y"] as? Number)?.toInt() ?: y
        )
    }

    suspend fun injectSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int = 350,
        displayId: Int = 0
    ): Result<InjectSwipeResult> = runCatching {
        val response = send(
            ClawRuntimeRequest(
                requestId = newRequestId(),
                timestamp = nowSeconds(),
                action = "inject_swipe",
                capability = "input.inject",
                args = mapOf(
                    "x1" to x1,
                    "y1" to y1,
                    "x2" to x2,
                    "y2" to y2,
                    "duration_ms" to durationMs,
                    "display_id" to displayId
                )
            )
        )
        ensureSuccess(response)

        InjectSwipeResult(
            accepted = response.data["accepted"] as? Boolean ?: false,
            displayId = (response.data["display_id"] as? Number)?.toInt() ?: displayId,
            x1 = (response.data["x1"] as? Number)?.toInt() ?: x1,
            y1 = (response.data["y1"] as? Number)?.toInt() ?: y1,
            x2 = (response.data["x2"] as? Number)?.toInt() ?: x2,
            y2 = (response.data["y2"] as? Number)?.toInt() ?: y2,
            durationMs = (response.data["duration_ms"] as? Number)?.toInt() ?: durationMs
        )
    }

    suspend fun readFileFullyLimited(
        path: String,
        chunkSize: Int = 262144,
        maxTotalBytes: Int = 8388608
    ): Result<ByteArray> = runCatching {
        require(chunkSize in 1..1048576) { "chunkSize must be between 1 and 1048576" }
        require(maxTotalBytes >= chunkSize) { "maxTotalBytes must be >= chunkSize" }

        var offset = 0L
        val chunks = mutableListOf<ByteArray>()
        var totalRead = 0
        var expectedTotalSize: Long? = null

        while (totalRead < maxTotalBytes) {
            val nextChunkSize = minOf(chunkSize, maxTotalBytes - totalRead)
            val result = readFileLimited(path = path, offset = offset, maxBytes = nextChunkSize).getOrThrow()
            if (expectedTotalSize == null && result.totalSize > 0) {
                expectedTotalSize = result.totalSize
            }
            val decoded = Base64.getDecoder().decode(result.contentBase64)
            chunks += decoded
            totalRead += decoded.size
            offset += decoded.size

            if (result.eof || decoded.isEmpty()) {
                break
            }
        }

        val totalSize = expectedTotalSize
        if (totalSize != null && totalSize > maxTotalBytes && totalRead < totalSize) {
            error("file too large for preview: total_size=$totalSize, max_total_bytes=$maxTotalBytes")
        }

        val merged = ByteArray(totalRead)
        var writeOffset = 0
        for (chunk in chunks) {
            chunk.copyInto(merged, destinationOffset = writeOffset)
            writeOffset += chunk.size
        }
        merged
    }

    suspend fun execShellLimited(
        command: String,
        timeoutMs: Int = 3000
    ): Result<ExecShellLimitedResult> = runCatching {
        val response = send(
            ClawRuntimeRequest(
                requestId = newRequestId(),
                timestamp = nowSeconds(),
                action = "exec_shell_limited",
                capability = "shell.exec.limited",
                args = mapOf(
                    "command" to command,
                    "timeout_ms" to timeoutMs
                )
            )
        )
        ensureSuccess(response)

        ExecShellLimitedResult(
            command = response.data["command"]?.toString().orEmpty().ifBlank { command },
            templateName = response.data["template_name"]?.toString().orEmpty(),
            timeoutMs = (response.data["timeout_ms"] as? Number)?.toInt() ?: timeoutMs,
            durationMs = (response.data["duration_ms"] as? Number)?.toInt() ?: 0,
            exitCode = (response.data["exit_code"] as? Number)?.toInt() ?: -1,
            stdout = response.data["stdout"]?.toString().orEmpty(),
            stderr = response.data["stderr"]?.toString().orEmpty(),
            stdoutTruncated = response.data["stdout_truncated"] as? Boolean ?: false,
            stderrTruncated = response.data["stderr_truncated"] as? Boolean ?: false,
            timedOut = response.data["timed_out"] as? Boolean ?: false,
            allowedCommands = (response.data["allowed_commands"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        )
    }

    suspend fun subscribeEvents(
        events: List<String>
    ): Result<ClawRuntimeSubscribeEventsResult> = runCatching {
        require(events.isNotEmpty()) { "events must not be empty" }

        withContext(Dispatchers.IO) {
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                val reader = socket.inputStream
                val writer = socket.outputStream
                performHandshake(reader, writer)

                val response = sendOnConnection(
                    writer = writer,
                    reader = reader,
                    request = ClawRuntimeRequest(
                        requestId = newRequestId(),
                        timestamp = nowSeconds(),
                        action = "subscribe_events",
                        capability = "event.subscribe",
                        args = mapOf("events" to events)
                    )
                )
                ensureSuccess(response)

                val frames = mutableListOf<ClawRuntimeEventFrame>()
                var closedReason = "stream_ended"
                while (true) {
                    val eventFrame = try {
                        parseEventFrame(readFramedText(reader))
                    } catch (_: EOFException) {
                        break
                    }
                    if (eventFrame.event == "subscription_closed") {
                        closedReason = eventFrame.data["reason"]?.toString().orEmpty().ifBlank { "stream_closed" }
                        break
                    }
                    frames += eventFrame
                    if (frames.size >= 64) {
                        closedReason = "client_limit_reached"
                        break
                    }
                }

                ClawRuntimeSubscribeEventsResult(
                    subscribed = (response.data["subscribed"] as? List<*>)?.mapNotNull { it?.toString() } ?: events,
                    streamMode = response.data["stream_mode"]?.toString().orEmpty(),
                    frames = frames,
                    closedReason = closedReason
                )
            } finally {
                socket.close()
            }
        }
    }

    suspend fun startEventSubscription(
        events: List<String>,
        onStarted: (ClawRuntimeEventSubscriptionStarted) -> Unit,
        onEvent: (ClawRuntimeEventFrame) -> Unit,
        onClosed: (String) -> Unit
    ) {
        require(events.isNotEmpty()) { "events must not be empty" }

        withContext(Dispatchers.IO) {
            stopEventSubscription()

            val socket = LocalSocket()
            registerActiveEventSocket(socket)
            try {
                socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                val reader = socket.inputStream
                val writer = socket.outputStream
                performHandshake(reader, writer)

                val response = sendOnConnection(
                    writer = writer,
                    reader = reader,
                    request = ClawRuntimeRequest(
                        requestId = newRequestId(),
                        timestamp = nowSeconds(),
                        action = "subscribe_events",
                        capability = "event.subscribe",
                        args = mapOf("events" to events)
                    )
                )
                ensureSuccess(response)
                withContext(Dispatchers.Main) {
                    onStarted(
                        ClawRuntimeEventSubscriptionStarted(
                            subscribed = (response.data["subscribed"] as? List<*>)?.mapNotNull { it?.toString() } ?: events,
                            streamMode = response.data["stream_mode"]?.toString().orEmpty(),
                            pollIntervalMs = (response.data["poll_interval_ms"] as? Number)?.toLong() ?: 0L
                        )
                    )
                }

                while (true) {
                    ensureActive()
                    val eventFrame = try {
                        parseEventFrame(readFramedText(reader))
                    } catch (_: EOFException) {
                        break
                    }
                    if (eventFrame.event == "subscription_closed") {
                        val reason = eventFrame.data["reason"]?.toString().orEmpty().ifBlank { "stream_closed" }
                        withContext(Dispatchers.Main) {
                            onClosed(reason)
                        }
                        return@withContext
                    }
                    withContext(Dispatchers.Main) {
                        onEvent(eventFrame)
                    }
                }
                withContext(Dispatchers.Main) {
                    onClosed("socket_closed")
                }
            } finally {
                unregisterActiveEventSocket(socket)
                runCatching { socket.close() }
            }
        }
    }

    fun stopEventSubscription() {
        synchronized(eventSocketLock) {
            val socket = activeEventSocket ?: return
            activeEventSocket = null
            runCatching { socket.close() }
        }
    }

    suspend fun probeSession(): Result<ClawRuntimeSessionProbeResult> = runCatching {
        withContext(Dispatchers.IO) {
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                val reader = socket.inputStream
                val writer = socket.outputStream
                val handshake = performHandshake(reader, writer)

                val pingResponse = sendOnConnection(
                    writer = writer,
                    reader = reader,
                    request = buildPingRequest(newRequestId(), nowSeconds())
                )
                ensureSuccess(pingResponse)

                val capabilitiesResponse = sendOnConnection(
                    writer = writer,
                    reader = reader,
                    request = ClawRuntimeRequest(
                        requestId = newRequestId(),
                        timestamp = nowSeconds(),
                        action = "get_capabilities",
                        capability = "system.inspect"
                    )
                )
                ensureSuccess(capabilitiesResponse)

                val pingResult = ClawRuntimePingResult(
                    daemonStatus = pingResponse.data["daemon_status"]?.toString().orEmpty(),
                    daemonVersion = pingResponse.data["version"]?.toString().orEmpty(),
                    latencyMs = (pingResponse.data["latency_ms"] as? Number)?.toInt() ?: -1
                )
                val capabilitiesResult = ClawRuntimeCapabilities(
                    root = capabilitiesResponse.data["root"] as? Boolean ?: false,
                    accessibility = capabilitiesResponse.data["accessibility"] as? Boolean ?: false,
                    lsposed = capabilitiesResponse.data["lsposed"] as? Boolean ?: false,
                    lsposedRuntimeLoaded = capabilitiesResponse.data["lsposed_runtime_loaded"] as? Boolean ?: false,
                    lsposedRuntimeProcess = capabilitiesResponse.data["lsposed_runtime_process"]?.toString().orEmpty(),
                    lsposedRuntimeLoadedAt = (capabilitiesResponse.data["lsposed_runtime_loaded_at"] as? Number)?.toLong() ?: 0L,
                    screenshotEnabled = capabilitiesResponse.data["screenshot_enabled"] as? Boolean ?: false,
                    fileBridgeEnabled = capabilitiesResponse.data["file_bridge_enabled"] as? Boolean ?: false,
                    capabilities = capabilitiesResponse.data.stringList("capabilities"),
                    serverTime = (capabilitiesResponse.data["server_time"] as? Number)?.toLong() ?: 0L,
                    sessionState = capabilitiesResponse.data.connectionState("session_state"),
                    stateTrace = capabilitiesResponse.data.connectionTrace(),
                    degradedReason = capabilitiesResponse.data["degraded_reason"]?.toString().orEmpty()
                )

                ClawRuntimeSessionProbeResult(
                    sessionId = capabilitiesResponse.data["session_id"]?.toString().orEmpty().ifBlank { handshake.sessionId },
                    authMode = capabilitiesResponse.data["auth_mode"]?.toString().orEmpty().ifBlank { handshake.authMode },
                    finalState = capabilitiesResult.sessionState,
                    stateTrace = capabilitiesResult.stateTrace,
                    ping = pingResult,
                    capabilities = capabilitiesResult
                )
            } finally {
                socket.close()
            }
        }
    }

    fun socketDisplayName(): String = socketName

    private fun registerActiveEventSocket(socket: LocalSocket) {
        synchronized(eventSocketLock) {
            activeEventSocket = socket
        }
    }

    private fun unregisterActiveEventSocket(socket: LocalSocket) {
        synchronized(eventSocketLock) {
            if (activeEventSocket === socket) {
                activeEventSocket = null
            }
        }
    }

    private suspend fun send(request: ClawRuntimeRequest): ClawRuntimeResponse = withContext(Dispatchers.IO) {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            val reader = socket.inputStream
            val writer = socket.outputStream
            performHandshake(reader, writer)
            sendOnConnection(writer, reader, request)
        } finally {
            socket.close()
        }
    }

    private suspend fun requestCapabilitiesResponse(): ClawRuntimeResponse {
        return send(
            ClawRuntimeRequest(
                requestId = newRequestId(),
                timestamp = nowSeconds(),
                action = "get_capabilities",
                capability = "system.inspect"
            )
        )
    }

    private fun sendOnConnection(
        writer: OutputStream,
        reader: InputStream,
        request: ClawRuntimeRequest
    ): ClawRuntimeResponse {
        writeFramedText(writer, request.toJson().toString())
        val responseText = readFramedText(reader)
        return parseResponse(responseText)
    }

    private fun performHandshake(
        reader: InputStream,
        writer: OutputStream
    ): ClawRuntimeHandshakeResult {
        val challengeFrame = parseControlFrame(readFramedText(reader))
        require(challengeFrame.type == "challenge") {
            "unexpected handshake frame: ${challengeFrame.type}"
        }

        val clientTimestamp = nowSeconds()
        val authFrame = JSONObject().apply {
            put("type", "auth")
            put("session_id", challengeFrame.sessionId)
            put("package_name", packageName)
            put("signature_digest", signatureDigest)
            put("client_timestamp", clientTimestamp)
            put(
                "response_digest",
                authDigest(
                    secret = sharedSecret,
                    nonce = challengeFrame.nonce,
                    packageName = packageName,
                    signatureDigest = signatureDigest,
                    clientTimestamp = clientTimestamp
                )
            )
        }
        writeFramedText(writer, authFrame.toString())

        val authResultFrame = parseControlFrame(readFramedText(reader))
        require(authResultFrame.type == "auth_result") {
            "unexpected auth result frame: ${authResultFrame.type}"
        }
        if (!authResultFrame.ok) {
            throw IllegalStateException(
                "ClawRuntime auth failed: ${authResultFrame.code} ${authResultFrame.message}"
            )
        }

        return ClawRuntimeHandshakeResult(
            sessionId = authResultFrame.sessionId.ifBlank { challengeFrame.sessionId },
            authMode = authResultFrame.authMode.ifBlank { challengeFrame.authMode },
            sessionState = parseConnectionState(authResultFrame.sessionState),
            stateTrace = authResultFrame.stateTrace.map(::parseConnectionState)
        )
    }

    private fun ensureSuccess(response: ClawRuntimeResponse) {
        if (!response.ok) {
            throw IllegalStateException("ClawRuntime request failed: ${response.code} ${response.message}")
        }
    }

    private fun parseResponse(raw: String): ClawRuntimeResponse {
        val payload = JSONObject(raw)
        val data = payload.optJSONObject("data") ?: JSONObject()
        return ClawRuntimeResponse(
            requestId = payload.optString("request_id"),
            ok = payload.optBoolean("ok"),
            code = payload.optInt("code"),
            message = payload.optString("message"),
            data = data.toMap()
        )
    }

    private fun parseEventFrame(raw: String): ClawRuntimeEventFrame {
        val payload = JSONObject(raw)
        val data = payload.optJSONObject("data") ?: JSONObject()
        return ClawRuntimeEventFrame(
            event = payload.optString("event"),
            timestamp = payload.optLong("timestamp"),
            data = data.toMap()
        )
    }

    private fun ClawRuntimeRequest.toJson(): JSONObject {
        return JSONObject().apply {
            put("version", version)
            put("request_id", requestId)
            put("timestamp", timestamp)
            put("action", action)
            put("capability", capability)
            put("args", JSONObject(args))
        }
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            result[key] = normalizeValue(opt(key))
        }
        return result
    }

    private fun normalizeValue(value: Any?): Any? {
        return when (value) {
            is JSONObject -> value.toMap()
            is JSONArray -> buildList {
                for (index in 0 until value.length()) {
                    add(normalizeValue(value.opt(index)))
                }
            }
            JSONObject.NULL -> null
            else -> value
        }
    }

    private fun Map<String, Any?>.connectionState(key: String): ClawRuntimeConnectionState {
        val raw = this[key]?.toString().orEmpty()
        return ClawRuntimeConnectionState.entries.firstOrNull { it.name == raw } ?: ClawRuntimeConnectionState.Disconnected
    }

    private fun Map<String, Any?>.connectionTrace(): List<ClawRuntimeConnectionState> {
        val raw = this["state_trace"] as? List<*> ?: return emptyList()
        return raw.mapNotNull { item ->
            ClawRuntimeConnectionState.entries.firstOrNull { it.name == item?.toString().orEmpty() }
        }
    }

    private fun Map<String, Any?>.stringList(key: String): List<String> {
        val raw = this[key] as? List<*> ?: return emptyList()
        return raw.mapNotNull { it?.toString() }
    }

    private fun parseControlFrame(raw: String): ControlFrame {
        val payload = JSONObject(raw)
        return ControlFrame(
            type = payload.optString("type"),
            sessionId = payload.optString("session_id"),
            nonce = payload.optString("nonce"),
            authMode = payload.optString("auth_mode"),
            ok = payload.optBoolean("ok"),
            code = payload.optInt("code"),
            message = payload.optString("message"),
            sessionState = payload.optString("session_state"),
            stateTrace = payload.optJSONArray("state_trace").toStringList()
        )
    }

    private fun parseConnectionState(raw: String): ClawRuntimeConnectionState {
        return ClawRuntimeConnectionState.entries.firstOrNull { it.name == raw } ?: ClawRuntimeConnectionState.Disconnected
    }

    private fun authDigest(
        secret: String,
        nonce: String,
        packageName: String,
        signatureDigest: String,
        clientTimestamp: Long
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(key)
        val payload = "$nonce|$packageName|${signatureDigest.lowercase()}|$clientTimestamp"
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                add(optString(index))
            }
        }
    }

    private fun writeFramedText(stream: OutputStream, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.isNotEmpty()) { "frame payload must not be empty" }
        require(payload.size <= maxFrameBytes) { "frame payload exceeds limit: ${payload.size}" }
        val header = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size)
            .array()
        stream.write(header)
        stream.write(payload)
        stream.flush()
    }

    private fun readFramedText(stream: InputStream): String {
        val header = readFully(stream, 4)
        val size = ByteBuffer.wrap(header)
            .order(ByteOrder.BIG_ENDIAN)
            .int
        require(size in 1..maxFrameBytes) { "invalid frame size: $size" }
        val payload = readFully(stream, size)
        return payload.toString(Charsets.UTF_8)
    }

    private fun readFully(stream: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = stream.read(buffer, offset, size - offset)
            if (read < 0) {
                throw EOFException("ClawRuntime socket closed while reading frame")
            }
            offset += read
        }
        return buffer
    }

    private fun newRequestId(): String = UUID.randomUUID().toString()

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    companion object {
        fun resolveSignatureDigest(context: Context, packageName: String): String {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNATURES
                )
            }
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            } ?: error("no APK signatures available")
            val firstSignature = signatures.firstOrNull()
                ?: error("empty APK signature list")
            val digest = MessageDigest.getInstance("SHA-256").digest(firstSignature.toByteArray())
            return "sha256:" + digest.joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}

private data class ControlFrame(
    val type: String,
    val sessionId: String,
    val nonce: String,
    val authMode: String,
    val ok: Boolean,
    val code: Int,
    val message: String,
    val sessionState: String,
    val stateTrace: List<String>
)
