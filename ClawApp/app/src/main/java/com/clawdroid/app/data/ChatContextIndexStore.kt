package com.clawdroid.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight inverted index of chat turns for retrieval into prompts.
 */
internal object ChatContextIndexStore {
    private const val PREFS = "clawdroid_chat_context_index"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_STATS = "stats"
    private const val MAX_ENTRIES = 400

    data class Entry(
        val sessionId: String,
        val role: String,
        val snippet: String,
        val tokens: List<String>,
        val atEpochMs: Long
    )

    fun indexTurn(
        context: Context,
        sessionId: String,
        role: String,
        content: String
    ) {
        val snippet = content.trim().take(280)
        if (snippet.isBlank()) return
        val tokens = tokenize(snippet)
        if (tokens.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = loadEntries(prefs).toMutableList()
        entries.add(
            0,
            Entry(
                sessionId = sessionId,
                role = role,
                snippet = snippet,
                tokens = tokens,
                atEpochMs = System.currentTimeMillis()
            )
        )
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        prefs.edit().putString(KEY_ENTRIES, serializeEntries(entries)).apply()
    }

    fun search(context: Context, query: String, limit: Int = 6): List<Entry> {
        val qTokens = tokenize(query)
        if (qTokens.isEmpty()) return emptyList()
        return loadEntries(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            .map { entry -> entry to qTokens.count { it in entry.tokens } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    fun recordCompression(context: Context, olderTurns: Int, summaryChars: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val obj = runCatching {
            JSONObject(prefs.getString(KEY_STATS, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        obj.put("compressCount", obj.optInt("compressCount", 0) + 1)
        obj.put("lastOlderTurns", olderTurns)
        obj.put("lastSummaryChars", summaryChars)
        obj.put("lastAt", System.currentTimeMillis())
        prefs.edit().putString(KEY_STATS, obj.toString()).apply()
    }

    fun statsSummary(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = loadEntries(prefs).size
        val stats = runCatching {
            JSONObject(prefs.getString(KEY_STATS, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        return "聊天索引 $count 条 · 压缩 ${stats.optInt("compressCount", 0)} 次"
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("""[^\p{L}\p{N}_]+"""))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(48)

    private fun loadEntries(prefs: android.content.SharedPreferences): List<Entry> {
        val raw = prefs.getString(KEY_ENTRIES, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val tokenArr = obj.optJSONArray("tokens") ?: JSONArray()
                    val tokens = (0 until tokenArr.length()).mapNotNull {
                        tokenArr.optString(it)?.takeIf { t -> t.isNotBlank() }
                    }
                    add(
                        Entry(
                            sessionId = obj.optString("sessionId"),
                            role = obj.optString("role"),
                            snippet = obj.optString("snippet"),
                            tokens = tokens,
                            atEpochMs = obj.optLong("atEpochMs")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun serializeEntries(entries: List<Entry>): String {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(
                JSONObject().apply {
                    put("sessionId", entry.sessionId)
                    put("role", entry.role)
                    put("snippet", entry.snippet)
                    put("tokens", JSONArray(entry.tokens))
                    put("atEpochMs", entry.atEpochMs)
                }
            )
        }
        return arr.toString()
    }
}
