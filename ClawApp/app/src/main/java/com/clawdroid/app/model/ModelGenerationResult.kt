package com.clawdroid.app.model

/**
 * 单次模型生成结果：文本与原生 tool_calls 可并存。
 * 编排层优先消费 [toolCalls]，否则回退解析 [text] 中的约定 JSON。
 */
internal data class ModelToolCall(
    val id: String = "",
    val name: String,
    val argumentsJson: String = "{}"
)

internal data class ModelGenerationResult(
    val text: String = "",
    val toolCalls: List<ModelToolCall> = emptyList()
) {
    fun hasToolCalls(): Boolean = toolCalls.isNotEmpty()
}
