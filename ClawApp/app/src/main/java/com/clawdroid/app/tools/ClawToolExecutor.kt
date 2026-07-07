package com.clawdroid.app.tools

import com.clawdroid.app.automation.AutomationRuntimeStore
import com.clawdroid.app.runtime.ClawRuntimeClient
import com.clawdroid.app.runtime.ClawRuntimeConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClawToolExecutor(
    private val runtimeClient: ClawRuntimeClient
) {
    fun confirmPage(
        expectedPackage: String,
        expectedText: String,
        expectedViewId: String
    ): ClawToolCallResult {
        val result = AutomationRuntimeStore.confirmLatestPage(
            expectedPackage = expectedPackage.trim(),
            expectedText = expectedText.trim(),
            expectedViewId = expectedViewId.trim()
        )
        return ClawToolCallResult(
            success = result.state.isSucceeded(),
            output = "状态=${result.state}\n${result.detail}"
        )
    }

    fun precheckClickTarget(
        expectedPackage: String,
        targetText: String,
        targetViewId: String
    ): ClawToolCallResult {
        val result = AutomationRuntimeStore.precheckClickTarget(
            expectedPackage = expectedPackage.trim(),
            targetText = targetText.trim(),
            targetViewId = targetViewId.trim()
        )
        return ClawToolCallResult(
            success = result.state.isSucceeded(),
            output = "状态=${result.state}\n${result.detail}"
        )
    }

    suspend fun safeTapUsingResolvedTarget(): ClawToolCallResult {
        val resolvedTarget = AutomationRuntimeStore.latestResolvedTapTarget()
            ?: return ClawToolCallResult(
                success = false,
                output = "失败: 还没有可用的解析点击点，请先执行点击前检查",
                error = "missing_resolved_tap_target"
            )

        return runtimeClient.injectTap(
            x = resolvedTarget.x,
            y = resolvedTarget.y
        ).fold(
            onSuccess = { result ->
                ClawToolCallResult(
                    success = result.accepted,
                    output = "成功: target=${resolvedTarget.label}, accepted=${result.accepted}, display=${result.displayId}, x=${result.x}, y=${result.y}"
                )
            },
            onFailure = { error ->
                ClawToolCallResult(
                    success = false,
                    output = "失败: target=${resolvedTarget.label}, ${error.message ?: error::class.java.simpleName}",
                    error = error.message
                )
            }
        )
    }

    suspend fun ping(): ClawToolCallResult {
        return runtimeClient.ping().fold(
            onSuccess = { result ->
                ClawToolCallResult(
                    success = true,
                    output = "成功: daemon=${result.daemonStatus}, version=${result.daemonVersion}, latency=${result.latencyMs}ms"
                )
            },
            onFailure = { error ->
                ClawToolCallResult(
                    success = false,
                    output = "失败: ${error.message ?: error::class.java.simpleName}",
                    error = error.message
                )
            }
        )
    }

    suspend fun pingForChat(): ClawToolCallResult {
        return runtimeClient.ping().fold(
            onSuccess = {
                ClawToolCallResult(
                    success = true,
                    output = "ClawRuntime 在线，延迟 ${it.latencyMs}ms，版本 ${it.daemonVersion}。"
                )
            },
            onFailure = {
                ClawToolCallResult(
                    success = false,
                    output = "Ping 失败：${it.message ?: it::class.java.simpleName}",
                    error = it.message
                )
            }
        )
    }

    suspend fun getVersion(): ClawToolCallResult {
        return runtimeClient.getVersion().fold(
            onSuccess = { result ->
                ClawToolCallResult(
                    success = true,
                    output = "成功: daemon=${result.daemonStatus}, version=${result.daemonVersion}, protocol=${result.protocolVersion}, socket=${result.socketName}, log=${result.logLevel}",
                    runtimeConfigSummary = "Socket: ${result.socketName}\nProtocol: ${result.protocolVersion}\nLog Level: ${result.logLevel}"
                )
            },
            onFailure = { error ->
                ClawToolCallResult(
                    success = false,
                    output = "失败: ${error.message ?: error::class.java.simpleName}",
                    error = error.message
                )
            }
        )
    }

    suspend fun getHealth(): ClawToolCallResult {
        return runtimeClient.getHealth().fold(
            onSuccess = { result ->
                ClawToolCallResult(
                    success = true,
                    output = "成功: uptime=${result.uptimeSeconds}s, root=${result.root}, accessibility=${result.accessibility}, lsposed=${result.lsposed}, runtime=${result.lsposedRuntimeLoaded}, rate_limit=${result.rateLimitPerMinute}/min, whitelist=${result.readonlyWhitelist.joinToString()}",
                    runtimeConfigSummary = "Audit Dir: ${result.auditDir}\nRequest Timeout: ${result.requestTimeoutMs}ms\nRate Limit: ${result.rateLimitPerMinute}/min\nReadonly Whitelist: ${result.readonlyWhitelist.joinToString()}\nProtocol: ${result.protocolVersion}\nCapabilities: ${result.capabilities.joinToString()}"
                )
            },
            onFailure = { error ->
                ClawToolCallResult(
                    success = false,
                    output = "失败: ${error.message ?: error::class.java.simpleName}",
                    error = error.message
                )
            }
        )
    }

    suspend fun getLastError(): ClawToolCallResult {
        return runtimeClient.getLastError().fold(
            onSuccess = { result ->
                ClawToolCallResult(
                    success = true,
                    output = "最近错误: ${result.lastError.ifBlank { "none" }}\n错误时间: ${formatEpochSeconds(result.lastErrorAt)}\n最近限流: ${result.lastRateLimit.ifBlank { "none" }}\n限流时间: ${formatEpochSeconds(result.lastRateLimitAt)}\n限流次数: ${result.rateLimitHits}\n只读白名单: ${result.readonlyWhitelist.joinToString()}",
                    runtimeConfigSummary = "Rate Limit: ${result.rateLimitPerMinute}/min\nReadonly Whitelist: ${result.readonlyWhitelist.joinToString()}\n最近限流: ${result.lastRateLimit.ifBlank { "none" }}"
                )
            },
            onFailure = { error ->
                ClawToolCallResult(
                    success = false,
                    output = "失败: ${error.message ?: error::class.java.simpleName}",
                    error = error.message
                )
            }
        )
    }

    suspend fun probeSession(): ClawToolCallResult {
        return runtimeClient.probeSession().fold(
            onSuccess = { result ->
                val degraded = result.capabilities.degradedReason.ifBlank { "none" }
                ClawToolCallResult(
                    success = true,
                    output = "Runtime Probe 成功\nsession=${result.sessionId}\nfinal=${result.finalState}\ntrace=${result.stateTrace.joinToString(" -> ")}\nauth=${result.authMode}\nping=${result.ping.daemonStatus}/${result.ping.daemonVersion}\nruntime_loaded=${result.capabilities.lsposedRuntimeLoaded}, runtime_process=${result.capabilities.lsposedRuntimeProcess.ifBlank { "unknown" }}, runtime_at=${formatEpochMillis(result.capabilities.lsposedRuntimeLoadedAt)}\ncapabilities=${result.capabilities.capabilities.joinToString()}\ndegraded=$degraded",
                    sessionSnapshot = ClawSessionSnapshot(
                        sessionState = result.finalState,
                        sessionTrace = result.stateTrace.joinToString(" -> "),
                        authMode = result.authMode,
                        runtimeLoaded = result.capabilities.lsposedRuntimeLoaded,
                        runtimeProcess = result.capabilities.lsposedRuntimeProcess,
                        runtimeLoadedAtEpochMs = result.capabilities.lsposedRuntimeLoadedAt,
                        degradedReason = result.capabilities.degradedReason
                    )
                )
            },
            onFailure = { error ->
                ClawToolCallResult(
                    success = false,
                    output = "失败: ${error.message ?: error::class.java.simpleName}",
                    error = error.message,
                    sessionSnapshot = ClawSessionSnapshot(
                        sessionState = ClawRuntimeConnectionState.Closed,
                        sessionTrace = "Disconnected -> Closed",
                        authMode = "失败"
                    )
                )
            }
        )
    }

    suspend fun getCapabilities(): ClawToolCallResult {
        return runtimeClient.getCapabilities().fold(
            onSuccess = { result ->
                val capabilityList = result.capabilities.joinToString()
                ClawToolCallResult(
                    success = true,
                    output = "Capabilities 成功\nsession=${result.sessionState}\ntrace=${result.stateTrace.joinToString(" -> ")}\nroot=${result.root}, accessibility=${result.accessibility}, lsposed=${result.lsposed}, screenshot=${result.screenshotEnabled}, file_bridge=${result.fileBridgeEnabled}\nruntime_loaded=${result.lsposedRuntimeLoaded}, runtime_process=${result.lsposedRuntimeProcess.ifBlank { "unknown" }}, runtime_at=${formatEpochMillis(result.lsposedRuntimeLoadedAt)}\ncapabilities=[$capabilityList]\ndegraded=${result.degradedReason.ifBlank { "none" }}",
                    sessionSnapshot = ClawSessionSnapshot(
                        sessionState = result.sessionState,
                        sessionTrace = result.stateTrace.joinToString(" -> "),
                        authMode = "capabilities",
                        runtimeLoaded = result.lsposedRuntimeLoaded,
                        runtimeProcess = result.lsposedRuntimeProcess,
                        runtimeLoadedAtEpochMs = result.lsposedRuntimeLoadedAt,
                        degradedReason = result.degradedReason
                    )
                )
            },
            onFailure = { error ->
                ClawToolCallResult(
                    success = false,
                    output = "失败: ${error.message ?: error::class.java.simpleName}",
                    error = error.message
                )
            }
        )
    }

    suspend fun captureScreen(
        includeShaPreview: Boolean
    ): ClawToolCallResult {
        return runtimeClient.captureScreen().fold(
            onSuccess = { result ->
                val artifact = ClawCaptureArtifact(
                    imagePath = result.imagePath,
                    format = result.format,
                    width = result.width,
                    height = result.height,
                    fileSize = result.fileSize,
                    sha256 = result.sha256
                )
                val output = if (includeShaPreview) {
                    "成功: ${result.width}x${result.height}, format=${result.format}, path=${result.imagePath}, size=${result.fileSize}, sha256=${result.sha256.take(16)}..."
                } else {
                    "截图成功，已保存到 ${result.imagePath}，尺寸 ${result.width}x${result.height}，格式 ${result.format}。"
                }
                ClawToolCallResult(
                    success = true,
                    output = output,
                    captureArtifact = artifact
                )
            },
            onFailure = { error ->
                val prefix = if (includeShaPreview) "失败" else "截图失败"
                ClawToolCallResult(
                    success = false,
                    output = "$prefix: ${error.message ?: error::class.java.simpleName}",
                    error = error.message
                )
            }
        )
    }

    suspend fun readLatestCapture(
        latestCapturePath: String,
        latestCaptureFormat: String,
        latestCaptureFileSize: Long,
        previewLimitBytes: Int
    ): ClawToolCallResult {
        if (latestCapturePath.isBlank()) {
            return ClawToolCallResult(
                success = false,
                output = "失败: 还没有可读取的截图文件路径",
                error = "missing_capture_path"
            )
        }
        if (latestCaptureFileSize > previewLimitBytes) {
            return ClawToolCallResult(
                success = false,
                output = "失败: 最近截图过大，size=${formatBytes(latestCaptureFileSize)}，预览上限=${formatBytes(previewLimitBytes.toLong())}，建议降低截图尺寸或改用 jpeg",
                error = "capture_too_large"
            )
        }
        return runtimeClient.readFileFullyLimited(
            path = latestCapturePath,
            maxTotalBytes = previewLimitBytes
        ).fold(
            onSuccess = { bytes ->
                if (bytes.isEmpty()) {
                    ClawToolCallResult(
                        success = false,
                        output = "失败: 文件为空，path=$latestCapturePath, format=$latestCaptureFormat, size=${formatBytes(latestCaptureFileSize)}",
                        error = "empty_capture_file"
                    )
                } else {
                    ClawToolCallResult(
                        success = true,
                        output = "成功: 已读取 ${formatBytes(bytes.size.toLong())}，capture_size=${formatBytes(latestCaptureFileSize)}, format=$latestCaptureFormat",
                        previewBytes = bytes
                    )
                }
            },
            onFailure = { error ->
                val message = error.message ?: error::class.java.simpleName
                val output = when {
                    message.startsWith("file too large for preview") ->
                        "失败: 文件过大，path=$latestCapturePath, format=$latestCaptureFormat, size=${formatBytes(latestCaptureFileSize)}，预览上限=${formatBytes(previewLimitBytes.toLong())}，建议降低截图尺寸或改用 jpeg"
                    message.contains("file bridge disabled", ignoreCase = true) ->
                        "失败: 运行时已完成截图，但当前配置禁用了文件读取桥接 `capability.file_bridge_enabled`，因此无法预览图片。path=$latestCapturePath, format=$latestCaptureFormat, size=${formatBytes(latestCaptureFileSize)}"
                    message.contains("path not in allowed roots", ignoreCase = true) ->
                        "失败: 截图文件路径不在运行时允许读取的目录内。path=$latestCapturePath, format=$latestCaptureFormat, size=${formatBytes(latestCaptureFileSize)}"
                    else ->
                        "失败: $message, path=$latestCapturePath, format=$latestCaptureFormat, size=${formatBytes(latestCaptureFileSize)}"
                }
                ClawToolCallResult(
                    success = false,
                    output = output,
                    error = error.message
                )
            }
        )
    }

    suspend fun injectTap(
        x: Int,
        y: Int,
        displayId: Int = 0
    ): ClawToolCallResult {
        return runtimeClient.injectTap(x = x, y = y, displayId = displayId).fold(
            onSuccess = { result ->
                ClawToolCallResult(
                    success = result.accepted,
                    output = "成功: accepted=${result.accepted}, display=${result.displayId}, x=${result.x}, y=${result.y}"
                )
            },
            onFailure = { error ->
                ClawToolCallResult(
                    success = false,
                    output = "失败: ${error.message ?: error::class.java.simpleName}",
                    error = error.message
                )
            }
        )
    }

    suspend fun injectSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int = 350,
        displayId: Int = 0
    ): ClawToolCallResult {
        return runtimeClient.injectSwipe(
            x1 = x1,
            y1 = y1,
            x2 = x2,
            y2 = y2,
            durationMs = durationMs,
            displayId = displayId
        ).fold(
            onSuccess = { result ->
                ClawToolCallResult(
                    success = result.accepted,
                    output = "成功: accepted=${result.accepted}, display=${result.displayId}, duration=${result.durationMs}ms"
                )
            },
            onFailure = { error ->
                ClawToolCallResult(
                    success = false,
                    output = "失败: ${error.message ?: error::class.java.simpleName}",
                    error = error.message
                )
            }
        )
    }

    suspend fun execShellLimited(
        command: String,
        timeoutMs: Int = 3000
    ): ClawToolCallResult {
        return runtimeClient.execShellLimited(command = command, timeoutMs = timeoutMs).fold(
            onSuccess = { result ->
                ClawToolCallResult(
                    success = result.exitCode == 0,
                    output = "成功: template=${result.templateName.ifBlank { result.command }}, exit=${result.exitCode}, duration=${result.durationMs}ms, stdout_truncated=${result.stdoutTruncated}, stderr_truncated=${result.stderrTruncated}",
                    shellOutput = buildShellOutput(result.stdout, result.stderr)
                )
            },
            onFailure = { error ->
                ClawToolCallResult(
                    success = false,
                    output = "失败: ${error.message ?: error::class.java.simpleName}",
                    error = error.message,
                    shellOutput = error.message ?: error::class.java.simpleName
                )
            }
        )
    }

    suspend fun readScreenSize(): ClawToolCallResult {
        return runtimeClient.execShellLimited(command = "wm size", timeoutMs = 3000).fold(
            onSuccess = {
                ClawToolCallResult(
                    success = it.exitCode == 0,
                    output = "屏幕尺寸：${it.stdout.ifBlank { "empty" }}",
                    shellOutput = buildShellOutput(it.stdout, it.stderr)
                )
            },
            onFailure = {
                ClawToolCallResult(
                    success = false,
                    output = "读取屏幕尺寸失败：${it.message ?: it::class.java.simpleName}",
                    error = it.message
                )
            }
        )
    }

    private fun formatEpochMillis(value: Long): String {
        if (value <= 0L) {
            return "unknown"
        }
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return formatter.format(Date(value))
    }

    private fun formatEpochSeconds(value: Long): String {
        if (value <= 0L) {
            return "unknown-time"
        }
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return formatter.format(Date(value * 1000))
    }

    private fun buildShellOutput(stdout: String, stderr: String): String {
        val normalizedStdout = stdout.ifBlank { "<empty>" }
        return if (stderr.isBlank()) {
            normalizedStdout
        } else {
            "stdout:\n$normalizedStdout\n\nstderr:\n$stderr"
        }
    }

    private fun formatBytes(byteCount: Long): String {
        if (byteCount < 1024) {
            return "${byteCount}B"
        }
        if (byteCount < 1024 * 1024) {
            return String.format("%.1fKB", byteCount / 1024.0)
        }
        return String.format("%.2fMB", byteCount / (1024.0 * 1024.0))
    }
}

private fun Enum<*>.isSucceeded(): Boolean {
    return name.equals("Succeeded", ignoreCase = true)
}
