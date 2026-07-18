package com.clawdroid.app.tools

import android.content.Context
import com.clawdroid.app.mcp.assist.AssistMcpController
import com.clawdroid.app.runtime.ClawRuntimeClient

/**
 * Bag of optional domain services for [ClawToolDispatcher].
 * Keeps composition roots (Shell) from listing every constructor param by hand.
 */
data class ToolServiceRegistry(
    val assist: AssistMcpController? = null,
    val files: LocalFileToolService? = null,
    val apps: AppToolService? = null,
    val downloads: ToolDownloadService? = null,
    val notifications: NotificationToolService? = null,
    val webPreview: WebPreviewService? = null,
    val webSearch: WebSearchService? = null,
    val sandboxShell: SandboxShellService? = null,
    val camera: CameraCaptureService? = null,
    val sensors: SensorReadService? = null,
    val cameraRecord: CameraRecordService? = null,
    val ftp: FtpTransferService? = null,
    val gpuNpu: GpuNpuProbeService? = null
) {
    companion object {
        val EMPTY = ToolServiceRegistry()

        fun create(
            context: Context,
            runtimeClient: ClawRuntimeClient,
            assist: AssistMcpController? = null
        ): ToolServiceRegistry {
            val appContext = context.applicationContext
            return ToolServiceRegistry(
                assist = assist,
                files = LocalFileToolService(appContext, runtimeClient),
                apps = AppToolService(appContext, runtimeClient),
                downloads = ToolDownloadService(appContext),
                notifications = NotificationToolService(appContext),
                webPreview = WebPreviewService(),
                webSearch = WebSearchService(),
                sandboxShell = SandboxShellService(appContext),
                camera = CameraCaptureService(appContext),
                sensors = SensorReadService(appContext),
                cameraRecord = CameraRecordService(appContext),
                ftp = FtpTransferService(appContext),
                gpuNpu = GpuNpuProbeService(appContext)
            )
        }
    }
}
