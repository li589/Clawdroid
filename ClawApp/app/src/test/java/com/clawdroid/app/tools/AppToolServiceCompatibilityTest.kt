package com.clawdroid.app.tools

import android.content.pm.PackageInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class AppToolServiceCompatibilityTest {
    @Test
    fun versionCodeForUsesLegacyFieldBelowApi28() {
        val info = PackageInfo().apply { versionCode = 7 }

        assertEquals(7L, versionCodeFor(info, 26))
        assertEquals(7L, versionCodeFor(info, 27))
    }

    @Test
    fun versionCodeForUsesLongFieldFromApi28() {
        val info = mockk<PackageInfo>()
        every { info.longVersionCode } returns 0x1_0000_0007L

        assertEquals(0x1_0000_0007L, versionCodeFor(info, 28))
    }
}
