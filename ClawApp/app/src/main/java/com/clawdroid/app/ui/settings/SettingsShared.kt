package com.clawdroid.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clawdroid.app.ui.ModernCard
import com.clawdroid.app.ui.ModelInputSanitizer
import com.clawdroid.app.data.model.ModelSettings
import com.clawdroid.app.ui.ResultPanel
import com.clawdroid.app.ui.SettingsCategoryId
import com.clawdroid.app.ui.StatusChip
import com.clawdroid.app.data.model.ThemeMode
import com.clawdroid.app.data.model.UrlPathMode
import com.clawdroid.app.data.model.modelProviderLabel
import com.clawdroid.app.ui.responsiveCardInnerSpacing
import com.clawdroid.app.ui.responsiveCardPadding
import com.clawdroid.app.ui.responsiveFlowHSpacing
import com.clawdroid.app.ui.responsiveFlowVSpacing

@Composable
internal fun focusCommitModifier(onCommit: () -> Unit): Modifier {
    var hadFocus by remember { mutableStateOf(false) }
    return Modifier.onFocusChanged { state ->
        if (hadFocus && !state.isFocused) {
            onCommit()
        }
        hadFocus = state.isFocused
    }
}

@Composable
internal fun SliderParameter(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueDisplay: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(text = valueDisplay, style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
    }
}

internal fun themeModeLabel(mode: ThemeMode): String {
    return when (mode) {
        ThemeMode.FollowSystem -> "跟随系统"
        ThemeMode.Dark -> "深色"
        ThemeMode.Light -> "浅色"
    }
}

internal fun modelSettingsValidationMessage(settings: ModelSettings): String? {
    return ModelInputSanitizer.validationError(settings)
}

internal fun urlPathModeLabel(mode: UrlPathMode): String {
    return when (mode) {
        UrlPathMode.AutoAppend -> "自动补全"
        UrlPathMode.FullUrl -> "完整 URL"
        UrlPathMode.AppendCustom -> "自定义路径"
    }
}

internal fun urlPathModeHint(mode: UrlPathMode): String {
    return when (mode) {
        UrlPathMode.AutoAppend -> "聊天追加 /chat/completions 或 /messages；拉列表用 /models"
        UrlPathMode.FullUrl -> "填完整聊天 URL；拉列表时自动改写为 /models"
        UrlPathMode.AppendCustom -> "追加下方自定义路径；拉列表仍走 /models"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryChipRow(
    title: String,
    values: List<String>,
    labelFor: (String) -> String = { it },
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            values.forEach { value ->
                AssistChip(
                    onClick = { onSelect(value) },
                    label = {
                        Text(
                            text = labelFor(value),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsReadinessCard(
    currentThemeMode: ThemeMode,
    modelSettings: ModelSettings,
    connectionSummary: String,
    validationMessage: String?
) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    val hSpacing = responsiveFlowHSpacing()
    val vSpacing = responsiveFlowVSpacing()
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "设置总览", style = MaterialTheme.typography.titleLarge)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(hSpacing),
                verticalArrangement = Arrangement.spacedBy(vSpacing)
            ) {
                StatusChip("主题 ${themeModeLabel(currentThemeMode)}")
                StatusChip("模型 ${modelProviderLabel(modelSettings.provider)}")
                StatusChip(
                    if (validationMessage == null) "配置就绪" else "配置待完善: ${validationMessage.take(12)}"
                )
            }
            ResultPanel(
                text = buildString {
                    append("连接摘要: ")
                    append(connectionSummary)
                    append('\n')
                    append("模型配置: ")
                    append(validationMessage ?: "字段完整，可直接测试连接或用于聊天")
                }
            )
        }
    }
}

@Composable
internal fun SettingsCategoryHubCard(
    onOpenCategory: (SettingsCategoryId) -> Unit
) {
    val pad = responsiveCardPadding()
    val innerSpacing = responsiveCardInnerSpacing()
    ModernCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(innerSpacing)
        ) {
            Text(text = "选择设置项", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "按类别浏览模型接入、Agent 工具、Termux 与诊断等配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingsCategoryId.entries.forEach { category ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenCategory(category) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = category.title,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = category.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
