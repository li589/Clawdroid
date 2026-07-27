package com.clawdroid.app.data

import android.content.Context
import com.clawdroid.app.chat.ChatTextLimits
import com.clawdroid.app.data.model.ChatMessage
import com.clawdroid.app.data.model.ChatMessageState
import com.clawdroid.app.data.model.ChatRole
import com.clawdroid.app.data.model.asTerminated
import com.clawdroid.app.data.model.newChatMessageId
import com.clawdroid.app.data.model.parseChatRole
import org.json.JSONArray
import org.json.JSONObject

/**
 * Multi-conversation chat persistence.
 * Migrates legacy single-list [ChatHistoryStore] into one default session on first load.
 */
internal data class ChatSessionSummary(
    val id: String,
    val title: String,
    val updatedAtEpochMs: Long,
    val preview: String = ""
)

internal data class ChatSession(
    val id: String,
    val title: String,
    val updatedAtEpochMs: Long,
    val messages: List<ChatMessage>
) {
    fun toSummary(): ChatSessionSummary = ChatSessionSummary(
        id = id,
        title = title,
        updatedAtEpochMs = updatedAtEpochMs,
        preview = messages.lastOrNull { it.content.isNotBlank() }?.content?.take(48).orEmpty()
    )
}

internal data class ChatSessionSnapshot(
    val activeSessionId: String,
    val sessions: List<ChatSessionSummary>,
    val activeMessages: List<ChatMessage>,
    val activeTitle: String
)

internal object ChatSessionStore {
    private const val prefsName = "clawdroid_chat_sessions"
    private const val keyActiveId = "active_session_id"
    private const val keySessions = "sessions"
    private const val maxSessions = 30

    fun loadSnapshot(context: Context): ChatSessionSnapshot {
        migrateLegacyIfNeeded(context)
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val sessions = decodeSessions(prefs.getString(keySessions, null))
        if (sessions.isEmpty()) {
            val created = newEmptySession(context)
            persistAll(context, created.id, listOf(created))
            return ChatSessionSnapshot(
                activeSessionId = created.id,
                sessions = listOf(created.toSummary()),
                activeMessages = created.messages,
                activeTitle = created.title
            )
        }
        val activeId = prefs.getString(keyActiveId, null)
            ?.takeIf { id -> sessions.any { it.id == id } }
            ?: sessions.maxByOrNull { it.updatedAtEpochMs }!!.id
        val active = sessions.first { it.id == activeId }
        return ChatSessionSnapshot(
            activeSessionId = active.id,
            sessions = sessions
                .sortedByDescending { it.updatedAtEpochMs }
                .map { it.toSummary() },
            activeMessages = active.messages,
            activeTitle = active.title
        )
    }

    fun saveActiveMessages(
        context: Context,
        activeSessionId: String,
        messages: List<ChatMessage>,
        titleHint: String? = null
    ) {
        val sessions = decodeSessions(
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(keySessions, null)
        ).toMutableList()
        val index = sessions.indexOfFirst { it.id == activeSessionId }
        val windowed = ChatTextLimits.windowMessages(messages)
        val now = System.currentTimeMillis()
        if (index < 0) {
            val created = ChatSession(
                id = activeSessionId,
                title = titleHint?.takeIf { it.isNotBlank() } ?: defaultTitle(windowed),
                updatedAtEpochMs = now,
                messages = windowed
            )
            sessions.add(0, created)
        } else {
            val existing = sessions[index]
            sessions[index] = existing.copy(
                title = when {
                    !titleHint.isNullOrBlank() -> titleHint
                    existing.title == "新对话" || existing.title == "默认对话" -> defaultTitle(windowed)
                    else -> existing.title
                },
                updatedAtEpochMs = now,
                messages = windowed
            )
        }
        persistAll(context, activeSessionId, trimSessions(sessions))
    }

    fun createSession(context: Context): ChatSession {
        val sessions = decodeSessions(
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(keySessions, null)
        ).toMutableList()
        val created = newEmptySession(context)
        sessions.add(0, created)
        persistAll(context, created.id, trimSessions(sessions))
        return created
    }

    fun selectSession(context: Context, sessionId: String): ChatSession? {
        val sessions = decodeSessions(
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(keySessions, null)
        )
        val target = sessions.firstOrNull { it.id == sessionId } ?: return null
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(keyActiveId, sessionId)
            .apply()
        return target
    }

    fun deleteSession(context: Context, sessionId: String): ChatSessionSnapshot {
        val sessions = decodeSessions(
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(keySessions, null)
        ).toMutableList()
        sessions.removeAll { it.id == sessionId }
        val next = if (sessions.isEmpty()) {
            val created = newEmptySession(context)
            persistAll(context, created.id, listOf(created))
            created
        } else {
            val active = sessions.maxByOrNull { it.updatedAtEpochMs }!!
            persistAll(context, active.id, trimSessions(sessions))
            active
        }
        return ChatSessionSnapshot(
            activeSessionId = next.id,
            sessions = decodeSessions(
                context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(keySessions, null)
            ).sortedByDescending { it.updatedAtEpochMs }.map { it.toSummary() },
            activeMessages = next.messages,
            activeTitle = next.title
        )
    }

    fun listSummaries(context: Context): List<ChatSessionSummary> {
        return decodeSessions(
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(keySessions, null)
        ).sortedByDescending { it.updatedAtEpochMs }.map { it.toSummary() }
    }

    private fun migrateLegacyIfNeeded(context: Context) {
        val sessionPrefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (sessionPrefs.contains(keySessions)) return
        val legacyPrefs = context.getSharedPreferences("clawdroid_chat_history", Context.MODE_PRIVATE)
        val raw = legacyPrefs.getString("messages", null)
        val legacyMessages = if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        add(
                            ChatMessage(
                                id = item.optString("id").ifBlank { newChatMessageId() },
                                role = parseChatRole(item.optString("role", ChatRole.Assistant.name)),
                                content = ChatTextLimits.truncateForDisplay(item.optString("content")),
                                attachmentLabel = item.optString("attachment_label").ifBlank { null },
                                createdAtEpochMs = item.optLong(
                                    "created_at_epoch_ms",
                                    System.currentTimeMillis()
                                ),
                                state = ChatMessageState.entries.firstOrNull {
                                    it.name == item.optString("state", ChatMessageState.Final.name)
                                } ?: ChatMessageState.Final
                            ).asTerminated()
                        )
                    }
                }.let { ChatTextLimits.windowMessages(it) }
            }.getOrElse { emptyList() }
        }
        val session = if (legacyMessages.isEmpty()) {
            newEmptySession(context)
        } else {
            ChatSession(
                id = newSessionId(),
                title = "默认对话",
                updatedAtEpochMs = System.currentTimeMillis(),
                messages = legacyMessages
            )
        }
        persistAll(context, session.id, listOf(session))
        legacyPrefs.edit().remove("messages").apply()
    }


    private fun newEmptySession(context: Context? = null): ChatSession {
        val welcomeText = context?.let {
            com.clawdroid.app.tools.ClawAssetPromptStore.chatWelcomePrompt(it).trim()
        }.orEmpty().ifBlank {
            "可以直接像聊天一样下达指令，例如「ping ClawRuntime」、「获取能力」、「截图并预览」、「运行时体检」。"
        }
        val welcome = ChatMessage(
            role = ChatRole.Assistant,
            content = welcomeText
        )
        return ChatSession(
            id = newSessionId(),
            title = "新对话",
            updatedAtEpochMs = System.currentTimeMillis(),
            messages = listOf(welcome)
        )
    }

    private fun defaultTitle(messages: List<ChatMessage>): String {
        val firstUser = messages.firstOrNull { it.role == ChatRole.User && it.content.isNotBlank() }
            ?.content
            ?.trim()
            .orEmpty()
        if (firstUser.isBlank()) return "新对话"
        return firstUser.take(18) + if (firstUser.length > 18) "…" else ""
    }

    private fun trimSessions(sessions: List<ChatSession>): List<ChatSession> {

        return sessions.sortedByDescending { it.updatedAtEpochMs }.take(maxSessions)
    }

    private fun persistAll(context: Context, activeId: String, sessions: List<ChatSession>) {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(encodeSession(session))
        }
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(keyActiveId, activeId)
            .putString(keySessions, array.toString())
            .apply()
    }

    private fun decodeSessions(raw: String?): List<ChatSession> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    decodeSession(array.optJSONObject(i))?.let(::add)
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun decodeSession(item: JSONObject?): ChatSession? {
        item ?: return null
        val messagesJson = item.optJSONArray("messages") ?: JSONArray()
        val messages = buildList {
            for (i in 0 until messagesJson.length()) {
                val msg = messagesJson.optJSONObject(i) ?: continue
                add(
                    ChatMessage(
                        id = msg.optString("id").ifBlank { newChatMessageId() },
                        role = parseChatRole(msg.optString("role", ChatRole.Assistant.name)),
                        content = ChatTextLimits.truncateForDisplay(msg.optString("content")),
                        attachmentLabel = msg.optString("attachment_label").ifBlank { null },
                        createdAtEpochMs = msg.optLong("created_at_epoch_ms", System.currentTimeMillis()),
                        state = ChatMessageState.entries.firstOrNull {
                            it.name == msg.optString("state", ChatMessageState.Final.name)
                        } ?: ChatMessageState.Final
                    ).asTerminated()
                )
            }
        }
        return ChatSession(
            id = item.optString("id").ifBlank { return null },
            title = item.optString("title").ifBlank {
            "可以直接像聊天一样下达指令，例如「ping ClawRuntime」、「获取能力」、「截图并预览」、「运行时体检」。"
        },
            updatedAtEpochMs = item.optLong("updated_at_epoch_ms", System.currentTimeMillis()),
            messages = ChatTextLimits.windowMessages(messages)
        )
    }

    private fun encodeSession(session: ChatSession): JSONObject {
        val messagesJson = JSONArray()
        session.messages.forEach { message ->
            messagesJson.put(
                JSONObject().apply {
                    put("id", message.id)
                    put("role", message.role.name)
                    put("content", ChatTextLimits.truncateForDisplay(message.content))
                    put("attachment_label", message.attachmentLabel ?: "")
                    put("created_at_epoch_ms", message.createdAtEpochMs)
                    put("state", message.state.name)
                }
            )
        }
        return JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            put("updated_at_epoch_ms", session.updatedAtEpochMs)
            put("messages", messagesJson)
        }
    }

    private fun newSessionId(): String = "sess-${System.currentTimeMillis()}-${(0..9999).random()}"
}
