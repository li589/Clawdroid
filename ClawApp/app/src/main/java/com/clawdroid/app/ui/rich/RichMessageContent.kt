package com.clawdroid.app.ui.rich

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Renders assistant markdown with code / table / math / mermaid / svg blocks.
 */
@Composable
internal fun RichMessageContent(
    content: String,
    contentColor: Color,
    streaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    var blocks by remember(content) { mutableStateOf<List<RichBlock>?>(RichParseCache.get(content)) }

    LaunchedEffect(content, streaming) {
        if (content.isBlank()) {
            blocks = emptyList()
            return@LaunchedEffect
        }
        val cached = RichParseCache.get(content)
        if (cached != null) {
            blocks = cached
            return@LaunchedEffect
        }
        if (streaming) {
            // Debounce while assistant status text is thrashing.
            delay(120)
        }
        blocks = RichMessageParser.parseAsync(content)
    }

    val resolved = blocks
    if (resolved == null) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = modifier.fillMaxWidth()
        )
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        resolved.forEachIndexed { index, block ->
            androidx.compose.runtime.key(index, block::class, blockContentKey(block)) {
                when (block) {
                    is RichBlock.Markdown -> MarkdownSegment(block.markdown, contentColor)
                    is RichBlock.Code -> CodeFenceBlock(block, contentColor)
                    is RichBlock.Table -> TableBlock(block, contentColor)
                    is RichBlock.Math -> PreviewableFence(
                        title = if (block.display) "公式" else "行内公式",
                        source = block.latex,
                        previewMode = "katex",
                        defaultPreview = true,
                        contentColor = contentColor,
                        darkTheme = darkTheme
                    )
                    is RichBlock.Mermaid -> PreviewableFence(
                        title = "Mermaid",
                        source = block.source,
                        previewMode = "mermaid",
                        defaultPreview = true,
                        contentColor = contentColor,
                        darkTheme = darkTheme
                    )
                    is RichBlock.Svg -> PreviewableFence(
                        title = "SVG",
                        source = block.source,
                        previewMode = "svg",
                        defaultPreview = true,
                        contentColor = contentColor,
                        darkTheme = darkTheme
                    )
                    is RichBlock.Drawio -> PreviewableFence(
                        title = "Draw.io",
                        source = block.source,
                        previewMode = if (block.previewAsSvg) "svg" else null,
                        defaultPreview = block.previewAsSvg,
                        contentColor = contentColor,
                        darkTheme = darkTheme,
                        unsupportedHint = "当前仅支持 SVG 形式的 drawio 预览；其它源码可复制后在 diagrams.net 打开。"
                    )
                    RichBlock.ThematicBreak -> HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = contentColor.copy(alpha = 0.25f)
                    )
                }
            }
        }
    }
}

private fun blockContentKey(block: RichBlock): Any = when (block) {
    is RichBlock.Markdown -> block.markdown.hashCode()
    is RichBlock.Code -> block.source.hashCode()
    is RichBlock.Table -> block.headers.hashCode() xor block.rows.hashCode()
    is RichBlock.Math -> block.latex.hashCode()
    is RichBlock.Mermaid -> block.source.hashCode()
    is RichBlock.Svg -> block.source.hashCode()
    is RichBlock.Drawio -> block.source.hashCode()
    RichBlock.ThematicBreak -> 0
}
