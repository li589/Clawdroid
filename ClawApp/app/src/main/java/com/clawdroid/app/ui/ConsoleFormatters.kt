package com.clawdroid.app.ui

import com.clawdroid.app.env.LocalEnvironmentDiagnosis
import com.clawdroid.app.env.LocalEnvironmentStatus
import com.clawdroid.app.env.buildLocalEnvironmentDiagnosis
import com.clawdroid.app.runtime.ClawRuntimeConnectionState
import com.clawdroid.app.runtime.ClawRuntimeEventFrame
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun buildLocalEnvironmentSummary(status: LocalEnvironmentStatus): String {
    val root = rootStatusLabel(status.rootGranted)
    val magisk = magiskDaemonStatusLabel(status)
    val module = moduleStatusLabel(status)
    val runtime = runtimeDaemonStatusLabel(status)
    val lsposed = lsposedStatusLabel(status)
    val accessibility = booleanStatusLabel(status.accessibilityEnabled)
    val notification = permissionGrantedLabel(status.notificationPermissionGranted)
    val writeSettings = permissionGrantedLabel(status.writeSettingsGranted)
    val allFiles = permissionGrantedLabel(status.allFilesAccessGranted)
    return "Root=$root, Magisk守护=$magisk, 模块=$module, Runtime=$runtime, LSPosed=$lsposed, Accessibility=$accessibility, 通知=$notification, 系统设置=$writeSettings, 全部文件=$allFiles"
}

internal fun rootStatusLabel(rootGranted: Boolean?): String {
    return when (rootGranted) {
        true -> "正常"
        false -> "未授权"
        null -> "未检测"
    }
}

internal fun lsposedStatusLabel(status: LocalEnvironmentStatus): String {
    return when {
        status.xposedInjected -> "已注入"
        status.lsposedManagerInstalled -> "Manager 已安装(待注入)"
        else -> "未确认"
    }
}

internal fun magiskStatusLabel(status: LocalEnvironmentStatus): String {
    return when {
        status.magiskModuleEnabled && status.runtimeDaemonRunning -> "模块正常"
        status.magiskModuleEnabled -> "模块已启用"
        status.magiskModuleInstalled -> "模块已安装"
        status.magiskDaemonRunning -> "Magisk 就绪"
        else -> "未连接"
    }
}

internal fun magiskDaemonStatusLabel(status: LocalEnvironmentStatus): String {
    return if (status.magiskDaemonRunning) "运行中" else "未检测到"
}

internal fun moduleStatusLabel(status: LocalEnvironmentStatus): String {
    return when {
        status.magiskModuleEnabled -> "已启用"
        status.magiskModuleInstalled -> "已安装但未启用"
        else -> "未安装"
    }
}

internal fun runtimeDaemonStatusLabel(status: LocalEnvironmentStatus): String {
    return if (status.runtimeDaemonRunning) "运行中" else "未运行"
}

internal fun booleanStatusLabel(value: Boolean): String {
    return if (value) "正常" else "未启用"
}

internal fun permissionGrantedLabel(value: Boolean): String {
    return if (value) "已授权" else "未授权"
}

internal fun buildRuntimeConnectionDiagnosis(
    localStatus: LocalEnvironmentStatus,
    runtimeState: OverviewRuntimeState
): String {
    val localDiagnosis = buildLocalEnvironmentDiagnosis(localStatus)
    return when {
        localStatus.rootGranted != true -> localDiagnosis.asMultilineString()
        !localStatus.magiskDaemonRunning || !localStatus.magiskModuleInstalled ||
            !localStatus.magiskModuleEnabled || !localStatus.runtimeDaemonRunning -> {
            localDiagnosis.asMultilineString()
        }

        runtimeState.session.state == ClawRuntimeConnectionState.Ready &&
            runtimeState.session.runtimeLoaded == false -> buildString {
            append("Runtime 会话已 Ready，但 LSPosed 运行时注入未落地")
            append('\n')
            append("状态轨迹: ")
            append(runtimeState.session.trace)
            append('\n')
            append("Runtime 进程: ")
            append(runtimeState.session.runtimeProcess.ifBlank { "unknown" })
            append('\n')
            append("最近摘要: ")
            append(runtimeState.session.summary)
            append('\n')
            append("建议优先检查 xposed_runtime_marker、LSPosed 作用域、自进程注入以及授权调整后是否已重启设备。")
        }

        runtimeState.session.state == ClawRuntimeConnectionState.Ready -> buildString {
            append("Runtime 会话正常")
            append('\n')
            append("状态轨迹: ")
            append(runtimeState.session.trace)
            append('\n')
            append("当前能力链路已 Ready，可继续做截图、滑动、Shell 与事件闭环验证。")
        }

        runtimeState.session.state == ClawRuntimeConnectionState.Degraded -> buildString {
            append("Runtime 会话已连通，但当前处于降级态")
            append('\n')
            append("状态轨迹: ")
            append(runtimeState.session.trace)
            append('\n')
            append("最近摘要: ")
            append(runtimeState.session.summary)
            append('\n')
            append("建议优先检查设备上实际生效的 runtime 配置、授权白名单与能力开关。")
        }

        runtimeState.session.state == ClawRuntimeConnectionState.Closed -> buildString {
            append("Runtime 会话已关闭或鉴权失败")
            append('\n')
            append("状态轨迹: ")
            append(runtimeState.session.trace)
            append('\n')
            append("最近摘要: ")
            append(runtimeState.session.summary)
            append('\n')
            append("建议确认 App 与模块是否来自同一轮构建，尤其是 shared secret、签名白名单和设备端 runtime.yaml。")
        }

        runtimeState.session.state == ClawRuntimeConnectionState.Disconnected &&
            runtimeState.session.summary == "尚未建立会话" -> {
            "Runtime 守护已可见，但还没有执行会话探针\n建议先执行 Runtime Probe 或 Capabilities，确认最终状态是 Ready、Degraded 还是 Closed。"
        }

        else -> buildString {
            append("Runtime 会话仍在协商或等待下一步探测")
            append('\n')
            append("当前状态: ")
            append(runtimeState.session.state)
            append('\n')
            append("状态轨迹: ")
            append(runtimeState.session.trace)
            append('\n')
            append("最近摘要: ")
            append(runtimeState.session.summary)
        }
    }
}

internal fun buildLocalEnvironmentDiagnosisText(status: LocalEnvironmentStatus): String {
    return buildLocalEnvironmentDiagnosis(status).asMultilineString()
}

internal fun summarizeEventFrame(frame: ClawRuntimeEventFrame): String {
    return when (frame.event) {
        "daemon_status_changed" -> {
            val status = frame.data["daemon_status"]?.toString().orEmpty()
            val daemonMetrics = buildDaemonMetricsSummary(frame.data)
            "daemon=$status, $daemonMetrics"
        }
        "capability_changed" -> {
            val root = frame.data["root"]?.toString().orEmpty()
            val accessibility = frame.data["accessibility"]?.toString().orEmpty()
            val lsposed = frame.data["lsposed_runtime_loaded"]?.toString().orEmpty()
            "capability[root=$root, accessibility=$accessibility, runtime=$lsposed]"
        }
        "window_changed" -> {
            val focused = parseFocusedWindowSummary(frame.data["focused_window"]?.toString())
            "window=$focused"
        }
        "task_state_changed" -> {
            val state = frame.data["state"]?.toString().orEmpty()
            "task=$state"
        }
        "diagnostic_changed" -> {
            buildDiagnosticEventSummary(frame.data)
        }
        else -> "${frame.event}=${frame.data}"
    }
}

internal fun formatEpochMillis(value: Long): String {
    if (value <= 0L) {
        return "unknown"
    }
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(value))
}

internal fun formatEpochSeconds(value: Long): String {
    if (value <= 0L) {
        return "unknown-time"
    }
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(value * 1000))
}

internal fun parseFocusedWindowSummary(raw: String?): String {
    if (raw.isNullOrBlank()) {
        return "unknown"
    }
    return runCatching {
        val payload = JSONObject(raw)
        val summary = payload.optString("summary").ifBlank { "unknown" }
        val source = payload.optString("source")
        if (source.isBlank()) summary else "$summary@$source"
    }.getOrElse {
        raw
    }
}

internal fun buildShellOutput(stdout: String, stderr: String): String {
    val normalizedStdout = stdout.ifBlank { "<empty>" }
    return if (stderr.isBlank()) {
        normalizedStdout
    } else {
        "stdout:\n$normalizedStdout\n\nstderr:\n$stderr"
    }
}

internal fun defaultShellCommandOptions(): List<String> {
    return listOf(
        "dumpsys window windows",
        "dumpsys activity top",
        "wm size",
        "wm density",
        "id",
        "getenforce",
        "getprop ro.product.model",
        "getprop ro.product.manufacturer",
        "getprop ro.build.version.release",
        "getprop ro.build.version.sdk",
        "getprop ro.hardware",
        "settings get secure accessibility_enabled",
        "settings get secure enabled_accessibility_services",
        "cmd overlay list"
    )
}

internal fun buildPreviewFailureMessage(
    error: Throwable,
    capturePath: String,
    captureFormat: String,
    captureFileSize: Long,
    previewLimitBytes: Long
): String {
    val message = error.message ?: error::class.java.simpleName
    return if (message.startsWith("file too large for preview")) {
        "失败: 文件过大，path=$capturePath, format=$captureFormat, size=${formatBytes(captureFileSize)}, 预览上限=${formatBytes(previewLimitBytes)}，建议降低截图尺寸或改用 jpeg"
    } else {
        "失败: $message, path=$capturePath, format=$captureFormat, size=${formatBytes(captureFileSize)}"
    }
}

internal fun formatBytes(byteCount: Long): String {
    if (byteCount < 1024) {
        return "${byteCount}B"
    }
    if (byteCount < 1024 * 1024) {
        return String.format("%.1fKB", byteCount / 1024.0)
    }
    return String.format("%.2fMB", byteCount / (1024.0 * 1024.0))
}

internal fun buildDaemonMetricsSummary(data: Map<String, Any?>): String {
    val load1 = (data["load_1"] as? Number)?.toDouble() ?: 0.0
    val load5 = (data["load_5"] as? Number)?.toDouble() ?: 0.0
    val memTotalKB = (data["mem_total_kb"] as? Number)?.toLong() ?: 0L
    val memAvailableKB = (data["mem_available_kb"] as? Number)?.toLong() ?: 0L
    val memUsedKB = (memTotalKB - memAvailableKB).coerceAtLeast(0L)
    return "load=${"%.2f".format(load1)}/${"%.2f".format(load5)}, mem=${formatBytes(memUsedKB * 1024)}/${formatBytes(memTotalKB * 1024)}"
}

internal fun buildRuntimeProcessSummary(data: Map<String, Any?>): String {
    val pid = (data["runtime_pid"] as? Number)?.toInt() ?: 0
    val rssKB = (data["runtime_rss_kb"] as? Number)?.toLong() ?: 0L
    return "pid=$pid, rss=${formatBytes(rssKB * 1024)}"
}

internal fun buildDiagnosticEventSummary(data: Map<String, Any?>): String {
    val lastError = data["last_error"]?.toString().orEmpty().ifBlank { "none" }
    val rateLimitHits = (data["rate_limit_hits"] as? Number)?.toInt() ?: 0
    val rateLimitPerMinute = (data["rate_limit_per_minute"] as? Number)?.toInt() ?: 0
    return "diagnostic[last_error=$lastError, rate_limit_hits=$rateLimitHits, rate_limit=${rateLimitPerMinute}/min]"
}
