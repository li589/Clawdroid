package com.clawdroid.app.automation

internal class AccessibilityObservationSource(
    private val snapshotProvider: () -> AccessibilitySnapshot = {
        AutomationRuntimeStore.accessibilitySnapshot.value
    },
    private val nodesProvider: () -> List<NodeSemantic> = {
        AutomationRuntimeStore.currentNodeSemantics()
    },
    private val generationProvider: () -> Long = AutomationRuntimeStore::observationGeneration
) : ObservationSourceProvider {
    override fun snapshot(packageName: String): ObservationSnapshot? {
        val snapshot = snapshotProvider()
        if (!snapshot.serviceConnected) return null
        if (snapshot.packageName.isBlank() ||
            (packageName.isNotBlank() && !snapshot.packageName.equals(packageName, ignoreCase = true))
        ) {
            return null
        }
        return ObservationSnapshot(
            source = ObservationSource.Accessibility,
            packageName = snapshot.packageName,
            activityClass = snapshot.className,
            generation = generationProvider(),
            timestampEpochMs = snapshot.updatedAtEpochMs,
            nodes = nodesProvider().map { node ->
                ObservedNode(
                    id = node.viewId,
                    label = node.text,
                    viewId = node.viewId,
                    className = node.className,
                    clickable = node.clickable,
                    enabled = node.enabled,
                    bounds = ObservedBounds(
                        left = node.bounds.left,
                        top = node.bounds.top,
                        right = node.bounds.right,
                        bottom = node.bounds.bottom
                    )
                )
            },
            focusSummary = snapshot.textSummary,
            viewSummary = snapshot.clickableTargetSummary
        )
    }
}
