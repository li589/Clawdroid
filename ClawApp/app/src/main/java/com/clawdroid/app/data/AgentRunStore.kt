package com.clawdroid.app.data

import android.content.Context
import com.clawdroid.app.agent.AgentPlan
import com.clawdroid.app.agent.AgentPlanStep
import com.clawdroid.app.agent.AgentGoal
import com.clawdroid.app.agent.AgentRiskLevel
import com.clawdroid.app.agent.AgentRun
import com.clawdroid.app.agent.AgentRunPhase
import com.clawdroid.app.agent.MemoryBundle
import com.clawdroid.app.agent.WorldSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists incomplete Agent runs per chat session for「继续」resume (Stage B).
 * Does **not** implement Runtime WaitingSignal approval IPC.
 */
object AgentRunStore {
    private const val PREFS = "clawdroid_agent_runs"
    private const val KEY_PREFIX = "run:"

    fun saveIncomplete(context: Context, run: AgentRun) {
        if (run.phase == AgentRunPhase.Done || run.phase == AgentRunPhase.Idle) {
            clear(context, run.sessionId)
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PREFIX + run.sessionId, serialize(run)).apply()
    }

    fun loadIncomplete(context: Context, sessionId: String): AgentRun? {
        val id = sessionId.trim().ifBlank { return null }
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + id, null)
            ?: return null
        return runCatching { deserialize(raw) }.getOrNull()
            ?.takeIf { it.phase != AgentRunPhase.Done && it.phase != AgentRunPhase.Idle }
    }

    fun clear(context: Context, sessionId: String) {
        val id = sessionId.trim()
        if (id.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PREFIX + id)
            .apply()
    }

    private fun serialize(run: AgentRun): String {
        return JSONObject()
            .put("id", run.id)
            .put("sessionId", run.sessionId)
            .put("phase", run.phase.name)
            .put("runtimeTaskId", run.runtimeTaskId)
            .put("eventCount", run.eventCount)
            .put("lastError", run.lastError)
            .put("success", run.success)
            .put(
                "goal",
                JSONObject()
                    .put("id", run.goal.id)
                    .put("intent", run.goal.intent)
                    .put("successCriteria", run.goal.successCriteria)
                    .put("risk", run.goal.risk.name)
            )
            .put(
                "world",
                JSONObject()
                    .put("sessionSummary", run.world.sessionSummary)
                    .put("capabilityStatus", run.world.capabilityStatus)
                    .put("eventStreaming", run.world.eventStreaming)
                    .put("rootAvailable", run.world.rootAvailable)
                    .put("accessibilityAvailable", run.world.accessibilityAvailable)
            )
            .put(
                "memory",
                JSONObject()
                    .put("workingSummary", run.memory.workingSummary)
                    .put("episodic", JSONArray(run.memory.episodicSnippets))
                    .put("semantic", JSONArray(run.memory.semanticFacts))
                    .put("files", JSONArray(run.memory.filePaths))
            )
            .put(
                "plan",
                JSONObject()
                    .put("parallelizable", run.plan.parallelizable)
                    .put(
                        "steps",
                        JSONArray().also { arr ->
                            run.plan.steps.forEach { step ->
                                arr.put(
                                    JSONObject()
                                        .put("id", step.id)
                                        .put("title", step.title)
                                        .put("kind", step.kind.name)
                                        .put("payload", step.payload)
                                )
                            }
                        }
                    )
            )
            .toString()
    }

    private fun deserialize(raw: String): AgentRun {
        val obj = JSONObject(raw)
        val goalObj = obj.getJSONObject("goal")
        val worldObj = obj.getJSONObject("world")
        val memObj = obj.optJSONObject("memory") ?: JSONObject()
        val planObj = obj.optJSONObject("plan") ?: JSONObject()
        val stepsArr = planObj.optJSONArray("steps") ?: JSONArray()
        val steps = buildList {
            for (i in 0 until stepsArr.length()) {
                val s = stepsArr.optJSONObject(i) ?: continue
                add(
                    AgentPlanStep(
                        id = s.optString("id"),
                        title = s.optString("title"),
                        kind = runCatching {
                            AgentPlanStep.Kind.valueOf(s.optString("kind", "Tool"))
                        }.getOrDefault(AgentPlanStep.Kind.Tool),
                        payload = s.optString("payload")
                    )
                )
            }
        }
        fun stringList(key: String): List<String> {
            val arr = memObj.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optString(it)?.takeIf { v -> v.isNotBlank() } }
        }
        return AgentRun(
            id = obj.getString("id"),
            sessionId = obj.getString("sessionId"),
            goal = AgentGoal(
                id = goalObj.optString("id"),
                intent = goalObj.optString("intent"),
                successCriteria = goalObj.optString("successCriteria"),
                risk = runCatching {
                    AgentRiskLevel.valueOf(goalObj.optString("risk", "Medium"))
                }.getOrDefault(AgentRiskLevel.Medium)
            ),
            world = WorldSnapshot(
                sessionSummary = worldObj.optString("sessionSummary"),
                capabilityStatus = worldObj.optString("capabilityStatus"),
                eventStreaming = worldObj.optBoolean("eventStreaming"),
                rootAvailable = worldObj.optBoolean("rootAvailable"),
                accessibilityAvailable = worldObj.optBoolean("accessibilityAvailable")
            ),
            memory = MemoryBundle(
                workingSummary = memObj.optString("workingSummary"),
                episodicSnippets = stringList("episodic"),
                semanticFacts = stringList("semantic"),
                filePaths = stringList("files")
            ),
            plan = AgentPlan(
                steps = steps,
                parallelizable = planObj.optBoolean("parallelizable")
            ),
            phase = runCatching {
                AgentRunPhase.valueOf(obj.optString("phase", "Done"))
            }.getOrDefault(AgentRunPhase.Done),
            runtimeTaskId = obj.optString("runtimeTaskId").takeIf { it.isNotBlank() },
            eventCount = obj.optInt("eventCount"),
            lastError = obj.optString("lastError").takeIf { it.isNotBlank() },
            success = if (obj.has("success") && !obj.isNull("success")) obj.optBoolean("success") else null
        )
    }
}
