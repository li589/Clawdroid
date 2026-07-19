package com.clawdroid.app.tools

/**
 * Serialization lane for tool execution.
 * Lanes are independent so a long [Agent] poll does not HOL-block [None]/[Capture] reads.
 */
enum class ToolSerializeLane {
    None,
    DeviceMutate,
    Capture,
    Agent
}

/**
 * Whether a tool must run under a dispatcher serialize mutex
 * (device mutation, shared capture state, or agent nesting parent).
 * Derived primarily from [ClawToolDefinitions] risk/backend.
 */
internal object ToolExecutionPolicy {
    fun serializeLane(tool: ClawTool): ToolSerializeLane {
        when (tool) {
            ClawTool.RUN_AGENT -> return ToolSerializeLane.Agent
            ClawTool.CAPTURE_SCREEN,
            ClawTool.READ_LATEST_CAPTURE,
            ClawTool.CAMERA_CAPTURE,
            ClawTool.CAMERA_RECORD -> return ToolSerializeLane.Capture
            else -> Unit
        }
        val spec = ClawToolDefinitions.spec(tool)
        return when {
            spec.risk == ToolRisk.Destructive -> ToolSerializeLane.DeviceMutate
            spec.risk == ToolRisk.Write &&
                (spec.backend == ToolBackend.Runtime || spec.backend == ToolBackend.Hybrid) ->
                ToolSerializeLane.DeviceMutate
            tool == ClawTool.SUBSCRIBE_EVENTS ||
                tool == ClawTool.TASK_SUBMIT ||
                tool == ClawTool.TASK_CANCEL ||
                tool == ClawTool.SANDBOX_SHELL ||
                tool == ClawTool.FTP_TRANSFER ||
                tool == ClawTool.SHIZUKU_EXEC ||
                tool == ClawTool.SHIZUKU_REQUEST ||
                // APP_LAUNCH 在旧 ALWAYS_SERIALIZED 中被显式序列化；按 risk/backend
                // 归类（Write+Local）会落到 None，与同类的 SANDBOX_SHELL 行为不一致，
                // 此处显式补回 DeviceMutate lane 以避免并发拉起破坏设备状态快照。
                tool == ClawTool.APP_LAUNCH -> ToolSerializeLane.DeviceMutate
            else -> ToolSerializeLane.None
        }
    }

    fun requiresSerialization(tool: ClawTool): Boolean =
        serializeLane(tool) != ToolSerializeLane.None
}
