package com.clawdroid.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clawdroid.app.ai.AgentRunEvent
import com.clawdroid.app.ai.AgentRunEventKind

@Composable
internal fun AgentActivityTimeline(
    events: List<AgentRunEvent>,
    expanded: Boolean,
    apiCallsRemaining: Int,
    awaitingBudgetContinue: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (events.isEmpty() && !awaitingBudgetContinue) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (expanded) {
                            "Agent 时间线"
                        } else {
                            "Agent 时间线 (${events.size})"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = if (awaitingBudgetContinue) {
                        "本轮预算耗尽"
                    } else {
                        "本轮 API 剩余 $apiCallsRemaining"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (awaitingBudgetContinue) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    events.takeLast(24).forEach { event ->
                        AgentTimelineRow(event)
                    }
                }
            }
            if (!expanded && events.isNotEmpty()) {
                val last = events.last()
                Text(
                    text = "${kindLabel(last.kind)} · ${last.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AgentTimelineRow(event: AgentRunEvent) {
    val statusIcon = when (event.success) {
        true -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        false -> Icons.Filled.Cancel to MaterialTheme.colorScheme.error
        null -> Icons.Filled.Circle to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val duration = event.durationMs?.let { " ${it}ms" }.orEmpty()
    val sub = event.subAgentName?.let { " [$it]" }.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = statusIcon.first,
                contentDescription = null,
                tint = statusIcon.second,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "${kindLabel(event.kind)} ${event.title}$sub$duration",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (event.detail.isNotBlank()) {
            Text(
                text = event.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp)
            )
        }
    }
}

private fun kindLabel(kind: AgentRunEventKind): String {
    return when (kind) {
        AgentRunEventKind.Thinking -> "思考"
        AgentRunEventKind.ToolCall -> "工具"
        AgentRunEventKind.ToolResult -> "结果"
        AgentRunEventKind.SubAgent -> "子代理"
        AgentRunEventKind.Budget -> "预算"
        AgentRunEventKind.Compress -> "压缩"
        AgentRunEventKind.SoftWarn -> "提示"
    }
}
