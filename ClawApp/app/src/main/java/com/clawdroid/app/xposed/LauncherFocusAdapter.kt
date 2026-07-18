package com.clawdroid.app.xposed

/**
 * Launcher-targeted focus adapter.
 */
internal class LauncherFocusAdapter(
    targetPackages: Set<String>
) : FocusSnapshotAdapter(
    id = "launcher_focus",
    targetPackages = targetPackages,
    targetLabel = "launcher"
)
