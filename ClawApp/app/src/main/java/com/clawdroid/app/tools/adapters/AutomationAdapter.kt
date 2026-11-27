package com.clawdroid.app.tools.adapters

import com.clawdroid.app.agent.task.ExecutionStep
import com.clawdroid.app.agent.task.StepKind
import com.clawdroid.app.agent.task.TaskFailure
import com.clawdroid.app.automation.ObservationPort
import com.clawdroid.app.automation.ObservationSnapshot
import com.clawdroid.app.automation.ObservationSource
import com.clawdroid.app.automation.ObservedBounds
import com.clawdroid.app.automation.TargetResolution
import com.clawdroid.app.automation.TargetResolver
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolDispatcher
import org.json.JSONObject

class AutomationAdapter(
    private val observationPort: ObservationPort,
    private val dispatcher: ClawToolDispatcher,
    private val resolver: TargetResolver = TargetResolver
) : TaskStepAdapter {
    override suspend fun execute(step: ExecutionStep, context: TaskExecutionContext): TaskStepResult {
        val payload = runCatching { JSONObject(step.payload) }.getOrDefault(JSONObject())
        val expectedPackage = payload.optString("expected_package")
        val snapshot = if (step.kind == StepKind.ConfirmPage) {
            observationPort.latest()
        } else {
            observationPort.current(expectedPackage)
        } ?: return failure("observation_unavailable", "没有可用的新鲜页面快照", expectedPackage)
        if (step.kind == StepKind.ConfirmPage && expectedPackage.isNotBlank() &&
            !snapshot.packageName.equals(expectedPackage, ignoreCase = true)
        ) {
            return failure(
                "package_mismatch",
                "页面包名不匹配: actual=${snapshot.packageName} expected=$expectedPackage",
                snapshot.packageName
            )
        }
        return when (step.kind) {
            StepKind.ObservePage -> TaskStepResult(
                success = true,
                summary = "source=${snapshot.source} package=${snapshot.packageName} generation=${snapshot.generation}"
            )
            StepKind.ConfirmPage -> confirmPage(snapshot, payload)
            StepKind.ResolveTarget -> resolveTarget(snapshot, payload)
            StepKind.SafeTap -> safeTap(snapshot, context)
            else -> failure("unsupported_automation_step", "不支持的自动化步骤: ${step.kind}", step.kind.name)
        }
    }

    private fun confirmPage(snapshot: ObservationSnapshot, payload: JSONObject): TaskStepResult {
        val expectedText = payload.optString("expected_text")
        val expectedViewId = payload.optString("expected_view_id")
        val combinedText = (snapshot.nodes.map { it.label } + snapshot.focusSummary + snapshot.viewSummary)
            .joinToString(" ")
        if (expectedText.isNotBlank() && !combinedText.contains(expectedText, ignoreCase = true)) {
            return failure("page_text_mismatch", "页面文本不匹配: $expectedText", snapshot.packageName)
        }
        if (expectedViewId.isNotBlank() && snapshot.nodes.none { it.viewId.equals(expectedViewId, ignoreCase = true) }) {
            return failure("view_id_mismatch", "页面视图 ID 不匹配: $expectedViewId", snapshot.packageName)
        }
        return TaskStepResult(true, "页面确认成功: ${snapshot.packageName}")
    }

    private fun resolveTarget(snapshot: ObservationSnapshot, payload: JSONObject): TaskStepResult {
        val resolution = resolver.resolve(
            snapshot = snapshot,
            targetText = payload.optString("target_text"),
            targetViewId = payload.optString("target_view_id")
        )
        return when (resolution) {
            is TargetResolution.NotFound -> failure("target_not_found", "未找到点击目标: ${resolution.query}", snapshot.packageName)
            is TargetResolution.Ambiguous -> failure(
                "target_ambiguous",
                "点击目标存在 ${resolution.candidates.size} 个候选",
                snapshot.packageName
            )
            is TargetResolution.Resolved -> TaskStepResult(
                success = true,
                summary = "已解析目标: ${resolution.label}",
                data = mapOf("target_resolution" to encodeResolution(resolution))
            )
        }
    }

    private suspend fun safeTap(snapshot: ObservationSnapshot, context: TaskExecutionContext): TaskStepResult {
        val raw = context.checkpoint.data["target_resolution"]
            ?: return failure("missing_resolution", "缺少目标解析令牌", snapshot.packageName)
        val resolution = decodeResolution(raw)
            ?: return failure("invalid_resolution", "目标解析令牌无效", snapshot.packageName)
        if (!resolver.validate(resolution, snapshot, observationPort.nowEpochMs())) {
            return failure("snapshot_stale", "页面快照已变化，需要重新解析目标", snapshot.packageName)
        }
        val result = dispatcher.execute(
            ClawTool.INJECT_TAP,
            mapOf(
                "x" to (resolution.bounds.left + resolution.bounds.right) / 2,
                "y" to (resolution.bounds.top + resolution.bounds.bottom) / 2,
                "display_id" to 0
            )
        )
        return if (result.success) {
            TaskStepResult(true, result.output)
        } else {
            failure(result.error ?: "injection_rejected", result.output, snapshot.packageName)
        }
    }

    private fun failure(code: String, summary: String, source: String): TaskStepResult = TaskStepResult(
        success = false,
        summary = summary,
        failure = TaskFailure(code = code, summary = summary, source = source)
    )

    private fun encodeResolution(resolution: TargetResolution.Resolved): String = JSONObject().apply {
        put("token", resolution.token)
        put("nodeKey", resolution.nodeKey)
        put("label", resolution.label)
        put("left", resolution.bounds.left)
        put("top", resolution.bounds.top)
        put("right", resolution.bounds.right)
        put("bottom", resolution.bounds.bottom)
        put("packageName", resolution.packageName)
        put("snapshotGeneration", resolution.snapshotGeneration)
        put("source", resolution.source.name)
        put("resolvedAtEpochMs", resolution.resolvedAtEpochMs)
    }.toString()

    private fun decodeResolution(raw: String): TargetResolution.Resolved? = runCatching {
        val json = JSONObject(raw)
        TargetResolution.Resolved(
            token = json.getString("token"),
            nodeKey = json.getString("nodeKey"),
            label = json.getString("label"),
            bounds = ObservedBounds(
                left = json.getInt("left"),
                top = json.getInt("top"),
                right = json.getInt("right"),
                bottom = json.getInt("bottom")
            ),
            packageName = json.getString("packageName"),
            snapshotGeneration = json.getLong("snapshotGeneration"),
            source = ObservationSource.valueOf(json.getString("source")),
            resolvedAtEpochMs = json.getLong("resolvedAtEpochMs")
        )
    }.getOrNull()
}
