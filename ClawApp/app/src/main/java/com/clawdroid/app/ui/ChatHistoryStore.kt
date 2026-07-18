package com.clawdroid.app.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object ChatHistoryStore {
    private const val prefsName = "clawdroid_chat_history"
    private const val keyMessages = "messages"
    private const val maxMessages = 60

    suspend fun load(context: Context): List<ChatMessage> = withContext(Dispatchers.IO) {
        val raw = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(keyMessages, null)
            ?: return@withContext emptyList()

        runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        ChatMessage(
                            id = item.optString("id").ifBlank { newChatMessageId() },
                            role = ChatRole.valueOf(item.optString("role", ChatRole.Assistant.name)),
                            content = item.optString("content"),
                            attachmentLabel = item.optString("attachment_label").ifBlank { null },
                            createdAtEpochMs = item.optLong("created_at_epoch_ms", System.currentTimeMillis()),
                            state = item.optString("state", ChatMessageState.Final.name)
                                .let { state ->
                                    ChatMessageState.entries.firstOrNull { it.name == state }
                                        ?: ChatMessageState.Final
                                }
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    fun save(context: Context, messages: List<ChatMessage>) {
        val payload = JSONArray()
        messages.takeLast(maxMessages).forEach { message ->
            payload.put(
                JSONObject().apply {
                    put("id", message.id)
                    put("role", message.role.name)
                    put("content", message.content)
                    put("attachment_label", message.attachmentLabel ?: "")
                    put("created_at_epoch_ms", message.createdAtEpochMs)
                    put("state", message.state.name)
                }
            )
        }
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(keyMessages, payload.toString())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .remove(keyMessages)
            .apply()
    }
}

internal object ChatTaskHistoryStore {
    private const val prefsName = "clawdroid_task_history"
    private const val keyTasks = "tasks"
    private const val keyCurrentTask = "current_task"
    private const val maxTasks = 20

    fun load(context: Context): PersistedChatTaskState {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val rawTasks = prefs.getString(keyTasks, null)
        val rawCurrentTask = prefs.getString(keyCurrentTask, null)
        val taskHistory = rawTasks?.let(::decodeTaskList).orEmpty()
        val currentTask = rawCurrentTask?.let(::decodeTaskFromString)
        return PersistedChatTaskState(
            currentTask = currentTask,
            taskHistory = taskHistory
        )
    }

    fun save(
        context: Context,
        currentTask: ChatTaskExecutionState?,
        taskHistory: List<ChatTaskExecutionState>
    ) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(keyTasks, encodeTaskList(taskHistory.take(maxTasks)))
            if (currentTask == null) {
                remove(keyCurrentTask)
            } else {
                putString(keyCurrentTask, encodeTask(currentTask).toString())
            }
        }.apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .remove(keyTasks)
            .remove(keyCurrentTask)
            .apply()
    }

    private fun decodeTaskList(raw: String): List<ChatTaskExecutionState> {
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    decodeTask(array.optJSONObject(index))?.let(::add)
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun decodeTaskFromString(raw: String): ChatTaskExecutionState? {
        return runCatching {
            decodeTask(JSONObject(raw))
        }.getOrNull()
    }

    private fun encodeTaskList(tasks: List<ChatTaskExecutionState>): String {
        val payload = JSONArray()
        tasks.forEach { task ->
            payload.put(encodeTask(task))
        }
        return payload.toString()
    }

    private fun decodeTask(item: JSONObject?): ChatTaskExecutionState? {
        item ?: return null
        val stepsJson = item.optJSONArray("steps") ?: JSONArray()
        val steps = buildList {
            for (sIdx in 0 until stepsJson.length()) {
                val stepItem = stepsJson.optJSONObject(sIdx) ?: continue
                add(
                    ChatTaskStepState(
                        title = stepItem.optString("title"),
                        status = ChatTaskProgressState.entries.firstOrNull {
                            it.name == stepItem.optString("status", ChatTaskProgressState.Pending.name)
                        } ?: ChatTaskProgressState.Pending,
                        detail = stepItem.optString("detail", "等待执行"),
                        startedAtEpochMs = stepItem.optLong("started_at_epoch_ms", 0L),
                        finishedAtEpochMs = stepItem.optLong("finished_at_epoch_ms", 0L)
                    )
                )
            }
        }
        return ChatTaskExecutionState(
            taskId = item.optString("task_id"),
            title = item.optString("title"),
            summary = item.optString("summary"),
            status = ChatTaskProgressState.entries.firstOrNull {
                it.name == item.optString("status", ChatTaskProgressState.Pending.name)
            } ?: ChatTaskProgressState.Pending,
            steps = steps,
            startedAtEpochMs = item.optLong("started_at_epoch_ms", 0L),
            finishedAtEpochMs = item.optLong("finished_at_epoch_ms", 0L),
            taskAction = item.optString("task_action")
                .takeIf { it.isNotBlank() }
                ?.let { actionName ->
                    com.clawdroid.app.chat.ChatTaskAction.entries.firstOrNull { it.name == actionName }
                },
            runtimeTaskId = item.optString("runtime_task_id").takeIf { it.isNotBlank() },
            failureReason = item.optString("failure_reason").takeIf { it.isNotBlank() },
            originPrompt = item.optString("origin_prompt"),
            retryCount = item.optInt("retry_count", 0),
            retryFromTaskId = item.optString("retry_from_task_id").takeIf { it.isNotBlank() },
            failure = item.optJSONObject("failure")?.let { failureItem ->
                val code = failureItem.optString("code")
                val summary = failureItem.optString("summary")
                val rawDetail = failureItem.optString("raw_detail")
                if (code.isBlank() && summary.isBlank() && rawDetail.isBlank()) {
                    null
                } else {
                    ChatTaskFailureState(
                        code = code,
                        summary = summary,
                        rawDetail = rawDetail
                    )
                }
            } ?: item.optString("failure_reason").takeIf { it.isNotBlank() }?.let {
                ChatTaskFailureState(
                    code = "legacy_failure",
                    summary = it,
                    rawDetail = it
                )
            }
        )
    }

    private fun encodeTask(task: ChatTaskExecutionState): JSONObject {
        val stepsJson = JSONArray()
        task.steps.forEach { step ->
            stepsJson.put(
                JSONObject().apply {
                    put("title", step.title)
                    put("status", step.status.name)
                    put("detail", step.detail)
                    put("started_at_epoch_ms", step.startedAtEpochMs)
                    put("finished_at_epoch_ms", step.finishedAtEpochMs)
                }
            )
        }
        return JSONObject().apply {
            put("task_id", task.taskId)
            put("title", task.title)
            put("summary", task.summary)
            put("status", task.status.name)
            put("steps", stepsJson)
            put("started_at_epoch_ms", task.startedAtEpochMs)
            put("finished_at_epoch_ms", task.finishedAtEpochMs)
            put("task_action", task.taskAction?.name ?: "")
            put("runtime_task_id", task.runtimeTaskId ?: "")
            put("failure_reason", task.failureReason ?: "")
            task.failure?.let {
                put(
                    "failure",
                    JSONObject().apply {
                        put("code", it.code)
                        put("summary", it.summary)
                        put("raw_detail", it.rawDetail)
                    }
                )
            }
            put("origin_prompt", task.originPrompt)
            put("retry_count", task.retryCount)
            put("retry_from_task_id", task.retryFromTaskId ?: "")
        }
    }
}

internal data class PersistedChatTaskState(
    val currentTask: ChatTaskExecutionState?,
    val taskHistory: List<ChatTaskExecutionState>
)
