package com.clawdroid.app

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.clawdroid.app.automation.AutomationRuntimeStore
import com.clawdroid.app.runtime.ClawRuntimeClient
import com.clawdroid.app.tools.ClawToolExecutor
import com.clawdroid.app.ui.ChatHistoryStore
import com.clawdroid.app.ui.ChatMessageState
import com.clawdroid.app.ui.ChatRole
import com.clawdroid.app.ui.ChatViewModel
import com.clawdroid.app.ui.ModelSettings
import com.clawdroid.app.ui.OverviewController
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DebugRuntimeBridgeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val statusView = TextView(this).apply {
            text = "Clawdroid debug bridge running..."
            setPadding(32, 48, 32, 48)
        }
        setContentView(statusView)

        lifecycleScope.launch {
            val result = runCatching { executeOperation(intent?.getStringExtra(EXTRA_OPERATION).orEmpty()) }
                .getOrElse { error ->
                    JSONObject().apply {
                        put("operation", intent?.getStringExtra(EXTRA_OPERATION).orEmpty())
                        put("ok", false)
                        put("packageName", packageName)
                        put("error", error.stackTraceToString())
                        put("errorMessage", error.message.orEmpty())
                    }
                }

            val outputFile = File(filesDir, OUTPUT_FILE_NAME)
            outputFile.writeText(result.toString(2))
            statusView.text = "Debug bridge finished: ${result.optBoolean("ok")}\n${outputFile.absolutePath}"
            delay(500)
            finish()
        }
    }

    private suspend fun executeOperation(operation: String): JSONObject {
        val runtimeClient = ClawRuntimeClient(
            packageName = packageName,
            sharedSecret = BuildConfig.CLAW_RUNTIME_SHARED_SECRET,
            signatureDigest = ClawRuntimeClient.resolveSignatureDigest(this, packageName)
        )
        val toolExecutor = ClawToolExecutor(runtimeClient)
        val runtimeSignatureDigest = ClawRuntimeClient.resolveSignatureDigest(this, packageName)
        val eventDurationMs = intent?.getIntExtra(EXTRA_DURATION_MS, DEFAULT_EVENT_DURATION_MS)
            ?.takeIf { it > 0 }
            ?: DEFAULT_EVENT_DURATION_MS
        val bridgeMetadata = JSONObject().apply {
            put("packageName", packageName)
            put("socketName", runtimeClient.socketDisplayName())
            put("signatureDigest", runtimeSignatureDigest)
        }
        return when (operation) {
            "signature" -> JSONObject().apply {
                put("operation", operation)
                put("ok", true)
                put("packageName", packageName)
                put("signatureDigest", runtimeSignatureDigest)
            }

            "ping" -> runtimeClient.ping().fold(
                onSuccess = { ping ->
                    JSONObject(bridgeMetadata.toString()).apply {
                        put("operation", operation)
                        put("ok", true)
                        put("daemonStatus", ping.daemonStatus)
                        put("daemonVersion", ping.daemonVersion)
                        put("latencyMs", ping.latencyMs)
                    }
                },
                onFailure = { failureJson(operation, it) }
            )

            "probe" -> runtimeClient.probeSession().fold(
                onSuccess = { probe ->
                    JSONObject(bridgeMetadata.toString()).apply {
                        put("operation", operation)
                        put("ok", true)
                        put("sessionId", probe.sessionId)
                        put("authMode", probe.authMode)
                        put("finalState", probe.finalState.name)
                        put("stateTrace", JSONArray(probe.stateTrace.map { it.name }))
                        put("daemonStatus", probe.ping.daemonStatus)
                        put("daemonVersion", probe.ping.daemonVersion)
                        put("latencyMs", probe.ping.latencyMs)
                        put("root", probe.capabilities.root)
                        put("accessibility", probe.capabilities.accessibility)
                        put("lsposed", probe.capabilities.lsposed)
                        put("lsposedRuntimeLoaded", probe.capabilities.lsposedRuntimeLoaded)
                        put("lsposedRuntimeProcess", probe.capabilities.lsposedRuntimeProcess)
                        put("lsposedRuntimeLoadedAt", probe.capabilities.lsposedRuntimeLoadedAt)
                        put("capabilities", JSONArray(probe.capabilities.capabilities))
                        put("degradedReason", probe.capabilities.degradedReason)
                    }
                },
                onFailure = { failureJson(operation, it) }
            )

            "capabilities" -> runtimeClient.getCapabilities().fold(
                onSuccess = { capabilities ->
                    JSONObject(bridgeMetadata.toString()).apply {
                        put("operation", operation)
                        put("ok", true)
                        put("root", capabilities.root)
                        put("accessibility", capabilities.accessibility)
                        put("lsposed", capabilities.lsposed)
                        put("lsposedRuntimeLoaded", capabilities.lsposedRuntimeLoaded)
                        put("lsposedRuntimeProcess", capabilities.lsposedRuntimeProcess)
                        put("lsposedRuntimeLoadedAt", capabilities.lsposedRuntimeLoadedAt)
                        put("screenshotEnabled", capabilities.screenshotEnabled)
                        put("fileBridgeEnabled", capabilities.fileBridgeEnabled)
                        put("sessionState", capabilities.sessionState.name)
                        put("stateTrace", JSONArray(capabilities.stateTrace.map { it.name }))
                        put("capabilities", JSONArray(capabilities.capabilities))
                        put("degradedReason", capabilities.degradedReason)
                    }
                },
                onFailure = { failureJson(operation, it) }
            )

            "capture_and_read" -> runtimeClient.captureScreen().fold(
                onSuccess = { capture ->
                    runtimeClient.readFileLimited(path = capture.imagePath, maxBytes = 4096).fold(
                        onSuccess = { readResult ->
                            JSONObject(bridgeMetadata.toString()).apply {
                                put("operation", operation)
                                put("ok", true)
                                put("imagePath", capture.imagePath)
                                put("format", capture.format)
                                put("width", capture.width)
                                put("height", capture.height)
                                put("fileSize", capture.fileSize)
                                put("sha256", capture.sha256)
                                put("transport", capture.transport)
                                put("readBytes", readResult.readBytes)
                                put("totalSize", readResult.totalSize)
                                put("eof", readResult.eof)
                            }
                        },
                        onFailure = { readFailure ->
                            failureJson(operation, readFailure).apply {
                                put("imagePath", capture.imagePath)
                                put("format", capture.format)
                                put("width", capture.width)
                                put("height", capture.height)
                                put("fileSize", capture.fileSize)
                                put("sha256", capture.sha256)
                            }
                        }
                    )
                },
                onFailure = { failureJson(operation, it) }
            )

            "tap" -> runtimeClient.injectTap(x = 540, y = 1200).fold(
                onSuccess = { tap ->
                    JSONObject(bridgeMetadata.toString()).apply {
                        put("operation", operation)
                        put("ok", true)
                        put("accepted", tap.accepted)
                        put("displayId", tap.displayId)
                        put("x", tap.x)
                        put("y", tap.y)
                    }
                },
                onFailure = { failureJson(operation, it) }
            )

            "swipe" -> runtimeClient.injectSwipe(
                x1 = 540,
                y1 = 1800,
                x2 = 540,
                y2 = 400,
                durationMs = 350
            ).fold(
                onSuccess = { swipe ->
                    JSONObject(bridgeMetadata.toString()).apply {
                        put("operation", operation)
                        put("ok", true)
                        put("accepted", swipe.accepted)
                        put("displayId", swipe.displayId)
                        put("x1", swipe.x1)
                        put("y1", swipe.y1)
                        put("x2", swipe.x2)
                        put("y2", swipe.y2)
                        put("durationMs", swipe.durationMs)
                    }
                },
                onFailure = { failureJson(operation, it) }
            )

            "exec_shell_limited" -> runtimeClient.execShellLimited(
                command = intent?.getStringExtra(EXTRA_COMMAND).orEmpty().ifBlank { "wm size" },
                timeoutMs = intent?.getIntExtra(EXTRA_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
                    ?.takeIf { it > 0 }
                    ?: DEFAULT_TIMEOUT_MS
            ).fold(
                onSuccess = { result ->
                    JSONObject(bridgeMetadata.toString()).apply {
                        put("operation", operation)
                        put("ok", true)
                        put("command", result.command)
                        put("templateName", result.templateName)
                        put("timeoutMs", result.timeoutMs)
                        put("durationMs", result.durationMs)
                        put("exitCode", result.exitCode)
                        put("stdout", result.stdout)
                        put("stderr", result.stderr)
                        put("stdoutTruncated", result.stdoutTruncated)
                        put("stderrTruncated", result.stderrTruncated)
                        put("timedOut", result.timedOut)
                        put("allowedCommands", JSONArray(result.allowedCommands))
                    }
                },
                onFailure = { failureJson(operation, it) }
            )

            "events" -> {
                val frames = mutableListOf<JSONObject>()
                val bridgeResult = JSONObject(bridgeMetadata.toString()).apply {
                    put("operation", operation)
                    put("ok", true)
                }
                runCatching {
                    coroutineScope {
                        val subscriptionJob = launch {
                            runCatching {
                                runtimeClient.startEventSubscription(
                                    events = listOf("daemon_status_changed", "capability_changed", "window_changed"),
                                    onStarted = { started ->
                                        bridgeResult.put("subscribed", JSONArray(started.subscribed))
                                        bridgeResult.put("streamMode", started.streamMode)
                                        bridgeResult.put("pollIntervalMs", started.pollIntervalMs)
                                    },
                                    onEvent = { frame ->
                                        if (frames.size < 8) {
                                            frames += JSONObject().apply {
                                                put("event", frame.event)
                                                put("timestamp", frame.timestamp)
                                                put("data", JSONObject(frame.data))
                                            }
                                        }
                                    },
                                    onClosed = { reason ->
                                        bridgeResult.put("closedReason", reason)
                                    }
                                )
                            }.onFailure { error ->
                                val message = error.message.orEmpty()
                                if ("socket closed" !in message.lowercase()) {
                                    throw error
                                }
                                if (!bridgeResult.has("closedReason")) {
                                    bridgeResult.put("closedReason", "client_stop")
                                }
                            }
                        }
                        delay(eventDurationMs.toLong())
                        runtimeClient.stopEventSubscription()
                        subscriptionJob.join()
                    }
                }.onFailure { error ->
                    return failureJson(operation, error)
                }
                bridgeResult.put("requestedDurationMs", eventDurationMs)
                bridgeResult.put("frameCount", frames.size)
                bridgeResult.put("frames", JSONArray(frames))
                bridgeResult
            }

            "automation_snapshot" -> {
                val snapshot = AutomationRuntimeStore.accessibilitySnapshot.value
                JSONObject(bridgeMetadata.toString()).apply {
                    put("operation", operation)
                    put("ok", true)
                    put("serviceConnected", snapshot.serviceConnected)
                    put("eventCount", snapshot.eventCount)
                    put("eventName", snapshot.eventName)
                    put("packageName", snapshot.packageName)
                    put("className", snapshot.className)
                    put("textSummary", snapshot.textSummary)
                    put("contentDescription", snapshot.contentDescription)
                    put("visibleNodeSummary", snapshot.visibleNodeSummary)
                    put("viewIdSummary", snapshot.viewIdSummary)
                    put("clickableTargetSummary", snapshot.clickableTargetSummary)
                    put("lastResolvedTapLabel", snapshot.lastResolvedTapLabel)
                    put("lastResolvedTapX", snapshot.lastResolvedTapX ?: -1)
                    put("lastResolvedTapY", snapshot.lastResolvedTapY ?: -1)
                    put("updatedAtEpochMs", snapshot.updatedAtEpochMs)
                }
            }

            "chat_prompt" -> executeChatPromptOperation(
                prompt = intent?.getStringExtra(EXTRA_PROMPT).orEmpty(),
                toolExecutor = toolExecutor,
                runtimeClient = runtimeClient
            )

            else -> JSONObject().apply {
                put("operation", operation)
                put("ok", false)
                put("error", "unsupported operation")
            }
        }
    }

    private fun failureJson(operation: String, throwable: Throwable): JSONObject {
        return JSONObject().apply {
            put("operation", operation)
            put("ok", false)
            put("error", throwable.stackTraceToString())
            put("errorMessage", throwable.message.orEmpty())
            put("packageName", packageName)
        }
    }

    private suspend fun executeChatPromptOperation(
        prompt: String,
        toolExecutor: ClawToolExecutor,
        runtimeClient: ClawRuntimeClient
    ): JSONObject {
        val normalizedPrompt = prompt.trim()
        if (normalizedPrompt.isBlank()) {
            return JSONObject().apply {
                put("operation", "chat_prompt")
                put("ok", false)
                put("error", "prompt is blank")
            }
        }

        if (intent?.getBooleanExtra(EXTRA_CLEAR_HISTORY, true) != false) {
            ChatHistoryStore.clear(this)
        }

        val overviewController = OverviewController(
            appContext = applicationContext,
            runtimeClient = runtimeClient,
            toolExecutor = toolExecutor,
            previewLimitBytes = DEFAULT_PREVIEW_LIMIT_BYTES
        )
        configureChatAutomation(overviewController)
        delay(CHAT_AUTOMATION_SYNC_DELAY_MS)

        val chatViewModel = ChatViewModel(
            appContext = applicationContext,
            overviewController = overviewController
        )
        val initialMessageCount = chatViewModel.uiState.value.messages.size
        chatViewModel.submitPrompt(
            prompt = normalizedPrompt,
            modelSettings = ModelSettings()
        )

        repeat(DEFAULT_CHAT_WAIT_TICKS) {
            val state = chatViewModel.uiState.value
            val finalAssistant = state.messages
                .drop(initialMessageCount)
                .lastOrNull { it.role == ChatRole.Assistant && it.state == ChatMessageState.Final }
            if (!state.chatBusy && finalAssistant != null) {
                return buildChatPromptResult(
                    prompt = normalizedPrompt,
                    initialMessageCount = initialMessageCount,
                    chatViewModel = chatViewModel
                )
            }
            delay(CHAT_POLL_INTERVAL_MS)
        }

        return buildChatPromptResult(
            prompt = normalizedPrompt,
            initialMessageCount = initialMessageCount,
            chatViewModel = chatViewModel
        ).apply {
            if (optBoolean("ok")) {
                put("ok", false)
            }
            put("timedOut", true)
        }
    }

    private fun configureChatAutomation(overviewController: OverviewController) {
        val automationController = overviewController.automationController
        if (intent?.hasExtra(EXTRA_PAGE_CONFIRM_PACKAGE) == true) {
            automationController.updatePageConfirmPackage(
                intent?.getStringExtra(EXTRA_PAGE_CONFIRM_PACKAGE).orEmpty()
            )
        }
        if (intent?.hasExtra(EXTRA_PAGE_CONFIRM_TEXT) == true) {
            automationController.updatePageConfirmText(
                intent?.getStringExtra(EXTRA_PAGE_CONFIRM_TEXT).orEmpty()
            )
        }
        if (intent?.hasExtra(EXTRA_PAGE_CONFIRM_VIEW_ID) == true) {
            automationController.updatePageConfirmViewId(
                intent?.getStringExtra(EXTRA_PAGE_CONFIRM_VIEW_ID).orEmpty()
            )
        }
        if (intent?.hasExtra(EXTRA_TARGET_PACKAGE) == true) {
            automationController.updateClickPrecheckPackage(
                intent?.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
            )
        }
        if (intent?.hasExtra(EXTRA_TARGET_TEXT) == true) {
            automationController.updateClickPrecheckText(
                intent?.getStringExtra(EXTRA_TARGET_TEXT).orEmpty()
            )
        }
        if (intent?.hasExtra(EXTRA_TARGET_VIEW_ID) == true) {
            automationController.updateClickPrecheckViewId(
                intent?.getStringExtra(EXTRA_TARGET_VIEW_ID).orEmpty()
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
        val finalAssistant = newMessages
            .lastOrNull { it.role == ChatRole.Assistant }
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
        private const val EXTRA_COMMAND = "command"
        private const val EXTRA_TIMEOUT_MS = "timeout_ms"
        private const val EXTRA_DURATION_MS = "duration_ms"
        private const val EXTRA_PROMPT = "prompt"
        private const val EXTRA_CLEAR_HISTORY = "clear_history"
        private const val EXTRA_PAGE_CONFIRM_PACKAGE = "page_confirm_package"
        private const val EXTRA_PAGE_CONFIRM_TEXT = "page_confirm_text"
        private const val EXTRA_PAGE_CONFIRM_VIEW_ID = "page_confirm_view_id"
        private const val EXTRA_TARGET_PACKAGE = "target_package"
        private const val EXTRA_TARGET_TEXT = "target_text"
        private const val EXTRA_TARGET_VIEW_ID = "target_view_id"
        private const val OUTPUT_FILE_NAME = "debug-runtime-result.json"
        private const val DEFAULT_TIMEOUT_MS = 3000
        private const val DEFAULT_EVENT_DURATION_MS = 2500
        private const val DEFAULT_PREVIEW_LIMIT_BYTES = 8 * 1024 * 1024
        private const val DEFAULT_CHAT_WAIT_TICKS = 40
        private const val CHAT_POLL_INTERVAL_MS = 250L
        private const val CHAT_AUTOMATION_SYNC_DELAY_MS = 200L
    }
}
