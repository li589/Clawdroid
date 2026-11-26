package com.clawdroid.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadLimitsTest {
    @Test
    fun enforcesActiveAndSizeLimits() {
        assertTrue(DownloadLimits.canStart(DownloadLimits.MAX_ACTIVE_DOWNLOADS - 1))
        assertFalse(DownloadLimits.canStart(DownloadLimits.MAX_ACTIVE_DOWNLOADS))
        assertTrue(DownloadLimits.isWithinSizeLimit(DownloadLimits.MAX_DOWNLOAD_BYTES))
        assertFalse(DownloadLimits.isWithinSizeLimit(DownloadLimits.MAX_DOWNLOAD_BYTES + 1))
    }
}
