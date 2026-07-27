package com.clawdroid.app.tools.handlers

import com.clawdroid.app.tools.ClawCaptureArtifact
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolDispatcher
import com.clawdroid.app.tools.ClawToolExecutor
import com.clawdroid.app.tools.InputGuards
import com.clawdroid.app.tools.ToolHandler

fun deviceToolHandlers(
    executor: ClawToolExecutor,
    eventBridge: ClawToolDispatcher.EventBridge?,
    previewLimitBytes: Int,
    peekLastCapture: () -> ClawCaptureArtifact?,
    rememberCapture: (ClawCaptureArtifact?) -> Unit,
    dispatcher: ClawToolDispatcher
): Map<ClawTool, ToolHandler> = mapOf(
    ClawTool.PAGE_CONFIRM to ToolHandler { _, arguments ->
        executor.confirmPage(
            expectedPackage = arguments.string("expected_package", "package", "expectedPackage"),
            expectedText = arguments.string("expected_text", "text", "expectedText"),
            expectedViewId = arguments.string("expected_view_id", "view_id", "expectedViewId")
        )
    },
    ClawTool.CLICK_PRECHECK to ToolHandler { _, arguments ->
        executor.precheckClickTarget(
            expectedPackage = arguments.string("expected_package", "package", "expectedPackage"),
            targetText = arguments.string("target_text", "text", "targetText"),
            targetViewId = arguments.string("target_view_id", "view_id", "targetViewId")
        )
    },
    ClawTool.SAFE_TAP to ToolHandler { _, _ ->
        executor.safeTapUsingResolvedTarget()
    },
    ClawTool.CAPTURE_SCREEN to ToolHandler { _, arguments ->
        val withPreview = arguments.bool("read_after_capture", default = false)
        val capture = executor.captureScreen(includeShaPreview = true)
        if (capture.success) {
            rememberCapture(capture.captureArtifact)
        }
        if (!withPreview || !capture.success || capture.captureArtifact == null) {
            return@ToolHandler capture
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
    },
    ClawTool.READ_LATEST_CAPTURE to ToolHandler { _, _ ->
        val artifact = peekLastCapture()
            ?: return@ToolHandler ClawToolCallResult(
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
    },
    ClawTool.READ_FILE_LIMITED to ToolHandler { _, arguments ->
        val path = arguments.string("path")
        InputGuards.validatePath(path)?.let { err ->
            return@ToolHandler InputGuards.toToolResult(err)
        }
        executor.readFileLimited(
            path = path,
            offset = arguments.long("offset", default = 0L),
            maxBytes = arguments.int("max_bytes", default = 65536)
        )
    },
    ClawTool.INJECT_TAP to ToolHandler { _, arguments ->
        executor.injectTap(
            x = arguments.int("x", default = 540),
            y = arguments.int("y", default = 1200),
            displayId = arguments.int("display_id", default = 0)
        )
    },
    ClawTool.INJECT_KEYEVENT to ToolHandler { _, arguments ->
        val keyCode = arguments.optionalInt("keycode")
        val key = arguments.string("key").ifBlank { if (keyCode == null) "BACK" else "" }
        executor.injectKeyevent(
            key = key.takeIf { it.isNotBlank() && keyCode == null },
            keyCode = keyCode,
            displayId = arguments.int("display_id", default = 0)
        )
    },
    ClawTool.INJECT_SWIPE to ToolHandler { _, arguments ->
        executor.injectSwipe(
            x1 = arguments.int("x1", default = 540),
            y1 = arguments.int("y1", default = 1800),
            x2 = arguments.int("x2", default = 540),
            y2 = arguments.int("y2", default = 400),
            durationMs = arguments.int("duration_ms", default = 350),
            displayId = arguments.int("display_id", default = 0)
        )
    },
    ClawTool.EXECUTE_SHELL_LIMITED to ToolHandler { _, arguments ->
        val command = InputGuards.normalizeShellCommand(arguments.string("command"))
        InputGuards.validateShellCommand(command)?.let { err ->
            return@ToolHandler InputGuards.toToolResult(err)
        }
        executor.execShellLimited(command = command)
    },
    ClawTool.SUBSCRIBE_EVENTS to ToolHandler { _, arguments ->
        val operation = arguments.string("operation").ifBlank { "start" }.lowercase()
        val bridge = eventBridge
            ?: return@ToolHandler ClawToolCallResult(
                success = false,
                output = "失败: 当前未绑定事件桥接，无法在 MCP 中订阅事件流",
                error = "events_unavailable"
            )
        bridge.handle(operation)
    },
    ClawTool.TASK_SUBMIT to ToolHandler { _, arguments ->
        val task = resolveTaskPayload(arguments)
            ?: return@ToolHandler ClawToolCallResult(
                success = false,
                output = "失败: 需要 task 对象、task_json，或 task_id + steps_json",
                error = "missing_task"
            )
        executor.taskSubmit(task)
    },
    ClawTool.TASK_GET to ToolHandler { _, arguments ->
        val taskId = arguments.string("task_id", "id")
        if (taskId.isBlank()) {
            return@ToolHandler ClawToolCallResult(
                success = false,
                output = "失败: task_id 不能为空",
                error = "missing_task_id"
            )
        }
        executor.taskGet(taskId)
    },
    ClawTool.TASK_LIST to ToolHandler { _, _ ->
        executor.taskList()
    },
    ClawTool.TASK_CANCEL to ToolHandler { _, arguments ->
        val taskId = arguments.string("task_id", "id")
        if (taskId.isBlank()) {
            return@ToolHandler ClawToolCallResult(
                success = false,
                output = "失败: task_id 不能为空",
                error = "missing_task_id"
            )
        }
        executor.taskCancel(taskId)
    },
    ClawTool.TASK_WAIT to ToolHandler { _, arguments ->
        val taskId = arguments.string("task_id", "id")
        if (taskId.isBlank()) {
            return@ToolHandler ClawToolCallResult(
                success = false,
                output = "失败: task_id 不能为空",
                error = "missing_task_id"
            )
        }
        InputGuards.rejectNullOrControl(taskId, "task_id")?.let { err ->
            return@ToolHandler ClawToolCallResult(
                success = false,
                output = err.message,
                error = err.code
            )
        }
        if (!taskId.matches(Regex("""^[A-Za-z0-9_.:-]+$"""))) {
            return@ToolHandler ClawToolCallResult(
                success = false,
                output = "失败: task_id 含非法字符",
                error = "invalid_task_id"
            )
        }
        val timeoutMs = arguments.long("timeout_ms", default = 180_000L)
            .coerceIn(1_000L, 600_000L)
        val awaited = com.clawdroid.app.skills.RuntimeTaskPoller.awaitTerminal(
            dispatcher = dispatcher,
            taskId = taskId,
            timeoutMs = timeoutMs
        )
        val result = com.clawdroid.app.skills.RuntimeTaskPoller.toToolResult(awaited, taskId)
        // Detach is soft: keep tracking via events; do not present as a hard tool failure.
        if (awaited.detached) {
            result.copy(
                success = true,
                output = buildString {
                    appendLine("detached=true")
                    append(awaited.output)
                }
            )
        } else {
            result
        }
    }
)
