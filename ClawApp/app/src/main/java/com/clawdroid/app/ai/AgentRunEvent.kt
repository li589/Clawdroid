package com.clawdroid.app.ai

import androidx.compose.runtime.Immutable

internal enum class AgentRunEventKind {
    Thinking,
    ToolCall,
    ToolResult,
    SubAgent,
    Budget,
    Compress,
    SoftWarn
}

@Immutable
internal data class AgentRunEvent(
    val id: String = newAgentRunEventId(),
    val kind: AgentRunEventKind,
    val title: String,
    val detail: String = "",
    val success: Boolean? = null,
    val durationMs: Long? = null,
    val subAgentName: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

internal fun newAgentRunEventId(): String {
    return "evt-${System.currentTimeMillis()}-${(0..9999).random()}"
}
