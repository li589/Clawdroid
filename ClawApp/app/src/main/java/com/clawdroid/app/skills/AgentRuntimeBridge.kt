package com.clawdroid.app.skills

import com.clawdroid.app.runtime.RuntimeActionCatalog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps in-app agent step tool ids onto ClawRuntime IPC actions for [AgentExecutionMode.RuntimeTask].
 */
object AgentRuntimeBridge {
    fun runtimeActionForStep(stepId: String): String? {
        return when (stepId.trim().lowercase()) {
            "runtime_ping", "ping" -> RuntimeActionCatalog.PING
            "get_runtime_status" -> RuntimeActionCatalog.GET_RUNTIME_STATUS
            "get_capabilities" -> RuntimeActionCatalog.GET_CAPABILITIES
            "capture_screen" -> RuntimeActionCatalog.CAPTURE_SCREEN
            "inject_tap" -> RuntimeActionCatalog.INJECT_TAP
            "inject_swipe" -> RuntimeActionCatalog.INJECT_SWIPE
            "inject_keyevent" -> RuntimeActionCatalog.INJECT_KEYEVENT
            "read_file_limited" -> RuntimeActionCatalog.READ_FILE_LIMITED
            "execute_shell_limited", "exec_shell_limited" -> RuntimeActionCatalog.EXEC_SHELL_LIMITED
            "subscribe_events" -> RuntimeActionCatalog.SUBSCRIBE_EVENTS
            else -> null
        }
    }

    fun canSubmitAsRuntimeTask(agent: ClawAgentDefinition): Boolean {
        if (agent.executionMode != AgentExecutionMode.RuntimeTask) {
            return false
        }
        return agent.steps.isNotEmpty() && agent.steps.all { runtimeActionForStep(it) != null }
    }

    fun buildRuntimeTaskPayload(
        agent: ClawAgentDefinition,
        taskId: String,
        arguments: Map<String, Any?> = emptyMap()
    ): Map<String, Any?>? {
        if (!canSubmitAsRuntimeTask(agent)) {
            return null
        }
        val steps = agent.steps.mapIndexed { index, stepId ->
            val action = runtimeActionForStep(stepId) ?: return null
            linkedMapOf<String, Any?>(
                "action" to action,
                "args" to argumentsForRuntimeStep(action, arguments),
                "timeout_ms" to 8_000,
                "on_failure" to "fail",
                "description" to agent.stepTitles.getOrElse(index) { stepId }
            )
        }
        return linkedMapOf(
            "task_id" to taskId,
            "name" to agent.name,
            "steps" to steps
        )
    }

    fun buildStepsJson(
        agent: ClawAgentDefinition,
        arguments: Map<String, Any?> = emptyMap()
    ): String? {
        val payload = buildRuntimeTaskPayload(
            agent = agent,
            taskId = "placeholder",
            arguments = arguments
        ) ?: return null
        val steps = payload["steps"] as? List<*> ?: return null
        val array = JSONArray()
        steps.forEach { step ->
            val map = step as? Map<*, *> ?: return@forEach
            array.put(toJsonObject(map))
        }
        return array.toString()
    }

    private fun toJsonObject(map: Map<*, *>): JSONObject {
        val obj = JSONObject()
        map.forEach { (rawKey, value) ->
            val key = rawKey?.toString() ?: return@forEach
            when (value) {
                null -> obj.put(key, JSONObject.NULL)
                is Map<*, *> -> obj.put(key, toJsonObject(value))
                is List<*> -> obj.put(key, toJsonArray(value))
                else -> obj.put(key, value)
            }
        }
        return obj
    }

    private fun toJsonArray(values: List<*>): JSONArray {
        val array = JSONArray()
        values.forEach { value ->
            when (value) {
                null -> array.put(JSONObject.NULL)
                is Map<*, *> -> array.put(toJsonObject(value))
                is List<*> -> array.put(toJsonArray(value))
                else -> array.put(value)
            }
        }
        return array
    }

    private fun argumentsForRuntimeStep(
        action: String,
        arguments: Map<String, Any?>
    ): Map<String, Any?> {
        return when (action) {
            RuntimeActionCatalog.INJECT_SWIPE -> mapOf(
                "x1" to (arguments["x1"] ?: 540),
                "y1" to (arguments["y1"] ?: 1800),
                "x2" to (arguments["x2"] ?: 540),
                "y2" to (arguments["y2"] ?: 400),
                "duration_ms" to (arguments["duration_ms"] ?: 350),
                "display_id" to (arguments["display_id"] ?: 0)
            )
            RuntimeActionCatalog.INJECT_TAP -> mapOf(
                "x" to (arguments["x"] ?: 540),
                "y" to (arguments["y"] ?: 1200),
                "display_id" to (arguments["display_id"] ?: 0)
            )
            RuntimeActionCatalog.INJECT_KEYEVENT -> mapOf(
                "key" to (arguments["key"] ?: "BACK"),
                "display_id" to (arguments["display_id"] ?: 0)
            )
            RuntimeActionCatalog.EXEC_SHELL_LIMITED -> mapOf(
                "command" to (arguments["command"] ?: "wm size")
            )
            RuntimeActionCatalog.CAPTURE_SCREEN -> mapOf(
                "display_id" to (arguments["display_id"] ?: 0)
            )
            else -> emptyMap()
        }
    }
}
