package com.clawdroid.app.chat

import android.content.Context
import com.clawdroid.app.data.ChatSessionStore
import com.clawdroid.app.ui.ChatMessage
import com.clawdroid.app.ui.ChatMessageState
import com.clawdroid.app.ui.ChatRole
import com.clawdroid.app.ui.ChatUiState
import com.clawdroid.app.ui.finalizeAbandonedStreaming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ChatSessionCoordinator(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val getState: () -> ChatUiState,
    private val updateState: ((ChatUiState) -> ChatUiState) -> Unit,
    private val getHistorySaveJob: () -> Job?,
    private val setHistorySaveJob: (Job?) -> Unit,
    private val isSessionChangeBlocked: () -> Boolean,
    private val onCreateSessionBlocked: () -> Unit,
    private val onSelectSessionBlocked: () -> Unit,
    private val onDeleteSessionBlocked: () -> Unit,
    private val cancelActiveTaskJob: () -> Unit,
    private val welcomeMessage: () -> ChatMessage
) {
    suspend fun restoreHistory() {
        val snapshot = ChatSessionStore.loadSnapshot(appContext)
        val hadStreaming = snapshot.activeMessages.any { it.state == ChatMessageState.Streaming }
        val messages = snapshot.activeMessages
            .finalizeAbandonedStreaming()
            .ifEmpty { listOf(welcomeMessage()) }
        updateState {
            it.copy(
                activeSessionId = snapshot.activeSessionId,
                activeSessionTitle = snapshot.activeTitle,
                sessionSummaries = snapshot.sessions,
                messages = messages,
                chatBusy = false
            )
        }
        if (hadStreaming && snapshot.activeSessionId.isNotBlank()) {
            runCatching {
                ChatSessionStore.saveActiveMessages(
                    context = appContext,
                    activeSessionId = snapshot.activeSessionId,
                    messages = messages
                )
            }
        }
    }

    fun createNewSession() {
        if (isSessionChangeBlocked()) {
            onCreateSessionBlocked()
            return
        }
        cancelActiveTaskJob()
        val created = ChatSessionStore.createSession(appContext)
        getHistorySaveJob()?.cancel()
        updateState {
            it.copy(
                input = "",
                chatBusy = false,
                pendingImageLabel = null,
                activeSessionId = created.id,
                activeSessionTitle = created.title,
                messages = created.messages,
                sessionSummaries = ChatSessionStore.listSummaries(appContext),
                taskExecution = null
            )
        }
    }

    fun selectSession(sessionId: String) {
        if (sessionId == getState().activeSessionId) return
        if (isSessionChangeBlocked()) {
            onSelectSessionBlocked()
            return
        }
        val selected = ChatSessionStore.selectSession(appContext, sessionId) ?: return
        getHistorySaveJob()?.cancel()
        val messages = selected.messages.finalizeAbandonedStreaming()
        updateState {
            it.copy(
                input = "",
                pendingImageLabel = null,
                chatBusy = false,
                activeSessionId = selected.id,
                activeSessionTitle = selected.title,
                messages = messages,
                sessionSummaries = ChatSessionStore.listSummaries(appContext)
            )
        }
    }

    fun deleteCurrentSession() {
        if (isSessionChangeBlocked()) {
            onDeleteSessionBlocked()
            return
        }
        val activeId = getState().activeSessionId
        if (activeId.isBlank()) {
            createNewSession()
            return
        }
        cancelActiveTaskJob()
        getHistorySaveJob()?.cancel()
        val snapshot = ChatSessionStore.deleteSession(appContext, activeId)
        updateState {
            it.copy(
                input = "",
                chatBusy = false,
                pendingImageLabel = null,
                activeSessionId = snapshot.activeSessionId,
                activeSessionTitle = snapshot.activeTitle,
                messages = snapshot.activeMessages,
                sessionSummaries = snapshot.sessions,
                taskExecution = null
            )
        }
    }

    fun replaceMessages(
        messages: List<ChatMessage>,
        persistImmediately: Boolean = false
    ) {
        val windowed = ChatTextLimits.windowMessages(messages)
        updateState { it.copy(messages = windowed) }
        scheduleHistoryPersist(windowed, persistImmediately)
    }

    fun scheduleHistoryPersist(messages: List<ChatMessage>, immediate: Boolean) {
        val sessionId = getState().activeSessionId
        if (sessionId.isBlank()) return
        getHistorySaveJob()?.cancel()
        setHistorySaveJob(
            scope.launch {
                if (!immediate) {
                    delay(HISTORY_SAVE_DEBOUNCE_MS)
                }
                runCatching {
                    ChatSessionStore.saveActiveMessages(
                        context = appContext,
                        activeSessionId = sessionId,
                        messages = messages
                    )
                }
                val summaries = ChatSessionStore.listSummaries(appContext)
                val title = summaries.firstOrNull { it.id == sessionId }?.title
                updateState { state ->
                    state.copy(
                        sessionSummaries = summaries,
                        activeSessionTitle = title ?: state.activeSessionTitle
                    )
                }
            }
        )
    }

    fun onCleared() {
        getHistorySaveJob()?.cancel()
        setHistorySaveJob(null)
    }

    companion object {
        private const val HISTORY_SAVE_DEBOUNCE_MS = 450L
    }
}
