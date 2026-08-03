package com.clawdroid.app.agent

/**
 * Risk level for a goal or tool action. Used by [AgentPolicy] (App-side only).
 */
enum class AgentRiskLevel {
    Low,
    Medium,
    High,
    Critical
}

/**
 * App-kernel run lifecycle. Runtime task terminal states remain separate (see task_wait).
 */
enum class AgentRunPhase {
    Idle,
    Perceive,
    Plan,
    Act,
    Observe,
    Reflect,
    AwaitUser,
    AwaitRuntime,
    Done
}

data class AgentGoal(
    val id: String,
    val intent: String,
    val successCriteria: String = "",
    val risk: AgentRiskLevel = AgentRiskLevel.Medium
)

data class AgentPlanStep(
    val id: String,
    val title: String,
    val kind: Kind = Kind.Tool,
    val payload: String = ""
) {
    enum class Kind {
        Tool,
        CatalogAgent,
        AwaitRuntimeTask,
        LocalAction
    }
}

data class AgentPlan(
    val steps: List<AgentPlanStep> = emptyList(),
    val parallelizable: Boolean = false
)

data class WorldSnapshot(
    val sessionSummary: String = "",
    val capabilityStatus: String = "",
    val eventStreaming: Boolean = false,
    val rootAvailable: Boolean = false,
    val accessibilityAvailable: Boolean = false
)

data class MemoryBundle(
    val workingSummary: String = "",
    val episodicSnippets: List<String> = emptyList(),
    val semanticFacts: List<String> = emptyList(),
    val filePaths: List<String> = emptyList()
) {
    fun asRetrievedContext(): String {
        if (episodicSnippets.isEmpty() && semanticFacts.isEmpty() && filePaths.isEmpty()) {
            return ""
        }
        return buildString {
            if (episodicSnippets.isNotEmpty()) {
                appendLine("聊天索引：")
                episodicSnippets.forEach { appendLine("- $it") }
            }
            if (semanticFacts.isNotEmpty()) {
                appendLine("记忆图谱：")
                semanticFacts.forEach { appendLine("- $it") }
            }
            if (filePaths.isNotEmpty()) {
                appendLine("文件索引：")
                filePaths.forEach { appendLine("- $it") }
            }
        }.trim()
    }
}

sealed interface PolicyDecision {
    data object Allow : PolicyDecision
    data class RequireConfirm(val reason: String) : PolicyDecision
    data class Reject(val reason: String) : PolicyDecision
}

data class AgentSession(
    val id: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

data class AgentRun(
    val id: String,
    val sessionId: String,
    val goal: AgentGoal,
    val world: WorldSnapshot,
    val memory: MemoryBundle = MemoryBundle(),
    val plan: AgentPlan = AgentPlan(),
    val phase: AgentRunPhase = AgentRunPhase.Perceive,
    val runtimeTaskId: String? = null,
    val eventCount: Int = 0,
    val lastError: String? = null,
    val success: Boolean? = null
)
