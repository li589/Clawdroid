package com.clawdroid.app.ai

import android.content.Context
import com.clawdroid.app.chat.ChatHistoryTurn
import com.clawdroid.app.data.ChatContextIndexStore
import com.clawdroid.app.data.MemoryGraphStore
import com.clawdroid.app.model.ModelApiClient
import com.clawdroid.app.tools.ClawAssetPromptStore
import com.clawdroid.app.data.model.ModelProvider
import com.clawdroid.app.data.model.ModelSettings

internal data class ContextCompressResult(
    val recentChat: List<ChatHistoryTurn>,
    val compressedMemory: String,
    val didCompress: Boolean
)

/**
 * When chat history looks large relative to the model context window,
 * summarize older turns into [compressedMemory] and keep only the newest turns.
 */
internal object ContextCompressor {
    private const val KEEP_RECENT_MIN = 4
    private const val KEEP_RECENT_MAX = 12
    /** Approximate chars-per-token for budget estimates. */
    private const val CHARS_PER_TOKEN = 3

    suspend fun maybeCompress(
        settings: ModelSettings,
        history: List<ChatHistoryTurn>,
        existingCompressed: String,
        appContext: Context? = null,
        contextWindowTokens: Int = settings.contextSettings.effectiveContextWindow(
            settings.modelName.ifBlank { settings.localModelName }
        )
    ): ContextCompressResult {
        val keepRecent = keepRecentForWindow(contextWindowTokens)
        val charThreshold = compressThresholdChars(contextWindowTokens)
        val totalChars = history.sumOf { it.content.length } + existingCompressed.length
        if (history.size <= keepRecent || totalChars < charThreshold) {
            return ContextCompressResult(history, existingCompressed, didCompress = false)
        }
        if (!isConfigured(settings)) {
            val older = history.dropLast(keepRecent)
            val local = buildString {
                if (existingCompressed.isNotBlank()) {
                    appendLine(existingCompressed)
                }
                older.forEach { turn ->
                    appendLine("${turn.role}: ${turn.content.take(240)}")
                }
            }.take(2_500)
            return ContextCompressResult(
                recentChat = history.takeLast(keepRecent),
                compressedMemory = local,
                didCompress = true
            )
        }
        val system = ClawAssetPromptStore.contextCompressPrompt(appContext).ifBlank {
            "将旧对话压缩为简短要点，保留目标、约束、已完成工具结果与待办。只用中文，不超过 400 字。"
        }
        val older = history.dropLast(keepRecent)
        val prompt = buildString {
            if (existingCompressed.isNotBlank()) {
                appendLine("已有摘要：")
                appendLine(existingCompressed.take(800))
                appendLine()
            }
            appendLine("请压缩以下旧轮对话：")
            older.forEach { turn ->
                appendLine("${turn.role}: ${turn.content.take(500)}")
            }
        }
        val summary = ModelApiClient.generateReply(
            settings = settings,
            prompt = prompt,
            systemPrompt = system
        ).getOrElse {
            older.joinToString("\n") { "${it.role}: ${it.content.take(160)}" }.take(1_600)
        }
        val compressed = summary.trim().take(2_500)
        appContext?.let { ctx ->
            ChatContextIndexStore.recordCompression(ctx, older.size, compressed.length)
            MemoryGraphStore.upsertFact(
                ctx,
                id = "compress-${System.currentTimeMillis()}",
                label = "对话压缩摘要",
                content = compressed.take(400),
                tags = listOf("compression", "chat")
            )
        }
        return ContextCompressResult(
            recentChat = history.takeLast(keepRecent),
            compressedMemory = compressed,
            didCompress = true
        )
    }

    fun keepRecentForWindow(contextWindowTokens: Int): Int {
        val scaled = (contextWindowTokens / 16_000).coerceIn(KEEP_RECENT_MIN, KEEP_RECENT_MAX)
        return scaled
    }

    fun compressThresholdChars(contextWindowTokens: Int): Int {
        // Trigger when history uses ~35% of a soft input budget (window minus reply headroom).
        val inputBudgetTokens = (contextWindowTokens * 0.55).toInt().coerceAtLeast(4_000)
        return (inputBudgetTokens * CHARS_PER_TOKEN * 0.35).toInt().coerceIn(4_000, 80_000)
    }

    private fun isConfigured(settings: ModelSettings): Boolean {
        return when (settings.provider) {
            ModelProvider.Local ->
                settings.localEndpoint.isNotBlank() && settings.localModelName.isNotBlank()
            else ->
                settings.baseUrl.isNotBlank() &&
                    settings.modelName.isNotBlank() &&
                    settings.apiKey.isNotBlank()
        }
    }
}
