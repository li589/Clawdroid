package com.clawdroid.app.ui.rich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RichMessageParserTest {
    @Before
    fun clearCache() {
        RichParseCache.clear()
    }

    @Test
    fun parsesGfmTable() {
        val md = """
            | Name | Value |
            | --- | --- |
            | a | 1 |
            | b | 2 |
        """.trimIndent()
        val blocks = RichMessageParser.parse(md)
        val table = blocks.filterIsInstance<RichBlock.Table>().single()
        assertEquals(listOf("Name", "Value"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals("a", table.rows[0][0])
    }

    @Test
    fun routesFenceLanguages() {
        val md = """
            ```kotlin
            fun main() {}
            ```

            ```mermaid
            graph TD; A-->B;
            ```

            ```svg
            <svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"/>
            ```

            ```drawio
            <mxfile><diagram/></mxfile>
            ```

            ```math
            E=mc^2
            ```
        """.trimIndent()
        val blocks = RichMessageParser.parse(md)
        assertTrue(blocks.any { it is RichBlock.Code && it.language == "kotlin" })
        assertTrue(blocks.any { it is RichBlock.Mermaid })
        assertTrue(blocks.any { it is RichBlock.Svg })
        assertTrue(blocks.any { it is RichBlock.Drawio && !it.previewAsSvg })
        assertTrue(blocks.any { it is RichBlock.Math && it.latex.contains("E=mc") })
    }

    @Test
    fun parsesDisplayMath() {
        val blocks = RichMessageParser.parse("before\n\$\$a^2+b^2\$\$\nafter")
        assertTrue(blocks.any { it is RichBlock.Math && it.display })
        assertTrue(blocks.any { it is RichBlock.Markdown })
    }

    @Test
    fun drawioSvgPreviewFlag() {
        val blocks = RichMessageParser.parse(
            """
            ```drawio
            <svg xmlns="http://www.w3.org/2000/svg"><circle r="1"/></svg>
            ```
            """.trimIndent()
        )
        val drawio = blocks.filterIsInstance<RichBlock.Drawio>().single()
        assertTrue(drawio.previewAsSvg)
    }

    @Test
    fun cacheReturnsSameInstanceList() {
        val content = "hello **world**"
        val first = RichMessageParser.parse(content)
        val second = RichMessageParser.parse(content)
        assertEquals(first, second)
    }
}
