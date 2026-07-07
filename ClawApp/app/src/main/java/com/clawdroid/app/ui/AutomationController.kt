package com.clawdroid.app.ui

import com.clawdroid.app.automation.AccessibilitySnapshot
import com.clawdroid.app.automation.AutomationRuntimeStore
import com.clawdroid.app.automation.AutomationTaskSnapshot
import com.clawdroid.app.automation.AutomationTaskState
import com.clawdroid.app.tools.ClawToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class AutomationController(
    private val toolExecutor: ClawToolExecutor,
    scope: CoroutineScope
) {
    private val controllerScope = scope
    private val formState = MutableStateFlow(AutomationFormState())

    val state: StateFlow<OverviewAutomationState> = combine(
        AutomationRuntimeStore.accessibilitySnapshot,
        AutomationRuntimeStore.taskSnapshot,
        formState.asStateFlow()
    ) { accessibilitySnapshot, taskSnapshot, currentFormState ->
        currentFormState.toOverviewAutomationState(
            accessibilitySnapshot = accessibilitySnapshot,
            taskSnapshot = taskSnapshot
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = AutomationFormState().toOverviewAutomationState(
            accessibilitySnapshot = AccessibilitySnapshot(),
            taskSnapshot = AutomationTaskSnapshot()
        )
    )

    fun updatePageConfirmPackage(value: String) {
        formState.update { it.copy(pageConfirmPackage = value) }
    }

    fun updatePageConfirmText(value: String) {
        formState.update { it.copy(pageConfirmText = value) }
    }

    fun updatePageConfirmViewId(value: String) {
        formState.update { it.copy(pageConfirmViewId = value) }
    }

    fun updateClickPrecheckPackage(value: String) {
        formState.update { it.copy(clickPrecheckPackage = value) }
    }

    fun updateClickPrecheckText(value: String) {
        formState.update { it.copy(clickPrecheckText = value) }
    }

    fun updateClickPrecheckViewId(value: String) {
        formState.update { it.copy(clickPrecheckViewId = value) }
    }

    fun currentTaskInputs(): AutomationTaskInputs {
        return formState.value.toTaskInputs()
    }

    fun confirmPage(
        expectedPackage: String = state.value.pageConfirmPackage,
        expectedText: String = state.value.pageConfirmText,
        expectedViewId: String = state.value.pageConfirmViewId
    ): String {
        formState.update {
            it.copy(
                pageConfirmPackage = expectedPackage,
                pageConfirmText = expectedText,
                pageConfirmViewId = expectedViewId
            )
        }
        val result = toolExecutor.confirmPage(
            expectedPackage = expectedPackage,
            expectedText = expectedText,
            expectedViewId = expectedViewId
        )
        formState.update { it.copy(pageConfirmStatus = result.output) }
        return result.output
    }

    fun precheckClickTarget(
        expectedPackage: String = state.value.clickPrecheckPackage,
        targetText: String = state.value.clickPrecheckText,
        targetViewId: String = state.value.clickPrecheckViewId
    ): String {
        formState.update {
            it.copy(
                clickPrecheckPackage = expectedPackage,
                clickPrecheckText = targetText,
                clickPrecheckViewId = targetViewId
            )
        }
        val result = toolExecutor.precheckClickTarget(
            expectedPackage = expectedPackage,
            targetText = targetText,
            targetViewId = targetViewId
        )
        formState.update { it.copy(clickPrecheckStatus = result.output) }
        return result.output
    }

    fun launchSafeTap() {
        controllerScope.launch {
            safeTapUsingResolvedTarget()
        }
    }

    suspend fun safeTapUsingResolvedTarget(): String {
        formState.update { it.copy(safeTapStatus = "请求中...") }
        val result = toolExecutor.safeTapUsingResolvedTarget()
        formState.update { it.copy(safeTapStatus = result.output) }
        return result.output
    }
}

internal data class AutomationTaskInputs(
    val pageConfirmPackage: String,
    val pageConfirmText: String,
    val pageConfirmViewId: String,
    val clickPrecheckPackage: String,
    val clickPrecheckText: String,
    val clickPrecheckViewId: String
)

private data class AutomationFormState(
    val pageConfirmPackage: String = "",
    val pageConfirmText: String = "",
    val pageConfirmViewId: String = "",
    val pageConfirmStatus: String = "未执行页面确认",
    val clickPrecheckPackage: String = "",
    val clickPrecheckText: String = "",
    val clickPrecheckViewId: String = "",
    val clickPrecheckStatus: String = "未执行点击前检查",
    val safeTapStatus: String = "未执行安全点击"
) {
    fun toOverviewAutomationState(
        accessibilitySnapshot: AccessibilitySnapshot,
        taskSnapshot: AutomationTaskSnapshot
    ): OverviewAutomationState {
        return OverviewAutomationState(
            accessibilitySnapshotStatus = buildAccessibilitySnapshotStatus(accessibilitySnapshot),
            taskState = taskSnapshot.state,
            taskSummary = buildTaskSummary(taskSnapshot),
            pageConfirmPackage = pageConfirmPackage,
            pageConfirmText = pageConfirmText,
            pageConfirmViewId = pageConfirmViewId,
            pageConfirmStatus = pageConfirmStatus,
            clickPrecheckPackage = clickPrecheckPackage,
            clickPrecheckText = clickPrecheckText,
            clickPrecheckViewId = clickPrecheckViewId,
            clickPrecheckStatus = clickPrecheckStatus,
            safeTapStatus = safeTapStatus
        )
    }

    fun toTaskInputs(): AutomationTaskInputs {
        return AutomationTaskInputs(
            pageConfirmPackage = pageConfirmPackage,
            pageConfirmText = pageConfirmText,
            pageConfirmViewId = pageConfirmViewId,
            clickPrecheckPackage = clickPrecheckPackage,
            clickPrecheckText = clickPrecheckText,
            clickPrecheckViewId = clickPrecheckViewId
        )
    }
}

private fun buildAccessibilitySnapshotStatus(snapshot: AccessibilitySnapshot): String {
    fun compact(value: String, limit: Int): String {
        val normalized = value
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) {
            return "none"
        }
        return if (normalized.length <= limit) normalized else normalized.take(limit) + "..."
    }

    return "服务连接: ${booleanStatusLabel(snapshot.serviceConnected)}\n" +
        "最近事件: ${snapshot.eventName}\n" +
        "节点数: ${snapshot.nodeCount} / 可点击: ${snapshot.clickableNodeCount}\n" +
        "包名: ${snapshot.packageName.ifBlank { "unknown" }}\n" +
        "类名: ${snapshot.className.ifBlank { "unknown" }}\n" +
        "文本: ${compact(snapshot.textSummary, 80)}\n" +
        "描述: ${compact(snapshot.contentDescription, 80)}\n" +
        "可见节点: ${compact(snapshot.visibleNodeSummary, 120)}\n" +
        "视图ID: ${compact(snapshot.viewIdSummary, 120)}\n" +
        "可点击目标: ${compact(snapshot.clickableTargetSummary, 120)}\n" +
        "最近解析点击点: ${snapshot.lastResolvedTapLabel.ifBlank { "none" }} @ (${snapshot.lastResolvedTapX ?: -1}, ${snapshot.lastResolvedTapY ?: -1})\n" +
        "更新时间: ${formatEpochMillis(snapshot.updatedAtEpochMs)}"
}

private fun buildTaskSummary(taskSnapshot: AutomationTaskSnapshot): String {
    return "${taskSnapshot.goal}\n" +
        "${taskSnapshot.detail}\n" +
        "期望包名: ${taskSnapshot.expectedPackage.ifBlank { "none" }}\n" +
        "期望文本: ${taskSnapshot.expectedText.ifBlank { "none" }}\n" +
        "期望视图ID: ${taskSnapshot.expectedViewId.ifBlank { "none" }}\n" +
        "解析点击点: ${taskSnapshot.resolvedTapLabel.ifBlank { "none" }} @ (${taskSnapshot.resolvedTapX ?: -1}, ${taskSnapshot.resolvedTapY ?: -1})\n" +
        "更新时间: ${formatEpochMillis(taskSnapshot.updatedAtEpochMs)}"
}
