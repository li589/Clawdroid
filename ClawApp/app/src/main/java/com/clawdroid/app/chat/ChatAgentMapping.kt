package com.clawdroid.app.chat

import com.clawdroid.app.skills.ClawAgentCatalog
import com.clawdroid.app.skills.ClawAgentDefinition

internal fun ChatTaskAction.toAgentId(): String = when (this) {
    ChatTaskAction.ConfirmThenSafeTap -> "confirm_then_safe_tap"
    ChatTaskAction.ProbeThenCapabilities -> "probe_then_capabilities"
    ChatTaskAction.CaptureThenPreview -> "capture_then_preview"
    ChatTaskAction.RuntimeHealthSweep -> "runtime_health_sweep"
    ChatTaskAction.SwipeThenCapture -> "swipe_then_capture"
}

internal fun ChatTaskAction.toAgentDefinition(): ClawAgentDefinition {
    return requireNotNull(ClawAgentCatalog.byId(toAgentId())) {
        "Missing agent mapping for $this"
    }
}
