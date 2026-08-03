package com.clawdroid.app.data

import android.content.Context
import com.clawdroid.app.agent.MemoryBundle

/**
 * Single entry for chat/memory/file retrieval used by Agent turns.
 * Underlying prefs stores remain the source of truth.
 */
object MemoryFacade {
    private const val DEFAULT_CHAT_LIMIT = 4
    private const val DEFAULT_MEMORY_LIMIT = 4
    private const val DEFAULT_FILE_LIMIT = 4
    private const val MAX_TOTAL_CHARS = 1_800
    private const val SNIPPET_CHARS = 160

    fun indexUserTurn(context: Context, sessionId: String, content: String) {
        ChatContextIndexStore.indexTurn(
            context = context,
            sessionId = sessionId,
            role = "user",
            content = content
        )
    }

    fun retrieve(
        context: Context,
        prompt: String,
        chatLimit: Int = DEFAULT_CHAT_LIMIT,
        memoryLimit: Int = DEFAULT_MEMORY_LIMIT,
        fileLimit: Int = DEFAULT_FILE_LIMIT,
        maxTotalChars: Int = MAX_TOTAL_CHARS
    ): MemoryBundle {
        val chatHits = ChatContextIndexStore.search(context, prompt, limit = chatLimit * 2)
        val memoryHits = MemoryGraphStore.search(context, prompt, limit = memoryLimit * 2)
        val fileHits = FileIndexStore.search(context, prompt, limit = fileLimit * 2)

        val episodic = dedupePreserveOrder(
            chatHits.map { hit -> "[${hit.role}] ${hit.snippet.take(SNIPPET_CHARS)}" }
        ).take(chatLimit)
        val semantic = dedupePreserveOrder(
            memoryHits.map { node -> "${node.label}: ${node.content.take(SNIPPET_CHARS)}" }
        ).take(memoryLimit)
        val files = dedupePreserveOrder(fileHits).take(fileLimit)

        return trimToBudget(
            MemoryBundle(
                episodicSnippets = episodic,
                semanticFacts = semantic,
                filePaths = files
            ),
            maxTotalChars = maxTotalChars
        )
    }

    fun retrieveContextText(
        context: Context,
        prompt: String,
        chatLimit: Int = DEFAULT_CHAT_LIMIT,
        memoryLimit: Int = DEFAULT_MEMORY_LIMIT,
        fileLimit: Int = DEFAULT_FILE_LIMIT
    ): String = retrieve(context, prompt, chatLimit, memoryLimit, fileLimit).asRetrievedContext()

    fun summary(context: Context): String =
        listOf(
            ChatContextIndexStore.statsSummary(context),
            MemoryGraphStore.summary(context),
            FileIndexStore.summary(context)
        ).joinToString(" · ")

    fun clearAll(context: Context) {
        ChatContextIndexStore.clear(context)
        MemoryGraphStore.clear(context)
        FileIndexStore.clear(context)
    }

    internal fun dedupePreserveOrder(items: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<String>(items.size)
        for (item in items) {
            val key = item.trim().lowercase()
            if (key.isEmpty() || !seen.add(key)) continue
            out.add(item.trim())
        }
        return out
    }

    internal fun trimToBudget(bundle: MemoryBundle, maxTotalChars: Int): MemoryBundle {
        if (maxTotalChars <= 0) return MemoryBundle()
        var budget = maxTotalChars
        val working = bundle.workingSummary.take(minOf(budget, 400))
        budget -= working.length
        fun take(list: List<String>): List<String> {
            if (budget <= 0) return emptyList()
            val kept = ArrayList<String>()
            for (item in list) {
                val cost = item.length + 4
                if (cost > budget) break
                kept.add(item)
                budget -= cost
            }
            return kept
        }
        return MemoryBundle(
            workingSummary = working,
            episodicSnippets = take(bundle.episodicSnippets),
            semanticFacts = take(bundle.semanticFacts),
            filePaths = take(bundle.filePaths)
        )
    }
}
