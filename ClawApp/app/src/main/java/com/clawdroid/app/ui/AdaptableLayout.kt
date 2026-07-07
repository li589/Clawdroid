package com.clawdroid.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 响应式布局配置
 * 根据屏幕尺寸动态调整间距、内边距和组件大小
 */
internal data class AdaptableLayoutConfig(
    val screenWidth: ScreenWidthClass,
    // FlowRow 间距
    val flowRowHorizontalSpacing: Dp,
    val flowRowVerticalSpacing: Dp,
    // 卡片间距
    val cardPadding: Dp,
    val cardInnerSpacing: Dp,
    // SectionTitle 间距
    val sectionTitleTopPadding: Dp,
    val sectionTitleBottomPadding: Dp,
    // MetricInfoCard 尺寸
    val metricCardMinWidth: Dp,
    val metricCardMaxWidth: Dp,
    val heroInfoTileWeight: Float,
    // 聊天区域高度
    val chatAreaMinHeight: Dp,
    val chatAreaMaxHeight: Dp,
    // 输入框行数
    val textFieldMinLines: Int,
    val textFieldMaxLines: Int,
    // 面板高度
    val resultPanelMaxHeight: Dp,
    val outputPanelMaxHeight: Dp,
    // 底部导航栏
    val navBarPaddingH: Dp,
    val navBarPaddingV: Dp,
    val navBarSpacing: Dp,
    // 内边距 (通用)
    val contentPadding: PaddingValues
)

internal enum class ScreenWidthClass {
    Compact,    // 手机竖屏 (< 600dp)
    Medium,     // 手机横屏/小平板 (600-840dp)
    Expanded    // 大平板/桌面 (> 840dp)
}

@Composable
internal fun rememberAdaptableLayoutConfig(): AdaptableLayoutConfig {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val density = LocalDensity.current

    return remember(configuration) {
        val screenWidth = when {
            screenWidthDp < 600.dp -> ScreenWidthClass.Compact
            screenWidthDp < 840.dp -> ScreenWidthClass.Medium
            else -> ScreenWidthClass.Expanded
        }

        when (screenWidth) {
            ScreenWidthClass.Compact -> AdaptableLayoutConfig(
                screenWidth = ScreenWidthClass.Compact,
                flowRowHorizontalSpacing = 6.dp,
                flowRowVerticalSpacing = 6.dp,
                cardPadding = 14.dp,
                cardInnerSpacing = 10.dp,
                sectionTitleTopPadding = 16.dp,
                sectionTitleBottomPadding = 6.dp,
                metricCardMinWidth = 140.dp,
                metricCardMaxWidth = 180.dp,
                heroInfoTileWeight = 1f,
                chatAreaMinHeight = 240.dp,
                chatAreaMaxHeight = 360.dp,
                textFieldMinLines = 3,
                textFieldMaxLines = 5,
                resultPanelMaxHeight = 160.dp,
                outputPanelMaxHeight = 200.dp,
                navBarPaddingH = 12.dp,
                navBarPaddingV = 4.dp,
                navBarSpacing = 4.dp,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            )
            ScreenWidthClass.Medium -> AdaptableLayoutConfig(
                screenWidth = ScreenWidthClass.Medium,
                flowRowHorizontalSpacing = 8.dp,
                flowRowVerticalSpacing = 8.dp,
                cardPadding = 16.dp,
                cardInnerSpacing = 12.dp,
                sectionTitleTopPadding = 20.dp,
                sectionTitleBottomPadding = 8.dp,
                metricCardMinWidth = 160.dp,
                metricCardMaxWidth = 220.dp,
                heroInfoTileWeight = 1f,
                chatAreaMinHeight = 280.dp,
                chatAreaMaxHeight = 420.dp,
                textFieldMinLines = 3,
                textFieldMaxLines = 6,
                resultPanelMaxHeight = 200.dp,
                outputPanelMaxHeight = 280.dp,
                navBarPaddingH = 16.dp,
                navBarPaddingV = 6.dp,
                navBarSpacing = 6.dp,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            )
            ScreenWidthClass.Expanded -> AdaptableLayoutConfig(
                screenWidth = ScreenWidthClass.Expanded,
                flowRowHorizontalSpacing = 10.dp,
                flowRowVerticalSpacing = 10.dp,
                cardPadding = 20.dp,
                cardInnerSpacing = 14.dp,
                sectionTitleTopPadding = 24.dp,
                sectionTitleBottomPadding = 10.dp,
                metricCardMinWidth = 180.dp,
                metricCardMaxWidth = 260.dp,
                heroInfoTileWeight = 1f,
                chatAreaMinHeight = 320.dp,
                chatAreaMaxHeight = 500.dp,
                textFieldMinLines = 4,
                textFieldMaxLines = 8,
                resultPanelMaxHeight = 240.dp,
                outputPanelMaxHeight = 320.dp,
                navBarPaddingH = 20.dp,
                navBarPaddingV = 8.dp,
                navBarSpacing = 8.dp,
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp)
            )
        }
    }
}

/**
 * 获取 FlowRow 水平间距
 */
@Composable
internal fun flowRowHSpacing(): Dp = rememberAdaptableLayoutConfig().flowRowHorizontalSpacing

/**
 * 获取 FlowRow 垂直间距
 */
@Composable
internal fun flowRowVSpacing(): Dp = rememberAdaptableLayoutConfig().flowRowVerticalSpacing

/**
 * 获取卡片内边距
 */
@Composable
internal fun cardContentPadding(): Dp = rememberAdaptableLayoutConfig().cardPadding

/**
 * 获取卡片内部元素间距
 */
@Composable
internal fun cardInnerSpacing(): Dp = rememberAdaptableLayoutConfig().cardInnerSpacing

/**
 * 获取 MetricInfoCard 最小宽度
 */
@Composable
internal fun metricCardMinWidth(): Dp = rememberAdaptableLayoutConfig().metricCardMinWidth

/**
 * 获取 MetricInfoCard 最大宽度
 */
@Composable
internal fun metricCardMaxWidth(): Dp = rememberAdaptableLayoutConfig().metricCardMaxWidth
