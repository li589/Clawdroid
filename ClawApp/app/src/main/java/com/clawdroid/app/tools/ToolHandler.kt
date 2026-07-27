package com.clawdroid.app.tools

/**
 * Single-tool execution contract used by [ClawToolDispatcher]'s handler registry.
 */
fun interface ToolHandler {
    suspend fun execute(tool: ClawTool, arguments: Map<String, Any?>): ClawToolCallResult
}
