package com.clawdroid.app.agent

/**
 * App-side policy gate. Does **not** implement Runtime WaitingSignal approval IPC.
 */
object AgentPolicy {
    private val highRiskIntentHints = listOf(
        "reboot", "重启", "wipe", "factory reset", "rm -rf", "格式化",
        "force-stop", "卸载", "uninstall", "su -c"
    )

    fun evaluateGoal(goal: AgentGoal, world: WorldSnapshot): PolicyDecision {
        val intent = goal.intent.trim()
        if (intent.isBlank()) {
            return PolicyDecision.Reject("空意图，无法创建 Agent Run")
        }
        val risk = effectiveRisk(goal)
        if (risk >= AgentRiskLevel.Critical) {
            return PolicyDecision.RequireConfirm("高危意图需要应用内确认：$intent")
        }
        if (risk >= AgentRiskLevel.High) {
            return PolicyDecision.RequireConfirm("较高风险操作需要确认后再执行")
        }
        if (looksLikeRootShell(intent) && !world.rootAvailable) {
            return PolicyDecision.Reject("当前能力画像无 Root，无法执行该类 Shell 意图")
        }
        return PolicyDecision.Allow
    }

    fun evaluateToolHint(toolId: String, world: WorldSnapshot): PolicyDecision {
        val id = toolId.trim().lowercase()
        if (id.isEmpty()) return PolicyDecision.Allow
        if ((id == "execute_shell_limited" || id == "exec_shell_limited") && !world.rootAvailable) {
            return PolicyDecision.Reject("Root / Runtime Shell 不可用")
        }
        if (id.startsWith("inject_") && !world.accessibilityAvailable && !world.rootAvailable) {
            return PolicyDecision.Reject("注入类工具需要无障碍或 Root")
        }
        if (id == "app_stop" || id == "execute_shell_limited") {
            return PolicyDecision.RequireConfirm("破坏性工具需要确认：$id")
        }
        return PolicyDecision.Allow
    }

    fun effectiveRisk(goal: AgentGoal): AgentRiskLevel {
        val lower = goal.intent.lowercase()
        if (highRiskIntentHints.any { lower.contains(it.lowercase()) }) {
            return maxOf(goal.risk, AgentRiskLevel.Critical)
        }
        return goal.risk
    }

    private fun looksLikeRootShell(intent: String): Boolean {
        val lower = intent.lowercase()
        return lower.contains("wm size") ||
            lower.contains("execute_shell") ||
            lower.contains("exec_shell") ||
            lower.contains("su ")
    }

    private fun maxOf(a: AgentRiskLevel, b: AgentRiskLevel): AgentRiskLevel =
        if (a.ordinal >= b.ordinal) a else b
}
