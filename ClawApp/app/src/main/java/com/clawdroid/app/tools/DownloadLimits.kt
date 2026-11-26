package com.clawdroid.app.tools

internal object DownloadLimits {
    const val MAX_ACTIVE_DOWNLOADS = 4
    const val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024

    fun canStart(activeDownloads: Int): Boolean = activeDownloads < MAX_ACTIVE_DOWNLOADS

    fun isWithinSizeLimit(bytes: Long): Boolean = bytes in 0..MAX_DOWNLOAD_BYTES
}
