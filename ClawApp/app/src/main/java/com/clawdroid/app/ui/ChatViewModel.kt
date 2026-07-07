package com.clawdroid.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawdroid.app.ai.AiAgentOrchestrator
import com.clawdroid.app.ai.AiToolReflectionInput
import com.clawdroid.app.ai.AiRuntimeSnapshot
import com.clawdroid.app.chat.ChatLocalAction
import com.clawdroid.app.chat.ChatPlannerContext
import com.clawdroid.app.chat.ChatPromptPlan
import com.clawdroid.app.chat.ChatPromptPlanner
import com.clawdroid.app.chat.ChatTaskAction
import com.clawdroid.app.tools.ClawTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ChatUiState(
    val input: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val pendingImageLabel: String? = null,
    val chatBusy: Boolean = false,
    val latestAiStatus: String = "规则优先，模型待命",
    val taskExecution: ChatTaskExecutionState? = null,
    val taskHistory: List<ChatTaskExecutionState> = emptyList(),
    val taskHistoryFilter: ChatTaskHistoryFilter = ChatTaskHistoryFilter.All
)

internal enum class ChatTaskProgressState {
    Pending,
    Running,
    Succeeded,
    Failed,
    Cancelled
}

internal enum class ChatTaskHistoryFilter {
    All,
    Failed,
    Cancelled,
    Succeeded,
    Retried
}

internal data class ChatTaskFailureState(
    val code: String,
    val summary: String,
    val rawDetail: String
)

internal data class ChatTaskStepState(
    val title: String,
    val status: ChatTaskProgressState = ChatTaskProgressState.Pending,
    val detail: String = "等待执行",
    val startedAtEpochMs: Long = 0L,
    val finishedAtEpochMs: Long = 0L
)

internal data class ChatTaskExecutionState(
    val taskId: String,
    val title: String,
    val summary: String,
    val status: ChatTaskProgressState,
    val steps: List<ChatTaskStepState>,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long = 0L,
    val taskAction: com.clawdroid.app.chat.ChatTaskAction? = null,
    val failureReason: String? = null,
    val originPrompt: String = "",
    val retryCount: Int = 0,
    val retryFromTaskId: String? = null,
    val failure: ChatTaskFailureState? = null
)

internal class ChatViewModel(
    private val appContext: Context,
    private val overviewController: OverviewController
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private var currentTaskJob: Job? = null

    init {
        restoreHistory()
        restoreTaskState()
    }

    fun updateInput(value: String) {
        updateState { it.copy(input = value) }
    }

    fun applyVoiceTranscript(transcript: String) {
        val normalized = transcript.trim()
        if (normalized.isBlank()) {
            return
        }
        updateState { it.copy(input = normalized) }
    }

    fun onImagePicked(label: String) {
        if (label.isBlank()) {
            return
        }
        updateState { it.copy(pendingImageLabel = label) }
        appendChat(ChatRole.User, "附加了一张图片", label)
        appendChat(
            ChatRole.Assistant,
            "图片入口已准备好，当前版本已记录附件，下一步会接入视觉理解与基于图片的控制指令。"
        )
    }

    fun clearHistory(systemMessage: String = "聊天历史已清空。") {
        currentTaskJob?.cancel()
        currentTaskJob = null
        updateState {
            it.copy(
                input = "",
                chatBusy = false,
                pendingImageLabel = null,
                taskExecution = null,
                taskHistory = emptyList()
            )
        }
        ChatTaskHistoryStore.clear(appContext)
        val messages = buildList {
            add(welcomeMessage())
            add(ChatMessage(role = ChatRole.Assistant, content = systemMessage))
        }
        replaceMessages(messages)
    }

    fun cancelCurrentTaskExecution() {
        val runningTask = uiState.value.taskExecution
            ?.takeIf { it.status == ChatTaskProgressState.Running }
            ?: return
        currentTaskJob?.cancel(
            CancellationException("用户取消任务：${runningTask.title}")
        )
    }

    fun clearCurrentTaskExecution() {
        updateState { state ->
            val currentTask = state.taskExecution ?: return@updateState state
            state.copy(
                taskExecution = null,
                taskHistory = appendTaskHistory(state.taskHistory, currentTask)
            )
        }
        persistTaskState()
    }

    fun clearTaskHistory() {
        updateState { it.copy(taskHistory = emptyList()) }
        persistTaskState()
    }

    fun setTaskHistoryFilter(filter: ChatTaskHistoryFilter) {
        updateState { it.copy(taskHistoryFilter = filter) }
    }

    fun retryTask(task: ChatTaskExecutionState) {
        val action = task.taskAction ?: return
        updateState { it.copy(chatBusy = true) }
        startTaskExecution(
            action = action,
            originPrompt = task.originPrompt.ifBlank { task.title },
            retryCount = task.retryCount + 1,
            retryFromTaskId = task.taskId
        )
        viewModelScope.launch {
            try {
                currentTaskJob = currentCoroutineContext()[Job]
                when (action) {
                    ChatTaskAction.ConfirmThenSafeTap -> {
                        val automationTaskInputs = overviewController.automationController.currentTaskInputs()
                        executeConfirmThenSafeTapTask(automationTaskInputs)
                    }
                    ChatTaskAction.ProbeThenCapabilities -> {
                        executeProbeThenCapabilitiesTask()
                    }
                }
            } catch (_: CancellationException) {
                cancelTaskExecution("任务已取消：已停止后续步骤。")
            } finally {
                currentTaskJob = null
                finishChat()
            }
        }
    }

    private fun restoreTaskState() {
        val persistedState = ChatTaskHistoryStore.load(appContext)
        updateState {
            it.copy(
                taskExecution = persistedState.currentTask?.normalizeRestoredTask(),
                taskHistory = persistedState.taskHistory.take(MAX_TASK_HISTORY_ITEMS)
            )
        }
    }

    private fun persistTaskState() {
        val currentState = uiState.value
        if (currentState.taskExecution == null && currentState.taskHistory.isEmpty()) {
            ChatTaskHistoryStore.clear(appContext)
            return
        }
        ChatTaskHistoryStore.save(
            context = appContext,
            currentTask = currentState.taskExecution,
            taskHistory = currentState.taskHistory.take(MAX_TASK_HISTORY_ITEMS)
        )
    }

    fun submitCurrentInput(
        modelSettings: ModelSettings,
        onModelCallSuccess: () -> Unit = {}
    ) {
        submitPrompt(uiState.value.input, modelSettings, onModelCallSuccess)
    }

    fun submitPrompt(
        prompt: String,
        modelSettings: ModelSettings,
        onModelCallSuccess: () -> Unit = {}
    ) {
        val normalized = prompt.trim()
        if (normalized.isBlank()) {
            return
        }
        val attachment = uiState.value.pendingImageLabel
        appendChat(ChatRole.User, normalized, attachment)
        updateState {
            it.copy(
                input = "",
                pendingImageLabel = null,
                chatBusy = true
            )
        }
        viewModelScope.launch {
            val replyMessageId = appendChat(
                ChatRole.Assistant,
                "正在分析指令...",
                state = ChatMessageState.Streaming
            )
            val overviewUiState = overviewController.uiState.value
            val automationUiState = overviewController.automationController.state.value
            val automationTaskInputs = overviewController.automationController.currentTaskInputs()
            val plan = ChatPromptPlanner.plan(
                ChatPlannerContext(
                    prompt = normalized,
                    modelSettings = modelSettings,
                    sessionSummary = overviewUiState.runtimeState.session.summary,
                    capabilityStatus = overviewUiState.runtimeState.capabilityStatus,
                    eventStreaming = overviewUiState.eventState.eventStreaming
                )
            )
            updateState {
                it.copy(
                    latestAiStatus = when (plan) {
                        is ChatPromptPlan.AssistantReply -> plan.aiStatus
                        is ChatPromptPlan.LocalActionExecution -> plan.aiStatus
                        is ChatPromptPlan.TaskExecution -> plan.aiStatus
                        is ChatPromptPlan.ToolExecution -> plan.aiStatus
                    }
                )
            }
            when (plan) {
                is ChatPromptPlan.AssistantReply -> {
                    if (plan.aiStatus.startsWith("AI ") && plan.aiStatus != "AI 请求失败") {
                        onModelCallSuccess()
                    }
                    updateChatMessage(replyMessageId, plan.message, ChatMessageState.Final)
                    finishChat()
                }

                is ChatPromptPlan.LocalActionExecution -> {
                    updateChatMessage(replyMessageId, plan.assistantMessage, ChatMessageState.Streaming)
                    when (plan.action) {
                        ChatLocalAction.SafeTap -> {
                            val reply = overviewController.automationController.safeTapUsingResolvedTarget()
                            updateChatMessage(replyMessageId, reply, ChatMessageState.Final)
                            finishChat()
                        }

                        ChatLocalAction.ReadScreenSize -> {
                            val reply = overviewController.readScreenSizeForChat()
                            updateChatMessage(replyMessageId, reply, ChatMessageState.Final)
                            finishChat()
                        }
                    }
                }

                is ChatPromptPlan.TaskExecution -> {
                    startTaskExecution(
                        action = plan.action,
                        originPrompt = normalized
                    )
                    currentTaskJob = currentCoroutineContext()[Job]
                    try {
                        updateChatMessage(replyMessageId, plan.assistantMessage, ChatMessageState.Streaming)
                        val reply = when (plan.action) {
                            ChatTaskAction.ConfirmThenSafeTap ->
                                executeConfirmThenSafeTapTask(automationTaskInputs)
                            ChatTaskAction.ProbeThenCapabilities ->
                                executeProbeThenCapabilitiesTask()
                        }
                        updateChatMessage(replyMessageId, reply, ChatMessageState.Final)
                    } catch (_: CancellationException) {
                        cancelTaskExecution("任务已取消：已停止后续步骤。")
                        updateChatMessage(
                            replyMessageId,
                            "任务已取消：已停止后续步骤。",
                            ChatMessageState.Final
                        )
                    } finally {
                        currentTaskJob = null
                        finishChat()
                    }
                }

                is ChatPromptPlan.ToolExecution -> {
                    if (plan.reflectResultWithModel) {
                        onModelCallSuccess()
                    }
                    handleToolIntent(
                        tool = plan.tool,
                        arguments = plan.arguments,
                        normalizedPrompt = normalized,
                        replyMessageId = replyMessageId,
                        assistantMessage = plan.assistantMessage,
                        reflectResultWithModel = plan.reflectResultWithModel,
                        modelSettings = modelSettings,
                        automationUiState = automationUiState,
                        onModelCallSuccess = onModelCallSuccess
                    )
                }
            }
        }
    }

    private fun restoreHistory() {
        val restoredMessages = ChatHistoryStore.load(appContext)
        if (restoredMessages.isNotEmpty()) {
            replaceMessages(restoredMessages)
        } else {
            replaceMessages(listOf(welcomeMessage()))
        }
    }

    private fun appendChat(
        role: ChatRole,
        content: String,
        attachmentLabel: String? = null,
        state: ChatMessageState = ChatMessageState.Final
    ): String {
        val message = ChatMessage(
            role = role,
            content = content,
            attachmentLabel = attachmentLabel,
            state = state
        )
        val updatedMessages = uiState.value.messages + message
        replaceMessages(updatedMessages)
        return message.id
    }

    private fun updateChatMessage(
        messageId: String,
        content: String,
        state: ChatMessageState = ChatMessageState.Streaming,
        attachmentLabel: String? = null
    ) {
        val messages = uiState.value.messages
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) {
            return
        }
        val existing = messages[index]
        val updatedMessages = messages.toMutableList().apply {
            this[index] = existing.copy(
                content = content,
                state = state,
                attachmentLabel = attachmentLabel ?: existing.attachmentLabel
            )
        }
        replaceMessages(updatedMessages)
    }

    private fun replaceMessages(messages: List<ChatMessage>) {
        ChatHistoryStore.save(appContext, messages)
        updateState { it.copy(messages = messages) }
    }

    private suspend fun executeConfirmThenSafeTapTask(
        automationTaskInputs: AutomationTaskInputs
    ): String {
        val expectedPackage = automationTaskInputs.pageConfirmPackage
        val expectedText = automationTaskInputs.pageConfirmText
        val expectedViewId = automationTaskInputs.pageConfirmViewId
        val targetPackage = automationTaskInputs.clickPrecheckPackage
        val targetText = automationTaskInputs.clickPrecheckText
        val targetViewId = automationTaskInputs.clickPrecheckViewId

        val steps = mutableListOf<String>()

        markTaskStepRunning(0, "正在比对页面确认条件")
        val confirmReply = overviewController.automationController.confirmPage(
            expectedPackage = expectedPackage,
            expectedText = expectedText,
            expectedViewId = expectedViewId
        )
        val confirmSucceeded = looksLikeSuccessfulStep(confirmReply)
        markTaskStepFinished(0, confirmSucceeded, confirmReply)
        steps += renderTaskStep(1, "页面确认", confirmReply)
        if (!confirmSucceeded) {
            finishTaskExecution(
                success = false,
                summary = "任务已中止：页面确认未通过。"
            )
            steps += "任务中止：页面确认未通过，未继续执行点击前检查和安全点击。"
            return steps.joinToString("\n\n")
        }

        markTaskStepRunning(1, "正在检查点击前置条件")
        val precheckReply = overviewController.automationController.precheckClickTarget(
            expectedPackage = targetPackage,
            targetText = targetText,
            targetViewId = targetViewId
        )
        val precheckSucceeded = looksLikeSuccessfulStep(precheckReply)
        markTaskStepFinished(1, precheckSucceeded, precheckReply)
        steps += renderTaskStep(2, "点击前检查", precheckReply)
        if (!precheckSucceeded) {
            finishTaskExecution(
                success = false,
                summary = "任务已中止：点击前检查未通过。"
            )
            steps += "任务中止：点击前检查未通过，未继续执行安全点击。"
            return steps.joinToString("\n\n")
        }

        markTaskStepRunning(2, "正在执行安全点击")
        val safeTapReply = overviewController.automationController.safeTapUsingResolvedTarget()
        val safeTapSucceeded = looksLikeSuccessfulStep(safeTapReply)
        markTaskStepFinished(2, safeTapSucceeded, safeTapReply)
        steps += renderTaskStep(3, "安全点击", safeTapReply)
        steps += if (safeTapSucceeded) {
            "任务完成：已依次完成页面确认、点击前检查和安全点击。"
        } else {
            "任务结束：安全点击未成功，请结合上面的步骤结果继续排查。"
        }
        finishTaskExecution(
            success = safeTapSucceeded,
            summary = if (safeTapSucceeded) {
                "任务已完成：页面确认、点击前检查和安全点击均成功。"
            } else {
                "任务已结束：前置步骤成功，但安全点击未完成。"
            }
        )
        return steps.joinToString("\n\n")
    }

    private suspend fun executeProbeThenCapabilitiesTask(): String {
        markTaskStepRunning(0, "正在执行 Runtime Probe")
        val probeReply = overviewController.probeSessionForChat()
        val probeSucceeded = looksLikeSuccessfulStep(probeReply)
        markTaskStepFinished(0, probeSucceeded, probeReply)
        if (!probeSucceeded) {
            finishTaskExecution(
                success = false,
                summary = "任务已中止：Runtime Probe 未通过。"
            )
            return buildString {
                append(renderTaskStep(1, "Runtime Probe", probeReply))
                append("\n\n")
                append("任务中止：Runtime Probe 未通过，未继续读取能力。")
            }
        }
        markTaskStepRunning(1, "正在读取运行时能力")
        val capabilitiesReply = overviewController.getCapabilitiesForChat()
        val capabilitiesSucceeded = looksLikeSuccessfulStep(capabilitiesReply)
        markTaskStepFinished(1, capabilitiesSucceeded, capabilitiesReply)
        finishTaskExecution(
            success = capabilitiesSucceeded,
            summary = if (capabilitiesSucceeded) {
                "任务已完成：运行时探测和能力读取均成功。"
            } else {
                "任务已结束：Runtime Probe 成功，但能力读取未通过。"
            }
        )
        return listOf(
            renderTaskStep(1, "Runtime Probe", probeReply),
            renderTaskStep(2, "获取能力", capabilitiesReply),
            if (capabilitiesSucceeded) {
                "任务完成：运行时探测和能力读取均已完成。"
            } else {
                "任务结束：Runtime Probe 已完成，但能力读取未成功。"
            }
        ).joinToString("\n\n")
    }

    private suspend fun finalizeToolReply(
        replyMessageId: String,
        normalizedPrompt: String,
        tool: ClawTool,
        arguments: Map<String, String>,
        assistantMessage: String?,
        result: String,
        reflectResultWithModel: Boolean,
        modelSettings: ModelSettings,
        onModelCallSuccess: () -> Unit
    ) {
        val reflectionMessage = if (reflectResultWithModel) {
            AiAgentOrchestrator.reflectToolResult(
                settings = modelSettings,
                input = AiToolReflectionInput(
                    originalPrompt = normalizedPrompt,
                    tool = tool,
                    arguments = arguments,
                    toolResult = result,
                    runtimeSnapshot = overviewController.uiState.value.let { overviewUiState ->
                        AiRuntimeSnapshot(
                            sessionSummary = overviewUiState.runtimeState.session.summary,
                            capabilityStatus = overviewUiState.runtimeState.capabilityStatus,
                            eventStreaming = overviewUiState.eventState.eventStreaming
                        )
                    }
                )
            ).fold(
                onSuccess = {
                    onModelCallSuccess()
                    updateState { state -> state.copy(latestAiStatus = "AI 已总结工具结果") }
                    it
                },
                onFailure = {
                    updateState { state -> state.copy(latestAiStatus = "AI 总结回退为原始结果") }
                    null
                }
            )
        } else {
            null
        }
        updateChatMessage(
            replyMessageId,
            buildAssistantReply(assistantMessage, reflectionMessage, result),
            ChatMessageState.Final
        )
        finishChat()
    }

    private suspend fun handleToolIntent(
        tool: ClawTool,
        arguments: Map<String, String>,
        normalizedPrompt: String,
        replyMessageId: String,
        assistantMessage: String?,
        reflectResultWithModel: Boolean,
        modelSettings: ModelSettings,
        automationUiState: OverviewAutomationState,
        onModelCallSuccess: () -> Unit
    ) {
        when (tool) {
            ClawTool.PAGE_CONFIRM -> {
                updateChatMessage(replyMessageId, assistantMessage ?: "正在执行页面确认...", ChatMessageState.Streaming)
                val reply = overviewController.automationController.confirmPage(
                    expectedPackage = arguments.stringArg("expected_package", "package", "expectedPackage").ifBlank { automationUiState.pageConfirmPackage },
                    expectedText = arguments.stringArg("expected_text", "text", "expectedText").ifBlank { automationUiState.pageConfirmText },
                    expectedViewId = arguments.stringArg("expected_view_id", "view_id", "expectedViewId").ifBlank { automationUiState.pageConfirmViewId }
                )
                finalizeToolReply(
                    replyMessageId = replyMessageId,
                    normalizedPrompt = normalizedPrompt,
                    tool = tool,
                    arguments = arguments,
                    assistantMessage = assistantMessage,
                    result = reply,
                    reflectResultWithModel = reflectResultWithModel,
                    modelSettings = modelSettings,
                    onModelCallSuccess = onModelCallSuccess
                )
            }

            ClawTool.CLICK_PRECHECK -> {
                updateChatMessage(replyMessageId, assistantMessage ?: "正在执行点击前检查...", ChatMessageState.Streaming)
                val reply = overviewController.automationController.precheckClickTarget(
                    expectedPackage = arguments.stringArg("expected_package", "package", "expectedPackage").ifBlank { automationUiState.clickPrecheckPackage },
                    targetText = arguments.stringArg("target_text", "text", "targetText").ifBlank { automationUiState.clickPrecheckText },
                    targetViewId = arguments.stringArg("target_view_id", "view_id", "targetViewId").ifBlank { automationUiState.clickPrecheckViewId }
                )
                finalizeToolReply(
                    replyMessageId = replyMessageId,
                    normalizedPrompt = normalizedPrompt,
                    tool = tool,
                    arguments = arguments,
                    assistantMessage = assistantMessage,
                    result = reply,
                    reflectResultWithModel = reflectResultWithModel,
                    modelSettings = modelSettings,
                    onModelCallSuccess = onModelCallSuccess
                )
            }

            ClawTool.RUNTIME_PING -> {
                updateChatMessage(replyMessageId, assistantMessage ?: "正在连接 ClawRuntime...", ChatMessageState.Streaming)
                val reply = overviewController.pingForChat()
                finalizeToolReply(replyMessageId, normalizedPrompt, tool, arguments, assistantMessage, reply, reflectResultWithModel, modelSettings, onModelCallSuccess)
            }

            ClawTool.GET_VERSION -> {
                updateChatMessage(replyMessageId, assistantMessage ?: "正在读取版本信息...", ChatMessageState.Streaming)
                val reply = overviewController.getVersionForChat()
                finalizeToolReply(replyMessageId, normalizedPrompt, tool, arguments, assistantMessage, reply, reflectResultWithModel, modelSettings, onModelCallSuccess)
            }

            ClawTool.GET_HEALTH -> {
                updateChatMessage(replyMessageId, assistantMessage ?: "正在读取健康状态...", ChatMessageState.Streaming)
                val reply = overviewController.getHealthForChat()
                finalizeToolReply(replyMessageId, normalizedPrompt, tool, arguments, assistantMessage, reply, reflectResultWithModel, modelSettings, onModelCallSuccess)
            }

            ClawTool.GET_LAST_ERROR -> {
                updateChatMessage(replyMessageId, assistantMessage ?: "正在读取最近错误...", ChatMessageState.Streaming)
                val reply = overviewController.getLastErrorForChat()
                finalizeToolReply(replyMessageId, normalizedPrompt, tool, arguments, assistantMessage, reply, reflectResultWithModel, modelSettings, onModelCallSuccess)
            }

            ClawTool.PROBE_SESSION -> {
                updateChatMessage(replyMessageId, assistantMessage ?: "正在执行 Runtime Probe...", ChatMessageState.Streaming)
                val reply = overviewController.probeSessionForChat()
                finalizeToolReply(replyMessageId, normalizedPrompt, tool, arguments, assistantMessage, reply, reflectResultWithModel, modelSettings, onModelCallSuccess)
            }

            ClawTool.GET_CAPABILITIES -> {
                updateChatMessage(replyMessageId, assistantMessage ?: "正在读取运行时能力...", ChatMessageState.Streaming)
                val reply = overviewController.getCapabilitiesForChat()
                finalizeToolReply(replyMessageId, normalizedPrompt, tool, arguments, assistantMessage, reply, reflectResultWithModel, modelSettings, onModelCallSuccess)
            }

            ClawTool.CAPTURE_SCREEN -> {
                val withPreview = arguments["read_after_capture"] == "true"
                updateChatMessage(
                    replyMessageId,
                    assistantMessage ?: if (withPreview) "正在截图并预览..." else "正在请求截图...",
                    ChatMessageState.Streaming
                )
                val reply = overviewController.captureScreenForChat(includePreview = withPreview)
                finalizeToolReply(replyMessageId, normalizedPrompt, tool, arguments, assistantMessage, reply, reflectResultWithModel, modelSettings, onModelCallSuccess)
            }

            ClawTool.READ_LATEST_CAPTURE -> {
                updateChatMessage(replyMessageId, assistantMessage ?: "正在读取并预览最近截图...", ChatMessageState.Streaming)
                val reply = overviewController.readLatestCaptureForChat()
                finalizeToolReply(replyMessageId, normalizedPrompt, tool, arguments, assistantMessage, reply, reflectResultWithModel, modelSettings, onModelCallSuccess)
            }

            ClawTool.READ_FILE_LIMITED -> {
                finalizeToolReply(
                    replyMessageId = replyMessageId,
                    normalizedPrompt = normalizedPrompt,
                    tool = tool,
                    arguments = arguments,
                    assistantMessage = assistantMessage,
                    result = "请在概览页使用“读取并预览最近截图”，当前聊天入口暂不接受文件路径参数。",
                    reflectResultWithModel = false,
                    modelSettings = modelSettings,
                    onModelCallSuccess = onModelCallSuccess
                )
            }

            ClawTool.INJECT_TAP -> {
                val x = arguments.intArg("x", 540)
                val y = arguments.intArg("y", 1200)
                val displayId = arguments.intArg("display_id", 0)
                updateChatMessage(replyMessageId, assistantMessage ?: "正在执行点击 ($x,$y)...", ChatMessageState.Streaming)
                val reply = overviewController.injectTapForChat(x = x, y = y, displayId = displayId)
                finalizeToolReply(replyMessageId, normalizedPrompt, tool, arguments, assistantMessage, reply, reflectResultWithModel, modelSettings, onModelCallSuccess)
            }

            ClawTool.INJECT_SWIPE -> {
                val x1 = arguments.intArg("x1", 540)
                val y1 = arguments.intArg("y1", 1800)
                val x2 = arguments.intArg("x2", 540)
                val y2 = arguments.intArg("y2", 400)
                val durationMs = arguments.intArg("duration_ms", 350)
                val displayId = arguments.intArg("display_id", 0)
                updateChatMessage(replyMessageId, assistantMessage ?: "正在执行滑动 ($x1,$y1 -> $x2,$y2)...", ChatMessageState.Streaming)
                val reply = overviewController.injectSwipeForChat(
                    x1 = x1,
                    y1 = y1,
                    x2 = x2,
                    y2 = y2,
                    durationMs = durationMs,
                    displayId = displayId
                )
                finalizeToolReply(replyMessageId, normalizedPrompt, tool, arguments, assistantMessage, reply, reflectResultWithModel, modelSettings, onModelCallSuccess)
            }

            ClawTool.EXECUTE_SHELL_LIMITED -> {
                val command = arguments.stringArg("command").ifBlank {
                    normalizedPrompt
                        .takeIf { it.startsWith("/shell ", ignoreCase = true) }
                        ?.removePrefix("/shell ")
                        ?.trim()
                        .orEmpty()
                }
                if (command.isBlank()) {
                    updateChatMessage(
                        replyMessageId,
                        mergeAssistantMessage(assistantMessage, "请提供受限 Shell 命令，例如 `/shell wm size`。"),
                        ChatMessageState.Final
                    )
                    finishChat()
                    return
                }
                updateChatMessage(replyMessageId, assistantMessage ?: "正在执行受限 Shell: $command", ChatMessageState.Streaming)
                val reply = overviewController.executeShellForChat(command)
                finalizeToolReply(replyMessageId, normalizedPrompt, tool, arguments, assistantMessage, reply, reflectResultWithModel, modelSettings, onModelCallSuccess)
            }

            ClawTool.SUBSCRIBE_EVENTS -> {
                val operation = arguments.stringArg("operation").lowercase()
                if (operation == "stop" || normalizedPrompt.contains("停")) {
                    updateChatMessage(replyMessageId, assistantMessage ?: "正在停止事件流...", ChatMessageState.Streaming)
                    overviewController.stopContinuousSubscription {
                        updateChatMessage(replyMessageId, "事件流已手动停止。", ChatMessageState.Final)
                        finishChat()
                    }
                } else {
                    updateChatMessage(replyMessageId, assistantMessage ?: "正在建立事件流连接...", ChatMessageState.Streaming)
                    overviewController.startContinuousSubscription(
                        onStarted = {
                            updateChatMessage(replyMessageId, it, ChatMessageState.Final)
                            finishChat()
                        },
                        onClosed = {
                            finishChat()
                        },
                        onFailure = {
                            updateChatMessage(replyMessageId, "事件订阅失败：$it", ChatMessageState.Final)
                            finishChat()
                        }
                    )
                }
            }
        }
    }

    private fun welcomeMessage(): ChatMessage {
        return ChatMessage(
            role = ChatRole.Assistant,
            content = "可以像聊天一样直接下达指令，例如“ping ClawRuntime”、“获取能力”、“截图并预览”、“确认页面后安全点击”、“检查运行时状态”、“执行 wm size”、“开始事件订阅”。"
        )
    }

    private fun finishChat() {
        updateState { it.copy(chatBusy = false) }
    }

    private fun updateState(transform: (ChatUiState) -> ChatUiState) {
        _uiState.update(transform)
    }

    private fun startTaskExecution(
        action: ChatTaskAction,
        originPrompt: String,
        retryCount: Int = 0,
        retryFromTaskId: String? = null
    ) {
        val startedAt = System.currentTimeMillis()
        val title = when (action) {
            ChatTaskAction.ConfirmThenSafeTap -> "页面确认后安全点击"
            ChatTaskAction.ProbeThenCapabilities -> "运行时状态检查"
        }
        val summary = when (action) {
            ChatTaskAction.ConfirmThenSafeTap -> "正在按“页面确认 -> 点击前检查 -> 安全点击”推进任务。"
            ChatTaskAction.ProbeThenCapabilities -> "正在按“Runtime Probe -> 获取能力”推进任务。"
        }
        val stepTitles = when (action) {
            ChatTaskAction.ConfirmThenSafeTap -> listOf("页面确认", "点击前检查", "安全点击")
            ChatTaskAction.ProbeThenCapabilities -> listOf("Runtime Probe", "获取能力")
        }
        updateState {
            val archivedHistory = it.taskExecution?.let { existingTask ->
                appendTaskHistory(it.taskHistory, existingTask)
            } ?: it.taskHistory
            it.copy(
                taskExecution = ChatTaskExecutionState(
                    taskId = buildChatTaskId(),
                    title = title,
                    summary = summary,
                    status = ChatTaskProgressState.Running,
                    startedAtEpochMs = startedAt,
                    steps = stepTitles.map { stepTitle ->
                        ChatTaskStepState(title = stepTitle)
                    },
                    finishedAtEpochMs = 0L,
                    taskAction = action,
                    failureReason = null,
                    originPrompt = originPrompt,
                    retryCount = retryCount,
                    retryFromTaskId = retryFromTaskId,
                    failure = null
                ),
                taskHistory = archivedHistory
            )
        }
        persistTaskState()
    }

    private fun markTaskStepRunning(stepIndex: Int, detail: String) {
        val startedAt = System.currentTimeMillis()
        updateTaskExecution { task ->
            task.copy(
                steps = task.steps.mapIndexed { index, step ->
                    if (index == stepIndex) {
                        step.copy(
                            status = ChatTaskProgressState.Running,
                            detail = detail,
                            startedAtEpochMs = if (step.startedAtEpochMs > 0L) step.startedAtEpochMs else startedAt
                        )
                    } else {
                        step
                    }
                }
            )
        }
    }

    private fun markTaskStepFinished(stepIndex: Int, success: Boolean, detail: String) {
        val finishedAt = System.currentTimeMillis()
        updateTaskExecution { task ->
            task.copy(
                steps = task.steps.mapIndexed { index, step ->
                    if (index == stepIndex) {
                        step.copy(
                            status = if (success) ChatTaskProgressState.Succeeded else ChatTaskProgressState.Failed,
                            detail = detail,
                            finishedAtEpochMs = finishedAt,
                            startedAtEpochMs = if (step.startedAtEpochMs > 0L) step.startedAtEpochMs else finishedAt
                        )
                    } else {
                        step
                    }
                }
            )
        }
    }

    private fun finishTaskExecution(success: Boolean, summary: String) {
        val finishedAt = System.currentTimeMillis()
        updateTaskExecution { task ->
            val failureDetail = if (!success) {
                task.steps.firstOrNull { it.status == ChatTaskProgressState.Failed }?.detail ?: summary
            } else {
                null
            }
            task.copy(
                summary = summary,
                status = if (success) ChatTaskProgressState.Succeeded else ChatTaskProgressState.Failed,
                finishedAtEpochMs = finishedAt,
                failureReason = failureDetail,
                failure = if (success) {
                    null
                } else {
                    buildTaskFailureState(
                        summary = summary,
                        rawDetail = failureDetail.orEmpty()
                    )
                }
            )
        }
    }

    private fun cancelTaskExecution(summary: String) {
        val finishedAt = System.currentTimeMillis()
        updateTaskExecution { task ->
            task.copy(
                summary = summary,
                status = ChatTaskProgressState.Cancelled,
                finishedAtEpochMs = finishedAt,
                failureReason = summary,
                failure = ChatTaskFailureState(
                    code = "task_cancelled",
                    summary = "任务已取消",
                    rawDetail = summary
                ),
                steps = task.steps.map { step ->
                    if (step.status == ChatTaskProgressState.Running) {
                        step.copy(
                            status = ChatTaskProgressState.Cancelled,
                            detail = "用户已取消该步骤",
                            startedAtEpochMs = if (step.startedAtEpochMs > 0L) step.startedAtEpochMs else finishedAt,
                            finishedAtEpochMs = finishedAt
                        )
                    } else {
                        step
                    }
                }
            )
        }
    }

    private fun updateTaskExecution(transform: (ChatTaskExecutionState) -> ChatTaskExecutionState) {
        updateState { state ->
            val task = state.taskExecution ?: return@updateState state
            state.copy(taskExecution = transform(task))
        }
        persistTaskState()
    }

    private fun buildChatTaskId(): String {
        return "chat-task-${System.currentTimeMillis()}"
    }

    private fun appendTaskHistory(
        history: List<ChatTaskExecutionState>,
        task: ChatTaskExecutionState
    ): List<ChatTaskExecutionState> {
        return listOf(task) + history.filterNot { it.taskId == task.taskId }
            .take(MAX_TASK_HISTORY_ITEMS - 1)
    }

    private fun ChatTaskExecutionState.normalizeRestoredTask(): ChatTaskExecutionState {
        if (status != ChatTaskProgressState.Running) {
            return this
        }
        val restoredAt = System.currentTimeMillis()
        return copy(
            status = ChatTaskProgressState.Cancelled,
            summary = "应用已重启，之前运行中的任务未继续执行。",
            finishedAtEpochMs = if (finishedAtEpochMs > 0L) finishedAtEpochMs else restoredAt,
            failureReason = "应用重启导致任务中断",
            failure = ChatTaskFailureState(
                code = "app_restarted",
                summary = "应用重启导致任务中断",
                rawDetail = "应用恢复时发现该任务仍处于执行中，已自动标记为取消"
            ),
            steps = steps.map { step ->
                if (step.status == ChatTaskProgressState.Running) {
                    step.copy(
                        status = ChatTaskProgressState.Cancelled,
                        detail = "应用重启后，该步骤未继续执行",
                        finishedAtEpochMs = if (step.finishedAtEpochMs > 0L) {
                            step.finishedAtEpochMs
                        } else {
                            restoredAt
                        }
                    )
                } else {
                    step
                }
            }
        )
    }

    companion object {
        private const val MAX_TASK_HISTORY_ITEMS = 20

        fun provideFactory(
            appContext: Context,
            overviewController: OverviewController
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    return ChatViewModel(
                        appContext = appContext,
                        overviewController = overviewController
                    ) as T
                }
            }
        }
    }
}

private fun buildTaskFailureState(
    summary: String,
    rawDetail: String
): ChatTaskFailureState {
    val normalized = "$summary\n$rawDetail".lowercase()
    val code = when {
        "页面确认" in summary || "matched=false" in normalized -> "page_confirm_failed"
        "点击前检查" in summary || "target" in normalized -> "click_precheck_failed"
        "安全点击" in summary || "accepted=false" in normalized -> "safe_tap_failed"
        "runtime probe" in normalized -> "runtime_probe_failed"
        "能力" in summary || "capabilities" in normalized -> "capabilities_failed"
        "denied" in normalized -> "permission_denied"
        else -> "task_failed"
    }
    val failureSummary = when (code) {
        "page_confirm_failed" -> "页面确认未通过"
        "click_precheck_failed" -> "点击前检查未通过"
        "safe_tap_failed" -> "安全点击未成功"
        "runtime_probe_failed" -> "Runtime Probe 未通过"
        "capabilities_failed" -> "能力读取未通过"
        "permission_denied" -> "权限或执行条件不足"
        else -> summary.ifBlank { "任务执行失败" }
    }
    return ChatTaskFailureState(
        code = code,
        summary = failureSummary,
        rawDetail = rawDetail.ifBlank { summary }
    )
}

@Composable
internal fun rememberChatViewModel(
    context: Context,
    overviewController: OverviewController
): ChatViewModel {
    val factory = remember(context, overviewController) {
        ChatViewModel.provideFactory(
            appContext = context.applicationContext,
            overviewController = overviewController
        )
    }
    return viewModel(factory = factory)
}

private fun Map<String, String>.intArg(key: String, defaultValue: Int): Int {
    return this[key]?.toIntOrNull() ?: defaultValue
}

private fun Map<String, String>.stringArg(vararg keys: String): String {
    return keys.firstNotNullOfOrNull { key ->
        this[key]?.trim()?.takeIf { it.isNotEmpty() }
    }.orEmpty()
}

private fun mergeAssistantMessage(message: String?, result: String): String {
    val normalizedMessage = message?.trim().orEmpty()
    return if (normalizedMessage.isBlank()) result else "$normalizedMessage\n$result"
}

private fun buildAssistantReply(
    assistantMessage: String?,
    reflectionMessage: String?,
    result: String
): String {
    val lines = buildList {
        assistantMessage?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        reflectionMessage?.trim()?.takeIf {
            it.isNotEmpty() && it != assistantMessage?.trim() && it != result.trim()
        }?.let(::add)
        add(result)
    }
    return lines.joinToString("\n")
}

private fun renderTaskStep(stepNumber: Int, title: String, output: String): String {
    return "步骤 $stepNumber $title\n$output"
}

private fun looksLikeSuccessfulStep(output: String): Boolean {
    val normalized = output.lowercase()
    if ("失败" in output || "error" in normalized || "denied" in normalized) {
        return false
    }
    return "成功" in output ||
        ("session=" in normalized && "ping=" in normalized) ||
        "状态=Succeeded" in output ||
        "状态=Completed" in output ||
        "accepted=true" in normalized ||
        "matched=true" in normalized
}
