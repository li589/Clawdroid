package com.clawdroid.app.automation

class ObservationPort(
    private val lsposedFocus: ObservationSourceProvider,
    private val lsposedView: ObservationSourceProvider,
    private val accessibility: ObservationSourceProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    private val freshnessMs: Long = 15_000
) {
    fun nowEpochMs(): Long = clock()

    fun latest(): ObservationSnapshot? {
        val nowEpochMs = clock()
        return sequenceOf(lsposedFocus, lsposedView, accessibility)
            .mapNotNull { provider -> runCatching { provider.snapshot("") }.getOrNull() }
            .firstOrNull { it.isFresh(nowEpochMs, freshnessMs) }
    }

    fun current(packageName: String): ObservationSnapshot? {
        val expected = packageName.trim()
        return latest()?.takeIf { expected.isBlank() || it.packageName.equals(expected, ignoreCase = true) }
    }
}
