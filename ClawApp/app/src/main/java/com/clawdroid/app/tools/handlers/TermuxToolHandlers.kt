package com.clawdroid.app.tools.handlers

import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.InputGuards
import com.clawdroid.app.tools.ToolHandler
import com.clawdroid.app.tools.ToolServiceRegistry

fun termuxToolHandlers(services: ToolServiceRegistry): Map<ClawTool, ToolHandler> = mapOf(
    ClawTool.TERMUX_EXEC to ToolHandler { _, arguments ->
        // Termux runs in its own UID sandbox with executable path confinement;
        // guard against null bytes / control chars / unbounded length (DoS),
        // but do NOT apply the Runtime restricted shell whitelist here.
        val command = arguments.string("command", "cmd")
        InputGuards.rejectNullOrControl(command, "command")?.let { err ->
            return@ToolHandler InputGuards.toToolResult(err)
        }
        if (command.length > InputGuards.MAX_TOOL_STRING_CHARS) {
            return@ToolHandler ClawToolCallResult(
                success = false,
                output = "失败: command 过长（最多 ${InputGuards.MAX_TOOL_STRING_CHARS} 字符）",
                error = "command_too_long"
            )
        }
        services.termuxOrMissing { bridge ->
            bridge.exec(
                command = command,
                workdir = arguments.string("workdir").ifBlank { null },
                timeoutMs = arguments.long("timeout_ms", default = 30_000L)
            )
        }
    }
)
