package com.clawdroid.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class CacheDirPrunerTest {
    @Test
    fun prunesByAgeAndQuota() {
        val dir = createTempDirectory("claw-cache-prune").toFile()
        try {
            val old = File(dir, "old.bin").apply {
                writeBytes(ByteArray(1024))
                setLastModified(System.currentTimeMillis() - 48L * 60L * 60L * 1000L)
            }
            val keep = File(dir, "keep.bin").apply {
                writeBytes(ByteArray(64))
            }
            val deleted = CacheDirPruner.prune(
                directory = dir,
                maxBytes = 10_000,
                maxAgeMs = 24L * 60L * 60L * 1000L
            )
            assertTrue(deleted >= 1)
            assertFalse(old.exists())
            assertTrue(keep.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun prunesOldestWhenOverQuota() {
        val dir = createTempDirectory("claw-cache-quota").toFile()
        try {
            val a = File(dir, "a.bin").apply {
                writeBytes(ByteArray(800))
                setLastModified(System.currentTimeMillis() - 10_000)
            }
            val b = File(dir, "b.bin").apply {
                writeBytes(ByteArray(800))
                setLastModified(System.currentTimeMillis())
            }
            CacheDirPruner.prune(directory = dir, maxBytes = 900, maxAgeMs = Long.MAX_VALUE)
            assertFalse(a.exists())
            assertTrue(b.exists())
            assertEquals(1, dir.listFiles()?.size ?: 0)
        } finally {
            dir.deleteRecursively()
        }
    }
}
