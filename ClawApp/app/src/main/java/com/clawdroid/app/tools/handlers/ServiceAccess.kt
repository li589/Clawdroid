package com.clawdroid.app.tools.handlers

import com.clawdroid.app.mcp.assist.AssistMcpController
import com.clawdroid.app.termux.TermuxBridge
import com.clawdroid.app.tools.AppToolService
import com.clawdroid.app.tools.CameraCaptureService
import com.clawdroid.app.tools.CameraRecordService
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.FtpTransferService
import com.clawdroid.app.tools.GpuNpuProbeService
import com.clawdroid.app.tools.LocalFileToolService
import com.clawdroid.app.tools.NotificationToolService
import com.clawdroid.app.tools.SandboxShellClient
import com.clawdroid.app.tools.SensorReadService
import com.clawdroid.app.tools.ToolDownloadService
import com.clawdroid.app.tools.ToolServiceRegistry
import com.clawdroid.app.tools.WebPreviewService
import com.clawdroid.app.tools.WebSearchService

internal suspend fun ToolServiceRegistry.assistOrMissing(
    block: suspend (AssistMcpController) -> ClawToolCallResult
): ClawToolCallResult {
    val ctrl = assist
        ?: return ClawToolCallResult(false, "协助 MCP 未绑定", error = "assist_unavailable")
    return block(ctrl)
}

internal suspend fun ToolServiceRegistry.fileOrMissing(
    block: suspend (LocalFileToolService) -> ClawToolCallResult
): ClawToolCallResult {
    val svc = files
        ?: return ClawToolCallResult(false, "文件工具未绑定", error = "file_unavailable")
    return block(svc)
}

internal suspend fun ToolServiceRegistry.appOrMissing(
    block: suspend (AppToolService) -> ClawToolCallResult
): ClawToolCallResult {
    val svc = apps
        ?: return ClawToolCallResult(false, "应用工具未绑定", error = "app_unavailable")
    return block(svc)
}

internal fun ToolServiceRegistry.downloadOrMissing(
    block: (ToolDownloadService) -> ClawToolCallResult
): ClawToolCallResult {
    val svc = downloads
        ?: return ClawToolCallResult(false, "下载工具未绑定", error = "download_unavailable")
    return block(svc)
}

internal fun ToolServiceRegistry.notificationOrMissing(
    block: (NotificationToolService) -> ClawToolCallResult
): ClawToolCallResult {
    val svc = notifications
        ?: return ClawToolCallResult(false, "通知工具未绑定", error = "notification_unavailable")
    return block(svc)
}

internal fun ToolServiceRegistry.webOrMissing(
    block: (WebPreviewService) -> ClawToolCallResult
): ClawToolCallResult {
    val svc = webPreview
        ?: return ClawToolCallResult(false, "网页预览未绑定", error = "web_unavailable")
    return block(svc)
}

internal fun ToolServiceRegistry.webSearchOrMissing(
    block: (WebSearchService) -> ClawToolCallResult
): ClawToolCallResult {
    val svc = webSearch
        ?: return ClawToolCallResult(false, "网页搜索未绑定", error = "web_search_unavailable")
    return block(svc)
}

internal fun ToolServiceRegistry.sandboxOrMissing(
    block: (SandboxShellClient) -> ClawToolCallResult
): ClawToolCallResult {
    val svc = sandboxShell
        ?: return ClawToolCallResult(false, "沙箱 Shell 未绑定", error = "sandbox_unavailable")
    return block(svc)
}

internal suspend fun ToolServiceRegistry.termuxOrMissing(
    block: suspend (TermuxBridge) -> ClawToolCallResult
): ClawToolCallResult {
    val bridge = termux
        ?: return ClawToolCallResult(false, "Termux 未绑定", error = "termux_unavailable")
    return block(bridge)
}

internal fun ToolServiceRegistry.cameraOrMissing(
    block: (CameraCaptureService) -> ClawToolCallResult
): ClawToolCallResult {
    val svc = camera
        ?: return ClawToolCallResult(false, "摄像头工具未绑定", error = "camera_unavailable")
    return block(svc)
}

internal fun ToolServiceRegistry.sensorOrMissing(
    block: (SensorReadService) -> ClawToolCallResult
): ClawToolCallResult {
    val svc = sensors
        ?: return ClawToolCallResult(false, "传感器工具未绑定", error = "sensor_unavailable")
    return block(svc)
}

internal fun ToolServiceRegistry.cameraRecordOrMissing(
    block: (CameraRecordService) -> ClawToolCallResult
): ClawToolCallResult {
    val svc = cameraRecord
        ?: return ClawToolCallResult(false, "录像工具未绑定", error = "camera_record_unavailable")
    return block(svc)
}

internal fun ToolServiceRegistry.ftpOrMissing(
    block: (FtpTransferService) -> ClawToolCallResult
): ClawToolCallResult {
    val svc = ftp
        ?: return ClawToolCallResult(false, "FTP 工具未绑定", error = "ftp_unavailable")
    return block(svc)
}

internal fun ToolServiceRegistry.gpuOrMissing(
    block: (GpuNpuProbeService) -> ClawToolCallResult
): ClawToolCallResult {
    val svc = gpuNpu
        ?: return ClawToolCallResult(false, "GPU/NPU 探测未绑定", error = "gpu_probe_unavailable")
    return block(svc)
}
