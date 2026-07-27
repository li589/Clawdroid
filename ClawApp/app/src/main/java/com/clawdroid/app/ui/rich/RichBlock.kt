package com.clawdroid.app.ui.rich

/**
 * Parsed rich-message blocks consumed by Compose / WebView renderers.
 */
sealed class RichBlock {
    data class Markdown(val markdown: String) : RichBlock()

    data class Code(
        val language: String,
        val source: String,
        val highlighted: HighlightedCode? = null
    ) : RichBlock()

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>
    ) : RichBlock()

    data class Math(
        val latex: String,
        val display: Boolean
    ) : RichBlock()

    data class Mermaid(val source: String) : RichBlock()

    data class Svg(val source: String) : RichBlock()

    data class Drawio(
        val source: String,
        val previewAsSvg: Boolean
    ) : RichBlock()

    data object ThematicBreak : RichBlock()
}

enum class PreviewFenceKind {
    Math,
    Mermaid,
    Svg,
    Drawio
}
