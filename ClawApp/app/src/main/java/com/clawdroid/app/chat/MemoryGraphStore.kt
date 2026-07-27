package com.clawdroid.app.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight memory graph: facts + edges for agent grounding.
 */
internal object MemoryGraphStore {
    private const val PREFS = "clawdroid_memory_graph"
    private const val KEY_NODES = "nodes"
    private const val KEY_EDGES = "edges"
    private const val MAX_NODES = 200
    private const val MAX_EDGES = 400

    data class Node(
        val id: String,
        val label: String,
        val content: String,
        val tags: List<String>,
        val updatedAtEpochMs: Long
    )

    data class Edge(
        val fromId: String,
        val toId: String,
        val relation: String
    )

    fun upsertFact(
        context: Context,
        id: String,
        label: String,
        content: String,
        tags: List<String> = emptyList()
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nodes = loadNodes(prefs).toMutableList()
        nodes.removeAll { it.id == id }
        nodes.add(
            0,
            Node(
                id = id,
                label = label.take(80),
                content = content.take(800),
                tags = tags.map { it.take(32) }.distinct().take(8),
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
        while (nodes.size > MAX_NODES) nodes.removeAt(nodes.lastIndex)
        prefs.edit().putString(KEY_NODES, serializeNodes(nodes)).apply()
    }

    fun link(context: Context, fromId: String, toId: String, relation: String) {
        if (fromId.isBlank() || toId.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edges = loadEdges(prefs).toMutableList()
        edges.removeAll { it.fromId == fromId && it.toId == toId && it.relation == relation }
        edges.add(0, Edge(fromId, toId, relation.take(40)))
        while (edges.size > MAX_EDGES) edges.removeAt(edges.lastIndex)
        prefs.edit().putString(KEY_EDGES, serializeEdges(edges)).apply()
    }

    fun topFacts(context: Context, limit: Int = 8): List<Node> =
        loadNodes(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).take(limit)

    fun search(context: Context, query: String, limit: Int = 6): List<Node> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return topFacts(context, limit)
        return loadNodes(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            .filter {
                it.label.lowercase().contains(q) ||
                    it.content.lowercase().contains(q) ||
                    it.tags.any { tag -> tag.lowercase().contains(q) }
            }
            .take(limit)
    }

    fun summary(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return "记忆图谱 ${loadNodes(prefs).size} 节点 · ${loadEdges(prefs).size} 边"
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun loadNodes(prefs: android.content.SharedPreferences): List<Node> {
        val raw = prefs.getString(KEY_NODES, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val tagsArr = obj.optJSONArray("tags") ?: JSONArray()
                    add(
                        Node(
                            id = obj.optString("id"),
                            label = obj.optString("label"),
                            content = obj.optString("content"),
                            tags = (0 until tagsArr.length()).mapNotNull {
                                tagsArr.optString(it)?.takeIf { t -> t.isNotBlank() }
                            },
                            updatedAtEpochMs = obj.optLong("updatedAtEpochMs")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun loadEdges(prefs: android.content.SharedPreferences): List<Edge> {
        val raw = prefs.getString(KEY_EDGES, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        Edge(
                            fromId = obj.optString("fromId"),
                            toId = obj.optString("toId"),
                            relation = obj.optString("relation")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun serializeNodes(nodes: List<Node>): String {
        val arr = JSONArray()
        nodes.forEach { node ->
            arr.put(
                JSONObject().apply {
                    put("id", node.id)
                    put("label", node.label)
                    put("content", node.content)
                    put("tags", JSONArray(node.tags))
                    put("updatedAtEpochMs", node.updatedAtEpochMs)
                }
            )
        }
        return arr.toString()
    }

    private fun serializeEdges(edges: List<Edge>): String {
        val arr = JSONArray()
        edges.forEach { edge ->
            arr.put(
                JSONObject().apply {
                    put("fromId", edge.fromId)
                    put("toId", edge.toId)
                    put("relation", edge.relation)
                }
            )
        }
        return arr.toString()
    }
}
