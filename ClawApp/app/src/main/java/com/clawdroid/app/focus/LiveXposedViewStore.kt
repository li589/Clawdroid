package com.clawdroid.app.focus

import com.clawdroid.app.xposed.ClawJsonLite
import com.clawdroid.app.xposed.ViewHierarchyDumper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-scoped cache of the latest Xposed view hierarchy snapshot.
 */
object LiveXposedViewStore {
    @Volatile
    private var rawJson: String? = null

    @Volatile
    private var updatedAtMs: Long = 0L

    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()

    fun hasLive(): Boolean = !rawJson.isNullOrBlank()

    fun updatedAtMs(): Long = updatedAtMs

    fun readRaw(): String? = rawJson

    fun summaryForProbe(): String {
        val raw = rawJson
        if (raw.isNullOrBlank()) {
            return ""
        }
        return ViewHierarchyDumper.formatSummary(raw)
    }

    fun updateFromJson(json: String): Boolean {
        val trimmed = json.trim()
        if (!ClawJsonLite.hasRequiredIdentity(trimmed)) {
            return false
        }
        rawJson = trimmed
        updatedAtMs = System.currentTimeMillis()
        val summary = ViewHierarchyDumper.formatSummary(trimmed)
        listeners.forEach { listener ->
            runCatching { listener(summary) }
        }
        return true
    }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun clearForTests() {
        rawJson = null
        updatedAtMs = 0L
        listeners.clear()
    }
}
