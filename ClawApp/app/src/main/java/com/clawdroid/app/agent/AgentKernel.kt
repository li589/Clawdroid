package com.clawdroid.app.agent

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin Agent kernel: owns Session/Run lifecycle and policy checks.
 * Stage A does **not** replace [com.clawdroid.app.ai.AgentToolLoopController];
 * UI still drives Planner/Loop and reports transitions here.
 */
class AgentKernel {
    private val sessions = ConcurrentHashMap<String, AgentSession>()
    private val runs = ConcurrentHashMap<String, AgentRun>()
    @Volatile
    private var activeRunId: String? = null

    fun session(sessionId: String): AgentSession {
        val id = sessionId.trim().ifBlank { "default" }
        return sessions.getOrPut(id) { AgentSession(id = id) }
    }

    fun activeRun(): AgentRun? = activeRunId?.let { runs[it] }

    fun getRun(runId: String): AgentRun? = runs[runId]

    /** Re-attach a persisted incomplete run (Stage B 「继续」). */
    fun restoreRun(run: AgentRun): AgentRun {
        session(run.sessionId)
        runs[run.id] = run
        if (run.phase != AgentRunPhase.Done && run.phase != AgentRunPhase.Idle) {
            activeRunId = run.id
        }
        return run
    }

    /**
     * Create a Goal + Run after Perceive, evaluate policy, stash as active.
     * Caller should abort the chat turn when [AgentTurn.policy] is [PolicyDecision.Reject].
     * [PolicyDecision.RequireConfirm] is advisory in stage A (existing command review still applies).
     */
    fun beginTurn(
        sessionId: String,
        intent: String,
        world: WorldSnapshot,
        memory: MemoryBundle = MemoryBundle(),
        risk: AgentRiskLevel = AgentRiskLevel.Medium,
        successCriteria: String = ""
    ): AgentTurn {
        session(sessionId)
        val goal = AgentGoal(
            id = newId("goal"),
            intent = intent.trim(),
            successCriteria = successCriteria,
            risk = risk
        )
        val policy = AgentPolicy.evaluateGoal(goal, world)
        val phase = when (policy) {
            is PolicyDecision.Reject -> AgentRunPhase.Done
            is PolicyDecision.RequireConfirm -> AgentRunPhase.AwaitUser
            PolicyDecision.Allow -> AgentRunPhase.Plan
        }
        val run = AgentRun(
            id = newId("run"),
            sessionId = session(sessionId).id,
            goal = goal,
            world = world,
            memory = memory,
            phase = phase,
            success = if (policy is PolicyDecision.Reject) false else null,
            lastError = (policy as? PolicyDecision.Reject)?.reason
        )
        runs[run.id] = run
        if (policy !is PolicyDecision.Reject) {
            activeRunId = run.id
        }
        return AgentTurn(run = run, policy = policy)
    }

    fun markPlanned(runId: String, plan: AgentPlan = AgentPlan()): AgentRun? =
        transition(runId) { it.copy(plan = plan, phase = AgentRunPhase.Act) }

    fun markActing(runId: String): AgentRun? =
        transition(runId) { it.copy(phase = AgentRunPhase.Act, eventCount = it.eventCount + 1) }

    fun markObserving(runId: String): AgentRun? =
        transition(runId) { it.copy(phase = AgentRunPhase.Observe, eventCount = it.eventCount + 1) }

    fun markAwaitRuntime(runId: String, taskId: String?): AgentRun? =
        transition(runId) {
            it.copy(phase = AgentRunPhase.AwaitRuntime, runtimeTaskId = taskId ?: it.runtimeTaskId)
        }

    fun markAwaitUser(runId: String): AgentRun? =
        transition(runId) { it.copy(phase = AgentRunPhase.AwaitUser) }

    fun complete(runId: String, success: Boolean, error: String? = null): AgentRun? {
        val updated = transition(runId) {
            it.copy(
                phase = AgentRunPhase.Done,
                success = success,
                lastError = error,
                eventCount = it.eventCount + 1
            )
        }
        if (activeRunId == runId) {
            activeRunId = null
        }
        return updated
    }

    fun clearSessionRuns(sessionId: String) {
        val id = sessionId.trim()
        runs.entries.removeIf { it.value.sessionId == id }
        if (activeRunId != null && runs[activeRunId]?.sessionId == id) {
            activeRunId = null
        }
    }

    private fun transition(runId: String, transform: (AgentRun) -> AgentRun): AgentRun? {
        val current = runs[runId] ?: return null
        val next = transform(current)
        runs[runId] = next
        return next
    }

    private fun newId(prefix: String): String =
        "$prefix-${UUID.randomUUID().toString().replace("-", "").take(12)}"

    companion object {
        val shared: AgentKernel = AgentKernel()
    }
}

data class AgentTurn(
    val run: AgentRun,
    val policy: PolicyDecision
)
