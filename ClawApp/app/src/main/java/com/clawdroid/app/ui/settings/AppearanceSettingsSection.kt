package com.clawdroid.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.clawdroid.app.ui.ModernCard
import com.clawdroid.app.ui.ResultPanel
import com.clawdroid.app.ui.SectionTitle
import com.clawdroid.app.ui.SettingsCategoryId
import com.clawdroid.app.ui.ThemeMode
import com.clawdroid.app.ui.responsiveCardInnerSpacing
import com.clawdroid.app.ui.responsiveCardPadding
import com.clawdroid.app.ui.responsiveFlowHSpacing
import com.clawdroid.app.ui.responsiveFlowVSpacing

internal fun LazyListScope.appearanceSettingsSection(
    categoryId: SettingsCategoryId,
    currentThemeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit
) {
    item { SectionTitle(categoryId.title) }
    item {
        ThemeSettingsCard(
            currentThemeMode = currentThemeMode,
            onThemeModeSelected = onThemeModeSelected
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeSettingsCard(
    currentThemeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit
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
            Text(text = "外观主题", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "当前模式: ${themeModeLabel(currentThemeMode)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(hSpacing),
                verticalArrangement = Arrangement.spacedBy(vSpacing)
            ) {
                ThemeMode.entries.forEach { mode ->
                    AssistChip(
                        onClick = { onThemeModeSelected(mode) },
                        label = { Text(themeModeLabel(mode)) }
                    )
                }
            }
            ResultPanel(text = "主题偏好已本地保存，重启后仍会保留。")
        }
    }
}
