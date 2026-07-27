package com.clawdroid.app.data.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Common model context-window catalog (official / major proxies / API forwarders).
 * Defaults are conservative when unknown.
 */
internal object ModelContextWindowCatalog {
    const val DEFAULT_WINDOW = 128_000

    data class Entry(
        val pattern: String,
        val contextWindow: Int,
        val source: String,
        val notes: String = ""
    )

    @Volatile
    private var cached: List<Entry>? = null

    fun entries(appContext: Context? = null): List<Entry> {
        cached?.let { return it }
        val fromAsset = appContext?.let { loadFromAssets(it) }.orEmpty()
        val merged = (fromAsset + builtinEntries())
            .distinctBy { it.pattern.lowercase() }
            .sortedByDescending { it.pattern.length }
        cached = merged
        return merged
    }

    fun resolve(modelName: String, appContext: Context? = null): Int {
        val name = modelName.trim().lowercase()
        if (name.isBlank()) return DEFAULT_WINDOW
        val hit = entries(appContext).firstOrNull { entry ->
            name == entry.pattern.lowercase() ||
                name.contains(entry.pattern.lowercase()) ||
                name.endsWith("/${entry.pattern.lowercase()}")
        }
        return hit?.contextWindow ?: DEFAULT_WINDOW
    }

    fun suggestForUi(query: String = "", limit: Int = 40, appContext: Context? = null): List<Entry> {
        val q = query.trim().lowercase()
        return entries(appContext)
            .asSequence()
            .filter { q.isEmpty() || it.pattern.lowercase().contains(q) || it.source.lowercase().contains(q) }
            .sortedBy { it.pattern.lowercase() }
            .take(limit)
            .toList()
    }

    private fun loadFromAssets(context: Context): List<Entry> {
        return runCatching {
            context.assets.open("claw/models/context-windows.json").bufferedReader().use { reader ->
                val root = JSONObject(reader.readText())
                val arr = root.optJSONArray("models") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val pattern = obj.optString("pattern").trim()
                        val window = obj.optInt("contextWindow", 0)
                        if (pattern.isBlank() || window <= 0) continue
                        add(
                            Entry(
                                pattern = pattern,
                                contextWindow = window,
                                source = obj.optString("source", "catalog"),
                                notes = obj.optString("notes", "")
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun builtinEntries(): List<Entry> = listOf(
        Entry("gpt-5", 400_000, "OpenAI / 常见转发", "含 gpt-5* 系列"),
        Entry("gpt-4.1", 1_047_576, "OpenAI", "1M context"),
        Entry("gpt-4o", 128_000, "OpenAI / Azure / OpenRouter"),
        Entry("gpt-4-turbo", 128_000, "OpenAI"),
        Entry("gpt-4", 8_192, "OpenAI", "经典 8k；turbo 见 gpt-4-turbo"),
        Entry("gpt-3.5-turbo", 16_385, "OpenAI"),
        Entry("o3", 200_000, "OpenAI"),
        Entry("o4-mini", 200_000, "OpenAI"),
        Entry("o1", 200_000, "OpenAI"),
        Entry("claude-opus-4", 200_000, "Anthropic / 常见代理"),
        Entry("claude-sonnet-4", 200_000, "Anthropic / 常见代理"),
        Entry("claude-3-5-sonnet", 200_000, "Anthropic"),
        Entry("claude-3-opus", 200_000, "Anthropic"),
        Entry("claude-3-haiku", 200_000, "Anthropic"),
        Entry("claude-3-sonnet", 200_000, "Anthropic"),
        Entry("gemini-2.5-pro", 1_048_576, "Google / OpenRouter"),
        Entry("gemini-2.0-flash", 1_048_576, "Google"),
        Entry("gemini-1.5-pro", 2_097_152, "Google"),
        Entry("gemini-1.5-flash", 1_048_576, "Google"),
        Entry("deepseek-chat", 64_000, "DeepSeek / 硅基流动等"),
        Entry("deepseek-reasoner", 64_000, "DeepSeek"),
        Entry("deepseek-r1", 64_000, "DeepSeek / 常见转发"),
        Entry("deepseek-v3", 64_000, "DeepSeek / 常见转发"),
        Entry("qwen-max", 32_768, "阿里云 / 百炼"),
        Entry("qwen-plus", 131_072, "阿里云 / 百炼"),
        Entry("qwen-turbo", 131_072, "阿里云 / 百炼"),
        Entry("qwen2.5-72b", 131_072, "开源 / 硅基流动 / OpenRouter"),
        Entry("qwen2.5", 32_768, "开源 / 常见转发"),
        Entry("qwq", 131_072, "通义 / 常见转发"),
        Entry("glm-4", 128_000, "智谱"),
        Entry("glm-4-plus", 128_000, "智谱"),
        Entry("kimi", 128_000, "Moonshot"),
        Entry("moonshot", 128_000, "Moonshot"),
        Entry("yi-lightning", 16_384, "零一万物"),
        Entry("yi-large", 32_768, "零一万物"),
        Entry("llama-3.1-405b", 128_000, "Meta / OpenRouter / Groq"),
        Entry("llama-3.1", 128_000, "Meta / 常见转发"),
        Entry("llama-3.3", 128_000, "Meta / 常见转发"),
        Entry("mistral-large", 128_000, "Mistral"),
        Entry("mistral-small", 32_768, "Mistral"),
        Entry("grok", 131_072, "xAI / 常见转发"),
        Entry("command-r-plus", 128_000, "Cohere"),
        Entry("command-r", 128_000, "Cohere")
    )
}
