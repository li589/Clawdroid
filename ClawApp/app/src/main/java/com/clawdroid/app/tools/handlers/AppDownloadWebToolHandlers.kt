package com.clawdroid.app.tools.handlers

import android.content.Context
import com.clawdroid.app.env.ShizukuSupport
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.InputGuards
import com.clawdroid.app.tools.ToolHandler
import com.clawdroid.app.tools.ToolServiceRegistry

fun appDownloadWebToolHandlers(
    services: ToolServiceRegistry,
    appContext: Context?
): Map<ClawTool, ToolHandler> = mapOf(
    ClawTool.APP_LIST to ToolHandler { _, arguments ->
        services.appOrMissing { svc ->
            svc.list(
                query = arguments.string("query"),
                limit = arguments.int("limit", default = 50)
            )
        }
    },
    ClawTool.APP_LAUNCH to ToolHandler { _, arguments ->
        services.appOrMissing { svc ->
            svc.launch(
                packageName = arguments.string("package", "package_name"),
                action = arguments.string("action"),
                dataUri = arguments.string("data_uri", "data")
            )
        }
    },
    ClawTool.APP_STOP to ToolHandler { _, arguments ->
        services.appOrMissing { svc ->
            svc.stop(arguments.string("package", "package_name"))
        }
    },
    ClawTool.APP_INFO to ToolHandler { _, arguments ->
        services.appOrMissing { svc ->
            svc.info(arguments.string("package", "package_name"))
        }
    },
    ClawTool.DOWNLOAD_START to ToolHandler { _, arguments ->
        services.downloadOrMissing { svc ->
            svc.start(
                url = arguments.string("url"),
                destPath = arguments.string("dest_path").ifBlank { null },
                expectedSha256 = arguments.string("expected_sha256", "sha256"),
                resume = arguments.bool("resume", default = true),
                threads = arguments.int("threads", default = 1)
            )
        }
    },
    ClawTool.DOWNLOAD_STATUS to ToolHandler { _, arguments ->
        services.downloadOrMissing { svc ->
            svc.status(arguments.string("download_id", "id"))
        }
    },
    ClawTool.DOWNLOAD_CANCEL to ToolHandler { _, arguments ->
        services.downloadOrMissing { svc ->
            svc.cancel(arguments.string("download_id", "id"))
        }
    },
    ClawTool.DOWNLOAD_VERIFY to ToolHandler { _, arguments ->
        services.downloadOrMissing { svc ->
            svc.verify(
                path = arguments.string("path"),
                expectedSha256 = arguments.string("expected_sha256", "sha256")
            )
        }
    },
    ClawTool.NOTIFICATION_LIST to ToolHandler { _, arguments ->
        services.notificationOrMissing { svc ->
            svc.list(
                query = arguments.string("query"),
                limit = arguments.int("limit", default = 50)
            )
        }
    },
    ClawTool.WEB_PREVIEW to ToolHandler { _, arguments ->
        services.webOrMissing { svc ->
            svc.preview(
                url = arguments.string("url"),
                maxBytes = arguments.int("max_bytes", default = 512_000),
                includeImages = arguments.bool("include_images", default = true)
            )
        }
    },
    ClawTool.WEB_SEARCH to ToolHandler { _, arguments ->
        services.webSearchOrMissing { svc ->
            svc.search(
                query = arguments.string("query", "q"),
                maxResults = arguments.int("max_results", default = 5),
                provider = arguments.string("provider").ifBlank { "auto" }
            )
        }
    },
    ClawTool.SANDBOX_SHELL to ToolHandler { _, arguments ->
        val command = arguments.string("command", "cmd")
        InputGuards.rejectNullOrControl(command, "command")?.let { err ->
            return@ToolHandler InputGuards.toToolResult(err)
        }
        if (command.length > InputGuards.MAX_SHELL_COMMAND_CHARS) {
            return@ToolHandler ClawToolCallResult(
                success = false,
                output = "失败: command 过长（最多 ${InputGuards.MAX_SHELL_COMMAND_CHARS} 字符）",
                error = "command_too_long"
            )
        }
        services.sandboxOrMissing { svc ->
            svc.exec(
                command = command,
                timeoutMs = arguments.long("timeout_ms", default = 8_000L)
            )
        }
    },
    ClawTool.CAMERA_CAPTURE to ToolHandler { _, arguments ->
        services.cameraOrMissing { svc ->
            svc.capture(
                facing = arguments.string("facing", "lens").ifBlank { "back" },
                maxDimension = arguments.int("max_dimension", default = 1280)
            )
        }
    },
    ClawTool.SENSOR_READ to ToolHandler { _, arguments ->
        services.sensorOrMissing { svc ->
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
    },
    ClawTool.CAMERA_RECORD to ToolHandler { _, arguments ->
        services.cameraRecordOrMissing { svc ->
            svc.record(
                facing = arguments.string("facing", "lens").ifBlank { "back" },
                durationMs = arguments.int("duration_ms", default = 3_000),
                maxDimension = arguments.int("max_dimension", default = 1280)
            )
        }
    },
    ClawTool.FTP_TRANSFER to ToolHandler { _, arguments ->
        services.ftpOrMissing { svc ->
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
    },
    ClawTool.GPU_NPU_PROBE to ToolHandler { _, _ ->
        services.gpuOrMissing { svc -> svc.probe() }
    },
    ClawTool.SHIZUKU_STATUS to ToolHandler { _, _ ->
        val ctx = appContext
            ?: return@ToolHandler ClawToolCallResult(false, "缺少 Context", error = "context_missing")
        ClawToolCallResult(success = true, output = ShizukuSupport.statusSummary(ctx))
    },
    ClawTool.SHIZUKU_REQUEST to ToolHandler { _, _ ->
        val result = ShizukuSupport.requestPermission()
        ClawToolCallResult(
            success = result.isSuccess,
            output = result.getOrElse { "失败: ${it.message}" },
            error = if (result.isSuccess) null else result.exceptionOrNull()?.message
        )
    },
    ClawTool.SHIZUKU_EXEC to ToolHandler { _, arguments ->
        val command = InputGuards.normalizeShellCommand(arguments.string("command", "cmd"))
        InputGuards.validateShellCommand(command)?.let { err ->
            return@ToolHandler InputGuards.toToolResult(err)
        }
        val result = ShizukuSupport.execShell(command)
        ClawToolCallResult(
            success = result.isSuccess,
            output = result.getOrElse { "失败: ${it.message}" },
            error = if (result.isSuccess) null else result.exceptionOrNull()?.message,
            shellOutput = result.getOrNull()
        )
    }
)
