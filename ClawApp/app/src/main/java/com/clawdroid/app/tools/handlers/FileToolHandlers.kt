package com.clawdroid.app.tools.handlers

import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.InputGuards
import com.clawdroid.app.tools.ToolHandler
import com.clawdroid.app.tools.ToolServiceRegistry

fun fileToolHandlers(services: ToolServiceRegistry): Map<ClawTool, ToolHandler> = mapOf(
    ClawTool.FILE_READ to ToolHandler { _, arguments ->
        InputGuards.validatePath(arguments.string("path"))?.let { err ->
            return@ToolHandler InputGuards.toToolResult(err)
        }
        services.fileOrMissing { svc ->
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
    },
    ClawTool.FILE_WRITE to ToolHandler { _, arguments ->
        InputGuards.validatePath(arguments.string("path"))?.let { err ->
            return@ToolHandler InputGuards.toToolResult(err)
        }
        InputGuards.validateFileWriteContent(arguments.string("content"))?.let { err ->
            return@ToolHandler InputGuards.toToolResult(err)
        }
        services.fileOrMissing { svc ->
            svc.write(
                path = arguments.string("path"),
                content = arguments.string("content"),
                append = arguments.bool("append", default = false)
            )
        }
    },
    ClawTool.FILE_REPLACE to ToolHandler { _, arguments ->
        InputGuards.validatePath(arguments.string("path"))?.let { err ->
            return@ToolHandler InputGuards.toToolResult(err)
        }
        InputGuards.validateReplaceFind(arguments.string("find"))?.let { err ->
            return@ToolHandler InputGuards.toToolResult(err)
        }
        InputGuards.validateFileWriteContent(arguments.string("replace"))?.let { err ->
            return@ToolHandler InputGuards.toToolResult(err)
        }
        services.fileOrMissing { svc ->
            svc.replace(
                path = arguments.string("path"),
                find = arguments.string("find"),
                replace = arguments.string("replace"),
                regex = arguments.bool("regex", default = false),
                lineStart = arguments.optionalInt("line_start"),
                lineEnd = arguments.optionalInt("line_end")
            )
        }
    },
    ClawTool.FILE_STAT to ToolHandler { _, arguments ->
        InputGuards.validatePath(arguments.string("path"))?.let { err ->
            return@ToolHandler InputGuards.toToolResult(err)
        }
        services.fileOrMissing { svc ->
            svc.stat(
                path = arguments.string("path"),
                computeHash = arguments.bool("compute_hash", default = true)
            )
        }
    },
    ClawTool.FILE_LIST to ToolHandler { _, arguments ->
        InputGuards.validatePath(arguments.string("path"))?.let { err ->
            return@ToolHandler InputGuards.toToolResult(err)
        }
        services.fileOrMissing { svc ->
            svc.listDir(
                path = arguments.string("path"),
                offset = arguments.int("offset", default = 0),
                limit = arguments.int("limit", default = 100)
            )
        }
    }
)
