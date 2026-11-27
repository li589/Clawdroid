package com.clawdroid.app.automation

import com.clawdroid.app.focus.LiveXposedFocusStore
import com.clawdroid.app.focus.LiveXposedViewStore
import org.json.JSONArray
import org.json.JSONObject

class LsposedFocusObservationSource(
    private val rawProvider: () -> String? = { LiveXposedFocusStore.readRaw() },
    private val updatedAtProvider: () -> Long = LiveXposedFocusStore::updatedAtMs,
    private val summaryProvider: () -> String = LiveXposedFocusStore::summaryForProbe
) : ObservationSourceProvider {
    override fun snapshot(packageName: String): ObservationSnapshot? = runCatching {
        val raw = rawProvider()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val json = JSONObject(raw)
        val actualPackage = json.optString("package_name")
        if (actualPackage.isBlank() || (packageName.isNotBlank() && !actualPackage.equals(packageName, ignoreCase = true))) return null
        ObservationSnapshot(
            source = ObservationSource.LsposedFocus,
            packageName = actualPackage,
            activityClass = json.optString("activity_class"),
            generation = updatedAtProvider(),
            timestampEpochMs = json.optLong("loaded_at_epoch_ms", updatedAtProvider()),
            focusSummary = summaryProvider()
        )
    }.getOrNull()
}

class LsposedViewObservationSource(
    private val rawProvider: () -> String? = { LiveXposedViewStore.readRaw() },
    private val updatedAtProvider: () -> Long = LiveXposedViewStore::updatedAtMs,
    private val summaryProvider: () -> String = LiveXposedViewStore::summaryForProbe
) : ObservationSourceProvider {
    override fun snapshot(packageName: String): ObservationSnapshot? = runCatching {
        val raw = rawProvider()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val json = JSONObject(raw)
        val actualPackage = json.optString("package_name")
        if (actualPackage.isBlank() || (packageName.isNotBlank() && !actualPackage.equals(packageName, ignoreCase = true))) return null
        val nodesArray = json.optJSONArray("nodes") ?: JSONArray()
        val nodes = buildList {
            for (index in 0 until nodesArray.length()) {
                val node = nodesArray.optJSONObject(index) ?: continue
                val className = node.optString("class")
                val semantics = node.optString("sem")
                val text = node.optString("text")
                val description = node.optString("desc")
                add(
                    ObservedNode(
                        id = node.optString("id"),
                        label = text.ifBlank { description },
                        viewId = node.optString("id"),
                        className = className,
                        clickable = semantics.contains("click", ignoreCase = true) ||
                            className.contains("Button", ignoreCase = true),
                        enabled = node.optString("visibility").equals("visible", ignoreCase = true),
                        bounds = parseBounds(node.optString("bounds"))
                    )
                )
            }
        }
        ObservationSnapshot(
            source = ObservationSource.LsposedView,
            packageName = actualPackage,
            activityClass = json.optString("activity_class"),
            generation = updatedAtProvider(),
            timestampEpochMs = json.optLong("loaded_at_epoch_ms", updatedAtProvider()),
            nodes = nodes,
            viewSummary = summaryProvider()
        )
    }.getOrNull()

    private fun parseBounds(raw: String): ObservedBounds {
        val match = BOUNDS_REGEX.find(raw) ?: return ObservedBounds(0, 0, 0, 0)
        return ObservedBounds(
            left = match.groupValues[1].toInt(),
            top = match.groupValues[2].toInt(),
            right = match.groupValues[3].toInt(),
            bottom = match.groupValues[4].toInt()
        )
    }

    companion object {
        private val BOUNDS_REGEX = Regex("""\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")
    }
}
