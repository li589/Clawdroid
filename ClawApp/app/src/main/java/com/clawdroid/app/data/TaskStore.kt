package com.clawdroid.app.data

import android.content.Context
import com.clawdroid.app.agent.task.ExecutionStep
import com.clawdroid.app.agent.task.ExecutionTask
import com.clawdroid.app.agent.task.StepKind
import com.clawdroid.app.agent.task.StepRetryPolicy
import com.clawdroid.app.agent.task.TaskCheckpoint
import com.clawdroid.app.agent.task.TaskFailure
import com.clawdroid.app.agent.task.TaskState
import org.json.JSONArray
import org.json.JSONObject

interface TaskStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

class InMemoryTaskStorage : TaskStorage {
    private val values = mutableMapOf<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String) {
        values[key] = value
    }
}

class SharedPreferencesTaskStorage(context: Context) : TaskStorage {
    private val prefs = context.getSharedPreferences("clawdroid_task_engine", Context.MODE_PRIVATE)

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

interface TaskStore {
    fun load(): List<ExecutionTask>
    fun save(task: ExecutionTask)
}

class InMemoryTaskStore : TaskStore {
    private val tasks = linkedMapOf<String, ExecutionTask>()

    override fun load(): List<ExecutionTask> = tasks.values.toList()

    override fun save(task: ExecutionTask) {
        tasks[task.id] = task
    }
}

class JsonTaskStore(private val storage: TaskStorage) : TaskStore {
    override fun load(): List<ExecutionTask> = runCatching {
        val raw = storage.read(KEY_TASKS) ?: return emptyList()
        val array = JSONArray(raw)
        buildList<ExecutionTask> {
            for (index in 0 until array.length()) {
                add(decodeTask(array.optJSONObject(index) ?: continue))
            }
        }
    }.getOrDefault(emptyList())

    override fun save(task: ExecutionTask) {
        val tasks = (load().filterNot { it.id == task.id } + task)
            .sortedByDescending { it.updatedAtEpochMs }
            .take(MAX_TASKS)
        val payload = JSONArray()
        tasks.forEach { payload.put(encodeTask(it)) }
        storage.write(KEY_TASKS, payload.toString())
    }

    private fun encodeTask(task: ExecutionTask): JSONObject = JSONObject().apply {
        put("id", task.id)
        put("title", task.title)
        put("origin_prompt", task.originPrompt)
        put("state", task.state.name)
        put("attempt", task.attempt)
        putNullable("retry_of", task.retryOf)
        put("created_at_epoch_ms", task.createdAtEpochMs)
        put("updated_at_epoch_ms", task.updatedAtEpochMs)
        put("steps", JSONArray().apply {
            task.steps.forEach { put(encodeStep(it)) }
        })
        put("checkpoint", encodeCheckpoint(task.checkpoint))
        task.failure?.let { put("failure", encodeFailure(it)) }
    }

    private fun encodeStep(step: ExecutionStep): JSONObject = JSONObject().apply {
        put("id", step.id)
        put("title", step.title)
        put("kind", step.kind.name)
        put("payload", step.payload)
        put("timeout_ms", step.timeoutMs)
        putNullable("side_effect_key", step.sideEffectKey)
        put("retry", JSONObject().apply {
            put("max_attempts", step.retry.maxAttempts)
            put("initial_delay_ms", step.retry.initialDelayMs)
            put("max_delay_ms", step.retry.maxDelayMs)
        })
    }

    private fun encodeCheckpoint(checkpoint: TaskCheckpoint): JSONObject = JSONObject().apply {
        put("next_step_index", checkpoint.nextStepIndex)
        put("completed_step_ids", JSONArray().apply {
            checkpoint.completedStepIds.sorted().forEach { put(it) }
        })
        put("step_attempts", JSONObject().apply {
            checkpoint.stepAttempts.forEach { (stepId, attempts) -> put(stepId, attempts) }
        })
        putNullable("runtime_task_id", checkpoint.runtimeTaskId)
        putNullable("waiting_reason", checkpoint.waitingReason)
        put("data", JSONObject().apply {
            checkpoint.data.forEach { (key, value) -> put(key, value) }
        })
    }

    private fun encodeFailure(failure: TaskFailure): JSONObject = JSONObject().apply {
        put("code", failure.code)
        put("summary", failure.summary)
        put("detail", failure.detail)
        put("retryable", failure.retryable)
        put("source", failure.source)
    }

    private fun decodeTask(item: JSONObject): ExecutionTask {
        val stepsArray = item.optJSONArray("steps") ?: JSONArray()
        val steps = buildList<ExecutionStep> {
            for (index in 0 until stepsArray.length()) {
                stepsArray.optJSONObject(index)?.let { add(decodeStep(it)) }
            }
        }
        return ExecutionTask(
            id = item.getString("id"),
            title = item.getString("title"),
            originPrompt = item.optString("origin_prompt"),
            steps = steps,
            state = enumValue(item.optString("state"), TaskState.Created),
            attempt = item.optInt("attempt", 1),
            retryOf = item.optStringOrNull("retry_of"),
            checkpoint = decodeCheckpoint(item.optJSONObject("checkpoint") ?: JSONObject()),
            failure = item.optJSONObject("failure")?.let(::decodeFailure),
            createdAtEpochMs = item.optLong("created_at_epoch_ms"),
            updatedAtEpochMs = item.optLong("updated_at_epoch_ms")
        )
    }

    private fun decodeStep(item: JSONObject): ExecutionStep {
        val retry = item.optJSONObject("retry") ?: JSONObject()
        return ExecutionStep(
            id = item.getString("id"),
            title = item.getString("title"),
            kind = enumValue(item.optString("kind"), StepKind.Tool),
            payload = item.optString("payload"),
            retry = StepRetryPolicy(
                maxAttempts = retry.optInt("max_attempts"),
                initialDelayMs = retry.optLong("initial_delay_ms", 500),
                maxDelayMs = retry.optLong("max_delay_ms", 5_000)
            ),
            timeoutMs = item.optLong("timeout_ms", 30_000),
            sideEffectKey = item.optStringOrNull("side_effect_key")
        )
    }

    private fun decodeCheckpoint(item: JSONObject): TaskCheckpoint {
        val completedArray = item.optJSONArray("completed_step_ids") ?: JSONArray()
        val completed = buildSet {
            for (index in 0 until completedArray.length()) {
                add(completedArray.optString(index))
            }
        }.filter { it.isNotBlank() }.toSet()
        val attemptsObject = item.optJSONObject("step_attempts") ?: JSONObject()
        val attempts = buildMap {
            attemptsObject.keys().forEach { key -> put(key, attemptsObject.optInt(key)) }
        }
        val dataObject = item.optJSONObject("data") ?: JSONObject()
        val data = buildMap {
            dataObject.keys().forEach { key -> put(key, dataObject.optString(key)) }
        }
        return TaskCheckpoint(
            nextStepIndex = item.optInt("next_step_index"),
            completedStepIds = completed,
            stepAttempts = attempts,
            runtimeTaskId = item.optStringOrNull("runtime_task_id"),
            waitingReason = item.optStringOrNull("waiting_reason"),
            data = data
        )
    }

    private fun decodeFailure(item: JSONObject): TaskFailure = TaskFailure(
        code = item.optString("code"),
        summary = item.optString("summary"),
        detail = item.optString("detail"),
        retryable = item.optBoolean("retryable"),
        source = item.optString("source")
    )

    private fun JSONObject.putNullable(key: String, value: String?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        optString(key).takeIf { it.isNotBlank() }

    private inline fun <reified T : Enum<T>> enumValue(raw: String, default: T): T =
        runCatching { enumValueOf<T>(raw) }.getOrDefault(default)

    companion object {
        private const val KEY_TASKS = "tasks"
        private const val MAX_TASKS = 50
    }
}
