package com.clawdroid.app.ui

/**
 * 大体量模型列表的筛选与分组辅助。
 */
internal object ModelListCatalog {
    private val COMMON_KEYWORDS = listOf(
        "gpt", "o1", "o3", "o4", "claude", "gemini", "deepseek", "qwen", "glm",
        "llama", "mistral", "kimi", "moonshot", "yi-", "ernie", "hunyuan",
        "minimax", "command", "grok", "embedding", "rerank", "tts", "whisper",
        "flux", "sdxl", "video", "image"
    )

    data class FilterResult(
        val query: String,
        val activeToken: String?,
        val filtered: List<String>,
        val total: Int
    ) {
        val shown: Int get() = filtered.size

        fun summary(): String {
            val tokenPart = activeToken?.takeIf { it.isNotBlank() }?.let { " · 标签 $it" }.orEmpty()
            val queryPart = query.trim().takeIf { it.isNotBlank() }?.let { " · 搜索 \"$it\"" }.orEmpty()
            return "显示 $shown / 共 $total$tokenPart$queryPart"
        }
    }

    fun filter(
        models: List<String>,
        query: String,
        activeToken: String? = null,
        limit: Int = 300
    ): FilterResult {
        val q = query.trim()
        val token = activeToken?.trim().orEmpty()
        val filtered = models.asSequence()
            .filter { model ->
                val hitQuery = q.isEmpty() || model.contains(q, ignoreCase = true)
                val hitToken = token.isEmpty() || model.contains(token, ignoreCase = true)
                hitQuery && hitToken
            }
            .take(limit)
            .toList()
        return FilterResult(
            query = q,
            activeToken = token.takeIf { it.isNotEmpty() },
            filtered = filtered,
            total = models.size
        )
    }

    /**
     * 从模型 ID 提取快捷筛选标签（厂商前缀 + 常见关键字）。
     */
    fun suggestTokens(models: List<String>, max: Int = 10): List<String> {
        if (models.isEmpty()) return emptyList()
        val counts = linkedMapOf<String, Int>()

        fun bump(token: String) {
            val key = token.trim().lowercase()
            if (key.length < 2) return
            counts[key] = (counts[key] ?: 0) + 1
        }

        models.forEach { model ->
            val slash = model.indexOf('/')
            if (slash > 0) {
                bump(model.substring(0, slash))
            }
            val lower = model.lowercase()
            COMMON_KEYWORDS.forEach { keyword ->
                if (lower.contains(keyword)) {
                    bump(keyword.trimEnd('-'))
                }
            }
        }

        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .filter { token ->
                // 至少命中 2 个，或是带厂商前缀的（出现次数 >= 1 且含非纯数字）
                (counts[token] ?: 0) >= 2 || models.any { it.startsWith("$token/", ignoreCase = true) }
            }
            .take(max)
    }
}
