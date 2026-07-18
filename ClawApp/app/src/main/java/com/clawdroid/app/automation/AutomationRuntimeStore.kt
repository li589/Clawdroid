package com.clawdroid.app.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class AutomationTaskState {
    Created,
    Queued,
    Running,
    WaitingSignal,
    Retrying,
    Succeeded,
    Failed,
    Cancelled,
    Compensating
}

data class AccessibilitySnapshot(
    val serviceConnected: Boolean = false,
    val eventCount: Int = 0,
    val eventName: String = "none",
    val nodeCount: Int = 0,
    val clickableNodeCount: Int = 0,
    val packageName: String = "",
    val className: String = "",
    val textSummary: String = "",
    val contentDescription: String = "",
    val visibleNodeSummary: String = "",
    val viewIdSummary: String = "",
    val clickableTargetSummary: String = "",
    val lastResolvedTapLabel: String = "",
    val lastResolvedTapX: Int? = null,
    val lastResolvedTapY: Int? = null,
    val updatedAtEpochMs: Long = 0L
)

data class AutomationTaskSnapshot(
    val taskId: String = "",
    val state: AutomationTaskState = AutomationTaskState.Created,
    val goal: String = "idle",
    val detail: String = "尚未开始任务",
    val expectedPackage: String = "",
    val expectedText: String = "",
    val expectedViewId: String = "",
    val resolvedTapLabel: String = "",
    val resolvedTapX: Int? = null,
    val resolvedTapY: Int? = null,
    val updatedAtEpochMs: Long = 0L
)

private data class NodeSemantic(
    val text: String,
    val viewId: String,
    val className: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val bounds: Rect
)

data class ResolvedTapTarget(
    val label: String,
    val x: Int,
    val y: Int
)

object AutomationRuntimeStore {
    private const val staleSnapshotThresholdMs = 15_000L
    private const val maxSemanticNodes = 24
    /** Cap UI/tree walks so Overview does not recompose on every a11y flood. */
    private const val minPublishIntervalMs = 750L

    private val _accessibilitySnapshot = MutableStateFlow(AccessibilitySnapshot())
    val accessibilitySnapshot: StateFlow<AccessibilitySnapshot> = _accessibilitySnapshot.asStateFlow()

    private val _taskSnapshot = MutableStateFlow(AutomationTaskSnapshot())
    val taskSnapshot: StateFlow<AutomationTaskSnapshot> = _taskSnapshot.asStateFlow()
    private var latestNodeSemantics: List<NodeSemantic> = emptyList()
    @Volatile
    private var lastPublishAtMs: Long = 0L
    @Volatile
    private var suppressedEventCount: Int = 0

    fun onAccessibilityServiceConnected() {
        _accessibilitySnapshot.value = _accessibilitySnapshot.value.copy(
            serviceConnected = true,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    fun onAccessibilityServiceInterrupted() {
        latestNodeSemantics = emptyList()
        _accessibilitySnapshot.value = _accessibilitySnapshot.value.copy(
            serviceConnected = false,
            lastResolvedTapLabel = "",
            lastResolvedTapX = null,
            lastResolvedTapY = null,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    fun publishAccessibilityEvent(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo?) {
        val now = System.currentTimeMillis()
        val highPriority = isHighPriorityAccessibilityEvent(event.eventType)
        if (!highPriority && now - lastPublishAtMs < minPublishIntervalMs) {
            suppressedEventCount += 1
            return
        }
        lastPublishAtMs = now
        val skipped = suppressedEventCount
        suppressedEventCount = 0

        val textSummary = event.text
            .mapNotNull { it?.toString()?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" | ")
        val semantics = collectNodeSemantics(rootNode)
        latestNodeSemantics = semantics.nodes

        _accessibilitySnapshot.value = AccessibilitySnapshot(
            serviceConnected = true,
            eventCount = _accessibilitySnapshot.value.eventCount + 1 + skipped,
            eventName = eventTypeName(event.eventType),
            nodeCount = semantics.nodes.size,
            clickableNodeCount = semantics.nodes.count { it.clickable && it.enabled },
            packageName = event.packageName?.toString().orEmpty(),
            className = event.className?.toString().orEmpty(),
            textSummary = textSummary,
            contentDescription = event.contentDescription?.toString().orEmpty(),
            visibleNodeSummary = semantics.visibleNodeSummary,
            viewIdSummary = semantics.viewIdSummary,
            clickableTargetSummary = semantics.clickableTargetSummary,
            lastResolvedTapLabel = "",
            lastResolvedTapX = null,
            lastResolvedTapY = null,
            updatedAtEpochMs = now
        )
    }

    fun confirmLatestPage(
        expectedPackage: String,
        expectedText: String,
        expectedViewId: String = ""
    ): AutomationTaskSnapshot {
        val goal = buildString {
            append("page_confirm")
            if (expectedPackage.isNotBlank()) append(":pkg=").append(expectedPackage)
            if (expectedText.isNotBlank()) append(":text=").append(expectedText)
            if (expectedViewId.isNotBlank()) append(":viewId=").append(expectedViewId)
        }
        val taskId = buildTaskId()
        transitionTask(taskId, AutomationTaskState.Created, goal, "任务已创建", expectedPackage, expectedText, expectedViewId)
        transitionTask(taskId, AutomationTaskState.Queued, goal, "等待检查最新无障碍快照", expectedPackage, expectedText, expectedViewId)
        transitionTask(taskId, AutomationTaskState.Running, goal, "正在比对页面条件", expectedPackage, expectedText, expectedViewId)

        val snapshot = _accessibilitySnapshot.value
        if (!snapshot.serviceConnected) {
            return transitionTask(
                taskId,
                AutomationTaskState.Failed,
                goal,
                "无障碍服务未连接，无法确认页面",
                expectedPackage,
                expectedText,
                expectedViewId
            )
        }
        if (snapshot.updatedAtEpochMs <= 0L || System.currentTimeMillis() - snapshot.updatedAtEpochMs > staleSnapshotThresholdMs) {
            return transitionTask(
                taskId,
                AutomationTaskState.WaitingSignal,
                goal,
                "正在等待新的页面事件，请先切换页面或触发界面更新",
                expectedPackage,
                expectedText,
                expectedViewId
            )
        }
        if (expectedPackage.isNotBlank() && !snapshot.packageName.equals(expectedPackage, ignoreCase = true)) {
            return transitionTask(
                taskId,
                AutomationTaskState.Failed,
                goal,
                "页面包名不匹配，当前=${snapshot.packageName.ifBlank { "unknown" }}，期望=$expectedPackage",
                expectedPackage,
                expectedText,
                expectedViewId
            )
        }

        val combinedText = listOf(
            snapshot.textSummary,
            snapshot.contentDescription,
            snapshot.className,
            snapshot.visibleNodeSummary
        )
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        if (expectedText.isNotBlank() && !combinedText.contains(expectedText.lowercase(Locale.ROOT))) {
            return transitionTask(
                taskId,
                AutomationTaskState.Failed,
                goal,
                "页面文本不匹配，当前未检测到关键字：$expectedText",
                expectedPackage,
                expectedText,
                expectedViewId
            )
        }
        if (expectedViewId.isNotBlank() && !snapshot.viewIdSummary.lowercase(Locale.ROOT).contains(expectedViewId.lowercase(Locale.ROOT))) {
            return transitionTask(
                taskId,
                AutomationTaskState.Failed,
                goal,
                "页面视图ID不匹配，当前未检测到：$expectedViewId",
                expectedPackage,
                expectedText,
                expectedViewId
            )
        }

        val detail = buildString {
            append("页面确认成功")
            if (snapshot.packageName.isNotBlank()) append("，包名=").append(snapshot.packageName)
            if (snapshot.className.isNotBlank()) append("，类=").append(snapshot.className)
            if (snapshot.textSummary.isNotBlank()) append("，文本=").append(snapshot.textSummary.take(96))
            if (snapshot.viewIdSummary.isNotBlank()) append("，viewId=").append(snapshot.viewIdSummary.take(96))
        }
        return transitionTask(
            taskId,
            AutomationTaskState.Succeeded,
            goal,
            detail,
            expectedPackage,
            expectedText,
            expectedViewId
        )
    }

    fun precheckClickTarget(
        expectedPackage: String,
        targetText: String,
        targetViewId: String
    ): AutomationTaskSnapshot {
        val goal = buildString {
            append("click_precheck")
            if (expectedPackage.isNotBlank()) append(":pkg=").append(expectedPackage)
            if (targetText.isNotBlank()) append(":text=").append(targetText)
            if (targetViewId.isNotBlank()) append(":viewId=").append(targetViewId)
        }
        val taskId = buildTaskId()
        transitionTask(taskId, AutomationTaskState.Created, goal, "任务已创建", expectedPackage, targetText, targetViewId)
        transitionTask(taskId, AutomationTaskState.Queued, goal, "正在检查点击目标前置条件", expectedPackage, targetText, targetViewId)
        transitionTask(taskId, AutomationTaskState.Running, goal, "正在分析最新页面节点", expectedPackage, targetText, targetViewId)

        if (targetText.isBlank() && targetViewId.isBlank()) {
            return transitionTask(
                taskId,
                AutomationTaskState.Failed,
                goal,
                "请至少提供目标文本或目标视图ID",
                expectedPackage,
                targetText,
                targetViewId
            )
        }

        val pageCheck = confirmLatestPage(expectedPackage, targetText, targetViewId)
        if (pageCheck.state != AutomationTaskState.Succeeded) {
            return transitionTask(
                taskId,
                pageCheck.state,
                goal,
                "点击前检查失败：${pageCheck.detail}",
                expectedPackage,
                targetText,
                targetViewId
            )
        }

        val resolvedTarget = resolveTapTarget(targetText, targetViewId)
        if (resolvedTarget == null) {
            return transitionTask(
                taskId,
                AutomationTaskState.WaitingSignal,
                goal,
                "页面已匹配，但当前可点击目标中未找到指定控件，请先刷新界面或展开目标区域",
                expectedPackage,
                targetText,
                targetViewId
            )
        }

        updateResolvedTapTarget(resolvedTarget)

        return transitionTask(
            taskId,
            AutomationTaskState.Succeeded,
            goal,
            "点击前检查通过，已解析点击坐标 (${resolvedTarget.x}, ${resolvedTarget.y})，目标=${resolvedTarget.label}",
            expectedPackage,
            targetText,
            targetViewId,
            resolvedTapTarget = resolvedTarget
        )
    }

    fun latestResolvedTapTarget(): ResolvedTapTarget? {
        val snapshot = _accessibilitySnapshot.value
        val x = snapshot.lastResolvedTapX ?: return null
        val y = snapshot.lastResolvedTapY ?: return null
        return ResolvedTapTarget(
            label = snapshot.lastResolvedTapLabel.ifBlank { "latest_target" },
            x = x,
            y = y
        )
    }

    private fun transitionTask(
        taskId: String,
        state: AutomationTaskState,
        goal: String,
        detail: String,
        expectedPackage: String,
        expectedText: String,
        expectedViewId: String,
        resolvedTapTarget: ResolvedTapTarget? = null
    ): AutomationTaskSnapshot {
        val snapshot = AutomationTaskSnapshot(
            taskId = taskId,
            state = state,
            goal = goal,
            detail = detail,
            expectedPackage = expectedPackage,
            expectedText = expectedText,
            expectedViewId = expectedViewId,
            resolvedTapLabel = resolvedTapTarget?.label.orEmpty(),
            resolvedTapX = resolvedTapTarget?.x,
            resolvedTapY = resolvedTapTarget?.y,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        _taskSnapshot.value = snapshot
        return snapshot
    }

    private fun eventTypeName(eventType: Int): String {
        return when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window_state_changed"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "window_content_changed"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "view_clicked"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "view_focused"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "view_text_changed"
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "windows_changed"
            else -> "event_$eventType"
        }
    }

    private fun isHighPriorityAccessibilityEvent(eventType: Int): Boolean {
        return when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> true
            else -> false
        }
    }

    private fun buildTaskId(): String {
        return "task-${System.currentTimeMillis()}"
    }

    private fun collectNodeSemantics(rootNode: AccessibilityNodeInfo?): NodeSemanticSummary {
        if (rootNode == null) {
            return NodeSemanticSummary(emptyList(), "", "", "")
        }

        val nodes = mutableListOf<NodeSemantic>()
        traverseNodeTree(rootNode, nodes)

        val visibleNodeSummary = nodes
            .map { it.text }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
            .joinToString(" | ")
        val viewIdSummary = nodes
            .map { it.viewId }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
            .joinToString(" | ")
        val clickableTargetSummary = nodes
            .filter { it.clickable && it.enabled }
            .map { semantic ->
                buildString {
                    append(semantic.className.ifBlank { "node" })
                    if (semantic.viewId.isNotBlank()) append("#").append(semantic.viewId)
                    if (semantic.text.isNotBlank()) append(":").append(semantic.text.take(24))
                    append("@(")
                    append(semantic.bounds.centerX())
                    append(",")
                    append(semantic.bounds.centerY())
                    append(")")
                }
            }
            .distinct()
            .take(8)
            .joinToString(" | ")
        return NodeSemanticSummary(
            nodes = nodes,
            visibleNodeSummary = visibleNodeSummary,
            viewIdSummary = viewIdSummary,
            clickableTargetSummary = clickableTargetSummary
        )
    }

    private fun traverseNodeTree(node: AccessibilityNodeInfo, sink: MutableList<NodeSemantic>) {
        if (sink.size >= maxSemanticNodes) {
            return
        }

        val directText = firstNonBlank(
            node.text?.toString(),
            node.contentDescription?.toString()
        )
        val text = if (node.isClickable && directText.isBlank()) {
            firstNonBlank(
                directText,
                collectDescendantText(node, maxDepth = 2)
            )
        } else {
            directText
        }
        val viewId = node.viewIdResourceName.orEmpty()
        val className = node.className?.toString().orEmpty()
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (text.isNotBlank() || viewId.isNotBlank() || node.isClickable) {
            sink += NodeSemantic(
                text = text,
                viewId = viewId,
                className = className,
                clickable = node.isClickable,
                enabled = node.isEnabled,
                bounds = bounds
            )
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                traverseNodeTree(child, sink)
            } finally {
                child.recycle()
            }
            if (sink.size >= maxSemanticNodes) {
                return
            }
        }
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    }

    private fun collectDescendantText(node: AccessibilityNodeInfo, maxDepth: Int): String {
        if (maxDepth <= 0) {
            return ""
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                val direct = firstNonBlank(
                    child.text?.toString(),
                    child.contentDescription?.toString()
                )
                if (direct.isNotBlank()) {
                    return direct
                }
                val nested = collectDescendantText(child, maxDepth - 1)
                if (nested.isNotBlank()) {
                    return nested
                }
            } finally {
                child.recycle()
            }
        }
        return ""
    }

    private fun resolveTapTarget(targetText: String, targetViewId: String): ResolvedTapTarget? {
        val targetTextLower = targetText.trim().lowercase(Locale.ROOT)
        val targetViewIdLower = targetViewId.trim().lowercase(Locale.ROOT)

        val clickableNodes = collectClickableNodesFromSnapshot()
        val matchedNode = clickableNodes.firstOrNull { node ->
            val textMatched = targetTextLower.isBlank() || node.text.lowercase(Locale.ROOT).contains(targetTextLower)
            val viewIdMatched = targetViewIdLower.isBlank() || node.viewId.lowercase(Locale.ROOT).contains(targetViewIdLower)
            textMatched && viewIdMatched
        } ?: return null

        return ResolvedTapTarget(
            label = buildString {
                append(matchedNode.className.ifBlank { "node" })
                if (matchedNode.viewId.isNotBlank()) append("#").append(matchedNode.viewId)
                if (matchedNode.text.isNotBlank()) append(":").append(matchedNode.text.take(32))
            },
            x = matchedNode.bounds.centerX(),
            y = matchedNode.bounds.centerY()
        )
    }

    private fun collectClickableNodesFromSnapshot(): List<NodeSemantic> {
        return latestNodeSemantics.filter { it.clickable && it.enabled }
    }

    private fun updateResolvedTapTarget(target: ResolvedTapTarget) {
        _accessibilitySnapshot.value = _accessibilitySnapshot.value.copy(
            lastResolvedTapLabel = target.label,
            lastResolvedTapX = target.x,
            lastResolvedTapY = target.y,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }
}

private data class NodeSemanticSummary(
    val nodes: List<NodeSemantic>,
    val visibleNodeSummary: String,
    val viewIdSummary: String,
    val clickableTargetSummary: String
)
