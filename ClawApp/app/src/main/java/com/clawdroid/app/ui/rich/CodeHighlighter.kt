package com.clawdroid.app.ui.rich

enum class CodeHighlightKind {
    Keyword,
    StringLiteral,
    Comment,
    Number,
    Type
}

data class CodeHighlightSpan(
    val start: Int,
    val end: Int,
    val kind: CodeHighlightKind
)

data class HighlightedCode(
    val text: String,
    val spans: List<CodeHighlightSpan>
)

/**
 * Lightweight regex highlighter for common languages (off main-thread safe).
 */
object CodeHighlighter {
    private val keywordsByLang: Map<String, Set<String>> = mapOf(
        "kotlin" to setOf(
            "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for",
            "while", "return", "null", "true", "false", "is", "in", "as", "import", "package",
            "suspend", "override", "private", "public", "internal", "data", "sealed", "enum"
        ),
        "java" to setOf(
            "class", "interface", "public", "private", "protected", "static", "final", "void",
            "return", "new", "if", "else", "for", "while", "import", "package", "true", "false", "null"
        ),
        "python" to setOf(
            "def", "class", "return", "if", "elif", "else", "for", "while", "import", "from",
            "True", "False", "None", "with", "as", "try", "except", "yield", "async", "await"
        ),
        "javascript" to setOf(
            "function", "const", "let", "var", "return", "if", "else", "for", "while", "class",
            "import", "export", "from", "async", "await", "true", "false", "null", "undefined"
        ),
        "typescript" to setOf(
            "function", "const", "let", "var", "return", "if", "else", "for", "while", "class",
            "import", "export", "from", "async", "await", "true", "false", "null", "type", "interface"
        ),
        "go" to setOf(
            "func", "package", "import", "return", "if", "else", "for", "range", "var", "const",
            "type", "struct", "interface", "true", "false", "nil", "go", "defer"
        ),
        "sql" to setOf(
            "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "JOIN", "ON", "AND", "OR",
            "NULL", "AS", "ORDER", "BY", "GROUP", "LIMIT"
        )
    )

    fun highlight(language: String, source: String): HighlightedCode {
        val lang = normalizeLang(language)
        val spans = ArrayList<CodeHighlightSpan>()
        addRegexSpans(spans, source, Regex("//.*?$", RegexOption.MULTILINE), CodeHighlightKind.Comment)
        addRegexSpans(spans, source, Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), CodeHighlightKind.Comment)
        addRegexSpans(spans, source, Regex("#.*?$", RegexOption.MULTILINE), CodeHighlightKind.Comment)
        addRegexSpans(spans, source, Regex("\"([^\"\\\\]|\\\\.)*\""), CodeHighlightKind.StringLiteral)
        addRegexSpans(spans, source, Regex("'([^'\\\\]|\\\\.)*'"), CodeHighlightKind.StringLiteral)
        addRegexSpans(spans, source, Regex("\\b\\d+(?:\\.\\d+)?\\b"), CodeHighlightKind.Number)

        val keywords = keywordsByLang[lang].orEmpty() + keywordsByLang["javascript"].orEmpty().takeIf {
            lang in setOf("js", "ts", "tsx", "jsx")
        }.orEmpty()
        if (keywords.isNotEmpty()) {
            val pattern = Regex("\\b(${keywords.joinToString("|") { Regex.escape(it) }})\\b")
            addRegexSpans(spans, source, pattern, CodeHighlightKind.Keyword)
        }
        addRegexSpans(
            spans,
            source,
            Regex("\\b[A-Z][A-Za-z0-9_]*\\b"),
            CodeHighlightKind.Type
        )

        val merged = spans
            .filter { it.start < it.end && it.start >= 0 && it.end <= source.length }
            .sortedWith(compareBy<CodeHighlightSpan> { it.start }.thenByDescending { it.end - it.start })
            .let { nonOverlapping(it) }

        return HighlightedCode(text = source, spans = merged)
    }

    private fun normalizeLang(language: String): String {
        return when (language.lowercase()) {
            "kt", "kts" -> "kotlin"
            "js", "jsx", "mjs" -> "javascript"
            "ts", "tsx" -> "typescript"
            "py" -> "python"
            "sh", "bash", "shell", "zsh" -> "shell"
            "json", "jsonc" -> "json"
            else -> language.lowercase()
        }
    }

    private fun addRegexSpans(
        out: MutableList<CodeHighlightSpan>,
        source: String,
        regex: Regex,
        kind: CodeHighlightKind
    ) {
        regex.findAll(source).forEach { match ->
            out += CodeHighlightSpan(match.range.first, match.range.last + 1, kind)
        }
    }

    private fun nonOverlapping(spans: List<CodeHighlightSpan>): List<CodeHighlightSpan> {
        if (spans.isEmpty()) return spans
        val result = ArrayList<CodeHighlightSpan>()
        var lastEnd = -1
        for (span in spans) {
            if (span.start >= lastEnd) {
                result += span
                lastEnd = span.end
            }
        }
        return result
    }
}
