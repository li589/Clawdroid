package com.clawdroid.app.orchestrator

import com.clawdroid.app.tools.ClawTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectCommandOrchestratorTest {
    @Test
    fun parseReturnsTapToolWithCoordinates() {
        val parsed = DirectCommandOrchestrator.parse("点击 120 340")

        assertTrue(parsed is DirectCommandOrchestrator.ParsedIntent.ToolCall)
        parsed as DirectCommandOrchestrator.ParsedIntent.ToolCall
        assertEquals(ClawTool.INJECT_TAP, parsed.tool)
        assertEquals("120", parsed.arguments["x"])
        assertEquals("340", parsed.arguments["y"])
    }

    @Test
    fun parseReturnsSwipeToolWithDuration() {
        val parsed = DirectCommandOrchestrator.parse("滑动 10 20 30 40 600")

        assertTrue(parsed is DirectCommandOrchestrator.ParsedIntent.ToolCall)
        parsed as DirectCommandOrchestrator.ParsedIntent.ToolCall
        assertEquals(ClawTool.INJECT_SWIPE, parsed.tool)
        assertEquals("10", parsed.arguments["x1"])
        assertEquals("20", parsed.arguments["y1"])
        assertEquals("30", parsed.arguments["x2"])
        assertEquals("40", parsed.arguments["y2"])
        assertEquals("600", parsed.arguments["duration_ms"])
    }

    @Test
    fun parseReturnsRawTextForSafeTapCompositeCommand() {
        val parsed = DirectCommandOrchestrator.parse("执行安全点击")

        assertEquals(
            DirectCommandOrchestrator.ParsedIntent.RawText("安全点击"),
            parsed
        )
    }
}
