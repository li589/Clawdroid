package com.clawdroid.app.data.model

// ---------------------------------------------------------------------------
// 聊天消息
// ---------------------------------------------------------------------------
internal enum class ChatRole {
    User,
    Assistant
}

internal enum class ChatMessageState {
    Final,
    Streaming,
    /** Process killed / user interrupt — UI shows 「已终止」 instead of 「输入中」. */
    Terminated
}

internal data class ChatMedia(
    val uri: String,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0
) {
    val isVideo: Boolean
        get() = mimeType.startsWith("video/", ignoreCase = true)
}

internal data class ChatMessage(
    val id: String = newChatMessageId(),
    val role: ChatRole,
    val content: String,
    val attachmentLabel: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val state: ChatMessageState = ChatMessageState.Final,
    val media: List<ChatMedia> = emptyList()
) {
    val hasMedia: Boolean
        get() = media.isNotEmpty()
}

internal fun newChatMessageId(): String {
    return "msg-${System.currentTimeMillis()}-${(0..9999).random()}"
}

internal fun parseChatRole(raw: String): ChatRole =
    ChatRole.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ChatRole.Assistant

/** Convert abandoned Streaming bubbles to Terminated after process death or interrupt. */
internal fun ChatMessage.asTerminated(reasonSuffix: String = "（已终止）"): ChatMessage {
    if (state != ChatMessageState.Streaming) return this
    val placeholder = setOf("正在分析指令...", "…", "...")
    val nextContent = when {
        content.isBlank() || content.trim() in placeholder -> reasonSuffix
        content.contains("已终止") || content.contains("已停止") -> content
        else -> "$content\n\n$reasonSuffix"
    }
    return copy(content = nextContent, state = ChatMessageState.Terminated)
}

internal fun List<ChatMessage>.finalizeAbandonedStreaming(): List<ChatMessage> =
    map { it.asTerminated() }
