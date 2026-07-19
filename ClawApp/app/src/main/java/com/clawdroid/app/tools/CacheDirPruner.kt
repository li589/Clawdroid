package com.clawdroid.app.tools

import java.io.File

/**
 * LRU/TTL prune for tool cache directories under app [cacheDir].
 */
object CacheDirPruner {
    const val DEFAULT_MAX_BYTES: Long = 32L * 1024L * 1024L
    const val DEFAULT_MAX_AGE_MS: Long = 24L * 60L * 60L * 1000L

    fun prune(
        directory: File,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        if (!directory.exists() || !directory.isDirectory) {
            return 0
        }
        val files = directory.walkTopDown()
            .filter { it.isFile }
            .toList()
        var deleted = 0
        files.forEach { file ->
            val age = nowMs - file.lastModified()
            if (age > maxAgeMs) {
                if (file.delete()) deleted++
            }
        }
        val remaining = directory.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }
        var total = remaining.sumOf { it.length() }
        for (file in remaining) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) {
                total -= size
                deleted++
            }
        }
        return deleted
    }

    fun pruneNamedCache(
        cacheDir: File,
        relativeDir: String,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS
    ): Int = prune(File(cacheDir, relativeDir), maxBytes, maxAgeMs)
}
