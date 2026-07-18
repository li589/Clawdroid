package com.clawdroid.app.xposed

/**
 * Caps-limited view hierarchy metadata (no bitmaps / passwords / WebView internals).
 * [NodeSpec] is pure data so unit tests do not need Android View instances.
 */
internal object ViewHierarchyDumper {
    const val SCHEMA_VERSION = 1
    const val MAX_DEPTH = 4
    const val MAX_NODES = 32
    private const val TEXT_MAX = 80

    data class NodeSpec(
        val className: String,
        val id: String = "",
        val text: String = "",
        val contentDescription: String = "",
        val bounds: String = "",
        val visibility: String = "visible",
        val childCount: Int = 0,
        val depth: Int = 0,
        val semantics: String = "",
        val children: List<NodeSpec> = emptyList()
    )

    fun flatten(root: NodeSpec, maxDepth: Int = MAX_DEPTH, maxNodes: Int = MAX_NODES): List<NodeSpec> {
        val out = ArrayList<NodeSpec>(maxNodes.coerceAtMost(64))
        fun walk(node: NodeSpec) {
            if (out.size >= maxNodes) return
            if (node.depth > maxDepth) return
            out += node.copy(children = emptyList())
            if (node.depth >= maxDepth) return
            for (child in node.children) {
                if (out.size >= maxNodes) return
                walk(child)
            }
        }
        walk(root)
        return out
    }

    fun buildPayload(
        packageName: String,
        activityClass: String,
        adapterId: String,
        loadedAtEpochMs: Long,
        nodes: List<NodeSpec>,
        truncated: Boolean,
        composeSurface: Boolean = nodes.any { isComposeClass(it.className) }
    ): String {
        return buildString {
            append('{')
            append("\"schema_version\":").append(SCHEMA_VERSION)
            append(",\"adapter_id\":\"").append(escape(adapterId)).append('"')
            append(",\"package_name\":\"").append(escape(packageName)).append('"')
            append(",\"activity_class\":\"").append(escape(activityClass)).append('"')
            append(",\"loaded_at_epoch_ms\":").append(loadedAtEpochMs)
            append(",\"max_depth\":").append(MAX_DEPTH)
            append(",\"max_nodes\":").append(MAX_NODES)
            append(",\"node_count\":").append(nodes.size)
            append(",\"truncated\":").append(truncated)
            append(",\"compose_surface\":").append(composeSurface)
            append(",\"nodes\":[")
            nodes.forEachIndexed { index, node ->
                if (index > 0) append(',')
                append('{')
                append("\"depth\":").append(node.depth)
                append(",\"class\":\"").append(escape(node.className)).append('"')
                if (isComposeClass(node.className)) {
                    append(",\"compose\":true")
                }
                if (node.id.isNotBlank()) {
                    append(",\"id\":\"").append(escape(node.id.take(64))).append('"')
                }
                if (node.text.isNotBlank()) {
                    append(",\"text\":\"").append(escape(node.text.take(TEXT_MAX))).append('"')
                }
                if (node.contentDescription.isNotBlank()) {
                    append(",\"desc\":\"").append(escape(node.contentDescription.take(TEXT_MAX))).append('"')
                }
                if (node.semantics.isNotBlank()) {
                    append(",\"sem\":\"").append(escape(node.semantics.take(160))).append('"')
                }
                if (node.bounds.isNotBlank()) {
                    append(",\"bounds\":\"").append(escape(node.bounds)).append('"')
                }
                append(",\"visibility\":\"").append(escape(node.visibility)).append('"')
                append(",\"child_count\":").append(node.childCount)
                append('}')
            }
            append(']')
            append('}')
        }
    }

    fun isComposeClass(className: String): Boolean {
        val name = className.lowercase()
        return name.contains("composeview") ||
            name.contains("androidcomposeview") ||
            (name.contains("composition") && name.contains("androidx"))
    }

    fun formatSummary(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) {
            return "暂无 View 层次快照"
        }
        val packageName = ClawJsonLite.string(text, "package_name").orEmpty()
        val activityClass = ClawJsonLite.string(text, "activity_class").orEmpty()
        val nodeCount = ClawJsonLite.long(text, "node_count") ?: 0L
        val truncated = ClawJsonLite.boolean(text, "truncated") == true
        val compose = ClawJsonLite.boolean(text, "compose_surface") == true
        val hasSem = text.contains("\"sem\"")
        val adapterId = ClawJsonLite.string(text, "adapter_id").orEmpty()
        if (packageName.isBlank() || activityClass.isBlank()) {
            return "View 快照已存在但字段不完整"
        }
        val truncSuffix = if (truncated) " truncated" else ""
        val composeSuffix = if (compose) " compose" else ""
        val semSuffix = if (hasSem) " sem" else ""
        val adapterSuffix = if (adapterId.isNotBlank()) " [$adapterId]" else ""
        return "$packageName/$activityClass nodes=$nodeCount$truncSuffix$composeSuffix$semSuffix$adapterSuffix"
    }

    fun classSimpleName(fqcn: String): String {
        val slash = fqcn.lastIndexOf('.')
        return if (slash >= 0 && slash + 1 < fqcn.length) fqcn.substring(slash + 1) else fqcn
    }

    private fun escape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
