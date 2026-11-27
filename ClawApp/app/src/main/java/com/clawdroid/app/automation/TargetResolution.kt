package com.clawdroid.app.automation

import java.util.Locale
import java.util.UUID

sealed interface TargetResolution {
    data class Resolved(
        val token: String,
        val nodeKey: String,
        val label: String,
        val bounds: ObservedBounds,
        val packageName: String,
        val snapshotGeneration: Long,
        val source: ObservationSource,
        val resolvedAtEpochMs: Long
    ) : TargetResolution

    data class NotFound(val query: String) : TargetResolution

    data class Ambiguous(val candidates: List<ObservedNode>) : TargetResolution
}

object TargetResolver {
    fun resolve(
        snapshot: ObservationSnapshot,
        targetText: String = "",
        targetViewId: String = ""
    ): TargetResolution {
        val text = targetText.trim()
        val viewId = targetViewId.trim()
        if (text.isBlank() && viewId.isBlank()) return TargetResolution.NotFound("empty target")
        val candidates = snapshot.nodes.filter { node ->
            node.clickable && node.enabled &&
                (text.isBlank() || node.label.contains(text, ignoreCase = true)) &&
                (viewId.isBlank() || node.viewId.equals(viewId, ignoreCase = true))
        }
        return when (candidates.size) {
            0 -> TargetResolution.NotFound("text=$text viewId=$viewId")
            1 -> {
                val node = candidates.single()
                TargetResolution.Resolved(
                    token = UUID.randomUUID().toString(),
                    nodeKey = node.id.ifBlank { node.viewId }.ifBlank { node.label },
                    label = node.label,
                    bounds = node.bounds,
                    packageName = snapshot.packageName,
                    snapshotGeneration = snapshot.generation,
                    source = snapshot.source,
                    resolvedAtEpochMs = System.currentTimeMillis()
                )
            }
            else -> TargetResolution.Ambiguous(candidates)
        }
    }

    fun validate(
        resolution: TargetResolution.Resolved,
        snapshot: ObservationSnapshot,
        nowEpochMs: Long = System.currentTimeMillis(),
        freshnessMs: Long = 15_000
    ): Boolean {
        if (!resolution.packageName.equals(snapshot.packageName, ignoreCase = true)) return false
        if (resolution.snapshotGeneration != snapshot.generation) return false
        if (!snapshot.isFresh(nowEpochMs, freshnessMs)) return false
        return snapshot.nodes.any { node ->
            nodeKey(node) == resolution.nodeKey &&
                node.clickable && node.enabled &&
                node.bounds == resolution.bounds
        }
    }

    private fun nodeKey(node: ObservedNode): String =
        node.id.ifBlank { node.viewId }.ifBlank { node.label.lowercase(Locale.ROOT) }
}
