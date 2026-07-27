package com.clawdroid.app.model

/**
 * Current-turn multimodal user image (not persisted in chat history).
 */
internal data class ModelUserImage(
    val mimeType: String,
    val base64Data: String
) {
    val dataUrl: String
        get() = "data:$mimeType;base64,$base64Data"
}
