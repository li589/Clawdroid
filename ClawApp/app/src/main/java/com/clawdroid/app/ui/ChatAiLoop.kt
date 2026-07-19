package com.clawdroid.app.ui

import com.clawdroid.app.ai.AiToolStepRecord
import com.clawdroid.app.chat.ChatTextLimits

/**
 * AI tool-loop helpers extracted from [ChatViewModel] (transcript / context shaping).
 */
internal object ChatAiLoop {
    fun buildTranscript(steps: List<AiToolStepRecord>): String {
        if (steps.isEmpty()) {
            return "未执行任何工具步骤。"
        }
        return steps.mapIndexed { index, step ->
            val status = if (step.success) "OK" else "FAIL"
            buildString {
                append("## Step ${index + 1}: ${step.tool.toolId} [$status]")
                append('\n')
                append(ChatTextLimits.truncateForContext(step.output))
            }
        }.joinToString("\n\n")
    }
}
