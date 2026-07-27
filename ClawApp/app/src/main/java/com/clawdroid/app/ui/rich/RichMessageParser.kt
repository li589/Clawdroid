package com.clawdroid.app.ui.rich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.tables.TableBlock as CmTableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.renderer.text.TextContentRenderer

/**
 * Parses AI markdown into [RichBlock]s on a background dispatcher.
 */
object RichMessageParser {
    private val fenceRegex = Regex(
        pattern = "(?ms)^[ \\t]*(```|~~~)[ \\t]*([^\\n`]*)\\n(.*?)\\n[ \\t]*\\1[ \\t]*$",
        option = RegexOption.MULTILINE
    )
    private val displayMathRegex = Regex("(?s)\\$\\$(.+?)\\$\\$")
    private val inlineMathRegex = Regex("(?<!\\$)\\$([^\\$\\n]+?)\\$(?!\\$)")

    private val extensions = listOf(TablesExtension.create())
    private val markdownParser: Parser = Parser.builder()
        .extensions(extensions)
        .build()
    private val textRenderer: TextContentRenderer = TextContentRenderer.builder().build()

    suspend fun parseAsync(content: String): List<RichBlock> = withContext(Dispatchers.Default) {
        parse(content)
    }

    fun parse(content: String): List<RichBlock> {
        if (content.isBlank()) return emptyList()
        RichParseCache.get(content)?.let { return it }

        val blocks = ArrayList<RichBlock>()
        var last = 0
        for (match in fenceRegex.findAll(content)) {
            val before = content.substring(last, match.range.first)
            if (before.isNotBlank()) {
                blocks += parseMarkdownSegment(before)
            }
            val lang = match.groupValues.getOrNull(2).orEmpty().trim().lowercase().substringBefore(' ')
            val body = match.groupValues.getOrNull(3).orEmpty().trimEnd('\n')
            blocks += fenceToBlock(lang, body)
            last = match.range.last + 1
        }
        val tail = content.substring(last)
        if (tail.isNotBlank()) {
            blocks += parseMarkdownSegment(tail)
        }

        val compacted = compactMarkdown(blocks)
        RichParseCache.put(content, compacted)
        return compacted
    }

    private fun fenceToBlock(lang: String, body: String): RichBlock {
        return when {
            lang in setOf("math", "latex", "tex") -> RichBlock.Math(body.trim(), display = true)
            lang == "mermaid" -> RichBlock.Mermaid(body)
            lang == "svg" -> RichBlock.Svg(body)
            lang in setOf("drawio", "diagrams.net", "diagrams", "mxfile") -> {
                val trimmed = body.trim()
                RichBlock.Drawio(
                    source = body,
                    previewAsSvg = trimmed.startsWith("<svg", ignoreCase = true)
                )
            }
            else -> RichBlock.Code(
                language = lang.ifBlank { "text" },
                source = body,
                highlighted = CodeHighlighter.highlight(lang, body)
            )
        }
    }

    private fun parseMarkdownSegment(segment: String): List<RichBlock> {
        val out = ArrayList<RichBlock>()
        var last = 0
        for (match in displayMathRegex.findAll(segment)) {
            val before = segment.substring(last, match.range.first)
            if (before.isNotBlank()) {
                out += parseCommonMarkChunk(before)
            }
            out += RichBlock.Math(match.groupValues[1].trim(), display = true)
            last = match.range.last + 1
        }
        val tail = segment.substring(last)
        if (tail.isNotBlank()) {
            out += parseCommonMarkChunk(tail)
        }
        return out
    }

    private fun parseCommonMarkChunk(chunk: String): List<RichBlock> {
        val trimmed = chunk.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Split inline math into markdown + math blocks while preserving order loosely.
        if (inlineMathRegex.containsMatchIn(trimmed) && !trimmed.contains("```")) {
            return splitInlineMath(trimmed)
        }

        val document = markdownParser.parse(trimmed)
        val blocks = ArrayList<RichBlock>()
        var markdownBuf = StringBuilder()

        fun flushMarkdown() {
            val md = markdownBuf.toString().trim()
            if (md.isNotEmpty()) {
                blocks += RichBlock.Markdown(md)
            }
            markdownBuf = StringBuilder()
        }

        var child = document.firstChild
        while (child != null) {
            when (child) {
                is CmTableBlock -> {
                    flushMarkdown()
                    blocks += tableFromNode(child)
                }
                is FencedCodeBlock -> {
                    flushMarkdown()
                    val lang = child.info.orEmpty().trim().lowercase().substringBefore(' ')
                    blocks += fenceToBlock(lang, child.literal.orEmpty().trimEnd('\n'))
                }
                is ThematicBreak -> {
                    flushMarkdown()
                    blocks += RichBlock.ThematicBreak
                }
                else -> {
                    // Keep original-ish markdown for mikepenz by reconstructing simple forms.
                    markdownBuf.append(nodeToMarkdown(child)).append('\n')
                }
            }
            child = child.next
        }
        flushMarkdown()
        return blocks.ifEmpty {
            listOf(RichBlock.Markdown(trimmed))
        }
    }

    private fun splitInlineMath(text: String): List<RichBlock> {
        val out = ArrayList<RichBlock>()
        var last = 0
        for (match in inlineMathRegex.findAll(text)) {
            val before = text.substring(last, match.range.first)
            if (before.isNotBlank()) {
                out += parseCommonMarkChunkWithoutInlineMath(before)
            }
            out += RichBlock.Math(match.groupValues[1].trim(), display = false)
            last = match.range.last + 1
        }
        val tail = text.substring(last)
        if (tail.isNotBlank()) {
            out += parseCommonMarkChunkWithoutInlineMath(tail)
        }
        return out
    }

    private fun parseCommonMarkChunkWithoutInlineMath(chunk: String): List<RichBlock> {
        val document = markdownParser.parse(chunk.trim())
        val blocks = ArrayList<RichBlock>()
        var markdownBuf = StringBuilder()
        fun flushMarkdown() {
            val md = markdownBuf.toString().trim()
            if (md.isNotEmpty()) blocks += RichBlock.Markdown(md)
            markdownBuf = StringBuilder()
        }
        var child = document.firstChild
        while (child != null) {
            when (child) {
                is CmTableBlock -> {
                    flushMarkdown()
                    blocks += tableFromNode(child)
                }
                is ThematicBreak -> {
                    flushMarkdown()
                    blocks += RichBlock.ThematicBreak
                }
                else -> markdownBuf.append(nodeToMarkdown(child)).append('\n')
            }
            child = child.next
        }
        flushMarkdown()
        return blocks.ifEmpty { listOf(RichBlock.Markdown(chunk.trim())) }
    }

    private fun tableFromNode(table: CmTableBlock): RichBlock.Table {
        val headers = ArrayList<String>()
        val rows = ArrayList<List<String>>()
        var node: Node? = table.firstChild
        while (node != null) {
            when (node) {
                is TableHead -> {
                    var row = node.firstChild
                    while (row != null) {
                        if (row is TableRow) {
                            headers += rowCells(row)
                        }
                        row = row.next
                    }
                }
                is TableBody -> {
                    var row = node.firstChild
                    while (row != null) {
                        if (row is TableRow) {
                            rows += rowCells(row)
                        }
                        row = row.next
                    }
                }
            }
            node = node.next
        }
        return RichBlock.Table(headers = headers, rows = rows)
    }

    private fun rowCells(row: TableRow): List<String> {
        val cells = ArrayList<String>()
        var cell = row.firstChild
        while (cell != null) {
            if (cell is TableCell) {
                val text = collectText(cell).ifBlank {
                    textRenderer.render(cell).trim()
                }
                cells += text
            }
            cell = cell.next
        }
        return cells
    }

    private fun nodeToMarkdown(node: Node): String {
        return when (node) {
            is Heading -> {
                val level = node.level.coerceIn(1, 6)
                "#".repeat(level) + " " + collectText(node)
            }
            is Paragraph -> collectText(node)
            else -> {
                // Fallback: text content; mikepenz still gets a usable chunk.
                textRenderer.render(node).trim()
            }
        }
    }

    private fun collectText(node: Node): String {
        val sb = StringBuilder()
        node.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                sb.append(text.literal)
            }
        })
        // Preserve basic emphasis markers if already in source is hard; use rendered text.
        val rendered = textRenderer.render(node).trim()
        return rendered.ifBlank { sb.toString().trim() }
    }

    private fun compactMarkdown(blocks: List<RichBlock>): List<RichBlock> {
        if (blocks.isEmpty()) return blocks
        val out = ArrayList<RichBlock>(blocks.size)
        val md = StringBuilder()
        fun flush() {
            val text = md.toString().trim()
            if (text.isNotEmpty()) out += RichBlock.Markdown(text)
            md.clear()
        }
        for (block in blocks) {
            if (block is RichBlock.Markdown) {
                if (md.isNotEmpty()) md.append("\n\n")
                md.append(block.markdown)
            } else {
                flush()
                out += block
            }
        }
        flush()
        return out
    }
}
