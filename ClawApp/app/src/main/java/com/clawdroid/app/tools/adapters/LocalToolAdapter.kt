package com.clawdroid.app.tools.adapters

import com.clawdroid.app.agent.task.ExecutionStep
import com.clawdroid.app.agent.task.TaskFailure
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolDispatcher

class LocalToolAdapter(
    private val dispatcher: ClawToolDispatcher
) : TaskStepAdapter {
    override suspend fun execute(step: ExecutionStep, context: TaskExecutionContext): TaskStepResult {
        val tool = ClawTool.byToolId(step.payload.trim())
            ?: return TaskStepResult(
                success = false,
                summary = "未知工具: ${step.payload}",
                failure = TaskFailure(
                    code = "unknown_tool",
                    summary = "未知工具",
                    detail = step.payload,
                    source = "local_tool"
                )
            )
        val result = dispatcher.execute(tool)
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
