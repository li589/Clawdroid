package com.clawdroid.app.tools.adapters

import com.clawdroid.app.agent.task.ExecutionStep
import com.clawdroid.app.agent.task.TaskFailure
import com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolDispatcher
import org.json.JSONArray
import org.json.JSONObject

class RuntimeTaskAdapter(
    private val dispatcher: ClawToolDispatcher
) : TaskStepAdapter, TaskCancellationAdapter, TaskResyncAdapter {
    override suspend fun execute(step: ExecutionStep, context: TaskExecutionContext): TaskStepResult {
        val payload = step.payload.trim()
        if (payload.isBlank()) {
            return TaskStepResult(
                success = false,
                summary = "Runtime 任务步骤缺少 action",
                failure = TaskFailure("invalid_runtime_step", "Runtime 任务步骤缺少 action", source = "runtime_task")
            )
        }
        val taskId = "${context.taskId}:${step.id}"
        val result = dispatcher.execute(
            ClawTool.TASK_SUBMIT,
            mapOf(
                "task_id" to taskId,
                "name" to step.title,
                "steps_json" to normalizeStepsJson(payload)
            )
        )
        if (!result.success) {
            return TaskStepResult(
                success = false,
                summary = result.output,
                failure = TaskFailure(
                    code = result.error ?: "task_submit_failed",
                    summary = result.output,
                    detail = result.output,
                    source = "runtime_task"
                )
            )
        }
        val runtimeTaskId = result.runtimeTaskId ?: taskId
        return TaskStepResult(
            success = false,
            summary = result.output,
            runtimeTaskId = runtimeTaskId,
            waitReason = "runtime_task"
        )
    }

    override suspend fun cancel(taskId: String): TaskStepResult {
        val result = dispatcher.execute(ClawTool.TASK_CANCEL, mapOf("task_id" to taskId))
        return TaskStepResult(
            success = result.success,
            summary = result.output,
            failure = if (result.success) null else TaskFailure(
                code = result.error ?: "task_cancel_failed",
                summary = result.output,
                detail = result.output,
                source = "runtime_task"
            )
        )
    }

    override suspend fun resync(taskId: String): ClawRuntimeTaskSnapshot? {
        val get = dispatcher.execute(ClawTool.TASK_GET, mapOf("task_id" to taskId))
        get.taskSnapshot?.takeIf { get.success }?.let { return it }
        val list = dispatcher.execute(ClawTool.TASK_LIST)
        return list.taskSnapshots?.firstOrNull { it.taskId == taskId }?.takeIf { list.success }
    }

    private fun normalizeStepsJson(payload: String): String = runCatching {
        JSONArray(payload)
        payload
    }.getOrElse {
        JSONArray().put(
            JSONObject().apply {
                put("action", payload)
                put("args", JSONObject())
            }
        ).toString()
    }
}
