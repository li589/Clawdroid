package com.clawdroid.app.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObservationPortTest {
    @Test
    fun staleLsposedFallsBackToAccessibility() {
        val now = 100_000L
        val port = ObservationPort(
            lsposedFocus = fakeProvider(snapshot(ObservationSource.LsposedFocus, now - 20_000)),
            lsposedView = fakeProvider(snapshot(ObservationSource.LsposedView, now - 20_000)),
            accessibility = fakeProvider(snapshot(ObservationSource.Accessibility, now - 100)),
            now = { now }
        )

        assertEquals(ObservationSource.Accessibility, port.current("com.example.app")?.source)
    }

    @Test
    fun packageMismatchIsRejected() {
        val now = 100_000L
        val port = ObservationPort(
            lsposedFocus = fakeProvider(snapshot(ObservationSource.LsposedFocus, now, packageName = "com.other")),
            lsposedView = fakeProvider(null),
            accessibility = fakeProvider(null),
            now = { now }
        )

        assertNull(port.current("com.example.app"))
    }

    private fun snapshot(
        source: ObservationSource,
        timestamp: Long,
        packageName: String = "com.example.app"
    ) = ObservationSnapshot(
        source = source,
        packageName = packageName,
        generation = 1,
        timestampEpochMs = timestamp
    )

    private fun fakeProvider(value: ObservationSnapshot?) = object : ObservationSourceProvider {
        override fun snapshot(packageName: String): ObservationSnapshot? = value
    }
}
