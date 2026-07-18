package com.clawdroid.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clawdroid.app.runtime.ClawRuntimeClient
import com.clawdroid.app.tools.ClawToolExecutor
import com.clawdroid.app.ui.ChatHistoryStore
import com.clawdroid.app.ui.ChatMessageState
import com.clawdroid.app.ui.ChatRole
import com.clawdroid.app.ui.ChatViewModel
import com.clawdroid.app.ui.ModelSettings
import com.clawdroid.app.ui.OverviewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DebugRuntimeBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val result = executeOperation(appContext, intent)
                File(appContext.filesDir, OUTPUT_FILE_NAME).writeText(result.toString(2))
            } catch (error: Throwable) {
                File(appContext.filesDir, OUTPUT_FILE_NAME).writeText(
                    JSONObject().apply {
                        put("operation", intent.getStringExtra(EXTRA_OPERATION).orEmpty())
                        put("ok", false)
                        put("error", error.stackTraceToString())
                    }.toString(2)
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun executeOperation(context: Context, intent: Intent): JSONObject {
        return when (intent.getStringExtra(EXTRA_OPERATION).orEmpty()) {
            "chat_prompt" -> executeChatPromptOperation(context, intent)
            else -> JSONObject().apply {
                put("operation", intent.getStringExtra(EXTRA_OPERATION).orEmpty())
                put("ok", false)
                put("error", "unsupported operation")
            }
        }
    }

    private suspend fun executeChatPromptOperation(context: Context, intent: Intent): JSONObject {
        val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty().trim()
        if (prompt.isBlank()) {
            return JSONObject().apply {
                put("operation", "chat_prompt")
                put("ok", false)
                put("error", "prompt is blank")
            }
        }

        if (!intent.hasExtra(EXTRA_CLEAR_HISTORY) || intent.getBooleanExtra(EXTRA_CLEAR_HISTORY, true)) {
            ChatHistoryStore.clear(context)
        }

        val runtimeClient = ClawRuntimeClient(
            packageName = context.packageName,
            sharedSecret = com.clawdroid.app.runtime.RuntimeSecretStore.resolve(context),
            signatureDigest = ClawRuntimeClient.resolveSignatureDigest(context, context.packageName)
        )
        val toolExecutor = ClawToolExecutor(runtimeClient)
        val overviewController = OverviewController(
            appContext = context,
            runtimeClient = runtimeClient,
            toolExecutor = toolExecutor,
            previewLimitBytes = DEFAULT_PREVIEW_LIMIT_BYTES
        )
        configureChatAutomation(intent, overviewController)
        delay(CHAT_AUTOMATION_SYNC_DELAY_MS)

        val chatViewModel = ChatViewModel(
            appContext = context,
            overviewController = overviewController
        )
        val initialMessageCount = chatViewModel.uiState.value.messages.size
        chatViewModel.submitPrompt(
            prompt = prompt,
            modelSettings = ModelSettings()
        )

        repeat(DEFAULT_CHAT_WAIT_TICKS) {
            val state = chatViewModel.uiState.value
            val finalAssistant = state.messages
                .drop(initialMessageCount)
                .lastOrNull { it.role == ChatRole.Assistant && it.state == ChatMessageState.Final }
            if (!state.chatBusy && finalAssistant != null) {
                return buildChatPromptResult(prompt, initialMessageCount, chatViewModel)
            }
            delay(CHAT_POLL_INTERVAL_MS)
        }

        return buildChatPromptResult(prompt, initialMessageCount, chatViewModel).apply {
            if (optBoolean("ok")) {
                put("ok", false)
            }
            put("timedOut", true)
        }
    }

    private fun configureChatAutomation(intent: Intent, overviewController: OverviewController) {
        val automationController = overviewController.automationController
        if (intent.hasExtra(EXTRA_PAGE_CONFIRM_PACKAGE)) {
            automationController.updatePageConfirmPackage(
                intent.getStringExtra(EXTRA_PAGE_CONFIRM_PACKAGE).orEmpty()
            )
        }
        if (intent.hasExtra(EXTRA_PAGE_CONFIRM_TEXT)) {
            automationController.updatePageConfirmText(
                intent.getStringExtra(EXTRA_PAGE_CONFIRM_TEXT).orEmpty()
            )
        }
        if (intent.hasExtra(EXTRA_PAGE_CONFIRM_VIEW_ID)) {
            automationController.updatePageConfirmViewId(
                intent.getStringExtra(EXTRA_PAGE_CONFIRM_VIEW_ID).orEmpty()
            )
        }
        if (intent.hasExtra(EXTRA_TARGET_PACKAGE)) {
            automationController.updateClickPrecheckPackage(
                intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
            )
        }
        if (intent.hasExtra(EXTRA_TARGET_TEXT)) {
            automationController.updateClickPrecheckText(
                intent.getStringExtra(EXTRA_TARGET_TEXT).orEmpty()
            )
        }
        if (intent.hasExtra(EXTRA_TARGET_VIEW_ID)) {
            automationController.updateClickPrecheckViewId(
                intent.getStringExtra(EXTRA_TARGET_VIEW_ID).orEmpty()
            )
        }
    }

    private fun buildChatPromptResult(
        prompt: String,
        initialMessageCount: Int,
        chatViewModel: ChatViewModel
    ): JSONObject {
        val state = chatViewModel.uiState.value
        val newMessages = state.messages.drop(initialMessageCount)
        val finalAssistant = newMessages.lastOrNull { it.role == ChatRole.Assistant }
        val messagesJson = JSONArray().apply {
            newMessages.forEach { message ->
                put(
                    JSONObject().apply {
                        put("role", message.role.name)
                        put("state", message.state.name)
                        put("content", message.content)
                    }
                )
            }
        }
        return JSONObject().apply {
            put("operation", "chat_prompt")
            put("ok", !state.chatBusy && finalAssistant?.state == ChatMessageState.Final)
            put("prompt", prompt)
            put("latestAiStatus", state.latestAiStatus)
            put("chatBusy", state.chatBusy)
            put("assistantState", finalAssistant?.state?.name.orEmpty())
            put("assistantMessage", finalAssistant?.content.orEmpty())
            put("newMessageCount", newMessages.size)
            put("messages", messagesJson)
        }
    }

    companion object {
        private const val EXTRA_OPERATION = "operation"
        private const val EXTRA_PROMPT = "prompt"
        private const val EXTRA_CLEAR_HISTORY = "clear_history"
        private const val EXTRA_PAGE_CONFIRM_PACKAGE = "page_confirm_package"
        private const val EXTRA_PAGE_CONFIRM_TEXT = "page_confirm_text"
        private const val EXTRA_PAGE_CONFIRM_VIEW_ID = "page_confirm_view_id"
        private const val EXTRA_TARGET_PACKAGE = "target_package"
        private const val EXTRA_TARGET_TEXT = "target_text"
        private const val EXTRA_TARGET_VIEW_ID = "target_view_id"
        private const val OUTPUT_FILE_NAME = "debug-runtime-result.json"
        private const val DEFAULT_PREVIEW_LIMIT_BYTES = 8 * 1024 * 1024
        private const val DEFAULT_CHAT_WAIT_TICKS = 40
        private const val CHAT_POLL_INTERVAL_MS = 250L
        private const val CHAT_AUTOMATION_SYNC_DELAY_MS = 200L
    }
}
