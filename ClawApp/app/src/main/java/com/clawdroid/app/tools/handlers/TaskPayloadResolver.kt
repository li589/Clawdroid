package com.clawdroid.app.tools.handlers

import org.json.JSONArray
import org.json.JSONObject

internal fun resolveTaskPayload(arguments: Map<String, Any?>): Map<String, Any?>? {
    val fromNested = resolveNestedOrJsonTask(arguments)
    if (fromNested != null) {
        return ensureTaskId(fromNested, arguments)
    }
    val taskId = arguments.string("task_id").ifBlank {
        "app-task-${System.currentTimeMillis()}"
    }
    val stepsJson = arguments.string("steps_json", "steps")
    if (!stepsJson.startsWith("[")) {
        return null
    }
    val steps = runCatching {
        val array = JSONArray(stepsJson)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(item.toAnyMap())
            }
        }
    }.getOrNull() ?: return null
    if (steps.isEmpty()) {
        return null
    }
    return linkedMapOf(
        "task_id" to taskId,
        "name" to arguments.string("name"),
        "steps" to steps
    ).filterValues { value ->
        value !is String || value.isNotBlank()
    }
}

private fun resolveNestedOrJsonTask(arguments: Map<String, Any?>): Map<String, Any?>? {
    val nested = arguments["task"]
    when (nested) {
        is Map<*, *> -> {
            return nested.entries.associate { (key, value) -> key.toString() to value }
        }
        is String -> {
            val trimmed = nested.trim()
            if (trimmed.startsWith("{")) {
                return runCatching { JSONObject(trimmed).toAnyMap() }.getOrNull()
            }
        }
    }
    val taskJson = arguments.string("task_json")
    if (taskJson.startsWith("{")) {
        return runCatching { JSONObject(taskJson).toAnyMap() }.getOrNull()
    }
    return null
}

private fun ensureTaskId(
    task: Map<String, Any?>,
    arguments: Map<String, Any?>
): Map<String, Any?> {
    val existingId = task["task_id"]?.toString()?.trim().orEmpty()
    if (existingId.isNotBlank()) {
        return task
    }
    val fallbackId = arguments.string("task_id").ifBlank {
        "app-task-${System.currentTimeMillis()}"
    }
    val fallbackName = task["name"]?.toString()?.trim().orEmpty().ifBlank {
        arguments.string("name")
    }
    return linkedMapOf<String, Any?>().apply {
        putAll(task)
        put("task_id", fallbackId)
        if (fallbackName.isNotBlank()) {
            put("name", fallbackName)
        }
    }
}

private fun JSONObject.toAnyMap(): Map<String, Any?> {
    val result = linkedMapOf<String, Any?>()
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        result[key] = normalizeJsonValue(opt(key))
    }
    return result
}

private fun normalizeJsonValue(value: Any?): Any? {
    return when (value) {
        is JSONObject -> value.toAnyMap()
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                add(normalizeJsonValue(value.opt(index)))
            }
        }
        JSONObject.NULL -> null
        else -> value
    }
}
