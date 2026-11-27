package com.clawdroid.app.tools.adapters

import com.clawdroid.app.agent.task.ExecutionStep
import com.clawdroid.app.agent.task.TaskFailure
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolDispatcher
import org.json.JSONObject

class LocalToolAdapter(
    private val dispatcher: ClawToolDispatcher
) : TaskStepAdapter {
    override suspend fun execute(step: ExecutionStep, context: TaskExecutionContext): TaskStepResult {
        val payload = step.payload.trim()
        val toolId: String
        val arguments: Map<String, Any?>
        if (payload.startsWith("{")) {
            val json = runCatching { JSONObject(payload) }.getOrNull()
                ?: return TaskStepResult(
                    success = false,
                    summary = "工具步骤 payload 不是有效 JSON",
                    failure = TaskFailure(
                        code = "invalid_payload",
                        summary = "工具步骤参数无效",
                        detail = payload,
                        source = "local_tool"
                    )
                )
            toolId = json.optString("tool")
            arguments = json.optJSONObject("arguments")?.let { args ->
                buildMap {
                    val keys = args.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, args.opt(key))
                    }
                }
            }.orEmpty()
        } else {
            toolId = payload
            arguments = emptyMap()
        }
        val tool = ClawTool.byToolId(toolId)
            ?: return TaskStepResult(
                success = false,
                summary = "未知工具: $toolId",
                failure = TaskFailure(
                    code = "unknown_tool",
                    summary = "未知工具",
                    detail = payload,
                    source = "local_tool"
                )
            )
        val result = dispatcher.execute(tool, arguments)
        return TaskStepResult(
            success = result.success,
            summary = result.output,
            failure = if (result.success) {
                null
            } else {
                TaskFailure(
                    code = result.error ?: "tool_failed",
                    summary = result.output,
                    detail = result.output,
                    source = tool.toolId
                )
            },
            runtimeTaskId = result.runtimeTaskId
        )
    }
}
