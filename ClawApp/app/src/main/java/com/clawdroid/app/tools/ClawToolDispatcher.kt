package com.clawdroid.app.tools

import android.content.Context
import com.clawdroid.app.fault.FaultCodes
import com.clawdroid.app.fault.FaultIsolation
import com.clawdroid.app.mcp.assist.AssistMcpController
import com.clawdroid.app.skills.ClawAgentRunner
import com.clawdroid.app.tools.handlers.agentToolHandlers
import com.clawdroid.app.tools.handlers.appDownloadWebToolHandlers
import com.clawdroid.app.tools.handlers.assistToolHandlers
import com.clawdroid.app.tools.handlers.deviceToolHandlers
import com.clawdroid.app.tools.handlers.fileToolHandlers
import com.clawdroid.app.tools.handlers.runtimeInspectToolHandlers
import com.clawdroid.app.tools.handlers.termuxToolHandlers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Unified tool entry for chat AI and MCP `tools/call`.
 * Mutating / Runtime tools share a serialize mutex; read-only local tools may run in parallel.
 * Nested agent steps re-enter without re-acquiring the mutex (avoids deadlock).
 */
class ClawToolDispatcher(
    private val executor: ClawToolExecutor,
    private val eventBridge: EventBridge? = null,
    private val previewLimitBytes: Int = 1 * 1024 * 1024,
    private val permissionGate: ToolPermissionGate? = null,
    private val appContext: Context? = null,
    private val services: ToolServiceRegistry = ToolServiceRegistry.EMPTY,
    assistController: AssistMcpController? = null,
    fileService: LocalFileToolService? = null,
    appService: AppToolService? = null,
    downloadService: ToolDownloadService? = null,
    notificationService: NotificationToolService? = null,
    webPreviewService: WebPreviewService? = null
) {
    fun interface EventBridge {
        suspend fun handle(operation: String): ClawToolCallResult
    }

    private val resolvedServices = ToolServiceRegistry(
        assist = services.assist ?: assistController,
        files = services.files ?: fileService,
        apps = services.apps ?: appService,
        downloads = services.downloads ?: downloadService,
        notifications = services.notifications ?: notificationService,
        webPreview = services.webPreview ?: webPreviewService,
        webSearch = services.webSearch,
        sandboxShell = services.sandboxShell,
        termux = services.termux,
        camera = services.camera,
        sensors = services.sensors,
        cameraRecord = services.cameraRecord,
        ftp = services.ftp,
        gpuNpu = services.gpuNpu
    )

    private val agentRunner = ClawAgentRunner(this)
    private val deviceMutateMutex = Mutex()
    private val captureMutex = Mutex()
    private val agentMutex = Mutex()
    private val capabilityProbe = CapabilityProbe(executor)

    private val handlers: Map<ClawTool, ToolHandler> = buildMap {
        putAll(runtimeInspectToolHandlers(executor, capabilityProbe, appContext))
        putAll(
            deviceToolHandlers(
                executor = executor,
                eventBridge = eventBridge,
                previewLimitBytes = previewLimitBytes,
                peekLastCapture = ::peekLastCapture,
                rememberCapture = ::rememberCapture,
                dispatcher = this@ClawToolDispatcher
            )
        )
        putAll(agentToolHandlers(agentRunner))
        putAll(assistToolHandlers(resolvedServices))
        putAll(fileToolHandlers(resolvedServices))
        putAll(termuxToolHandlers(resolvedServices))
        putAll(appDownloadWebToolHandlers(resolvedServices, appContext))
    }

    private class HoldingToolLock(
        val lanes: Set<ToolSerializeLane> = emptySet()
    ) : AbstractCoroutineContextElement(HoldingToolLock) {
        companion object Key : CoroutineContext.Key<HoldingToolLock>
    }

    @Volatile
    private var lastCapture: ClawCaptureArtifact? = null

    fun peekLastCapture(): ClawCaptureArtifact? = lastCapture

    fun rememberCapture(artifact: ClawCaptureArtifact?) {
        if (artifact != null) {
            lastCapture = artifact
        }
    }

    suspend fun execute(
        toolId: String,
        arguments: Map<String, Any?> = emptyMap()
    ): ClawToolCallResult {
        val tool = ClawTool.byToolId(toolId)
            ?: return ClawToolCallResult(
                success = false,
                output = "未知工具: $toolId",
                error = "unknown_tool"
            )
        return execute(tool, arguments)
    }

    suspend fun execute(
        tool: ClawTool,
        arguments: Map<String, Any?> = emptyMap()
    ): ClawToolCallResult {
        // 跟踪当前协程已持有的 lane 集合：只有当请求的 lane 与已持有的同一 lane
        // 重叠时才跳过加锁（防止非重入 Mutex 自死锁），不同 lane 仍需各自加锁，
        // 否则 RUN_AGENT 内嵌的 CAPTURE_SCREEN 会绕过 captureMutex，与另一个
        // 调用方的 CAPTURE_SCREEN 并发写 @Volatile lastCapture 造成脏读。
        val heldLanes = coroutineContext[HoldingToolLock]?.lanes ?: emptySet()
        val lane = ToolExecutionPolicy.serializeLane(tool)

        suspend fun runBody(): ClawToolCallResult {
            coroutineContext.ensureActive()
            return try {
                executeInternal(tool, arguments)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                FaultIsolation.recordFault("tool:${tool.toolId}", error)
                ClawToolCallResult(
                    success = false,
                    output = FaultIsolation.formatIsolatedError("tool:${tool.toolId}", error),
                    error = FaultCodes.TOOL_UNCAUGHT
                )
            }
        }

        val mutex = when (lane) {
            ToolSerializeLane.None -> null
            ToolSerializeLane.DeviceMutate -> deviceMutateMutex
            ToolSerializeLane.Capture -> captureMutex
            ToolSerializeLane.Agent -> agentMutex
        }
        return if (mutex == null || lane in heldLanes) {
            runBody()
        } else {
            mutex.withLock {
                withContext(HoldingToolLock(heldLanes + lane)) { runBody() }
            }
        }
    }

    private suspend fun executeInternal(
        tool: ClawTool,
        arguments: Map<String, Any?>
    ): ClawToolCallResult {
        val spec = ClawToolDefinitions.spec(tool)
        permissionGate?.evaluate(spec)?.let { decision ->
            if (!decision.allowed) {
                return ClawToolCallResult(
                    success = false,
                    output = decision.message,
                    error = decision.errorCode
                )
            }
        }
        val handler = handlers[tool]
            ?: return ClawToolCallResult(
                success = false,
                output = "未注册工具处理器: ${tool.toolId}",
                error = "handler_missing"
            )
        return handler.execute(tool, arguments)
    }
}
