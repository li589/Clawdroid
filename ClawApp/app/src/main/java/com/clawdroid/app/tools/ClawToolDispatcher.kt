package com.clawdroid.app.tools

import android.content.Context
import com.clawdroid.app.env.ShizukuSupport
import com.clawdroid.app.fault.FaultCodes
import com.clawdroid.app.fault.FaultIsolation
import com.clawdroid.app.mcp.assist.AssistMcpController
import com.clawdroid.app.skills.ClawAgentCatalog
import com.clawdroid.app.skills.ClawAgentRunner
import com.clawdroid.app.skills.ClawSkillCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
    private val previewLimitBytes: Int = 8 * 1024 * 1024,
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
        camera = services.camera,
        sensors = services.sensors,
        cameraRecord = services.cameraRecord,
        ftp = services.ftp,
        gpuNpu = services.gpuNpu
    )

    private val agentRunner = ClawAgentRunner(this)
    private val serializeMutex = Mutex()
    private val capabilityProbe = CapabilityProbe(executor)

    private class HoldingSerializeLock :
        AbstractCoroutineContextElement(HoldingSerializeLock) {
        companion object Key : CoroutineContext.Key<HoldingSerializeLock>
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
        val alreadyHolding = coroutineContext[HoldingSerializeLock] != null
        val needsSerialize = !alreadyHolding && ToolExecutionPolicy.requiresSerialization(tool)

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

        return when {
            alreadyHolding -> runBody()
            needsSerialize -> serializeMutex.withLock {
                withContext(HoldingSerializeLock()) { runBody() }
            }
            else -> runBody()
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
        return when (tool) {
            ClawTool.PAGE_CONFIRM -> executor.confirmPage(
                expectedPackage = arguments.string("expected_package", "package", "expectedPackage"),
                expectedText = arguments.string("expected_text", "text", "expectedText"),
                expectedViewId = arguments.string("expected_view_id", "view_id", "expectedViewId")
            )
            ClawTool.CLICK_PRECHECK -> executor.precheckClickTarget(
                expectedPackage = arguments.string("expected_package", "package", "expectedPackage"),
                targetText = arguments.string("target_text", "text", "targetText"),
                targetViewId = arguments.string("target_view_id", "view_id", "targetViewId")
            )
            ClawTool.SAFE_TAP -> executor.safeTapUsingResolvedTarget()
            ClawTool.RUNTIME_PING -> executor.ping()
            ClawTool.GET_VERSION -> executor.getVersion()
            ClawTool.GET_HEALTH -> executor.getHealth()
            ClawTool.GET_RUNTIME_STATUS -> executor.getRuntimeStatus()
            ClawTool.GET_LAST_ERROR -> executor.getLastError()
            ClawTool.PROBE_SESSION -> executor.probeSession()
            ClawTool.GET_CAPABILITIES -> executor.getCapabilities()
            ClawTool.CAPTURE_SCREEN -> {
                val withPreview = arguments.bool("read_after_capture", default = false)
                val capture = executor.captureScreen(includeShaPreview = true)
                if (capture.success) {
                    lastCapture = capture.captureArtifact
                }
                if (!withPreview || !capture.success || capture.captureArtifact == null) {
                    return capture
                }
                val artifact = capture.captureArtifact
                val preview = executor.readLatestCapture(
                    latestCapturePath = artifact.imagePath,
                    latestCaptureFormat = artifact.format,
                    latestCaptureFileSize = artifact.fileSize,
                    previewLimitBytes = previewLimitBytes
                )
                ClawToolCallResult(
                    success = preview.success,
                    output = capture.output + "\n" + preview.output,
                    error = preview.error,
                    captureArtifact = artifact,
                    previewBytes = preview.previewBytes
                )
            }
            ClawTool.READ_LATEST_CAPTURE -> {
                val artifact = lastCapture
                    ?: return ClawToolCallResult(
                        success = false,
                        output = "失败: 还没有可读取的截图，请先调用 capture_screen",
                        error = "missing_capture_path"
                    )
                executor.readLatestCapture(
                    latestCapturePath = artifact.imagePath,
                    latestCaptureFormat = artifact.format,
                    latestCaptureFileSize = artifact.fileSize,
                    previewLimitBytes = previewLimitBytes
                )
            }
            ClawTool.READ_FILE_LIMITED -> {
                val path = arguments.string("path")
                if (path.isBlank()) {
                    return ClawToolCallResult(
                        success = false,
                        output = "失败: path 不能为空",
                        error = "missing_path"
                    )
                }
                executor.readFileLimited(
                    path = path,
                    offset = arguments.long("offset", default = 0L),
                    maxBytes = arguments.int("max_bytes", default = 65536)
                )
            }
            ClawTool.INJECT_TAP -> executor.injectTap(
                x = arguments.int("x", default = 540),
                y = arguments.int("y", default = 1200),
                displayId = arguments.int("display_id", default = 0)
            )
            ClawTool.INJECT_KEYEVENT -> {
                val keyCode = arguments.optionalInt("keycode")
                val key = arguments.string("key").ifBlank { if (keyCode == null) "BACK" else "" }
                executor.injectKeyevent(
                    key = key.takeIf { it.isNotBlank() && keyCode == null },
                    keyCode = keyCode,
                    displayId = arguments.int("display_id", default = 0)
                )
            }
            ClawTool.INJECT_SWIPE -> executor.injectSwipe(
                x1 = arguments.int("x1", default = 540),
                y1 = arguments.int("y1", default = 1800),
                x2 = arguments.int("x2", default = 540),
                y2 = arguments.int("y2", default = 400),
                durationMs = arguments.int("duration_ms", default = 350),
                displayId = arguments.int("display_id", default = 0)
            )
            ClawTool.EXECUTE_SHELL_LIMITED -> {
                val command = arguments.string("command")
                if (command.isBlank()) {
                    return ClawToolCallResult(
                        success = false,
                        output = "失败: command 不能为空",
                        error = "missing_command"
                    )
                }
                executor.execShellLimited(command = command)
            }
            ClawTool.SUBSCRIBE_EVENTS -> {
                val operation = arguments.string("operation").ifBlank { "start" }.lowercase()
                val bridge = eventBridge
                    ?: return ClawToolCallResult(
                        success = false,
                        output = "失败: 当前未绑定事件桥接，无法在 MCP 中订阅事件流",
                        error = "events_unavailable"
                    )
                bridge.handle(operation)
            }
            ClawTool.LIST_SKILLS -> ClawToolCallResult(
                success = true,
                output = ClawSkillCatalog.all().joinToString("\n") { skill ->
                    "- ${skill.id}: ${skill.name} — ${skill.description}"
                }
            )
            ClawTool.GET_SKILL -> {
                val skillId = arguments.string("skill_id", "id", "name")
                val skill = ClawSkillCatalog.byId(skillId)
                    ?: return ClawToolCallResult(
                        success = false,
                        output = "未知 Skill: $skillId",
                        error = "unknown_skill"
                    )
                ClawToolCallResult(
                    success = true,
                    output = ClawSkillCatalog.toSkillMd(skill)
                )
            }
            ClawTool.LIST_AGENTS -> ClawToolCallResult(
                success = true,
                output = ClawAgentCatalog.all().joinToString("\n") { agent ->
                    "- ${agent.id}: ${agent.name} — ${agent.description} [${agent.steps.joinToString(" -> ")}]"
                }
            )
            ClawTool.RUN_AGENT -> {
                val agentId = arguments.string("agent_id", "agent", "id", "name")
                if (agentId.isBlank()) {
                    return ClawToolCallResult(
                        success = false,
                        output = "失败: agent_id 不能为空",
                        error = "missing_agent_id"
                    )
                }
                agentRunner.run(agentId, arguments)
            }
            ClawTool.TASK_SUBMIT -> {
                val task = resolveTaskPayload(arguments)
                    ?: return ClawToolCallResult(
                        success = false,
                        output = "失败: 需要 task 对象、task_json，或 task_id + steps_json",
                        error = "missing_task"
                    )
                executor.taskSubmit(task)
            }
            ClawTool.TASK_GET -> {
                val taskId = arguments.string("task_id", "id")
                if (taskId.isBlank()) {
                    return ClawToolCallResult(
                        success = false,
                        output = "失败: task_id 不能为空",
                        error = "missing_task_id"
                    )
                }
                executor.taskGet(taskId)
            }
            ClawTool.TASK_LIST -> executor.taskList()
            ClawTool.TASK_CANCEL -> {
                val taskId = arguments.string("task_id", "id")
                if (taskId.isBlank()) {
                    return ClawToolCallResult(
                        success = false,
                        output = "失败: task_id 不能为空",
                        error = "missing_task_id"
                    )
                }
                executor.taskCancel(taskId)
            }
            ClawTool.LIST_TOOLS -> {
                runCatching { capabilityProbe.refreshIfStale() }
                val tag = arguments.string("tag")
                val tierName = arguments.string("tier")
                val tier = ToolPermissionTier.entries.firstOrNull {
                    it.name.equals(tierName, ignoreCase = true)
                }
                val idPrefix = arguments.string("id_prefix")
                val includePlanned = arguments.bool("include_planned", default = false)
                val specs = ClawToolDefinitions.find(
                    tag = tag.ifBlank { null },
                    tier = tier,
                    idPrefix = idPrefix.ifBlank { null },
                    includePlanned = includePlanned
                ).filter {
                    ClawAssetPromptStore.isToolEnabled(appContext, it.id, default = true)
                }
                val lines = buildList {
                    specs.forEach { s ->
                        val availability = ClawToolCatalog.availabilityFor(s)
                        val flag = if (availability.available) "ok" else "unavailable"
                        add("- ${s.id} [$flag/${s.tier.name}/${s.risk.name}] ${s.summary}")
                    }
                    if (includePlanned) {
                        ClawAssetPromptStore.plannedBlueprints(appContext)
                            .filter { bp ->
                                (tag.isBlank() || bp.domain.equals(tag, true) ||
                                    bp.id.contains(tag, true)) &&
                                    (idPrefix.isBlank() || bp.id.startsWith(idPrefix, true)) &&
                                    (tierName.isBlank() || bp.tier.equals(tierName, true))
                            }
                            .forEach { bp ->
                                add("- ${bp.id} [planned/${bp.tier}/${bp.domain}] ${bp.summary}")
                            }
                    }
                }
                ClawToolCallResult(
                    success = true,
                    output = lines.joinToString("\n").ifBlank { "(empty)" }
                )
            }
            ClawTool.GET_TOOL -> {
                val toolId = arguments.string("tool_id", "id", "name")
                val json = ClawToolCatalog.describeTool(toolId, appContext)
                    ?: return ClawToolCallResult(false, "未知工具: $toolId", error = "unknown_tool")
                ClawToolCallResult(success = true, output = json.toString(2))
            }
            ClawTool.ASSIST_PING -> assistOrMissing { ctrl ->
                val result = ctrl.ping()
                ClawToolCallResult(
                    success = result.ok,
                    output = "ok=${result.ok} code=${result.errorCode} latencyMs=${result.latencyMs}\n${result.message}\n${result.raw.take(500)}",
                    error = if (result.ok) null else result.errorCode.name.lowercase()
                )
            }
            ClawTool.ASSIST_LIST_TOOLS -> assistOrMissing { ctrl ->
                val result = ctrl.listTools()
                val tools = com.clawdroid.app.mcp.assist.AssistMcpClient.toolsFromListResult(result.result)
                ClawToolCallResult(
                    success = result.ok,
                    output = if (result.ok) {
                        buildString {
                            appendLine("count=${tools.length()} latencyMs=${result.latencyMs}")
                            for (i in 0 until tools.length()) {
                                val t = tools.optJSONObject(i) ?: continue
                                appendLine("- ${t.optString("name")}: ${t.optString("description").take(120)}")
                            }
                        }
                    } else {
                        result.message
                    },
                    error = if (result.ok) null else result.errorCode.name.lowercase()
                )
            }
            ClawTool.ASSIST_CALL_TOOL -> assistOrMissing { ctrl ->
                val name = arguments.string("name", "tool", "tool_name")
                if (name.isBlank()) {
                    return@assistOrMissing ClawToolCallResult(false, "失败: name 不能为空", error = "missing_name")
                }
                val argsJson = arguments.string("arguments_json", "arguments")
                val argsObj = when {
                    argsJson.isBlank() -> JSONObject()
                    argsJson.trim().startsWith("{") -> runCatching { JSONObject(argsJson) }.getOrElse {
                        return@assistOrMissing ClawToolCallResult(false, "arguments_json 非法 JSON", error = "invalid_arguments")
                    }
                    else -> JSONObject()
                }
                val nested = arguments["arguments"]
                if (nested is Map<*, *>) {
                    nested.forEach { (k, v) -> if (k != null) argsObj.put(k.toString(), v) }
                }
                val correlation = com.clawdroid.app.mcp.assist.AssistMcpClient.correlationId()
                val result = ctrl.callTool(name, argsObj)
                ClawToolCallResult(
                    success = result.ok,
                    output = buildString {
                        appendLine("correlationId=$correlation")
                        appendLine("ok=${result.ok} code=${result.errorCode} latencyMs=${result.latencyMs}")
                        append(result.result?.toString(2) ?: result.message)
                    },
                    error = if (result.ok) null else result.errorCode.name.lowercase()
                )
            }
            ClawTool.ASSIST_STATUS -> assistOrMissing { ctrl ->
                ClawToolCallResult(success = true, output = ctrl.statusSnapshot().toString(2))
            }
            ClawTool.FILE_READ -> fileOrMissing { svc ->
                svc.read(
                    path = arguments.string("path"),
                    mode = arguments.string("mode").ifBlank { "bytes" },
                    offset = arguments.long("offset", default = 0L),
                    maxBytes = arguments.int("max_bytes", default = 65536),
                    lineStart = arguments.int("line_start", default = 1),
                    lineLimit = arguments.int("line_limit", default = 200),
                    delimiter = arguments.string("delimiter").ifBlank { "," },
                    column = arguments.int("column", default = 0)
                )
            }
            ClawTool.FILE_WRITE -> fileOrMissing { svc ->
                svc.write(
                    path = arguments.string("path"),
                    content = arguments.string("content"),
                    append = arguments.bool("append", default = false)
                )
            }
            ClawTool.FILE_REPLACE -> fileOrMissing { svc ->
                svc.replace(
                    path = arguments.string("path"),
                    find = arguments.string("find"),
                    replace = arguments.string("replace"),
                    regex = arguments.bool("regex", default = false),
                    lineStart = arguments.optionalInt("line_start"),
                    lineEnd = arguments.optionalInt("line_end")
                )
            }
            ClawTool.FILE_STAT -> fileOrMissing { svc ->
                svc.stat(
                    path = arguments.string("path"),
                    computeHash = arguments.bool("compute_hash", default = true)
                )
            }
            ClawTool.APP_LIST -> appOrMissing { svc ->
                svc.list(
                    query = arguments.string("query"),
                    limit = arguments.int("limit", default = 50)
                )
            }
            ClawTool.APP_LAUNCH -> appOrMissing { svc ->
                svc.launch(
                    packageName = arguments.string("package", "package_name"),
                    action = arguments.string("action"),
                    dataUri = arguments.string("data_uri", "data")
                )
            }
            ClawTool.APP_STOP -> appOrMissing { svc ->
                svc.stop(arguments.string("package", "package_name"))
            }
            ClawTool.APP_INFO -> appOrMissing { svc ->
                svc.info(arguments.string("package", "package_name"))
            }
            ClawTool.DOWNLOAD_START -> downloadOrMissing { svc ->
                svc.start(
                    url = arguments.string("url"),
                    destPath = arguments.string("dest_path").ifBlank { null },
                    expectedSha256 = arguments.string("expected_sha256", "sha256"),
                    resume = arguments.bool("resume", default = true),
                    threads = arguments.int("threads", default = 1)
                )
            }
            ClawTool.DOWNLOAD_STATUS -> downloadOrMissing { svc ->
                svc.status(arguments.string("download_id", "id"))
            }
            ClawTool.DOWNLOAD_CANCEL -> downloadOrMissing { svc ->
                svc.cancel(arguments.string("download_id", "id"))
            }
            ClawTool.DOWNLOAD_VERIFY -> downloadOrMissing { svc ->
                svc.verify(
                    path = arguments.string("path"),
                    expectedSha256 = arguments.string("expected_sha256", "sha256")
                )
            }
            ClawTool.NOTIFICATION_LIST -> notificationOrMissing { svc ->
                svc.list(
                    query = arguments.string("query"),
                    limit = arguments.int("limit", default = 50)
                )
            }
            ClawTool.WEB_PREVIEW -> webOrMissing { svc ->
                svc.preview(
                    url = arguments.string("url"),
                    maxBytes = arguments.int("max_bytes", default = 512_000),
                    includeImages = arguments.bool("include_images", default = true)
                )
            }
            ClawTool.WEB_SEARCH -> webSearchOrMissing { svc ->
                svc.search(
                    query = arguments.string("query", "q"),
                    maxResults = arguments.int("max_results", default = 5),
                    provider = arguments.string("provider").ifBlank { "auto" }
                )
            }
            ClawTool.SANDBOX_SHELL -> sandboxOrMissing { svc ->
                svc.exec(
                    command = arguments.string("command", "cmd"),
                    timeoutMs = arguments.long("timeout_ms", default = 8_000L)
                )
            }
            ClawTool.CAMERA_CAPTURE -> cameraOrMissing { svc ->
                svc.capture(
                    facing = arguments.string("facing", "lens").ifBlank { "back" },
                    maxDimension = arguments.int("max_dimension", default = 1280)
                )
            }
            ClawTool.SENSOR_READ -> sensorOrMissing { svc ->
                val op = arguments.string("op", "operation").ifBlank { "read" }.lowercase()
                when (op) {
                    "list" -> svc.list()
                    else -> svc.read(
                        typeAlias = arguments.string("type", "sensor", "name"),
                        durationMs = arguments.int("duration_ms", default = 0),
                        maxSamples = arguments.int("max_samples", default = 1)
                    )
                }
            }
            ClawTool.CAMERA_RECORD -> cameraRecordOrMissing { svc ->
                svc.record(
                    facing = arguments.string("facing", "lens").ifBlank { "back" },
                    durationMs = arguments.int("duration_ms", default = 3_000),
                    maxDimension = arguments.int("max_dimension", default = 1280)
                )
            }
            ClawTool.FTP_TRANSFER -> ftpOrMissing { svc ->
                val protocol = arguments.string("protocol").ifBlank { "ftp" }
                val defaultPort = if (protocol.equals("sftp", ignoreCase = true) ||
                    protocol.equals("ssh", ignoreCase = true)
                ) {
                    22
                } else {
                    21
                }
                svc.execute(
                    op = arguments.string("op", "operation"),
                    host = arguments.string("host"),
                    port = arguments.int("port", default = defaultPort),
                    user = arguments.string("user", "username").ifBlank { "anonymous" },
                    password = arguments.string("password", "pass"),
                    remotePath = arguments.string("remote_path", "remote").ifBlank { "/" },
                    localPath = arguments.string("local_path", "local", "path"),
                    passive = arguments.bool("passive", default = true),
                    timeoutMs = arguments.int("timeout_ms", default = 15_000),
                    protocol = protocol
                )
            }
            ClawTool.GPU_NPU_PROBE -> gpuOrMissing { svc -> svc.probe() }
            ClawTool.SHIZUKU_STATUS -> {
                val ctx = appContext
                    ?: return ClawToolCallResult(false, "缺少 Context", error = "context_missing")
                ClawToolCallResult(success = true, output = ShizukuSupport.statusSummary(ctx))
            }
            ClawTool.SHIZUKU_REQUEST -> {
                val result = ShizukuSupport.requestPermission()
                ClawToolCallResult(
                    success = result.isSuccess,
                    output = result.getOrElse { "失败: ${it.message}" },
                    error = if (result.isSuccess) null else result.exceptionOrNull()?.message
                )
            }
            ClawTool.SHIZUKU_EXEC -> {
                val command = arguments.string("command", "cmd")
                val result = ShizukuSupport.execShell(command)
                ClawToolCallResult(
                    success = result.isSuccess,
                    output = result.getOrElse { "失败: ${it.message}" },
                    error = if (result.isSuccess) null else result.exceptionOrNull()?.message,
                    shellOutput = result.getOrNull()
                )
            }
        }
    }

    private suspend fun assistOrMissing(
        block: suspend (AssistMcpController) -> ClawToolCallResult
    ): ClawToolCallResult {
        val ctrl = resolvedServices.assist
            ?: return ClawToolCallResult(false, "协助 MCP 未绑定", error = "assist_unavailable")
        return block(ctrl)
    }

    private suspend fun fileOrMissing(
        block: suspend (LocalFileToolService) -> ClawToolCallResult
    ): ClawToolCallResult {
        val svc = resolvedServices.files
            ?: return ClawToolCallResult(false, "文件工具未绑定", error = "file_unavailable")
        return block(svc)
    }

    private suspend fun appOrMissing(
        block: suspend (AppToolService) -> ClawToolCallResult
    ): ClawToolCallResult {
        val svc = resolvedServices.apps
            ?: return ClawToolCallResult(false, "应用工具未绑定", error = "app_unavailable")
        return block(svc)
    }

    private fun downloadOrMissing(
        block: (ToolDownloadService) -> ClawToolCallResult
    ): ClawToolCallResult {
        val svc = resolvedServices.downloads
            ?: return ClawToolCallResult(false, "下载工具未绑定", error = "download_unavailable")
        return block(svc)
    }

    private fun notificationOrMissing(
        block: (NotificationToolService) -> ClawToolCallResult
    ): ClawToolCallResult {
        val svc = resolvedServices.notifications
            ?: return ClawToolCallResult(false, "通知工具未绑定", error = "notification_unavailable")
        return block(svc)
    }

    private fun webOrMissing(
        block: (WebPreviewService) -> ClawToolCallResult
    ): ClawToolCallResult {
        val svc = resolvedServices.webPreview
            ?: return ClawToolCallResult(false, "网页预览未绑定", error = "web_unavailable")
        return block(svc)
    }

    private fun webSearchOrMissing(
        block: (WebSearchService) -> ClawToolCallResult
    ): ClawToolCallResult {
        val svc = resolvedServices.webSearch
            ?: return ClawToolCallResult(false, "网页搜索未绑定", error = "web_search_unavailable")
        return block(svc)
    }

    private fun sandboxOrMissing(
        block: (SandboxShellService) -> ClawToolCallResult
    ): ClawToolCallResult {
        val svc = resolvedServices.sandboxShell
            ?: return ClawToolCallResult(false, "沙箱 Shell 未绑定", error = "sandbox_unavailable")
        return block(svc)
    }

    private fun cameraOrMissing(
        block: (CameraCaptureService) -> ClawToolCallResult
    ): ClawToolCallResult {
        val svc = resolvedServices.camera
            ?: return ClawToolCallResult(false, "摄像头工具未绑定", error = "camera_unavailable")
        return block(svc)
    }

    private fun sensorOrMissing(
        block: (SensorReadService) -> ClawToolCallResult
    ): ClawToolCallResult {
        val svc = resolvedServices.sensors
            ?: return ClawToolCallResult(false, "传感器工具未绑定", error = "sensor_unavailable")
        return block(svc)
    }

    private fun cameraRecordOrMissing(
        block: (CameraRecordService) -> ClawToolCallResult
    ): ClawToolCallResult {
        val svc = resolvedServices.cameraRecord
            ?: return ClawToolCallResult(false, "录像工具未绑定", error = "camera_record_unavailable")
        return block(svc)
    }

    private fun ftpOrMissing(
        block: (FtpTransferService) -> ClawToolCallResult
    ): ClawToolCallResult {
        val svc = resolvedServices.ftp
            ?: return ClawToolCallResult(false, "FTP 工具未绑定", error = "ftp_unavailable")
        return block(svc)
    }

    private fun gpuOrMissing(
        block: (GpuNpuProbeService) -> ClawToolCallResult
    ): ClawToolCallResult {
        val svc = resolvedServices.gpuNpu
            ?: return ClawToolCallResult(false, "GPU/NPU 探测未绑定", error = "gpu_probe_unavailable")
        return block(svc)
    }

    private fun resolveTaskPayload(arguments: Map<String, Any?>): Map<String, Any?>? {
        val nested = arguments["task"]
        when (nested) {
            is Map<*, *> -> {
                return nested.entries.associate { (key, value) -> key.toString() to value }
            }
            is String -> {
                val trimmed = nested.trim()
                if (trimmed.startsWith("{")) {
                    return runCatching { JSONObject(trimmed).toAnyMap() }.getOrNull()
                }
            }
        }
        val taskJson = arguments.string("task_json")
        if (taskJson.startsWith("{")) {
            return runCatching { JSONObject(taskJson).toAnyMap() }.getOrNull()
        }
        val taskId = arguments.string("task_id").ifBlank {
            "app-task-${System.currentTimeMillis()}"
        }
        val stepsJson = arguments.string("steps_json", "steps")
        if (!stepsJson.startsWith("[")) {
            return null
        }
        val steps = runCatching {
            val array = JSONArray(stepsJson)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(item.toAnyMap())
                }
            }
        }.getOrNull() ?: return null
        if (steps.isEmpty()) {
            return null
        }
        return linkedMapOf(
            "task_id" to taskId,
            "name" to arguments.string("name"),
            "steps" to steps
        ).filterValues { value ->
            value !is String || value.isNotBlank()
        }
    }

    private fun JSONObject.toAnyMap(): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            result[key] = normalizeJsonValue(opt(key))
        }
        return result
    }

    private fun normalizeJsonValue(value: Any?): Any? {
        return when (value) {
            is JSONObject -> value.toAnyMap()
            is JSONArray -> buildList {
                for (index in 0 until value.length()) {
                    add(normalizeJsonValue(value.opt(index)))
                }
            }
            JSONObject.NULL -> null
            else -> value
        }
    }

    private fun Map<String, Any?>.string(vararg keys: String): String {
        for (key in keys) {
            val value = this[key] ?: continue
            val text = value.toString().trim()
            if (text.isNotEmpty() && text != "null") {
                return text
            }
        }
        return ""
    }

    private fun Map<String, Any?>.int(key: String, default: Int): Int {
        return optionalInt(key) ?: default
    }

    private fun Map<String, Any?>.optionalInt(key: String): Int? {
        val value = this[key] ?: return null
        return when (value) {
            is Number -> value.toInt()
            else -> value.toString().trim().toIntOrNull()
        }
    }

    private fun Map<String, Any?>.long(key: String, default: Long): Long {
        val value = this[key] ?: return default
        return when (value) {
            is Number -> value.toLong()
            else -> value.toString().trim().toLongOrNull() ?: default
        }
    }

    private fun Map<String, Any?>.bool(key: String, default: Boolean): Boolean {
        val value = this[key] ?: return default
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value.toString().trim().equals("true", ignoreCase = true)
        }
    }
}
