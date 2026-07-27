package com.clawdroid.app.ui

import com.clawdroid.app.env.ClawRuntimePaths
import com.clawdroid.app.env.LocalEnvironmentStatus
import com.clawdroid.app.env.buildLocalEnvironmentDiagnosis
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
    val shizuku = shizukuEnvLabel(status)
    return "Root=$root, Root环境=$magisk, 模块=$module, Runtime=$runtime, LSPosed=$lsposed, Accessibility=$accessibility, 通知=$notification, Shizuku=$shizuku, 系统设置=$writeSettings, 全部文件=$allFiles"
}

internal fun rootStatusLabel(rootGranted: Boolean?): String {
    return when (rootGranted) {
        true -> "正常"
        false -> "未授权"
        null -> "未检测"
    }
}

internal fun rootHeroLabel(rootGranted: Boolean?): String {
    return when (rootGranted) {
        true -> "正常"
        false -> "失败"
        null -> "失败"
    }
}

internal fun accessibilityHeroLabel(enabled: Boolean): String {
    return if (enabled) "启用" else "禁用"
}

internal fun runtimeHeroLabel(
    sessionState: com.clawdroid.app.runtime.ClawRuntimeConnectionState,
    status: LocalEnvironmentStatus
): String {
    return when {
        !status.runtimeDaemonRunning -> "丢失"
        sessionState == com.clawdroid.app.runtime.ClawRuntimeConnectionState.Ready -> "正常"
        sessionState == com.clawdroid.app.runtime.ClawRuntimeConnectionState.Degraded -> "故障"
        else -> "故障"
    }
}

internal fun lsposedStatusLabel(status: LocalEnvironmentStatus): String {
    return when {
        status.xposedInjected -> "已注入"
        status.lsposedManagerInstalled -> "未注入"
        else -> "未安装"
    }
}

internal fun shizukuEnvLabel(status: LocalEnvironmentStatus): String {
    return if (status.shizukuPermissionGranted) "已授权" else "未授权"
}

internal fun selinuxStatusLabel(status: LocalEnvironmentStatus): String {
    return when (status.selinuxEnforcing) {
        true -> "启用"
        false -> "禁用"
        null -> "未知"
    }
}

internal fun networkStatusLabel(status: LocalEnvironmentStatus): String {
    return if (status.networkConnected) "已连接" else "已断开"
}

internal fun eventSubscriptionLabel(streaming: Boolean): String {
    return if (streaming) "已订阅" else "未订阅"
}

internal fun corePermissionsGranted(status: LocalEnvironmentStatus): Boolean {
    return status.notificationPermissionGranted &&
        status.accessibilityEnabled &&
        status.writeSettingsGranted &&
        status.allFilesAccessGranted
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
    return when {
        status.magiskDaemonRunning -> "就绪"
        status.magiskModuleInstalled || status.runtimeDaemonRunning -> "环境可用"
        else -> "未检测到"
    }
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
    return buildRuntimeSessionDiagnosis(
        localStatus = localStatus,
        runtimeState = runtimeState
    ).asMultilineString()
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
        "xposed_focus_changed" -> {
            val pkg = frame.data["package_name"]?.toString().orEmpty()
            val activity = frame.data["activity_class"]?.toString().orEmpty()
            val source = frame.data["source"]?.toString().orEmpty()
            val active = frame.data["active"]?.toString().orEmpty()
            val extras = frame.data["extras"] as? Map<*, *>
            val fragment = frame.data["settings_fragment"]?.toString().orEmpty()
                .ifBlank { extras?.get("settings_fragment")?.toString().orEmpty() }
            buildString {
                append("xposed=")
                append(pkg.ifBlank { "?" })
                if (activity.isNotBlank()) append('/').append(activity)
                if (fragment.isNotBlank()) append(" fragment=").append(fragment)
                if (active.isNotBlank()) append(" active=").append(active)
                if (source.isNotBlank()) append(" src=").append(source)
            }
        }
        "xposed_view_changed" -> {
            val pkg = frame.data["package_name"]?.toString().orEmpty()
            val activity = frame.data["activity_class"]?.toString().orEmpty()
            val nodes = frame.data["node_count"]?.toString().orEmpty()
            val compose = frame.data["compose_surface"]?.toString().orEmpty()
            val source = frame.data["source"]?.toString().orEmpty()
            buildString {
                append("view=")
                append(pkg.ifBlank { "?" })
                if (activity.isNotBlank()) append('/').append(activity)
                if (nodes.isNotBlank()) append(" nodes=").append(nodes)
                if (compose == "true") append(" compose")
                if (source.isNotBlank()) append(" src=").append(source)
            }
        }
        "task_state_changed" -> {
            val taskId = frame.data["task_id"]?.toString().orEmpty()
            val state = frame.data["state"]?.toString().orEmpty()
            val current = (frame.data["current_step"] as? Number)?.toInt()
            val total = (frame.data["total_steps"] as? Number)?.toInt()
            val completed = (frame.data["completed_steps"] as? Number)?.toInt()
            val progress = when {
                total != null && total > 0 && current != null -> {
                    val done = completed ?: current.coerceAtLeast(0)
                    " step=${current + 1}/$total completed=$done"
                }
                else -> ""
            }
            "task=$state id=${taskId.ifBlank { "?" }}$progress"
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
    return requireNotNull(chatTimeFormatter.get()).format(Date(value * 1000))
}

/** 气泡时间：当天只显示时分，跨天显示月日+时分。 */
internal fun formatChatBubbleTime(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val now = System.currentTimeMillis()
    val sameDay = isSameCalendarDay(epochMs, now)
    val formatter = if (sameDay) {
        requireNotNull(chatTimeOfDayFormatter.get())
    } else {
        requireNotNull(chatDayTimeFormatter.get())
    }
    return formatter.format(Date(epochMs))
}

private fun isSameCalendarDay(aMs: Long, bMs: Long): Boolean {
    val calA = java.util.Calendar.getInstance().apply { timeInMillis = aMs }
    val calB = java.util.Calendar.getInstance().apply { timeInMillis = bMs }
    return calA.get(java.util.Calendar.YEAR) == calB.get(java.util.Calendar.YEAR) &&
        calA.get(java.util.Calendar.DAY_OF_YEAR) == calB.get(java.util.Calendar.DAY_OF_YEAR)
}

private val chatTimeFormatter = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
}

private val chatTimeOfDayFormatter = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat =
        SimpleDateFormat("HH:mm", Locale.getDefault())
}

private val chatDayTimeFormatter = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat =
        SimpleDateFormat("M/d HH:mm", Locale.getDefault())
}

internal fun parseFocusedWindowSummary(raw: String?): String {
    if (raw.isNullOrBlank()) {
        return "unknown"
    }
    val summaryMatch = Regex("\"summary\"\\s*:\\s*\"([^\"]*)\"").find(raw)
    if (summaryMatch != null) {
        val summary = summaryMatch.groupValues[1].ifBlank { "unknown" }
        val source = Regex("\"source\"\\s*:\\s*\"([^\"]*)\"").find(raw)?.groupValues?.get(1).orEmpty()
        return if (source.isBlank()) summary else "$summary@$source"
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

internal fun mergeShellCommandOptions(
    current: List<String>,
    remote: List<String>
): List<String> {
    if (remote.isEmpty()) {
        return current.ifEmpty { defaultShellCommandOptions() }
    }
    val merged = LinkedHashSet<String>()
    merged.addAll(remote)
    merged.addAll(current)
    return merged.toList()
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
        "cmd overlay list",
        "ls ${ClawRuntimePaths.MAGISK_MODULE_PATH}",
        "cat ${ClawRuntimePaths.WEBROOT_PATH}/status.json",
        "cat ${ClawRuntimePaths.WEBROOT_PATH}/verify.json",
        "pidof clawdroid-runtime"
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
