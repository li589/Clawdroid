package com.clawdroid.app.ai

import java.security.MessageDigest

/**
 * Decides whether the next planned tool step should continue, soft-warn, or hard-stop.
 * Replaces the naive "any prior identical tool+args → stop" guard.
 */
internal sealed interface ToolLoopDecision {
    data object Continue : ToolLoopDecision
    data class SoftWarn(val message: String) : ToolLoopDecision
    data class HardStop(val message: String) : ToolLoopDecision
    /** Same successful result already exists — inject hint, do not hard-stop the whole loop. */
    data class ReusePriorResult(val message: String, val priorOutput: String) : ToolLoopDecision
}

internal object ToolLoopDetector {
    const val DEFAULT_NO_PROGRESS_STREAK = 3
    const val CONSECUTIVE_FAILURE_LIMIT = 2

    fun evaluate(
        steps: List<AiToolStepRecord>,
        nextTool: com.clawdroid.app.tools.ClawTool,
        nextArguments: Map<String, String>,
        noProgressStreakLimit: Int = DEFAULT_NO_PROGRESS_STREAK
    ): ToolLoopDecision {
        if (steps.isEmpty()) return ToolLoopDecision.Continue

        val matching = steps.filter { it.tool == nextTool && it.arguments == nextArguments }
        val lastMatch = matching.lastOrNull()
        if (lastMatch != null && lastMatch.success) {
            return ToolLoopDecision.ReusePriorResult(
                message = "该工具与参数已有成功结果，请改用新路径/分页或基于已有输出继续。",
                priorOutput = lastMatch.output
            )
        }

        // Consecutive failures of the same tool+args at the end of the trail.
        var consecutiveFailures = 0
        for (step in steps.asReversed()) {
            if (step.tool != nextTool || step.arguments != nextArguments) break
            if (step.success) break
            consecutiveFailures++
        }
        if (consecutiveFailures >= CONSECUTIVE_FAILURE_LIMIT) {
            return ToolLoopDecision.HardStop(
                "同一工具参数连续失败 ${consecutiveFailures} 次且无新信息，已停止该分支。"
            )
        }

        // No-progress: last K output fingerprints identical (and we are about to call again).
        val limit = noProgressStreakLimit.coerceAtLeast(2)
        if (steps.size >= limit) {
            val recent = steps.takeLast(limit)
            val fingerprints = recent.map { fingerprint(it) }
            if (fingerprints.distinct().size == 1) {
                return ToolLoopDecision.SoftWarn(
                    "最近 $limit 步输出指纹相同，请换策略（不同路径、分页或其它工具），暂不终断整轮。"
                )
            }
        }

        return ToolLoopDecision.Continue
    }

    fun fingerprint(step: AiToolStepRecord): String {
        val raw = buildString {
            append(step.tool.toolId)
            append('|')
            append(step.success)
            append('|')
            append(step.output.trim().take(512))
        }
        return sha256Hex(raw).take(16)
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}
