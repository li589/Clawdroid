package com.clawdroid.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clawdroid.app.tools.ClawAssetPromptStore
import com.clawdroid.app.tools.ClawTextPackEntry
import com.clawdroid.app.tools.ClawTextPackKind
import com.clawdroid.app.ui.ModernCard
import com.clawdroid.app.ui.SectionTitle
import com.clawdroid.app.ui.SettingsCategoryId
import com.clawdroid.app.ui.responsiveCardInnerSpacing
import com.clawdroid.app.ui.responsiveCardPadding

internal fun LazyListScope.promptsSkillsSettingsSection(categoryId: SettingsCategoryId) {
    item { SectionTitle(categoryId.title) }
    item { PromptPackBrowserCard() }
}

@Composable
private fun PromptPackBrowserCard() {
    val context = LocalContext.current
    val packs = remember { ClawAssetPromptStore.listPackEntries() }
    var selected by remember { mutableStateOf<ClawTextPackEntry?>(null) }
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()

    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "内置文本包", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "统一存放于 assets/claw/：编排提示、Skill、Agent 能力说明与辅助短句。点选可预览；修改 APK 内文件后重装生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            packs.forEach { entry ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selected = entry },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            text = "${kindLabel(entry.kind)} · ${entry.title}",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = entry.assetPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        val body = remember(entry.id) {
            ClawAssetPromptStore.readPackBody(context, entry).ifBlank { "（文件缺失或为空，将使用代码内回退文案）" }
        }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(entry.title) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = entry.assetPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    if (entry.assetPath.endsWith(".md") || entry.assetPath.endsWith(".txt")) {
                        com.clawdroid.app.ui.rich.RichMessageContent(
                            content = body,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text("关闭") }
            }
        )
    }
}

private fun kindLabel(kind: ClawTextPackKind): String = when (kind) {
    ClawTextPackKind.Index -> "索引"
    ClawTextPackKind.Prompt -> "提示词"
    ClawTextPackKind.Helper -> "辅助"
    ClawTextPackKind.Agent -> "Agent"
    ClawTextPackKind.Skill -> "Skill"
    ClawTextPackKind.ToolOverlay -> "工具"
}