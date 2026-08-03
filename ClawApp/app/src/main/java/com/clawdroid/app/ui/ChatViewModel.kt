package com.clawdroid.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawdroid.app.agent.AgentKernel
import com.clawdroid.app.agent.AgentRunPhase
import com.clawdroid.app.agent.PolicyDecision
import com.clawdroid.app.agent.WorldSnapshot
import com.clawdroid.app.ai.AgentRunEvent
import com.clawdroid.app.ai.AgentRunEventKind
import com.clawdroid.app.ai.AgentToolLoopController
import com.clawdroid.app.ai.AgentToolLoopHost
import com.clawdroid.app.ai.ContextCompressor
import com.clawdroid.app.chat.ChatSessionCoordinator
import com.clawdroid.app.chat.ChatTaskExecutionController
import com.clawdroid.app.data.AppSettingsStore
import com.clawdroid.app.data.AgentRunStore
import com.clawdroid.app.data.MemoryFacade
import com.clawdroid.app.data.ChatSessionSummary
import com.clawdroid.app.data.model.AgentOrchestrationSettings
import com.clawdroid.app.data.model.ChatMedia
import com.clawdroid.app.data.model.ChatMessage
import com.clawdroid.app.data.model.ChatMessageState
import com.clawdroid.app.data.model.ChatRole
import com.clawdroid.app.data.model.ChatTaskExecutionState
import com.clawdroid.app.data.model.ChatTaskProgressState
import com.clawdroid.app.data.model.ModelSettings
import com.clawdroid.app.data.model.asTerminated
import com.clawdroid.app.ai.AiAgentOrchestrator
import com.clawdroid.app.ai.AiAgentPlan
import com.clawdroid.app.ai.AiToolReflectionInput
import com.clawdroid.app.ai.AiToolStepRecord
import com.clawdroid.app.ai.AiRuntimeSnapshot
import com.clawdroid.app.chat.ChatHistoryTurn
import com.clawdroid.app.chat.ChatLocalAction
import com.clawdroid.app.chat.ChatPlannerContext
import com.clawdroid.app.chat.ChatPromptPlan
import com.clawdroid.app.chat.ChatPromptPlanner
import com.clawdroid.app.chat.ChatTaskAction
import com.clawdroid.app.chat.ChatTextLimits
import com.clawdroid.app.chat.toAgentDefinition
import com.clawdroid.app.fault.FaultCodes
import com.clawdroid.app.fault.FaultIsolation
import com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot
import com.clawdroid.app.skills.AgentStepListener
import com.clawdroid.app.skills.ClawAgentCatalog
import com.clawdroid.app.skills.ClawAgentRunner
import com.clawdroid.app.skills.RuntimeTaskPoller
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolDispatcher
import com.clawdroid.app.tools.InputGuards
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ChatUiState(
    val input: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val pendingImageLabel: String? = null,
    val pendingImageUri: android.net.Uri? = null,
    val pendingImageMimeType: String? = null,
    val chatBusy: Boolean = false,
    val latestAiStatus: String = "规则优先，模型待命",
    val taskExecution: ChatTaskExecutionState? = null,
    val taskHistory: List<ChatTaskExecutionState> = emptyList(),
    val taskHistoryFilter: ChatTaskHistoryFilter = ChatTaskHistoryFilter.All,
    val activeSessionId: String = "",
    val activeSessionTitle: String = "新对话",
    val sessionSummaries: List<ChatSessionSummary> = emptyList(),
    val agentEvents: List<AgentRunEvent> = emptyList(),
    val agentTimelineExpanded: Boolean = false,
    val apiCallsRemaining: Int = AgentOrchestrationSettings.DEFAULT_MAX_MODEL_API_CALLS,
    val awaitingBudgetContinue: Boolean = false,
    val compressedMemory: String = "",
    val pendingCommandReview: PendingCommandReview? = null
)

internal data class PendingCommandReview(
    val toolId: String,
    val toolDisplayName: String,
    val argumentsPreview: String,
    /** Goal-level App HITL (Stage B); not Runtime WaitingSignal IPC. */
    val isGoalConfirm: Boolean = false
)

private const val GOAL_REVIEW_TOOL_ID = "__goal_confirm__"

internal enum class ChatTaskHistoryFilter {
    All,
    Failed,
    Cancelled,
    Succeeded,
    Retried
}

internal class ChatViewModel(
    private val appContext: Context,
    private val overviewController: OverviewController,
    override val toolDispatcher: ClawToolDispatcher? = null
) : ViewModel(), AgentToolLoopHost {
    private val _uiState = MutableStateFlow(ChatUiState())
    override val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    override var currentTaskJob: Job? = null
    private var historySaveJob: Job? = null
    private var turnApiCallsUsed: Int = 0
    private var activeChatJob: Job? = null
    private var commandReviewDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private val agentKernel: AgentKernel = AgentKernel.shared
    private var activeKernelRunId: String? = null

    private val taskController = ChatTaskExecutionController(
        appContext = appContext,
        scope = viewModelScope,
        toolDispatcher = toolDispatcher,
        getState = { uiState.value },
        updateState = ::updateState,
        getCurrentTaskJob = { currentTaskJob },
        setCurrentTaskJob = { currentTaskJob = it },
        appendChat = { role, content, state ->
            appendChat(role, content, state = state)
        }
    )

    private val sessionCoordinator = ChatSessionCoordinator(
        appContext = appContext,
        scope = viewModelScope,
        getState = { uiState.value },
        updateState = ::updateState,
        getHistorySaveJob = { historySaveJob },
        setHistorySaveJob = { historySaveJob = it },
        isSessionChangeBlocked = {
            uiState.value.chatBusy ||
                currentTaskJob?.isActive == true ||
                activeChatJob?.isActive == true
        },
        onCreateSessionBlocked = {
            appendChat(
                ChatRole.Assistant,
                "当前仍有指令在执行，请等待完成或先取消任务后再新建对话。",
                state = ChatMessageState.Final
            )
        },
        onSelectSessionBlocked = {
            appendChat(
                ChatRole.Assistant,
                "当前仍有指令在执行，请等待完成或先取消任务后再切换对话。",
                state = ChatMessageState.Final
            )
        },
        onDeleteSessionBlocked = {
            appendChat(
                ChatRole.Assistant,
                "当前仍有指令在执行，请等待完成或先取消任务后再删除对话。",
                state = ChatMessageState.Final
            )
        },
        cancelActiveTaskJob = {
            activeChatJob?.cancel()
            activeChatJob = null
            currentTaskJob?.cancel()
            currentTaskJob = null
        },
        welcomeMessage = ::welcomeMessage
    )

    private val toolLoopController = AgentToolLoopController(
        host = this,
        overviewController = overviewController
    )

    init {
        viewModelScope.launch {
            sessionCoordinator.restoreHistory()
            val agentSettings = AppSettingsStore.loadAgentOrchestrationSettings(appContext)
            updateState {
                it.copy(apiCallsRemaining = agentSettings.maxModelApiCalls)
            }
        }
        taskController.restoreTaskState()
    }

    fun toggleAgentTimeline() {
        updateState { it.copy(agentTimelineExpanded = !it.agentTimelineExpanded) }
    }

    override fun appendAgentEvent(event: AgentRunEvent) {
        updateState { state ->
            state.copy(agentEvents = (state.agentEvents + event).takeLast(80))
        }
    }

    private fun clearAgentEvents() {
        updateState { it.copy(agentEvents = emptyList(), agentTimelineExpanded = false) }
    }

    override fun loadAgentSettings(): AgentOrchestrationSettings =
        AppSettingsStore.loadAgentOrchestrationSettings(appContext)

    override fun noteModelApiCall(onModelCallSuccess: () -> Unit): Boolean {
        val settings = loadAgentSettings()
        turnApiCallsUsed += 1
        val remaining = (settings.maxModelApiCalls - turnApiCallsUsed).coerceAtLeast(0)
        updateState {
            it.copy(
                apiCallsRemaining = remaining,
                awaitingBudgetContinue = remaining <= 0
            )
        }
        onModelCallSuccess()
        if (remaining <= 0) {
            appendAgentEvent(
                AgentRunEvent(
                    kind = AgentRunEventKind.Budget,
                    title = "本轮 API 预算已耗尽",
                    detail = "本条消息的回复过程已用满 ${settings.maxModelApiCalls} 次模型调用。发送下一条消息会重新计数。"
                )
            )
            return false
        }
        return true
    }

    private fun resetTurnApiBudget() {
        val settings = loadAgentSettings()
        turnApiCallsUsed = 0
        updateState {
            it.copy(
                apiCallsRemaining = settings.maxModelApiCalls,
                awaitingBudgetContinue = false
            )
        }
    }

    fun updateInput(value: String) {
        val (sanitized, _) = com.clawdroid.app.tools.InputGuards.sanitizePromptInput(value)
        updateState { it.copy(input = sanitized) }
    }

    fun applyVoiceTranscript(transcript: String) {
        val normalized = transcript.trim()
        if (normalized.isBlank()) {
            return
        }
        updateState { it.copy(input = normalized) }
    }

    fun onImagePicked(uri: android.net.Uri, mimeType: String? = null) {
        val label = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: uri.toString()
        updateState {
            it.copy(
                pendingImageUri = uri,
                pendingImageLabel = label,
                pendingImageMimeType = mimeType
            )
        }
    }

    fun clearPendingImage() {
        updateState {
            it.copy(pendingImageUri = null, pendingImageLabel = null, pendingImageMimeType = null)
        }
    }

    fun clearHistory(systemMessage: String = "聊天历史已清空。") {
        createNewSession()
        appendChat(ChatRole.Assistant, systemMessage, state = ChatMessageState.Final)
    }

    fun createNewSession() = sessionCoordinator.createNewSession()

    fun selectSession(sessionId: String) = sessionCoordinator.selectSession(sessionId)

    fun deleteCurrentSession() = sessionCoordinator.deleteCurrentSession()

    fun cancelCurrentTaskExecution() = taskController.cancelCurrentTaskExecution()

    /** 打断当前正在生成/思考/工具循环的回复。 */
    fun interruptGeneration() {
        finalizeStreamingAsTerminated("（已终止）")
        resolvePendingCommandReview(approved = false)
        val job = activeChatJob ?: currentTaskJob
        if (job?.isActive == true) {
            job.cancel(CancellationException("用户打断输出"))
            return
        }
        if (uiState.value.taskExecution?.status == ChatTaskProgressState.Running) {
            taskController.cancelCurrentTaskExecution()
        }
        if (uiState.value.chatBusy) {
            finishChat()
        }
    }

    fun resolvePendingCommandReview(approved: Boolean) {
        val deferred = commandReviewDeferred
        commandReviewDeferred = null
        updateState { it.copy(pendingCommandReview = null) }
        deferred?.complete(approved)
    }

    override suspend fun awaitCommandReview(
        tool: ClawTool,
        arguments: Map<String, String>
    ): Boolean {
        val settings = loadAgentSettings()
        if (!settings.needsCommandReview(tool.toolId)) {
            return true
        }
        commandReviewDeferred?.complete(false)
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        commandReviewDeferred = deferred
        val preview = arguments.entries
            .take(6)
            .joinToString(", ") { "${it.key}=${it.value.take(48)}" }
            .ifBlank { "(无参数)" }
        updateState {
            it.copy(
                pendingCommandReview = PendingCommandReview(
                    toolId = tool.toolId,
                    toolDisplayName = tool.displayName,
                    argumentsPreview = preview,
                    isGoalConfirm = false
                )
            )
        }
        return try {
            deferred.await()
        } finally {
            if (commandReviewDeferred === deferred) {
                commandReviewDeferred = null
            }
            updateState { state ->
                if (state.pendingCommandReview?.toolId == tool.toolId) {
                    state.copy(pendingCommandReview = null)
                } else {
                    state
                }
            }
        }
    }

    private suspend fun awaitGoalConfirm(reason: String, intent: String): Boolean {
        commandReviewDeferred?.complete(false)
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        commandReviewDeferred = deferred
        updateState {
            it.copy(
                pendingCommandReview = PendingCommandReview(
                    toolId = GOAL_REVIEW_TOOL_ID,
                    toolDisplayName = "目标确认",
                    argumentsPreview = "$reason\n意图：$intent".take(400),
                    isGoalConfirm = true
                )
            )
        }
        return try {
            deferred.await()
        } finally {
            if (commandReviewDeferred === deferred) {
                commandReviewDeferred = null
            }
            updateState { state ->
                if (state.pendingCommandReview?.toolId == GOAL_REVIEW_TOOL_ID) {
                    state.copy(pendingCommandReview = null)
                } else {
                    state
                }
            }
        }
    }

    private fun finalizeStreamingAsTerminated(suffix: String = "（已终止）") {
        val current = uiState.value.messages
        if (current.none { it.state == ChatMessageState.Streaming }) return
        sessionCoordinator.replaceMessages(
            current.map { it.asTerminated(suffix) },
            persistImmediately = true
        )
    }

    fun onRuntimeTaskEvent(snapshot: ClawRuntimeTaskSnapshot) =
        taskController.onRuntimeTaskEvent(snapshot)

    fun clearCurrentTaskExecution() = taskController.clearCurrentTaskExecution()

    fun clearTaskHistory() = taskController.clearTaskHistory()

    fun setTaskHistoryFilter(filter: ChatTaskHistoryFilter) =
        taskController.setTaskHistoryFilter(filter)

    fun retryTask(task: ChatTaskExecutionState) {
        val action = task.taskAction ?: return
        if (taskController.isRetryBlocked()) {
            taskController.notifyRetryBlocked()
            return
        }
        updateState { it.copy(chatBusy = true) }
        viewModelScope.launch {
            currentTaskJob = currentCoroutineContext()[Job]
            try {
                taskController.startTaskExecution(
                    action = action,
                    originPrompt = task.originPrompt.ifBlank { task.title },
                    retryCount = task.retryCount + 1,
                    retryFromTaskId = task.taskId,
                    preserveJob = currentTaskJob
                )
                executeUnifiedAgentTask(action)
            } catch (_: CancellationException) {
                taskController.cancelTaskExecution("任务已取消：已停止后续步骤。")
            } finally {
                currentTaskJob = null
                finishChat()
            }
        }
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
        val hasAttachment = uiState.value.pendingImageUri != null
        if (normalized.isBlank() && !hasAttachment) {
            return
        }
        if (normalized.isNotBlank()) {
            InputGuards.validatePromptForSubmit(normalized)?.let { err ->
                appendChat(ChatRole.Assistant, err.message, state = ChatMessageState.Final)
                return
            }
        }
        if (uiState.value.chatBusy || currentTaskJob?.isActive == true || activeChatJob?.isActive == true) {
            appendChat(
                ChatRole.Assistant,
                "当前仍有指令在执行，请等待完成或先点停止打断。",
                state = ChatMessageState.Final
            )
            return
        }
        clearAgentEvents()
        resetTurnApiBudget()
        val attachmentLabel = uiState.value.pendingImageLabel
        val attachmentUri = uiState.value.pendingImageUri
        val attachmentMimeType = uiState.value.pendingImageMimeType
        val userMedia = attachmentUri?.let { uri ->
            listOf(ChatMedia(uri = uri.toString(), mimeType = attachmentMimeType ?: "image/*"))
        } ?: emptyList()
        val userText = normalized.ifBlank { "（附图）" }
        val userMessageId = appendChat(
            role = ChatRole.User,
            content = userText,
            attachmentLabel = attachmentLabel,
            media = userMedia
        )
        updateState {
            it.copy(
                input = "",
                pendingImageLabel = null,
                pendingImageUri = null,
                pendingImageMimeType = null,
                chatBusy = true
            )
        }
        activeChatJob = viewModelScope.launch {
            var replyMessageId: String? = null
            currentTaskJob = currentCoroutineContext()[Job]
            try {
            replyMessageId = appendChat(
                ChatRole.Assistant,
                "正在分析指令...",
                state = ChatMessageState.Streaming
            )
            val userImage = if (attachmentUri != null) {
                com.clawdroid.app.chat.ChatImageEncoder.encode(appContext, attachmentUri)
                    .getOrElse { error ->
                        patchChatMessage(
                            replyMessageId,
                            "无法读取附图：${error.message ?: error::class.java.simpleName}",
                            ChatMessageState.Final
                        )
                        finishChat()
                        return@launch
                    }
            } else {
                null
            }
            val overviewUiState = overviewController.uiState.value
            val automationUiState = overviewController.automationController.state.value
            val automationTaskInputs = overviewController.automationController.currentTaskInputs()
            val excludedIds = setOf(userMessageId, replyMessageId)
            val historyTurns = uiState.value.messages
                .asReversed()
                .asSequence()
                .filter { message ->
                    message.id !in excludedIds &&
                        message.state == ChatMessageState.Final &&
                        message.content.isNotBlank()
                }
                .take(24)
                .toList()
                .asReversed()
                .map { message ->
                    ChatHistoryTurn(
                        role = when (message.role) {
                            ChatRole.User -> "user"
                            ChatRole.Assistant -> "assistant"
                        },
                        content = message.content
                    )
                }
            val sessionId = uiState.value.activeSessionId
            val isContinuePrompt =
                normalized == "继续" || normalized.equals("continue", ignoreCase = true)
            if (isContinuePrompt) {
                AgentRunStore.loadIncomplete(appContext, sessionId)?.let { saved ->
                    agentKernel.restoreRun(saved)
                    activeKernelRunId = saved.id
                    appendAgentEvent(
                        AgentRunEvent(
                            kind = AgentRunEventKind.Thinking,
                            title = "恢复未完成 Run",
                            detail = "run=${saved.id} phase=${saved.phase} intent=${saved.goal.intent.take(80)}"
                        )
                    )
                }
            }
            MemoryFacade.indexUserTurn(appContext, sessionId = sessionId, content = normalized)
            val agentSettings = AppSettingsStore.loadAgentOrchestrationSettings(appContext)
            val modelName = modelSettings.modelName.ifBlank { modelSettings.localModelName }
            val contextWindow = modelSettings.contextSettings.effectiveContextWindow(modelName)
            var compressedMemory = uiState.value.compressedMemory
            var recentChat = historyTurns.takeLast(
                ContextCompressor.keepRecentForWindow(contextWindow).coerceAtLeast(6)
            )
            if (agentSettings.contextCompressionEnabled) {
                val compressed = ContextCompressor.maybeCompress(
                    settings = modelSettings,
                    history = historyTurns,
                    existingCompressed = compressedMemory,
                    appContext = appContext,
                    contextWindowTokens = contextWindow
                )
                if (compressed.didCompress) {
                    compressedMemory = compressed.compressedMemory
                    recentChat = compressed.recentChat
                    updateState { it.copy(compressedMemory = compressedMemory) }
                }
            }
            val memoryBundle = MemoryFacade.retrieve(appContext, normalized)
            val retrievedContext = memoryBundle.asRetrievedContext()
            val world = worldSnapshotFromOverview(overviewUiState)
            val agentTurn = agentKernel.beginTurn(
                sessionId = sessionId,
                intent = normalized,
                world = world,
                memory = memoryBundle.copy(workingSummary = compressedMemory)
            )
            activeKernelRunId = agentTurn.run.id.takeIf { agentTurn.policy !is PolicyDecision.Reject }
            when (val policy = agentTurn.policy) {
                is PolicyDecision.Reject -> {
                    appendAgentEvent(
                        AgentRunEvent(
                            kind = AgentRunEventKind.SoftWarn,
                            title = "策略拒绝",
                            detail = policy.reason,
                            success = false
                        )
                    )
                    patchChatMessage(replyMessageId, policy.reason, ChatMessageState.Final)
                    finishChat()
                    return@launch
                }
                is PolicyDecision.RequireConfirm -> {
                    appendAgentEvent(
                        AgentRunEvent(
                            kind = AgentRunEventKind.SoftWarn,
                            title = "需确认",
                            detail = policy.reason
                        )
                    )
                    agentKernel.markAwaitUser(agentTurn.run.id)
                    val approved = awaitGoalConfirm(
                        reason = policy.reason,
                        intent = agentTurn.run.goal.intent
                    )
                    if (!approved) {
                        agentKernel.complete(
                            agentTurn.run.id,
                            success = false,
                            error = "用户拒绝目标确认"
                        )
                        AgentRunStore.clear(appContext, sessionId)
                        activeKernelRunId = null
                        patchChatMessage(
                            replyMessageId,
                            "已取消：用户拒绝了高风险目标确认。\n${policy.reason}",
                            ChatMessageState.Final
                        )
                        finishChat()
                        return@launch
                    }
                }
                PolicyDecision.Allow -> Unit
            }
            agentKernel.markPlanned(agentTurn.run.id)
            val plan = ChatPromptPlanner.plan(
                ChatPlannerContext(
                    prompt = normalized,
                    modelSettings = modelSettings,
                    sessionSummary = overviewUiState.runtimeState.session.summary,
                    capabilityStatus = overviewUiState.runtimeState.capabilityStatus,
                    eventStreaming = overviewUiState.eventState.eventStreaming,
                    recentChat = recentChat,
                    compressedMemory = compressedMemory,
                    retrievedContext = retrievedContext,
                    userImage = userImage
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
                        noteModelApiCall(onModelCallSuccess)
                    }
                    patchChatMessage(replyMessageId, plan.message, ChatMessageState.Final)
                    finishChat()
                }

                is ChatPromptPlan.LocalActionExecution -> {
                    patchChatMessage(replyMessageId, plan.assistantMessage, ChatMessageState.Streaming)
                    when (plan.action) {
                        ChatLocalAction.SafeTap -> {
                            val reply = overviewController.automationController.safeTapUsingResolvedTarget()
                            patchChatMessage(replyMessageId, reply, ChatMessageState.Final)
                            finishChat()
                        }

                        ChatLocalAction.ReadScreenSize -> {
                            val reply = overviewController.readScreenSizeForChat()
                            patchChatMessage(replyMessageId, reply, ChatMessageState.Final)
                            finishChat()
                        }
                    }
                }

                is ChatPromptPlan.TaskExecution -> {
                    taskController.startTaskExecution(
                        action = plan.action,
                        originPrompt = normalized,
                        preserveJob = currentTaskJob
                    )
                    try {
                        patchChatMessage(replyMessageId, plan.assistantMessage, ChatMessageState.Streaming)
                        val reply = executeUnifiedAgentTask(plan.action, automationTaskInputs)
                        patchChatMessage(replyMessageId, reply, ChatMessageState.Final)
                    } catch (_: CancellationException) {
                        taskController.cancelTaskExecution("已停止：用户打断了当前任务。")
                        patchChatMessage(
                            replyMessageId,
                            "已停止：用户打断了当前任务。",
                            ChatMessageState.Final
                        )
                    }
                }

                is ChatPromptPlan.ToolExecution -> {
                    if (plan.reflectResultWithModel) {
                        if (!noteModelApiCall(onModelCallSuccess)) {
                            patchChatMessage(
                                replyMessageId,
                                "本轮 API 预算已耗尽，已停止。发送下一条消息会重新计数。",
                                ChatMessageState.Final
                            )
                            finishChat()
                            return@launch
                        }
                        try {
                            toolLoopController.runAiToolLoop(
                                initialTool = plan.tool,
                                initialArguments = plan.arguments,
                                initialAssistantMessage = plan.assistantMessage,
                                normalizedPrompt = normalized,
                                replyMessageId = replyMessageId,
                                modelSettings = modelSettings,
                                automationUiState = automationUiState,
                                onModelCallSuccess = onModelCallSuccess
                            )
                        } catch (_: CancellationException) {
                            taskController.cancelTaskExecution("已终止：用户打断了 AI 工具循环。")
                            patchChatMessage(
                                replyMessageId,
                                "已终止：用户打断了输出。",
                                ChatMessageState.Terminated
                            )
                        }
                    } else {
                        handleToolIntent(
                            tool = plan.tool,
                            arguments = plan.arguments,
                            normalizedPrompt = normalized,
                            replyMessageId = replyMessageId,
                            assistantMessage = plan.assistantMessage,
                            reflectResultWithModel = false,
                            modelSettings = modelSettings,
                            automationUiState = automationUiState,
                            onModelCallSuccess = onModelCallSuccess
                        )
                    }
                }
            }
            } catch (cancelled: CancellationException) {
                val id = replyMessageId
                if (id != null) {
                    val existing = uiState.value.messages.firstOrNull { it.id == id }
                    if (existing?.state == ChatMessageState.Streaming ||
                        existing?.state == ChatMessageState.Final
                    ) {
                        val keepPartial = existing.content
                            .takeIf { it.isNotBlank() && it != "正在分析指令..." }
                            ?.let { content ->
                                if (content.contains("已终止") || content.contains("已停止")) {
                                    content
                                } else {
                                    "$content\n\n（已终止）"
                                }
                            }
                            ?: "已终止：用户打断了思考/输出。"
                        patchChatMessage(id, keepPartial, ChatMessageState.Terminated)
                    }
                } else {
                    finalizeStreamingAsTerminated()
                }
                if (uiState.value.taskExecution?.status == ChatTaskProgressState.Running) {
                    taskController.cancelTaskExecution("已终止：用户打断。")
                }
                resolvePendingCommandReview(approved = false)
            } catch (error: Throwable) {
                FaultIsolation.recordFault("chat:submitPrompt", error)
                val isolated = FaultIsolation.formatIsolatedError("chat", error)
                val id = replyMessageId
                if (id != null) {
                    patchChatMessage(id, isolated, ChatMessageState.Final)
                } else {
                    appendChat(ChatRole.Assistant, isolated, state = ChatMessageState.Final)
                }
                if (uiState.value.taskExecution?.status == ChatTaskProgressState.Running) {
                    taskController.cancelTaskExecution(isolated)
                }
                updateState { it.copy(latestAiStatus = FaultCodes.ORCHESTRATOR_FAULT) }
            } finally {
                currentTaskJob = null
                activeChatJob = null
                finishChat()
            }
        }
    }

    private fun appendChat(
        role: ChatRole,
        content: String,
        attachmentLabel: String? = null,
        state: ChatMessageState = ChatMessageState.Final,
        media: List<ChatMedia> = emptyList()
    ): String {
        val message = ChatMessage(
            role = role,
            content = ChatTextLimits.truncateForDisplay(content),
            attachmentLabel = attachmentLabel,
            state = state,
            media = media
        )
        val updatedMessages = uiState.value.messages + message
        replaceMessages(
            messages = updatedMessages,
            persistImmediately = state == ChatMessageState.Final
        )
        return message.id
    }

    private fun patchChatMessage(
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
                content = ChatTextLimits.truncateForDisplay(content),
                state = state,
                attachmentLabel = attachmentLabel ?: existing.attachmentLabel
            )
        }
        replaceMessages(
            messages = updatedMessages,
            persistImmediately = state == ChatMessageState.Final
        )
    }

    override fun updateChatMessage(
        messageId: String,
        content: String,
        state: ChatMessageState,
        attachmentLabel: String?
    ) = patchChatMessage(messageId, content, state, attachmentLabel)

    private fun replaceMessages(
        messages: List<ChatMessage>,
        persistImmediately: Boolean = false
    ) {
        sessionCoordinator.replaceMessages(messages, persistImmediately)
    }

    override fun onCleared() {
        sessionCoordinator.onCleared()
        activeChatJob?.cancel()
        activeChatJob = null
        currentTaskJob?.cancel()
        currentTaskJob = null
        super.onCleared()
    }

    private suspend fun executeUnifiedAgentTask(
        action: ChatTaskAction,
        automationTaskInputs: AutomationTaskInputs = overviewController.automationController.currentTaskInputs()
    ): String {
        val agent = action.toAgentDefinition()
        val arguments = linkedMapOf<String, Any?>(
            "expected_package" to automationTaskInputs.pageConfirmPackage,
            "expected_text" to automationTaskInputs.pageConfirmText,
            "expected_view_id" to automationTaskInputs.pageConfirmViewId,
            "click_expected_package" to automationTaskInputs.clickPrecheckPackage.ifBlank {
                automationTaskInputs.pageConfirmPackage
            },
            "target_text" to automationTaskInputs.clickPrecheckText.ifBlank {
                automationTaskInputs.pageConfirmText
            },
            "target_view_id" to automationTaskInputs.clickPrecheckViewId.ifBlank {
                automationTaskInputs.pageConfirmViewId
            }
        )
        return runAgentById(
            agentId = agent.id,
            arguments = arguments,
            ensureTaskUi = false,
            finishTaskUi = true
        )
    }

    private suspend fun runAgentById(
        agentId: String,
        arguments: Map<String, Any?> = emptyMap(),
        ensureTaskUi: Boolean,
        originPrompt: String = "",
        finishTaskUi: Boolean = ensureTaskUi,
        bindRuntimeOnSubmit: Boolean = true
    ): String {
        val dispatcher = toolDispatcher
        if (dispatcher == null) {
            if (uiState.value.taskExecution?.status == ChatTaskProgressState.Running) {
                taskController.finishTaskExecution(success = false, summary = "任务失败：工具分发器未就绪。")
            }
            return "任务失败：工具分发器未就绪，无法执行 Agent。"
        }
        val agent = ClawAgentCatalog.byId(agentId)
            ?: return "任务失败：未知 Agent `$agentId`。"
        if (ensureTaskUi) {
            val running = uiState.value.taskExecution
                ?.takeIf { it.status == ChatTaskProgressState.Running }
            if (running == null) {
                taskController.startDynamicTaskExecution(
                    title = agent.name,
                    summary = "正在按“${agent.stepTitles.joinToString(" -> ")}”推进任务。",
                    initialStepTitles = agent.stepTitles,
                    originPrompt = originPrompt.ifBlank { agent.name },
                    preserveJob = currentTaskJob ?: currentCoroutineContext()[Job]
                )
            }
        }
        val renderedSteps = mutableListOf<String>()
        val result = ClawAgentRunner(dispatcher).run(
            agentId = agent.id,
            arguments = arguments,
            stepListener = AgentStepListener { index, stepId, title, started, stepResult ->
                if (started) {
                    taskController.ensureTaskStepSlot(index, title)
                    taskController.markTaskStepRunning(index, "正在执行$title")
                } else {
                    val output = stepResult?.output.orEmpty()
                    val ok = stepResult?.success == true
                    taskController.markTaskStepFinished(index, ok, output)
                    renderedSteps += renderTaskStep(index + 1, title, output)
                    if (stepResult != null) {
                        applyAgentStepSideEffects(stepId, arguments, stepResult)
                    }
                }
            },
            onRuntimeTaskSubmitted = if (bindRuntimeOnSubmit) {
                { runtimeId ->
                    taskController.trackRuntimeTask(
                        runtimeTaskId = runtimeId,
                        originPrompt = originPrompt.ifBlank { agent.name },
                        snapshotName = agent.name
                    )
                }
            } else {
                null
            }
        )
        overviewController.applyToolSideEffects(result)
        if (result.captureArtifact != null) {
            dispatcher.rememberCapture(result.captureArtifact)
        }
        val detached = result.error == ClawAgentRunner.ERROR_RUNTIME_TASK_DETACHED
        if (bindRuntimeOnSubmit) {
            result.taskSnapshot?.takeIf { snapshot ->
                snapshot.taskId.isNotBlank() &&
                    uiState.value.taskExecution?.runtimeTaskId == snapshot.taskId
            }?.let { taskController.applyRuntimeTaskSnapshot(it) }
        }
        val footer = when {
            detached -> {
                val runtimeId = result.runtimeTaskId.orEmpty()
                "任务跟踪中：${agent.name} 本地轮询已超时，继续通过事件同步 Runtime 任务 $runtimeId。"
            }
            result.success -> "任务完成：${agent.name} 已成功。"
            else -> "任务中止：${agent.name} 未完成。${result.error?.let { " ($it)" }.orEmpty()}"
        }
        if (finishTaskUi &&
            uiState.value.taskExecution?.status == ChatTaskProgressState.Running
        ) {
            if (detached) {
                taskController.updateTaskExecution { task ->
                    task.copy(summary = footer)
                }
            } else {
                taskController.finishTaskExecution(
                    success = result.success,
                    summary = if (result.success) {
                        "任务已完成：${agent.name}。"
                    } else {
                        "任务已中止：${agent.name}。"
                    }
                )
            }
        }
        return (renderedSteps + footer).joinToString("\n\n")
    }

    override suspend fun executeAgentById(
        agentId: String,
        arguments: Map<String, Any?>,
        ensureTaskUi: Boolean,
        originPrompt: String,
        finishTaskUi: Boolean,
        bindRuntimeOnSubmit: Boolean
    ): String = runAgentById(
        agentId = agentId,
        arguments = arguments,
        ensureTaskUi = ensureTaskUi,
        originPrompt = originPrompt,
        finishTaskUi = finishTaskUi,
        bindRuntimeOnSubmit = bindRuntimeOnSubmit
    )

    override fun currentAiRuntimeSnapshot(): AiRuntimeSnapshot {
        val overviewUiState = overviewController.uiState.value
        return AiRuntimeSnapshot(
            sessionSummary = overviewUiState.runtimeState.session.summary,
            capabilityStatus = overviewUiState.runtimeState.capabilityStatus,
            eventStreaming = overviewUiState.eventState.eventStreaming
        )
    }

    override fun enrichToolArguments(
        tool: ClawTool,
        arguments: Map<String, String>,
        automationUiState: OverviewAutomationState,
        normalizedPrompt: String
    ): Map<String, String> {
        return when (tool) {
            ClawTool.PAGE_CONFIRM -> linkedMapOf(
                "expected_package" to arguments.stringArg("expected_package", "package", "expectedPackage")
                    .ifBlank { automationUiState.pageConfirmPackage },
                "expected_text" to arguments.stringArg("expected_text", "text", "expectedText")
                    .ifBlank { automationUiState.pageConfirmText },
                "expected_view_id" to arguments.stringArg("expected_view_id", "view_id", "expectedViewId")
                    .ifBlank { automationUiState.pageConfirmViewId }
            ).filterValues { it.isNotBlank() } + arguments.filterKeys {
                it !in setOf(
                    "expected_package", "package", "expectedPackage",
                    "expected_text", "text", "expectedText",
                    "expected_view_id", "view_id", "expectedViewId"
                )
            }

            ClawTool.CLICK_PRECHECK -> linkedMapOf(
                "expected_package" to arguments.stringArg("expected_package", "package", "expectedPackage")
                    .ifBlank { automationUiState.clickPrecheckPackage },
                "target_text" to arguments.stringArg("target_text", "text", "targetText")
                    .ifBlank { automationUiState.clickPrecheckText },
                "target_view_id" to arguments.stringArg("target_view_id", "view_id", "targetViewId")
                    .ifBlank { automationUiState.clickPrecheckViewId }
            ).filterValues { it.isNotBlank() } + arguments.filterKeys {
                it !in setOf(
                    "expected_package", "package", "expectedPackage",
                    "target_text", "text", "targetText",
                    "target_view_id", "view_id", "targetViewId"
                )
            }

            ClawTool.EXECUTE_SHELL_LIMITED -> {
                val command = arguments.stringArg("command").ifBlank {
                    normalizedPrompt
                        .takeIf { it.startsWith("/shell ", ignoreCase = true) }
                        ?.removePrefix("/shell ")
                        ?.trim()
                        .orEmpty()
                }
                if (command.isBlank()) arguments else arguments + ("command" to command)
            }

            ClawTool.SUBSCRIBE_EVENTS -> {
                val operation = arguments.stringArg("operation").lowercase().ifBlank {
                    if (normalizedPrompt.contains("停")) "stop" else "start"
                }
                arguments + ("operation" to operation)
            }

            else -> arguments
        }
    }

    override suspend fun awaitSubmittedRuntimeTask(
        dispatcher: ClawToolDispatcher,
        submitResult: ClawToolCallResult
    ): ClawToolCallResult {
        val taskId = RuntimeTaskPoller.resolveTaskId(submitResult)
            ?: return submitResult.copy(
                success = false,
                output = buildString {
                    append(submitResult.output.trim())
                    if (isNotEmpty()) append("\n\n")
                    append("失败: task_submit 未返回可跟踪的 task_id，无法轮询。")
                },
                error = submitResult.error ?: "missing_task_id"
            )
        if (uiState.value.taskExecution?.status == ChatTaskProgressState.Running) {
            taskController.trackRuntimeTask(
                runtimeTaskId = taskId,
                originPrompt = uiState.value.taskExecution?.originPrompt.orEmpty(),
                snapshotName = submitResult.taskSnapshot?.name
            )
        }
        val awaited = RuntimeTaskPoller.awaitTerminal(
            dispatcher = dispatcher,
            taskId = taskId,
            onSnapshot = { snapshot ->
                if (uiState.value.taskExecution?.runtimeTaskId == snapshot.taskId) {
                    taskController.applyRuntimeTaskSnapshot(snapshot)
                }
            }
        )
        return RuntimeTaskPoller.toToolResult(awaited, taskId).copy(
            output = buildString {
                append(submitResult.output.trim())
                if (isNotEmpty()) append("\n\n")
                append(awaited.output.trim())
            }
        )
    }

    /**
     * Finish the AI-loop task card only when it is still local-owned.
     * If a Runtime task id is bound and still Running, keep the card open for event sync.
     */
    override fun syncRuntimeTaskTracking(
        tool: ClawTool,
        arguments: Map<String, String>,
        result: ClawToolCallResult,
        normalizedPrompt: String
    ) {
        when (tool) {
            ClawTool.TASK_SUBMIT -> {
                result.runtimeTaskId?.takeIf { it.isNotBlank() }?.let { runtimeId ->
                    taskController.trackRuntimeTask(
                        runtimeTaskId = runtimeId,
                        originPrompt = normalizedPrompt,
                        snapshotName = arguments["name"]
                    )
                }
                result.taskSnapshot?.let { taskController.applyRuntimeTaskSnapshot(it) }
            }
            ClawTool.TASK_GET, ClawTool.TASK_CANCEL, ClawTool.TASK_WAIT -> {
                result.taskSnapshot?.let { snapshot ->
                    val current = uiState.value.taskExecution ?: return@let
                    if (current.runtimeTaskId == snapshot.taskId) {
                        taskController.applyRuntimeTaskSnapshot(snapshot)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun applyAgentStepSideEffects(
        stepId: String,
        arguments: Map<String, Any?>,
        result: ClawToolCallResult
    ) {
        val tool = ClawTool.byToolId(stepId) ?: run {
            overviewController.applyToolSideEffects(result)
            if (result.captureArtifact != null) {
                toolDispatcher?.rememberCapture(result.captureArtifact)
            }
            return
        }
        val stringArgs = agentStepArgumentsAsStrings(stepId, arguments)
        overviewController.applyChatToolEffects(tool, stringArgs, result)
        if (result.captureArtifact != null) {
            toolDispatcher?.rememberCapture(result.captureArtifact)
        }
    }

    private fun agentStepArgumentsAsStrings(
        stepId: String,
        arguments: Map<String, Any?>
    ): Map<String, String> {
        fun text(vararg keys: String): String {
            for (key in keys) {
                val value = arguments[key]?.toString()?.trim().orEmpty()
                if (value.isNotEmpty() && value != "null") {
                    return value
                }
            }
            return ""
        }
        return when (stepId) {
            "page_confirm" -> linkedMapOf(
                "expected_package" to text("expected_package"),
                "expected_text" to text("expected_text"),
                "expected_view_id" to text("expected_view_id")
            )
            "click_precheck" -> linkedMapOf(
                "expected_package" to text("click_expected_package", "expected_package"),
                "target_text" to text("target_text", "expected_text"),
                "target_view_id" to text("target_view_id", "expected_view_id")
            )
            "capture_screen" -> linkedMapOf(
                "display_id" to text("display_id").ifBlank { "0" },
                "read_after_capture" to "false"
            )
            "inject_swipe" -> linkedMapOf(
                "x1" to text("x1").ifBlank { "540" },
                "y1" to text("y1").ifBlank { "1800" },
                "x2" to text("x2").ifBlank { "540" },
                "y2" to text("y2").ifBlank { "400" },
                "duration_ms" to text("duration_ms").ifBlank { "350" },
                "display_id" to text("display_id").ifBlank { "0" }
            )
            else -> emptyMap()
        }
    }

    private fun formatChatToolOutput(
        tool: ClawTool,
        result: ClawToolCallResult
    ): String {
        return when (tool) {
            ClawTool.EXECUTE_SHELL_LIMITED -> {
                if (result.success) {
                    result.output.replaceFirst("成功:", "Shell 执行完成，")
                } else {
                    "Shell 执行失败：${result.error ?: result.output}"
                }
            }
            else -> result.output
        }
    }

    override suspend fun finalizeToolReply(
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
            AiAgentOrchestrator.reflectToolCritique(
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
                onSuccess = { critique ->
                    onModelCallSuccess()
                    updateState { state -> state.copy(latestAiStatus = "AI 已总结工具结果") }
                    appendAgentEvent(
                        AgentRunEvent(
                            kind = AgentRunEventKind.Thinking,
                            title = "结构化反思",
                            detail = critique.asSystemHint(),
                            success = critique.ok
                        )
                    )
                    critique.summary.ifBlank { null }
                },
                onFailure = {
                    updateState { state -> state.copy(latestAiStatus = "AI 总结回退为原始结果") }
                    null
                }
            )
        } else {
            null
        }
        patchChatMessage(
            replyMessageId,
            buildAssistantReply(assistantMessage, reflectionMessage, result),
            ChatMessageState.Final
        )
        finishChat()
    }

    override suspend fun handleToolIntentWhenDispatcherMissing(
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
        handleToolIntent(
            tool = tool,
            arguments = arguments,
            normalizedPrompt = normalizedPrompt,
            replyMessageId = replyMessageId,
            assistantMessage = assistantMessage,
            reflectResultWithModel = reflectResultWithModel,
            modelSettings = modelSettings,
            automationUiState = automationUiState,
            onModelCallSuccess = onModelCallSuccess
        )
    }

    override fun applyChatToolEffects(
        tool: ClawTool,
        arguments: Map<String, String>,
        result: ClawToolCallResult
    ) {
        overviewController.applyChatToolEffects(tool, arguments, result)
    }

    override fun finishAiLoopTaskExecution(success: Boolean, summary: String) =
        taskController.finishAiLoopTaskExecution(success, summary)

    override fun ensureTaskStepSlot(stepIndex: Int, title: String) =
        taskController.ensureTaskStepSlot(stepIndex, title)

    override fun markTaskStepRunning(stepIndex: Int, detail: String) =
        taskController.markTaskStepRunning(stepIndex, detail)

    override fun markTaskStepFinished(stepIndex: Int, success: Boolean, detail: String) =
        taskController.markTaskStepFinished(stepIndex, success, detail)

    override fun startDynamicTaskExecution(
        title: String,
        summary: String,
        initialStepTitles: List<String>,
        originPrompt: String,
        preserveJob: Job?
    ) {
        taskController.startDynamicTaskExecution(
            title = title,
            summary = summary,
            initialStepTitles = initialStepTitles,
            originPrompt = originPrompt,
            preserveJob = preserveJob
        )
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
            ClawTool.RUN_AGENT -> {
                val agentId = arguments.stringArg("agent_id", "agent", "id", "name")
                if (agentId.isBlank()) {
                    finalizeToolReply(
                        replyMessageId = replyMessageId,
                        normalizedPrompt = normalizedPrompt,
                        tool = tool,
                        arguments = arguments,
                        assistantMessage = assistantMessage,
                        result = "失败: agent_id 不能为空",
                        reflectResultWithModel = false,
                        modelSettings = modelSettings,
                        onModelCallSuccess = onModelCallSuccess
                    )
                    return
                }
                if (!awaitCommandReview(tool, arguments)) {
                    finalizeToolReply(
                        replyMessageId = replyMessageId,
                        normalizedPrompt = normalizedPrompt,
                        tool = tool,
                        arguments = arguments,
                        assistantMessage = assistantMessage,
                        result = "已拒绝：用户未批准 Agent 调用 $agentId。",
                        reflectResultWithModel = false,
                        modelSettings = modelSettings,
                        onModelCallSuccess = onModelCallSuccess
                    )
                    return
                }
                patchChatMessage(
                    replyMessageId,
                    assistantMessage ?: "正在执行 Agent $agentId...",
                    ChatMessageState.Streaming
                )
                try {
                    val reply = runAgentById(
                        agentId = agentId,
                        arguments = arguments.mapValues { (_, value) -> value },
                        ensureTaskUi = true,
                        originPrompt = normalizedPrompt
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
                } catch (_: CancellationException) {
                    taskController.cancelTaskExecution("已终止：用户打断了 Agent 步骤。")
                    patchChatMessage(
                        replyMessageId,
                        "已终止：用户打断了 Agent 步骤。",
                        ChatMessageState.Terminated
                    )
                    finishChat()
                }
            }

            else -> {
                val dispatcher = toolDispatcher
                if (dispatcher == null) {
                    finalizeToolReply(
                        replyMessageId = replyMessageId,
                        normalizedPrompt = normalizedPrompt,
                        tool = tool,
                        arguments = arguments,
                        assistantMessage = assistantMessage,
                        result = "失败: 工具分发器未就绪",
                        reflectResultWithModel = false,
                        modelSettings = modelSettings,
                        onModelCallSuccess = onModelCallSuccess
                    )
                    return
                }
                val enrichedArgs = enrichToolArguments(
                    tool = tool,
                    arguments = arguments,
                    automationUiState = automationUiState,
                    normalizedPrompt = normalizedPrompt
                )
                if (tool == ClawTool.EXECUTE_SHELL_LIMITED &&
                    enrichedArgs.stringArg("command").isBlank()
                ) {
                    patchChatMessage(
                        replyMessageId,
                        mergeAssistantMessage(
                            assistantMessage,
                            "请提供受限 Shell 命令，例如 `/shell wm size`。"
                        ),
                        ChatMessageState.Final
                    )
                    finishChat()
                    return
                }
                if (tool == ClawTool.READ_FILE_LIMITED &&
                    enrichedArgs.stringArg("path").isBlank()
                ) {
                    finalizeToolReply(
                        replyMessageId = replyMessageId,
                        normalizedPrompt = normalizedPrompt,
                        tool = tool,
                        arguments = enrichedArgs,
                        assistantMessage = assistantMessage,
                        result = "请在概览页使用“读取并预览最近截图”，当前聊天入口暂不接受文件路径参数。",
                        reflectResultWithModel = false,
                        modelSettings = modelSettings,
                        onModelCallSuccess = onModelCallSuccess
                    )
                    return
                }
                val streamingLabel = when (tool) {
                    ClawTool.CAPTURE_SCREEN -> {
                        if (enrichedArgs["read_after_capture"] == "true") {
                            "正在截图并预览..."
                        } else {
                            "正在请求截图..."
                        }
                    }
                    ClawTool.EXECUTE_SHELL_LIMITED ->
                        "正在执行受限 Shell: ${enrichedArgs.stringArg("command")}"
                    ClawTool.SUBSCRIBE_EVENTS -> {
                        if (enrichedArgs.stringArg("operation").equals("stop", ignoreCase = true)) {
                            "正在停止事件流..."
                        } else {
                            "正在建立事件流连接..."
                        }
                    }
                    else -> "正在执行 ${tool.displayName}..."
                }
                patchChatMessage(
                    replyMessageId,
                    assistantMessage ?: streamingLabel,
                    ChatMessageState.Streaming
                )
                if (!awaitCommandReview(tool, enrichedArgs)) {
                    finalizeToolReply(
                        replyMessageId = replyMessageId,
                        normalizedPrompt = normalizedPrompt,
                        tool = tool,
                        arguments = enrichedArgs,
                        assistantMessage = assistantMessage,
                        result = "已拒绝：用户未批准执行 ${tool.displayName}。",
                        reflectResultWithModel = false,
                        modelSettings = modelSettings,
                        onModelCallSuccess = onModelCallSuccess
                    )
                    return
                }
                val result = dispatcher.execute(
                    tool,
                    enrichedArgs.mapValues { (_, value) -> value }
                )
                overviewController.applyChatToolEffects(tool, enrichedArgs, result)
                if (result.captureArtifact != null) {
                    dispatcher.rememberCapture(result.captureArtifact)
                }
                syncRuntimeTaskTracking(tool, enrichedArgs, result, normalizedPrompt)
                val polled = if (tool == ClawTool.TASK_SUBMIT &&
                    result.success &&
                    !isRebootOrientedTaskForViewModel(enrichedArgs)
                ) {
                    awaitSubmittedRuntimeTask(dispatcher, result)
                } else {
                    result
                }
                finalizeToolReply(
                    replyMessageId = replyMessageId,
                    normalizedPrompt = normalizedPrompt,
                    tool = tool,
                    arguments = enrichedArgs,
                    assistantMessage = assistantMessage,
                    result = formatChatToolOutput(tool, polled),
                    reflectResultWithModel = reflectResultWithModel,
                    modelSettings = modelSettings,
                    onModelCallSuccess = onModelCallSuccess
                )
            }
        }
    }

    private fun worldSnapshotFromOverview(overviewUiState: OverviewUiState): WorldSnapshot {
        val caps = overviewUiState.runtimeState.capabilityStatus
        val rootGranted =
            overviewUiState.permissionState.localEnvironmentStatus.rootGranted == true
        return WorldSnapshot(
            sessionSummary = overviewUiState.runtimeState.session.summary,
            capabilityStatus = caps,
            eventStreaming = overviewUiState.eventState.eventStreaming,
            rootAvailable = rootGranted || caps.contains("root=true", ignoreCase = true),
            accessibilityAvailable = caps.contains("accessibility=true", ignoreCase = true)
        )
    }

    private fun isRebootOrientedTaskForViewModel(arguments: Map<String, String>): Boolean {
        val blob = arguments.values.joinToString(" ").lowercase()
        return blob.contains("reboot") || blob.contains("svc power reboot")
    }

    private fun welcomeMessage(): ChatMessage {
        return ChatMessage(
            role = ChatRole.Assistant,
            content = "可以像聊天一样直接下达指令，例如“ping ClawRuntime”、“获取能力”、“截图并预览”、“运行时体检”、“/agents”、“/agent runtime_health_sweep”、“/task_list”、“/task_submit demo ping”、“取消 runtime 任务 <id>”、“执行 wm size”、“开始事件订阅”。"
        )
    }

    override fun finishChat() {
        activeKernelRunId?.let { runId ->
            val run = agentKernel.getRun(runId)
            val sessionId = run?.sessionId ?: uiState.value.activeSessionId
            if (run != null && run.phase != AgentRunPhase.Done) {
                val shouldPersist =
                    uiState.value.awaitingBudgetContinue ||
                        run.phase == AgentRunPhase.AwaitRuntime ||
                        run.phase == AgentRunPhase.AwaitUser
                if (shouldPersist) {
                    AgentRunStore.saveIncomplete(appContext, run)
                } else {
                    agentKernel.complete(runId, success = run.success != false)
                    AgentRunStore.clear(appContext, sessionId)
                }
            } else if (run?.phase == AgentRunPhase.Done) {
                AgentRunStore.clear(appContext, sessionId)
            }
            activeKernelRunId = null
        }
        updateState { it.copy(chatBusy = false) }
    }

    override fun updateState(transform: (ChatUiState) -> ChatUiState) {
        _uiState.update(transform)
    }

    companion object {
        fun provideFactory(
            appContext: Context,
            overviewController: OverviewController,
            toolDispatcher: ClawToolDispatcher? = null
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    return ChatViewModel(
                        appContext = appContext,
                        overviewController = overviewController,
                        toolDispatcher = toolDispatcher
                    ) as T
                }
            }
        }
    }
}

@Composable
internal fun rememberChatViewModel(
    context: Context,
    overviewController: OverviewController,
    toolDispatcher: ClawToolDispatcher? = null
): ChatViewModel {
    val factory = remember(context, overviewController, toolDispatcher) {
        ChatViewModel.provideFactory(
            appContext = context.applicationContext,
            overviewController = overviewController,
            toolDispatcher = toolDispatcher
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
