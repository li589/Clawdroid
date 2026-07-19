package com.clawdroid.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
    val runtimeTaskId: String? = null,
    val failureReason: String? = null,
    val originPrompt: String = "",
    val retryCount: Int = 0,
    val retryFromTaskId: String? = null,
    val failure: ChatTaskFailureState? = null
)

internal class ChatViewModel(
    private val appContext: Context,
    private val overviewController: OverviewController,
    private val toolDispatcher: ClawToolDispatcher? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private var currentTaskJob: Job? = null

    init {
        viewModelScope.launch {
            restoreHistory()
        }
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
        val runtimeTaskId = runningTask.runtimeTaskId
        val hadActiveJob = currentTaskJob?.isActive == true
        currentTaskJob?.cancel(
            CancellationException("用户取消任务：${runningTask.title}")
        )
        if (hadActiveJob) {
            // Local job catch path marks Cancelled; still best-effort stop Runtime work.
            if (!runtimeTaskId.isNullOrBlank()) {
                requestCancelRuntimeTask(runtimeTaskId)
            }
            return
        }
        if (runtimeTaskId.isNullOrBlank()) {
            cancelTaskExecution("任务已取消。")
            return
        }
        viewModelScope.launch {
            val cancelResult = runCatching {
                toolDispatcher?.execute(
                    ClawTool.TASK_CANCEL,
                    mapOf("task_id" to runtimeTaskId)
                )
            }.getOrNull()
            val stillTrackingSame = uiState.value.taskExecution
                ?.takeIf { it.status == ChatTaskProgressState.Running }
                ?.runtimeTaskId == runtimeTaskId
            if (!stillTrackingSame) {
                return@launch
            }
            if (cancelResult == null || cancelResult.success) {
                cancelResult?.taskSnapshot?.let { applyRuntimeTaskSnapshot(it) }
                if (uiState.value.taskExecution?.status == ChatTaskProgressState.Running) {
                    cancelTaskExecution("任务已取消：已停止 Runtime 任务 $runtimeTaskId。")
                }
            } else {
                updateTaskExecution { task ->
                    task.copy(
                        summary = "取消请求失败：${cancelResult.output.trim()}（任务仍在运行）"
                    )
                }
            }
        }
    }

    fun onRuntimeTaskEvent(snapshot: ClawRuntimeTaskSnapshot) {
        if (snapshot.taskId.isBlank()) {
            return
        }
        val current = uiState.value.taskExecution
        when {
            current == null -> {
                if (isActiveRuntimeTaskState(snapshot.state)) {
                    trackRuntimeTask(
                        runtimeTaskId = snapshot.taskId,
                        originPrompt = "runtime:${snapshot.taskId}",
                        snapshotName = snapshot.name
                    )
                    applyRuntimeTaskSnapshot(snapshot)
                }
            }
            // Only sync events for the Runtime task this chat card already tracks.
            // Do not auto-bind foreign events onto InApp agents (blank runtimeTaskId).
            current.runtimeTaskId == snapshot.taskId -> {
                applyRuntimeTaskSnapshot(snapshot)
            }
        }
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
        if (uiState.value.chatBusy || currentTaskJob?.isActive == true) {
            appendChat(
                ChatRole.Assistant,
                "当前仍有指令在执行，请等待完成或先取消任务后再重试。",
                state = ChatMessageState.Final
            )
            return
        }
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
                executeUnifiedAgentTask(action)
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
        val restoredCurrent = persistedState.currentTask?.normalizeRestoredTask()
        updateState {
            it.copy(
                taskExecution = restoredCurrent,
                taskHistory = persistedState.taskHistory.take(MAX_TASK_HISTORY_ITEMS)
            )
        }
        val runtimeTaskId = restoredCurrent
            ?.takeIf { it.status == ChatTaskProgressState.Running }
            ?.runtimeTaskId
            ?.takeIf { it.isNotBlank() }
        if (!runtimeTaskId.isNullOrBlank()) {
            viewModelScope.launch {
                resyncRuntimeTaskAfterRestore(runtimeTaskId)
            }
        }
    }

    private suspend fun resyncRuntimeTaskAfterRestore(runtimeTaskId: String) {
        val dispatcher = toolDispatcher ?: return
        val stillTracking = {
            uiState.value.taskExecution
                ?.takeIf { it.status == ChatTaskProgressState.Running }
                ?.runtimeTaskId == runtimeTaskId
        }
        if (!stillTracking()) {
            return
        }
        val getResult = runCatching {
            dispatcher.execute(ClawTool.TASK_GET, mapOf("task_id" to runtimeTaskId))
        }.getOrNull()
        val getSnapshot = getResult?.taskSnapshot?.takeIf { getResult.success }
        if (getSnapshot != null && stillTracking()) {
            applyRuntimeTaskSnapshot(getSnapshot)
            return
        }
        val listResult = runCatching {
            dispatcher.execute(ClawTool.TASK_LIST)
        }.getOrNull()
        val listed = listResult?.taskSnapshots
            ?.firstOrNull { it.taskId == runtimeTaskId }
        if (listed != null && stillTracking()) {
            applyRuntimeTaskSnapshot(listed)
            return
        }
        if (stillTracking()) {
            cancelTaskExecution(
                "应用重启后未能找到 Runtime 任务 $runtimeTaskId，已停止跟踪。"
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
        if (uiState.value.chatBusy || currentTaskJob?.isActive == true) {
            appendChat(
                ChatRole.Assistant,
                "当前仍有指令在执行，请等待完成或先取消任务。",
                state = ChatMessageState.Final
            )
            return
        }
        if (normalized.length > MAX_PROMPT_LENGTH) {
            appendChat(ChatRole.Assistant, "输入内容过长，请控制在 $MAX_PROMPT_LENGTH 字符以内。", state = ChatMessageState.Final)
            return
        }
        val attachment = uiState.value.pendingImageLabel
        val userMessageId = appendChat(ChatRole.User, normalized, attachment)
        updateState {
            it.copy(
                input = "",
                pendingImageLabel = null,
                chatBusy = true
            )
        }
        viewModelScope.launch {
            var replyMessageId: String? = null
            try {
            replyMessageId = appendChat(
                ChatRole.Assistant,
                "正在分析指令...",
                state = ChatMessageState.Streaming
            )
            val overviewUiState = overviewController.uiState.value
            val automationUiState = overviewController.automationController.state.value
            val automationTaskInputs = overviewController.automationController.currentTaskInputs()
            val excludedIds = setOf(userMessageId, replyMessageId)
            val recentChat = uiState.value.messages
                .asReversed()
                .asSequence()
                .filter { message ->
                    message.id !in excludedIds &&
                        message.state == ChatMessageState.Final &&
                        message.content.isNotBlank()
                }
                .take(6)
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
            val plan = ChatPromptPlanner.plan(
                ChatPlannerContext(
                    prompt = normalized,
                    modelSettings = modelSettings,
                    sessionSummary = overviewUiState.runtimeState.session.summary,
                    capabilityStatus = overviewUiState.runtimeState.capabilityStatus,
                    eventStreaming = overviewUiState.eventState.eventStreaming,
                    recentChat = recentChat
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
                        val reply = executeUnifiedAgentTask(plan.action, automationTaskInputs)
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
                        currentTaskJob = currentCoroutineContext()[Job]
                        try {
                            runAiToolLoop(
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
                            cancelTaskExecution("任务已取消：已停止 AI 工具循环。")
                            updateChatMessage(
                                replyMessageId,
                                "任务已取消：已停止后续工具步骤。",
                                ChatMessageState.Final
                            )
                            finishChat()
                        } finally {
                            currentTaskJob = null
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
                throw cancelled
            } catch (error: Throwable) {
                FaultIsolation.recordFault("chat:submitPrompt", error)
                val isolated = FaultIsolation.formatIsolatedError("chat", error)
                val id = replyMessageId
                if (id != null) {
                    updateChatMessage(id, isolated, ChatMessageState.Final)
                } else {
                    appendChat(ChatRole.Assistant, isolated, state = ChatMessageState.Final)
                }
                if (uiState.value.taskExecution?.status == ChatTaskProgressState.Running) {
                    cancelTaskExecution(isolated)
                }
                updateState { it.copy(latestAiStatus = FaultCodes.ORCHESTRATOR_FAULT) }
                finishChat()
            }
        }
    }

    private suspend fun restoreHistory() {
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
            content = ChatTextLimits.truncateForDisplay(content),
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
                content = ChatTextLimits.truncateForDisplay(content),
                state = state,
                attachmentLabel = attachmentLabel ?: existing.attachmentLabel
            )
        }
        replaceMessages(updatedMessages)
    }

    private fun replaceMessages(messages: List<ChatMessage>) {
        val windowed = ChatTextLimits.windowMessages(messages)
        runCatching { ChatHistoryStore.save(appContext, windowed) }
        updateState { it.copy(messages = windowed) }
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
        return executeAgentById(
            agentId = agent.id,
            arguments = arguments,
            ensureTaskUi = false,
            // Task card already started by startTaskExecution / retryTask.
            finishTaskUi = true
        )
    }

    private suspend fun executeAgentById(
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
                finishTaskExecution(success = false, summary = "任务失败：工具分发器未就绪。")
            }
            return "任务失败：工具分发器未就绪，无法执行 Agent。"
        }
        val agent = ClawAgentCatalog.byId(agentId)
            ?: return "任务失败：未知 Agent `$agentId`。"
        if (ensureTaskUi) {
            val running = uiState.value.taskExecution
                ?.takeIf { it.status == ChatTaskProgressState.Running }
            if (running == null) {
                startDynamicTaskExecution(
                    title = agent.name,
                    summary = "正在按“${agent.stepTitles.joinToString(" -> ")}”推进任务。",
                    initialStepTitles = agent.stepTitles,
                    originPrompt = originPrompt.ifBlank { agent.name }
                )
            }
        }
        val renderedSteps = mutableListOf<String>()
        val result = ClawAgentRunner(dispatcher).run(
            agentId = agent.id,
            arguments = arguments,
            stepListener = AgentStepListener { index, stepId, title, started, stepResult ->
                if (started) {
                    ensureTaskStepSlot(index, title)
                    markTaskStepRunning(index, "正在执行$title")
                } else {
                    val output = stepResult?.output.orEmpty()
                    val ok = stepResult?.success == true
                    markTaskStepFinished(index, ok, output)
                    renderedSteps += renderTaskStep(index + 1, title, output)
                    if (stepResult != null) {
                        applyAgentStepSideEffects(stepId, arguments, stepResult)
                    }
                }
            },
            onRuntimeTaskSubmitted = if (bindRuntimeOnSubmit) {
                { runtimeId ->
                    trackRuntimeTask(
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
            }?.let { applyRuntimeTaskSnapshot(it) }
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
                updateTaskExecution { task ->
                    task.copy(summary = footer)
                }
            } else {
                finishTaskExecution(
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

    private suspend fun runAiToolLoop(
        initialTool: ClawTool,
        initialArguments: Map<String, String>,
        initialAssistantMessage: String?,
        normalizedPrompt: String,
        replyMessageId: String,
        modelSettings: ModelSettings,
        automationUiState: OverviewAutomationState,
        onModelCallSuccess: () -> Unit
    ) {
        val dispatcher = toolDispatcher
        if (dispatcher == null) {
            handleToolIntent(
                tool = initialTool,
                arguments = initialArguments,
                normalizedPrompt = normalizedPrompt,
                replyMessageId = replyMessageId,
                assistantMessage = initialAssistantMessage,
                reflectResultWithModel = true,
                modelSettings = modelSettings,
                automationUiState = automationUiState,
                onModelCallSuccess = onModelCallSuccess
            )
            return
        }

        val steps = mutableListOf<AiToolStepRecord>()
        var tool = initialTool
        var arguments = initialArguments
        var assistantMessage = initialAssistantMessage
        val maxTurns = AiAgentOrchestrator.MAX_TOOL_LOOP_TURNS
        startDynamicTaskExecution(
            title = "AI 工具循环",
            summary = "正在按模型决策执行工具，最多 $maxTurns 步；可随时取消。",
            initialStepTitles = listOf(tool.displayName),
            originPrompt = normalizedPrompt
        )

        repeat(maxTurns) { turnIndex ->
            currentCoroutineContext().ensureActive()
            val turn = turnIndex + 1
            ensureTaskStepSlot(turnIndex, tool.displayName)
            markTaskStepRunning(turnIndex, "正在执行 ${tool.displayName}")
            updateState { state ->
                state.copy(latestAiStatus = "AI 工具循环 $turn/$maxTurns: ${tool.displayName}")
            }
            updateChatMessage(
                replyMessageId,
                assistantMessage ?: "正在执行 ${tool.displayName}（第 $turn 步）...",
                ChatMessageState.Streaming
            )

            val enrichedArgs = enrichToolArguments(tool, arguments, automationUiState, normalizedPrompt)
            val (stepSuccess, stepOutput) = if (tool == ClawTool.RUN_AGENT) {
                val agentId = enrichedArgs.stringArg("agent_id", "agent", "id", "name")
                val reply = if (agentId.isBlank()) {
                    "失败: agent_id 不能为空"
                } else {
                    executeAgentById(
                        agentId = agentId,
                        arguments = enrichedArgs.mapValues { (_, value) -> value },
                        ensureTaskUi = false,
                        finishTaskUi = false,
                        // Bind Runtime task id so cancel / event sync reach the daemon task.
                        bindRuntimeOnSubmit = true,
                        originPrompt = normalizedPrompt
                    )
                }
                val ok = !reply.startsWith("失败") && !reply.contains("任务中止")
                ok to reply
            } else {
                var result = dispatcher.execute(tool, enrichedArgs.mapValues { (_, value) -> value })
                overviewController.applyChatToolEffects(tool, enrichedArgs, result)
                if (result.captureArtifact != null) {
                    dispatcher.rememberCapture(result.captureArtifact)
                }
                syncRuntimeTaskTracking(tool, enrichedArgs, result, normalizedPrompt)
                if (tool == ClawTool.TASK_SUBMIT && result.success) {
                    result = awaitSubmittedRuntimeTask(dispatcher, result)
                }
                result.success to result.output
            }
            markTaskStepFinished(turnIndex, stepSuccess, stepOutput)
            steps += AiToolStepRecord(
                tool = tool,
                arguments = enrichedArgs,
                success = stepSuccess,
                output = stepOutput
            )

            val remainingTurns = maxTurns - turn
            if (remainingTurns <= 0) {
                val allOk = steps.all { it.success }
                finishAiLoopTaskExecution(
                    success = allOk,
                    summary = if (allOk) {
                        "AI 工具循环已结束（达到最大步数）。"
                    } else {
                        "AI 工具循环已结束：部分步骤失败。"
                    }
                )
                finalizeToolReply(
                    replyMessageId = replyMessageId,
                    normalizedPrompt = normalizedPrompt,
                    tool = tool,
                    arguments = enrichedArgs,
                    assistantMessage = assistantMessage,
                    result = ChatAiLoop.buildTranscript(steps),
                    reflectResultWithModel = true,
                    modelSettings = modelSettings,
                    onModelCallSuccess = onModelCallSuccess
                )
                return
            }

            currentCoroutineContext().ensureActive()
            val continuePlan = AiAgentOrchestrator.continueAfterTool(
                settings = modelSettings,
                originalPrompt = normalizedPrompt,
                steps = steps,
                runtimeSnapshot = currentAiRuntimeSnapshot(),
                remainingTurns = remainingTurns
            ).fold(
                onSuccess = {
                    onModelCallSuccess()
                    it
                },
                onFailure = { error ->
                    finishAiLoopTaskExecution(
                        success = false,
                        summary = "AI 续步失败，已停止工具循环。"
                    )
                    updateState { state ->
                        state.copy(latestAiStatus = "AI 续步失败，已回退原始结果")
                    }
                    updateChatMessage(
                        replyMessageId,
                        buildAssistantReply(
                            assistantMessage,
                            "模型续步失败：${error.message ?: error::class.java.simpleName}",
                            ChatAiLoop.buildTranscript(steps)
                        ),
                        ChatMessageState.Final
                    )
                    finishChat()
                    return
                }
            )

            when (continuePlan) {
                is AiAgentPlan.AssistantReply -> {
                    val allOk = steps.all { it.success }
                    finishAiLoopTaskExecution(
                        success = allOk,
                        summary = if (allOk) {
                            "AI 工具循环已完成。"
                        } else {
                            "AI 工具循环已结束：存在失败步骤。"
                        }
                    )
                    updateState { state -> state.copy(latestAiStatus = "AI 工具循环已完成") }
                    updateChatMessage(
                        replyMessageId,
                        buildAssistantReply(assistantMessage, continuePlan.message, ChatAiLoop.buildTranscript(steps)),
                        ChatMessageState.Final
                    )
                    finishChat()
                    return
                }

                is AiAgentPlan.ToolExecution -> {
                    val nextEnriched = enrichToolArguments(
                        tool = continuePlan.tool,
                        arguments = continuePlan.arguments,
                        automationUiState = automationUiState,
                        normalizedPrompt = normalizedPrompt
                    )
                    val duplicated = steps.any {
                        it.tool == continuePlan.tool && it.arguments == nextEnriched
                    }
                    if (duplicated) {
                        finishAiLoopTaskExecution(
                            success = steps.all { it.success },
                            summary = "AI 工具循环已停止（重复步骤）。"
                        )
                        updateState { state -> state.copy(latestAiStatus = "AI 工具循环已停止（重复步骤）") }
                        updateChatMessage(
                            replyMessageId,
                            buildAssistantReply(
                                continuePlan.assistantMessage,
                                "检测到重复工具步骤，已停止继续调用。",
                                ChatAiLoop.buildTranscript(steps)
                            ),
                            ChatMessageState.Final
                        )
                        finishChat()
                        return
                    }
                    tool = continuePlan.tool
                    arguments = continuePlan.arguments
                    assistantMessage = continuePlan.assistantMessage
                }
            }
        }
    }

    private fun currentAiRuntimeSnapshot(): AiRuntimeSnapshot {
        val overviewUiState = overviewController.uiState.value
        return AiRuntimeSnapshot(
            sessionSummary = overviewUiState.runtimeState.session.summary,
            capabilityStatus = overviewUiState.runtimeState.capabilityStatus,
            eventStreaming = overviewUiState.eventState.eventStreaming
        )
    }

    private fun enrichToolArguments(
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

    private suspend fun awaitSubmittedRuntimeTask(
        dispatcher: ClawToolDispatcher,
        submitResult: ClawToolCallResult
    ): ClawToolCallResult {
        val taskId = submitResult.runtimeTaskId?.takeIf { it.isNotBlank() }
            ?: return submitResult
        val awaited = RuntimeTaskPoller.awaitTerminal(
            dispatcher = dispatcher,
            taskId = taskId,
            onSnapshot = { snapshot ->
                if (uiState.value.taskExecution?.runtimeTaskId == snapshot.taskId) {
                    applyRuntimeTaskSnapshot(snapshot)
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
    private fun finishAiLoopTaskExecution(success: Boolean, summary: String) {
        val task = uiState.value.taskExecution ?: return
        if (task.status != ChatTaskProgressState.Running) {
            return
        }
        val runtimeId = task.runtimeTaskId?.trim().orEmpty()
        if (runtimeId.isNotEmpty()) {
            updateTaskExecution { current ->
                current.copy(summary = summary)
            }
            return
        }
        finishTaskExecution(success = success, summary = summary)
    }

    private fun syncRuntimeTaskTracking(
        tool: ClawTool,
        arguments: Map<String, String>,
        result: ClawToolCallResult,
        normalizedPrompt: String
    ) {
        when (tool) {
            ClawTool.TASK_SUBMIT -> {
                result.runtimeTaskId?.takeIf { it.isNotBlank() }?.let { runtimeId ->
                    trackRuntimeTask(
                        runtimeTaskId = runtimeId,
                        originPrompt = normalizedPrompt,
                        snapshotName = arguments["name"]
                    )
                }
                result.taskSnapshot?.let { applyRuntimeTaskSnapshot(it) }
            }
            ClawTool.TASK_GET, ClawTool.TASK_CANCEL -> {
                result.taskSnapshot?.let { snapshot ->
                    val current = uiState.value.taskExecution ?: return@let
                    if (current.runtimeTaskId == snapshot.taskId) {
                        applyRuntimeTaskSnapshot(snapshot)
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
                updateChatMessage(
                    replyMessageId,
                    assistantMessage ?: "正在执行 Agent $agentId...",
                    ChatMessageState.Streaming
                )
                currentTaskJob = currentCoroutineContext()[Job]
                try {
                    val reply = executeAgentById(
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
                    cancelTaskExecution("任务已取消：已停止 Agent 步骤。")
                    updateChatMessage(
                        replyMessageId,
                        "任务已取消：已停止 Agent 步骤。",
                        ChatMessageState.Final
                    )
                    finishChat()
                } finally {
                    currentTaskJob = null
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
                    updateChatMessage(
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
                updateChatMessage(
                    replyMessageId,
                    assistantMessage ?: streamingLabel,
                    ChatMessageState.Streaming
                )
                val result = dispatcher.execute(
                    tool,
                    enrichedArgs.mapValues { (_, value) -> value }
                )
                overviewController.applyChatToolEffects(tool, enrichedArgs, result)
                if (result.captureArtifact != null) {
                    dispatcher.rememberCapture(result.captureArtifact)
                }
                syncRuntimeTaskTracking(tool, enrichedArgs, result, normalizedPrompt)
                finalizeToolReply(
                    replyMessageId = replyMessageId,
                    normalizedPrompt = normalizedPrompt,
                    tool = tool,
                    arguments = enrichedArgs,
                    assistantMessage = assistantMessage,
                    result = formatChatToolOutput(tool, result),
                    reflectResultWithModel = reflectResultWithModel,
                    modelSettings = modelSettings,
                    onModelCallSuccess = onModelCallSuccess
                )
            }
        }
    }

    private fun welcomeMessage(): ChatMessage {
        return ChatMessage(
            role = ChatRole.Assistant,
            content = "可以像聊天一样直接下达指令，例如“ping ClawRuntime”、“获取能力”、“截图并预览”、“运行时体检”、“/agents”、“/agent runtime_health_sweep”、“/task_list”、“/task_submit demo ping”、“取消 runtime 任务 <id>”、“执行 wm size”、“开始事件订阅”。"
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
        val agent = action.toAgentDefinition()
        val stepFlow = agent.stepTitles.joinToString(" -> ")
        beginTaskExecution(
            title = agent.name,
            summary = "正在按“$stepFlow”推进任务。",
            stepTitles = agent.stepTitles,
            originPrompt = originPrompt,
            taskAction = action,
            retryCount = retryCount,
            retryFromTaskId = retryFromTaskId
        )
    }

    private fun startDynamicTaskExecution(
        title: String,
        summary: String,
        initialStepTitles: List<String>,
        originPrompt: String
    ) {
        beginTaskExecution(
            title = title,
            summary = summary,
            stepTitles = initialStepTitles.ifEmpty { listOf("执行中") },
            originPrompt = originPrompt,
            taskAction = null
        )
    }

    private fun beginTaskExecution(
        title: String,
        summary: String,
        stepTitles: List<String>,
        originPrompt: String,
        taskAction: ChatTaskAction?,
        retryCount: Int = 0,
        retryFromTaskId: String? = null
    ) {
        if (currentTaskJob?.isActive == true) {
            currentTaskJob?.cancel(CancellationException("被新任务替换：$title"))
            currentTaskJob = null
        }
        val previousRuntimeTaskId = uiState.value.taskExecution
            ?.takeIf { it.status == ChatTaskProgressState.Running }
            ?.runtimeTaskId
            ?.takeIf { it.isNotBlank() }
        if (!previousRuntimeTaskId.isNullOrBlank()) {
            requestCancelRuntimeTask(previousRuntimeTaskId)
        }
        val startedAt = System.currentTimeMillis()
        updateState {
            val archivedHistory = it.taskExecution?.let { existingTask ->
                val archivedTask = if (existingTask.status == ChatTaskProgressState.Running) {
                    existingTask.copy(
                        status = ChatTaskProgressState.Cancelled,
                        summary = "已被新任务替换，未继续执行。",
                        finishedAtEpochMs = startedAt,
                        failureReason = "被新任务替换",
                        failure = ChatTaskFailureState(
                            code = "task_replaced",
                            summary = "任务被替换",
                            rawDetail = "启动新任务前，先前运行中的任务已标记为取消"
                        ),
                        steps = existingTask.steps.map { step ->
                            if (step.status == ChatTaskProgressState.Running ||
                                step.status == ChatTaskProgressState.Pending
                            ) {
                                step.copy(
                                    status = ChatTaskProgressState.Cancelled,
                                    detail = "被新任务替换",
                                    finishedAtEpochMs = startedAt
                                )
                            } else {
                                step
                            }
                        }
                    )
                } else {
                    existingTask
                }
                appendTaskHistory(it.taskHistory, archivedTask)
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
                    taskAction = taskAction,
                    runtimeTaskId = null,
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

    private fun isActiveRuntimeTaskState(state: String): Boolean {
        return when (state.lowercase()) {
            "created", "queued", "running", "retrying", "waitingsignal", "compensating" -> true
            else -> false
        }
    }

    private fun ensureTaskStepSlot(stepIndex: Int, title: String) {
        updateTaskExecution { task ->
            if (stepIndex < task.steps.size) {
                val current = task.steps[stepIndex]
                if (current.title == title) {
                    task
                } else {
                    task.copy(
                        steps = task.steps.mapIndexed { index, step ->
                            if (index == stepIndex) step.copy(title = title) else step
                        }
                    )
                }
            } else {
                val padded = task.steps.toMutableList()
                while (padded.size < stepIndex) {
                    padded += ChatTaskStepState(title = "步骤 ${padded.size + 1}")
                }
                padded += ChatTaskStepState(title = title)
                task.copy(steps = padded)
            }
        }
    }

    private fun bindRuntimeTaskId(runtimeTaskId: String) {
        updateTaskExecution { task ->
            task.copy(runtimeTaskId = runtimeTaskId)
        }
    }

    private fun requestCancelRuntimeTask(runtimeTaskId: String) {
        if (runtimeTaskId.isBlank()) {
            return
        }
        viewModelScope.launch {
            runCatching {
                toolDispatcher?.execute(
                    ClawTool.TASK_CANCEL,
                    mapOf("task_id" to runtimeTaskId)
                )
            }
        }
    }

    private fun trackRuntimeTask(
        runtimeTaskId: String,
        originPrompt: String,
        snapshotName: String? = null
    ) {
        val current = uiState.value.taskExecution
        if (current?.status == ChatTaskProgressState.Running) {
            val previousId = current.runtimeTaskId
            when {
                previousId.isNullOrBlank() || previousId == runtimeTaskId -> {
                    bindRuntimeTaskId(runtimeTaskId)
                    if (!snapshotName.isNullOrBlank()) {
                        updateTaskExecution { task ->
                            task.copy(
                                title = snapshotName,
                                summary = "正在跟踪 Runtime 任务 $runtimeTaskId"
                            )
                        }
                    }
                }
                else -> {
                    // Switch tracking to a new Runtime task; stop the previous daemon job.
                    requestCancelRuntimeTask(previousId)
                    startDynamicTaskExecution(
                        title = snapshotName?.takeIf { it.isNotBlank() } ?: "Runtime 任务",
                        summary = "正在跟踪 Runtime 任务 $runtimeTaskId（需保持事件订阅）。",
                        initialStepTitles = listOf("排队/执行中"),
                        originPrompt = originPrompt
                    )
                    bindRuntimeTaskId(runtimeTaskId)
                }
            }
            return
        }
        startDynamicTaskExecution(
            title = snapshotName?.takeIf { it.isNotBlank() } ?: "Runtime 任务",
            summary = "正在跟踪 Runtime 任务 $runtimeTaskId（需保持事件订阅）。",
            initialStepTitles = listOf("排队/执行中"),
            originPrompt = originPrompt
        )
        bindRuntimeTaskId(runtimeTaskId)
    }

    internal fun applyRuntimeTaskSnapshot(snapshot: ClawRuntimeTaskSnapshot) {
        updateTaskExecution { task ->
            task.withRuntimeSnapshot(snapshot)
        }
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
        // Runtime-backed tasks can be reattached via task_get / task_list after restart.
        if (!runtimeTaskId.isNullOrBlank()) {
            return copy(
                summary = "应用重启后正在重新同步 Runtime 任务 $runtimeTaskId…",
                failureReason = null,
                failure = null
            )
        }
        val restoredAt = System.currentTimeMillis()
        return copy(
            status = ChatTaskProgressState.Cancelled,
            summary = "应用已重启，之前运行中的本地任务未继续执行。",
            finishedAtEpochMs = if (finishedAtEpochMs > 0L) finishedAtEpochMs else restoredAt,
            failureReason = "应用重启导致任务中断",
            failure = ChatTaskFailureState(
                code = "app_restarted",
                summary = "应用重启导致任务中断",
                rawDetail = "应用恢复时发现该本地任务仍处于执行中，且无法恢复协程，已自动标记为取消"
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
        private const val MAX_PROMPT_LENGTH = 8192

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
