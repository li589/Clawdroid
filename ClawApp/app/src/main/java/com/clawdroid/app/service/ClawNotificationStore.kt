package com.clawdroid.app.service

import java.util.concurrent.ConcurrentLinkedDeque

data class CapturedNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val subText: String,
    val postedAtMs: Long,
    val isOngoing: Boolean
)

object ClawNotificationStore {
    private const val MAX = 200
    private val items = ConcurrentLinkedDeque<CapturedNotification>()

    @Volatile
    var listenerConnected: Boolean = false

    fun add(item: CapturedNotification) {
        items.addFirst(item)
        while (items.size > MAX) {
            items.pollLast()
        }
    }

    fun remove(key: String) {
        items.removeIf { it.key == key }
    }

    fun list(query: String, limit: Int): List<CapturedNotification> {
        val q = query.trim().lowercase()
        return items.asSequence()
            .filter {
                q.isBlank() ||
                    it.packageName.lowercase().contains(q) ||
                    it.title.lowercase().contains(q) ||
                    it.text.lowercase().contains(q)
            }
            .take(limit.coerceIn(1, MAX))
            .toList()
    }

    fun clear() {
        items.clear()
    }
}
