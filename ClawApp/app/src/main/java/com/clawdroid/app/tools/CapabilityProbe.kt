package com.clawdroid.app.tools

import kotlinx.coroutines.withTimeoutOrNull

/**
 * Best-effort refresh of [LiveToolCapabilityStore] when the cache is empty or stale.
 */
class CapabilityProbe(
    private val toolExecutor: ClawToolExecutor,
    private val maxAgeMs: Long = LiveToolCapabilityStore.DEFAULT_MAX_AGE_MS,
    private val timeoutMs: Long = 2_500L
) {
    suspend fun refreshIfStale(): Boolean {
        if (!LiveToolCapabilityStore.isStale(maxAgeMs)) {
            return false
        }
        return withTimeoutOrNull(timeoutMs) {
            val caps = toolExecutor.getCapabilities()
            if (caps.success) {
                true
            } else {
                toolExecutor.getHealth().success
            }
        } ?: false
    }
}
