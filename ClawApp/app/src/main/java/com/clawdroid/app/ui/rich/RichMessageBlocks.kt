package com.clawdroid.app.ui.rich

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

@Composable
internal fun MarkdownSegment(
    markdown: String,
    contentColor: Color
) {
    val body = MaterialTheme.typography.bodyMedium
    val compactTitle = MaterialTheme.typography.titleSmall.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    )
    val compactSubtitle = MaterialTheme.typography.bodyLarge.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp
    )
    val compactH3 = body.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.5.sp,
        lineHeight = 18.sp
    )
    Markdown(
        content = markdown,
        colors = markdownColor(text = contentColor),
        typography = markdownTypography(
            h1 = compactTitle,
            h2 = compactSubtitle,
            h3 = compactH3,
            h4 = compactH3,
            h5 = body.copy(fontWeight = FontWeight.Medium),
            h6 = body.copy(fontWeight = FontWeight.Medium),
            text = body,
            paragraph = body,
            ordered = body,
            bullet = body,
            list = body,
            quote = body,
            code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )
    )
}

@Composable
internal fun CodeFenceBlock(
    block: RichBlock.Code,
    contentColor: Color
) {
    val context = LocalContext.current
    val annotated = remember(block.source, block.highlighted) {
        toAnnotatedString(block.highlighted ?: HighlightedCode(block.source, emptyList()), contentColor)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = block.language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f)
                )
                TextButton(onClick = { copyText(context, block.source) }) {
                    Text("复制", style = MaterialTheme.typography.labelSmall)
                }
            }
            SelectionContainer {
                Text(
                    text = annotated,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    ),
                    color = contentColor
                )
            }
        }
    }
}

@Composable
internal fun TableBlock(
    block: RichBlock.Table,
    contentColor: Color
) {
    val border = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val headerBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
    ) {
        if (block.headers.isNotEmpty()) {
            TableRowView(
                cells = block.headers,
                contentColor = contentColor,
                background = headerBg,
                bold = true,
                border = border
            )
        }
        block.rows.forEach { row ->
            TableRowView(
                cells = row,
                contentColor = contentColor,
                background = Color.Transparent,
                bold = false,
                border = border
            )
        }
    }
}

@Composable
private fun TableRowView(
    cells: List<String>,
    contentColor: Color,
    background: Color,
    bold: Boolean,
    border: Color
) {
    Row(
        modifier = Modifier
            .background(background)
            .padding(vertical = 1.dp)
    ) {
        cells.forEach { cell ->
            Box(
                modifier = Modifier
                    .padding(end = 1.dp)
                    .background(border.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = cell,
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    maxLines = 6
                )
            }
        }
    }
}

@Composable
internal fun PreviewableFence(
    title: String,
    source: String,
    previewMode: String?,
    defaultPreview: Boolean,
    contentColor: Color,
    darkTheme: Boolean,
    unsupportedHint: String? = null
) {
    var showPreview by remember(source, defaultPreview) { mutableStateOf(defaultPreview && previewMode != null) }
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.75f),
                    modifier = Modifier.padding(start = 4.dp)
                )
                Row {
                    if (previewMode != null) {
                        TextButton(onClick = { showPreview = false }) {
                            Text(
                                "源码",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (!showPreview) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                        TextButton(onClick = { showPreview = true }) {
                            Text(
                                "预览",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (showPreview) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                    TextButton(onClick = { copyText(context, source) }) {
                        Text("复制", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (showPreview && previewMode != null) {
                RichPreviewWebView(
                    mode = previewMode,
                    payload = source,
                    darkTheme = darkTheme,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                )
            } else {
                if (!showPreview && unsupportedHint != null && previewMode == null) {
                    Text(
                        text = unsupportedHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.65f),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                SelectionContainer {
                    Text(
                        text = source,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        color = contentColor
                    )
                }
            }
        }
    }
}

private fun toAnnotatedString(highlighted: HighlightedCode, base: Color) =
    buildAnnotatedString {
        append(highlighted.text)
        val keyword = base.copy(alpha = 0.95f).let { Color(0xFF7C4DFF) }
        val string = Color(0xFF2E7D32)
        val comment = base.copy(alpha = 0.55f)
        val number = Color(0xFF1565C0)
        val type = Color(0xFF00695C)
        highlighted.spans.forEach { span ->
            val color = when (span.kind) {
                CodeHighlightKind.Keyword -> keyword
                CodeHighlightKind.StringLiteral -> string
                CodeHighlightKind.Comment -> comment
                CodeHighlightKind.Number -> number
                CodeHighlightKind.Type -> type
            }
            addStyle(SpanStyle(color = color), span.start, span.end)
        }
    }

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("clawdroid", text))
}
