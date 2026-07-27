package com.clawdroid.app.chat

import android.content.Context
import com.clawdroid.app.data.ChatTaskHistoryStore
import com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolDispatcher
import com.clawdroid.app.ui.ChatMessageState
import com.clawdroid.app.ui.ChatRole
import com.clawdroid.app.ui.ChatTaskExecutionState
import com.clawdroid.app.ui.ChatTaskFailureState
import com.clawdroid.app.ui.ChatTaskHistoryFilter
import com.clawdroid.app.ui.ChatTaskProgressState
import com.clawdroid.app.ui.ChatTaskStepState
import com.clawdroid.app.ui.ChatUiState
import com.clawdroid.app.ui.withRuntimeSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class ChatTaskExecutionController(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val toolDispatcher: ClawToolDispatcher?,
    private val getState: () -> ChatUiState,
    private val updateState: ((ChatUiState) -> ChatUiState) -> Unit,
    private val getCurrentTaskJob: () -> Job?,
    private val setCurrentTaskJob: (Job?) -> Unit,
    private val appendChat: (ChatRole, String, ChatMessageState) -> Unit
) {
    fun restoreTaskState() {
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
            scope.launch {
                resyncRuntimeTaskAfterRestore(runtimeTaskId)
            }
        }
    }

    suspend fun resyncRuntimeTaskAfterRestore(runtimeTaskId: String) {
        val dispatcher = toolDispatcher ?: return
        val stillTracking = {
            getState().taskExecution
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

    fun persistTaskState() {
        val currentState = getState()
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

    fun cancelCurrentTaskExecution() {
        val runningTask = getState().taskExecution
            ?.takeIf { it.status == ChatTaskProgressState.Running }
            ?: return
        val runtimeTaskId = runningTask.runtimeTaskId
        getCurrentTaskJob()?.cancel(
            CancellationException("用户取消任务：${runningTask.title}")
        )
        if (runtimeTaskId.isNullOrBlank()) {
            cancelTaskExecution("任务已取消。")
            return
        }
        scope.launch {
            val cancelResult = runCatching {
                toolDispatcher?.execute(
                    ClawTool.TASK_CANCEL,
                    mapOf("task_id" to runtimeTaskId)
                )
            }.getOrNull()
            val stillTrackingSame = getState().taskExecution
                ?.takeIf { it.status == ChatTaskProgressState.Running }
                ?.runtimeTaskId == runtimeTaskId
            if (!stillTrackingSame) {
                return@launch
            }
            if (cancelResult == null || cancelResult.success) {
                cancelResult?.taskSnapshot?.let { applyRuntimeTaskSnapshot(it) }
                if (getState().taskExecution?.status == ChatTaskProgressState.Running) {
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
        val current = getState().taskExecution
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
            current.runtimeTaskId == snapshot.taskId -> {
                applyRuntimeTaskSnapshot(snapshot)
            }
        }
    }

    fun startTaskExecution(
        action: ChatTaskAction,
        originPrompt: String,
        retryCount: Int = 0,
        retryFromTaskId: String? = null,
        preserveJob: Job? = null
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
            retryFromTaskId = retryFromTaskId,
            preserveJob = preserveJob
        )
    }

    fun startDynamicTaskExecution(
        title: String,
        summary: String,
        initialStepTitles: List<String>,
        originPrompt: String,
        preserveJob: Job? = null
    ) {
        beginTaskExecution(
            title = title,
            summary = summary,
            stepTitles = initialStepTitles.ifEmpty { listOf("执行中") },
            originPrompt = originPrompt,
            taskAction = null,
            preserveJob = preserveJob
        )
    }

    fun beginTaskExecution(
        title: String,
        summary: String,
        stepTitles: List<String>,
        originPrompt: String,
        taskAction: ChatTaskAction?,
        retryCount: Int = 0,
        retryFromTaskId: String? = null,
        preserveJob: Job? = null
    ) {
        val active = getCurrentTaskJob()
        if (active?.isActive == true) {
            val shouldPreserve = preserveJob != null && jobsRelated(active, preserveJob)
            if (!shouldPreserve) {
                active.cancel(CancellationException("被新任务替换：$title"))
                setCurrentTaskJob(null)
            }
        }
        val previousRuntimeTaskId = getState().taskExecution
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

    fun ensureTaskStepSlot(stepIndex: Int, title: String) {
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

    fun markTaskStepRunning(stepIndex: Int, detail: String) {
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

    fun markTaskStepFinished(stepIndex: Int, success: Boolean, detail: String) {
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

    fun finishTaskExecution(success: Boolean, summary: String) {
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

    fun cancelTaskExecution(summary: String) {
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

    fun finishAiLoopTaskExecution(success: Boolean, summary: String) {
        val task = getState().taskExecution ?: return
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

    fun applyRuntimeTaskSnapshot(snapshot: ClawRuntimeTaskSnapshot) {
        updateTaskExecution { task ->
            task.withRuntimeSnapshot(snapshot)
        }
    }

    fun trackRuntimeTask(
        runtimeTaskId: String,
        originPrompt: String,
        snapshotName: String? = null
    ) {
        val current = getState().taskExecution
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
                    requestCancelRuntimeTask(previousId)
                    startDynamicTaskExecution(
                        title = snapshotName?.takeIf { it.isNotBlank() } ?: "Runtime 任务",
                        summary = "正在跟踪 Runtime 任务 $runtimeTaskId（需保持事件订阅）。",
                        initialStepTitles = listOf("排队/执行中"),
                        originPrompt = originPrompt,
                        preserveJob = getCurrentTaskJob()
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
            originPrompt = originPrompt,
            preserveJob = getCurrentTaskJob()
        )
        bindRuntimeTaskId(runtimeTaskId)
    }

    fun isRetryBlocked(): Boolean {
        return getState().chatBusy || getCurrentTaskJob()?.isActive == true
    }

    fun notifyRetryBlocked() {
        appendChat(
            ChatRole.Assistant,
            "当前仍有指令在执行，请等待完成或先取消任务后再重试。",
            ChatMessageState.Final
        )
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
        scope.launch {
            runCatching {
                toolDispatcher?.execute(
                    ClawTool.TASK_CANCEL,
                    mapOf("task_id" to runtimeTaskId)
                )
            }
        }
    }

    fun updateTaskExecution(transform: (ChatTaskExecutionState) -> ChatTaskExecutionState) {
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

    private fun isActiveRuntimeTaskState(state: String): Boolean {
        return when (state.lowercase()) {
            "created", "queued", "running", "retrying", "waitingsignal", "compensating" -> true
            else -> false
        }
    }

    companion object {
        const val MAX_TASK_HISTORY_ITEMS = 20
    }
}

/** True when [a] and [b] are the same job or one is an ancestor of the other. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun jobsRelated(a: Job, b: Job): Boolean {
    if (a === b) return true
    var cursor: Job? = b
    while (cursor != null) {
        if (cursor === a) return true
        cursor = cursor.parent
    }
    cursor = a
    while (cursor != null) {
        if (cursor === b) return true
        cursor = cursor.parent
    }
    return false
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
