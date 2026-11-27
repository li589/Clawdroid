package com.clawdroid.app.ui.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clawdroid.app.agent.task.ExecutionTask
import com.clawdroid.app.agent.task.TaskState
import com.clawdroid.app.agent.task.TaskStateMachine
import com.clawdroid.app.ui.ModernCard
import com.clawdroid.app.ui.SectionTitle
import com.clawdroid.app.ui.StatusChip

@Composable
internal fun TaskCenterScreen(
    state: TaskCenterUiState,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("任务中心", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "统一查看执行进度、恢复任务与失败诊断",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item { SectionTitle("活动任务") }
        if (state.activeTasks.isEmpty()) {
            item { EmptyTasks(label = "暂无活动任务") }
        } else {
            items(state.activeTasks, key = { it.id }) { task ->
                TaskCenterItem(
                    task = task,
                    expanded = state.selectedTaskId == task.id,
                    onPause = onPause,
                    onResume = onResume,
                    onRetry = onRetry,
                    onCancel = onCancel,
                    onSelect = onSelect
                )
            }
        }
        item { SectionTitle("历史任务") }
        if (state.history.isEmpty()) {
            item { EmptyTasks(label = "暂无历史任务") }
        } else {
            items(state.history, key = { it.id }) { task ->
                TaskCenterItem(
                    task = task,
                    expanded = state.selectedTaskId == task.id,
                    onPause = onPause,
                    onResume = onResume,
                    onRetry = onRetry,
                    onCancel = onCancel,
                    onSelect = onSelect
                )
            }
        }
    }
}

@Composable
private fun TaskCenterItem(
    task: ExecutionTask,
    expanded: Boolean,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(task.title, style = MaterialTheme.typography.titleMedium)
                StatusChip(label = taskStateLabel(task.state))
            }
            Text(
                task.originPrompt.ifBlank { task.summary() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (task.steps.isNotEmpty()) {
                val completed = task.checkpoint.completedStepIds.size.coerceAtMost(task.steps.size)
                LinearProgressIndicator(
                    progress = { completed.toFloat() / task.steps.size },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "步骤 $completed/${task.steps.size} · 尝试 ${task.attempt}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            task.failure?.let { failure ->
                Text(
                    "${failure.code} · ${failure.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (expanded) {
                SectionTitle("步骤时间线")
                task.steps.forEach { step ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(step.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        StatusChip(label = stepStateLabel(task, step.id))
                    }
                }
                task.checkpoint.runtimeTaskId?.takeIf { it.isNotBlank() }?.let {
                    Text("Runtime 任务：$it", style = MaterialTheme.typography.bodySmall)
                }
                task.retryOf?.takeIf { it.isNotBlank() }?.let {
                    Text("重试自：$it", style = MaterialTheme.typography.bodySmall)
                }
                task.failure?.detail?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!TaskStateMachine.isTerminal(task.state)) {
                    if (task.state == TaskState.Paused) {
                        AssistChip(onClick = { onResume(task.id) }, label = { Text("恢复") })
                    } else if (task.state == TaskState.WaitingSignal &&
                        task.checkpoint.waitingReason == "resume_confirmation_required"
                    ) {
                        AssistChip(onClick = { onResume(task.id) }, label = { Text("确认继续") })
                    } else {
                        AssistChip(onClick = { onPause(task.id) }, label = { Text("暂停") })
                    }
                    AssistChip(onClick = { onCancel(task.id) }, label = { Text("取消") })
                } else if (task.state == TaskState.Failed || task.state == TaskState.Cancelled) {
                    AssistChip(onClick = { onRetry(task.id) }, label = { Text("重试") })
                }
                AssistChip(
                    onClick = { onSelect(task.id) },
                    label = { Text(if (expanded) "收起" else "详情") }
                )
            }
        }
    }
}

@Composable
private fun EmptyTasks(label: String) {
    ModernCard {
        Text(
            label,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun ExecutionTask.summary(): String = checkpoint.waitingReason
    ?: failure?.summary
    ?: "等待执行"

private fun stepStateLabel(task: ExecutionTask, stepId: String): String = when {
    stepId in task.checkpoint.completedStepIds -> "已完成"
    task.checkpoint.nextStepIndex >= task.steps.size -> "待执行"
    task.steps[task.checkpoint.nextStepIndex].id != stepId -> "待执行"
    task.state == TaskState.Failed -> "失败"
    task.state == TaskState.Cancelled -> "已取消"
    else -> "执行中"
}

private fun taskStateLabel(state: TaskState): String = when (state) {
    TaskState.Created -> "已创建"
    TaskState.Queued -> "排队中"
    TaskState.Running -> "执行中"
    TaskState.WaitingSignal -> "等待中"
    TaskState.Retrying -> "重试中"
    TaskState.Paused -> "已暂停"
    TaskState.Succeeded -> "已成功"
    TaskState.Failed -> "已失败"
    TaskState.Cancelled -> "已取消"
    TaskState.Compensating -> "补偿中"
}
