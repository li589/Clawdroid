package com.clawdroid.app.automation

enum class ObservationSource {
    LsposedFocus,
    LsposedView,
    Accessibility
}

data class ObservedBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

data class ObservedNode(
    val id: String,
    val label: String,
    val viewId: String,
    val className: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val bounds: ObservedBounds
)

data class ObservationSnapshot(
    val source: ObservationSource,
    val packageName: String,
    val activityClass: String = "",
    val generation: Long,
    val timestampEpochMs: Long,
    val nodes: List<ObservedNode> = emptyList(),
    val focusSummary: String = "",
    val viewSummary: String = ""
) {
    fun isFresh(nowEpochMs: Long, maxAgeMs: Long): Boolean =
        timestampEpochMs > 0 && nowEpochMs >= timestampEpochMs && nowEpochMs - timestampEpochMs <= maxAgeMs
}

interface ObservationSourceProvider {
    fun snapshot(packageName: String): ObservationSnapshot?
}
