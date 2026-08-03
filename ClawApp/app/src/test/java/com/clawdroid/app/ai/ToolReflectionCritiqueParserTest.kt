package com.clawdroid.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolReflectionCritiqueParserTest {
    @Test
    fun parsesJsonCritique() {
        val critique = ToolReflectionCritiqueParser.parse(
            """{"ok":false,"action":"replan","summary":"路径不存在","hint":"先 file_list"}"""
        )
        assertEquals(false, critique.ok)
        assertEquals(CritiqueAction.Replan, critique.action)
        assertTrue(critique.summary.contains("路径"))
        assertTrue(critique.hint.contains("file_list"))
    }

    @Test
    fun fallsBackToFreeText() {
        val critique = ToolReflectionCritiqueParser.parse("工具已成功，可以结束。")
        assertEquals(CritiqueAction.Continue, critique.action)
        assertTrue(critique.summary.contains("成功"))
    }

    @Test
    fun failureHeuristicTimeoutIsRetry() {
        val critique = ToolReflectionCritiqueParser.failureHeuristic(
            "execute_shell_limited",
            "shell command timed out after 3000ms"
        )
        assertEquals(CritiqueAction.Retry, critique.action)
        assertFalse(critique.ok == true)
    }
}
