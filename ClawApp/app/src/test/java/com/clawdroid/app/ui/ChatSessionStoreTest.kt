package com.clawdroid.app.ui
import com.clawdroid.app.data.ChatSessionStore
import com.clawdroid.app.data.ChatHistoryStore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionStoreTest {
    @Test
    fun createSelectAndDeleteSessions() {
        val context = createAppContextMock()

        val first = ChatSessionStore.loadSnapshot(context)
        assertTrue(first.activeSessionId.isNotBlank())
        assertEquals(1, first.sessions.size)

        ChatSessionStore.saveActiveMessages(
            context = context,
            activeSessionId = first.activeSessionId,
            messages = listOf(
                ChatMessage(role = ChatRole.User, content = "第一条用户消息"),
                ChatMessage(role = ChatRole.Assistant, content = "回复")
            )
        )

        val created = ChatSessionStore.createSession(context)
        val afterCreate = ChatSessionStore.loadSnapshot(context)
        assertEquals(created.id, afterCreate.activeSessionId)
        assertEquals(2, afterCreate.sessions.size)

        val selected = ChatSessionStore.selectSession(context, first.activeSessionId)
        requireNotNull(selected)
        assertEquals(first.activeSessionId, selected.id)
        assertTrue(selected.messages.any { it.content.contains("第一条用户消息") })

        val afterDelete = ChatSessionStore.deleteSession(context, first.activeSessionId)
        assertEquals(1, afterDelete.sessions.size)
        assertEquals(created.id, afterDelete.activeSessionId)
    }

    @Test
    fun migratesLegacyChatHistory() {
        val context = createAppContextMock()
        val legacy = listOf(
            ChatMessage(role = ChatRole.User, content = "旧历史"),
            ChatMessage(role = ChatRole.Assistant, content = "旧回复")
        )
        ChatHistoryStore.save(context, legacy)

        val snapshot = ChatSessionStore.loadSnapshot(context)
        assertEquals(1, snapshot.sessions.size)
        assertEquals("默认对话", snapshot.activeTitle)
        assertTrue(snapshot.activeMessages.any { it.content == "旧历史" })
    }
}
