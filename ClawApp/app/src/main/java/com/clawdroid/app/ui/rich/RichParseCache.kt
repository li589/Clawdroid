package com.clawdroid.app.ui.rich

import java.util.LinkedHashMap

/**
 * Small LRU cache for parsed rich-message blocks.
 */
object RichParseCache {
    private const val maxEntries = 64
    private val lock = Any()
    private val map = object : LinkedHashMap<Long, List<RichBlock>>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<RichBlock>>?): Boolean {
            return size > maxEntries
        }
    }

    fun keyOf(content: String): Long {
        var h = content.hashCode().toLong()
        h = (h shl 32) xor (content.length.toLong() and 0xffffffffL)
        return h
    }

    fun get(content: String): List<RichBlock>? = synchronized(lock) {
        map[keyOf(content)]
    }

    fun put(content: String, blocks: List<RichBlock>) = synchronized(lock) {
        map[keyOf(content)] = blocks
    }

    fun clear() = synchronized(lock) {
        map.clear()
    }
}
