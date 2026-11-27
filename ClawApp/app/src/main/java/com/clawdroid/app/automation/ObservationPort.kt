package com.clawdroid.app.automation

class ObservationPort(
    private val lsposedFocus: ObservationSourceProvider,
    private val lsposedView: ObservationSourceProvider,
    private val accessibility: ObservationSourceProvider,
    private val now: () -> Long = System::currentTimeMillis,
    private val freshnessMs: Long = 15_000
) {
    fun current(packageName: String): ObservationSnapshot? {
        val expected = packageName.trim()
        val nowEpochMs = now()
        return sequenceOf(lsposedFocus, lsposedView, accessibility)
            .mapNotNull { provider -> runCatching { provider.snapshot(expected) }.getOrNull() }
            .firstOrNull { snapshot ->
                (expected.isBlank() || snapshot.packageName.equals(expected, ignoreCase = true)) &&
                    snapshot.isFresh(nowEpochMs, freshnessMs)
            }
    }
}
