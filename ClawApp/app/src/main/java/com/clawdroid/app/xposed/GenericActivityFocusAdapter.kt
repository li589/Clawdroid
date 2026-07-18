package com.clawdroid.app.xposed

/**
 * Generic target-app adapter that records the currently resumed Activity.
 */
internal class GenericActivityFocusAdapter(
    targetPackages: Set<String>
) : FocusSnapshotAdapter(
    id = "activity_focus",
    targetPackages = targetPackages,
    targetLabel = null
)
