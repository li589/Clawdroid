package com.clawdroid.app.tools

/**
 * Whether a tool must run under the dispatcher serialize mutex
 * (device mutation, shared capture state, or agent nesting parent).
 */
internal object ToolExecutionPolicy {
    fun requiresSerialization(tool: ClawTool): Boolean {
        if (tool in ALWAYS_SERIALIZED) return true
        val spec = ClawToolDefinitions.spec(tool)
        return when {
            spec.risk == ToolRisk.Destructive -> true
            spec.risk == ToolRisk.Write &&
                (spec.backend == ToolBackend.Runtime || spec.backend == ToolBackend.Hybrid) -> true
            else -> false
        }
    }

    private val ALWAYS_SERIALIZED = setOf(
        ClawTool.RUN_AGENT,
        ClawTool.CAPTURE_SCREEN,
        ClawTool.READ_LATEST_CAPTURE,
        ClawTool.SAFE_TAP,
        ClawTool.PAGE_CONFIRM,
        ClawTool.CLICK_PRECHECK,
        ClawTool.SUBSCRIBE_EVENTS,
        ClawTool.TASK_SUBMIT,
        ClawTool.TASK_CANCEL,
        ClawTool.FILE_WRITE,
        ClawTool.FILE_REPLACE,
        ClawTool.APP_LAUNCH,
        ClawTool.APP_STOP,
        ClawTool.SANDBOX_SHELL,
        ClawTool.CAMERA_CAPTURE,
        ClawTool.CAMERA_RECORD,
        ClawTool.FTP_TRANSFER,
        ClawTool.SHIZUKU_EXEC,
        ClawTool.SHIZUKU_REQUEST
    )
}
