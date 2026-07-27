package com.clawdroid.app.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Simple file path index under app-accessible roots for prompt grounding.
 */
internal object FileIndexStore {
    private const val PREFS = "clawdroid_file_index"
    private const val KEY_PATHS = "paths"
    private const val MAX_PATHS = 300

    fun rememberPath(context: Context, path: String) {
        val normalized = path.trim()
        if (normalized.isBlank() || normalized.length > 512) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = loadPaths(prefs).toMutableList()
        existing.removeAll { it.equals(normalized, ignoreCase = true) }
        existing.add(0, normalized)
        while (existing.size > MAX_PATHS) existing.removeAt(existing.lastIndex)
        prefs.edit().putString(KEY_PATHS, JSONArray(existing).toString()).apply()
    }

    fun search(context: Context, query: String, limit: Int = 8): List<String> {
        val q = query.trim().lowercase()
        val all = loadPaths(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        if (q.isBlank()) return all.take(limit)
        return all.filter { it.lowercase().contains(q) }.take(limit)
    }

    fun scanSandbox(context: Context, limit: Int = 80): Int {
        val root = File(context.filesDir, "sandbox")
        if (!root.isDirectory) return 0
        var added = 0
        root.walkTopDown().maxDepth(4).forEach { file ->
            if (added >= limit) return@forEach
            if (file.isFile) {
                rememberPath(context, file.absolutePath)
                added++
            }
        }
        return added
    }

    fun summary(context: Context): String {
        val n = loadPaths(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).size
        return "文件索引 $n 条"
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun loadPaths(prefs: android.content.SharedPreferences): List<String> {
        val raw = prefs.getString(KEY_PATHS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it)?.takeIf { p -> p.isNotBlank() } }
        }.getOrDefault(emptyList())
    }
}
