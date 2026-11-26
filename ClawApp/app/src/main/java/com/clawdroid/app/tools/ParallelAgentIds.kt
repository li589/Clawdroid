package com.clawdroid.app.tools

internal const val MAX_PARALLEL_AGENTS = 4

internal fun parseParallelAgentIds(raw: String, max: Int = MAX_PARALLEL_AGENTS): List<String>? {
    val ids = raw.split(',', ';', '|', ' ', '\n', '\t')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    return ids.takeIf { it.isNotEmpty() && it.size <= max }
}
