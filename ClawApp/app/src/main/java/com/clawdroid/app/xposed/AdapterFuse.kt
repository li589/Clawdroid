package com.clawdroid.app.xposed

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-adapter fuse: after [threshold] in-process failures, skip further installs until
 * process restart. Marker files under xposed/ are diagnostic/audit only and do not
 * block install across restarts.
 */
internal object AdapterFuse {
    private val failures = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile
    internal var dirOverride: File? = null

    @Volatile
    internal var thresholdOverride: Int? = null

    fun threshold(config: XposedAdapterConfig = XposedAdapterConfig.load()): Int {
        return thresholdOverride ?: config.fuseAfterFailures.coerceIn(1, 100)
    }

    fun isBlown(adapterId: String, config: XposedAdapterConfig = XposedAdapterConfig.load()): Boolean {
        val count = failures[adapterId]?.get() ?: 0
        return count >= threshold(config)
    }

    fun recordFailure(adapterId: String, config: XposedAdapterConfig = XposedAdapterConfig.load()) {
        val count = failures.getOrPut(adapterId) { AtomicInteger(0) }.incrementAndGet()
        if (count >= threshold(config)) {
            blow(adapterId, count)
        }
    }

    /** Separate fuse id for view-dump side effects so focus hooks stay installable. */
    fun viewFuseId(adapterId: String): String = "${adapterId}_view"

    fun recordViewFailure(adapterId: String, config: XposedAdapterConfig = XposedAdapterConfig.load()) {
        recordFailure(viewFuseId(adapterId), config)
    }

    fun isViewBlown(adapterId: String, config: XposedAdapterConfig = XposedAdapterConfig.load()): Boolean {
        return isBlown(viewFuseId(adapterId), config)
    }

    fun blow(adapterId: String, failureCount: Int) {
        runCatching {
            val file = fuseFile(adapterId)
            file.parentFile?.mkdirs()
            file.writeText(
                """{"adapter_id":"$adapterId","failures":$failureCount,"blown_at_epoch_ms":${System.currentTimeMillis()}}"""
            )
        }
        de.robv.android.xposed.XposedBridge.log(
            "Clawdroid/fuse blown for adapter=$adapterId failures=$failureCount"
        )
    }

    fun clearForTests() {
        failures.clear()
        dirOverride = null
        thresholdOverride = null
    }

    private fun fuseFile(adapterId: String): File {
        val dir = dirOverride ?: File(XposedPaths.DIR)
        val safe = adapterId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "fuse_$safe.json")
    }
}
