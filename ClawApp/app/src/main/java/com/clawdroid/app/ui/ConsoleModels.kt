package com.clawdroid.app.ui

internal enum class ConsolePage {
    Overview,
    Chat,
    Settings
}

internal enum class ThemeMode {
    FollowSystem,
    Dark,
    Light
}

internal enum class ModelProvider {
    OpenAI,
    Gemini,
    Anthropic,
    OpenAICompatible,
    Custom,
    Local
}

internal data class ModelSettings(
    val provider: ModelProvider = ModelProvider.OpenAI,
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val modelName: String = "",
    val localEndpoint: String = "http://127.0.0.1:11434/v1",
    val localModelName: String = ""
)

internal enum class ChatRole {
    User,
    Assistant
}

internal enum class ChatMessageState {
    Final,
    Streaming
}

internal data class ChatMessage(
    val id: String = newChatMessageId(),
    val role: ChatRole,
    val content: String,
    val attachmentLabel: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val state: ChatMessageState = ChatMessageState.Final
)

internal fun newChatMessageId(): String {
    return "msg-${System.currentTimeMillis()}-${(0..9999).random()}"
}

internal fun defaultBaseUrlFor(provider: ModelProvider): String {
    return when (provider) {
        ModelProvider.OpenAI -> "https://api.openai.com/v1"
        ModelProvider.Gemini -> "https://generativelanguage.googleapis.com/v1beta/openai"
        ModelProvider.Anthropic -> "https://api.anthropic.com/v1"
        ModelProvider.OpenAICompatible -> "https://api.openai.com/v1"
        ModelProvider.Custom -> ""
        ModelProvider.Local -> ""
    }
}
