package com.clawdroid.app.tools

/**
 * Process-wide cache of Runtime capability tokens for catalog filtering / gates.
 * Updated whenever get_capabilities / probe_session / get_runtime_status succeeds,
 * or when RuntimeEventService receives `capability_changed`.
 */
object LiveToolCapabilityStore {
    const val DEFAULT_MAX_AGE_MS: Long = 60_000L

    @Volatile
    private var capabilities: Set<String> = emptySet()

    @Volatile
    private var updatedAtMs: Long = 0L

    fun snapshot(): Set<String> = capabilities

    fun updatedAtMs(): Long = updatedAtMs

    fun isStale(maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Boolean {
        if (updatedAtMs <= 0L) return true
        if (capabilities.isEmpty()) return true
        return System.currentTimeMillis() - updatedAtMs > maxAgeMs
    }

    fun update(caps: Collection<String>) {
        capabilities = caps.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        updatedAtMs = System.currentTimeMillis()
    }

    fun updateFromCapabilityList(list: List<String>) {
        update(list)
    }

    /**
     * Parse `capability_changed` event payload (includes `capabilities` list from Runtime).
     * Returns true when the store was updated.
     */
    fun updateFromEventData(data: Map<String, Any?>): Boolean {
        val caps = data["capabilities"] ?: return false
        val parsed = when (caps) {
            is Collection<*> -> caps.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is Array<*> -> caps.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is String -> caps.split(',', ' ', '\t', ';', '[', ']')
                .map { it.trim().trim('"').trim('\'') }
                .filter { it.isNotEmpty() }
            else -> emptyList()
        }
        if (parsed.isEmpty()) return false
        update(parsed)
        return true
    }

    /**
     * Best-effort parse from tool output lines like `capabilities=[a, b, c]` or `capabilities=a, b`.
     */
    fun updateFromToolOutput(output: String) {
        val match = Regex(
            """capabilities\s*=\s*\[([^\]]*)]|capabilities\s*=\s*([^\n]+)""",
            RegexOption.IGNORE_CASE
        ).find(output) ?: return
        val raw = (match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: match.groupValues.getOrNull(2).orEmpty())
        val parsed = raw.split(',', ' ', '\t', ';')
            .map { it.trim().trim('[', ']', '"', '\'') }
            .filter { it.contains('.') || it.startsWith("system") || it.startsWith("file") ||
                it.startsWith("shell") || it.startsWith("input") || it.startsWith("screen") ||
                it.startsWith("event") || it.startsWith("task") }
        if (parsed.isNotEmpty()) {
            update(parsed)
        }
    }

    fun clear() {
        capabilities = emptySet()
        updatedAtMs = 0L
    }
}
