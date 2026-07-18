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

    @Test
    fun parseListsAgentsAndRunsAgentBySlash() {
        val listed = DirectCommandOrchestrator.parse("/agents")
        assertTrue(listed is DirectCommandOrchestrator.ParsedIntent.ToolCall)
        listed as DirectCommandOrchestrator.ParsedIntent.ToolCall
        assertEquals(ClawTool.LIST_AGENTS, listed.tool)

        val run = DirectCommandOrchestrator.parse("/agent runtime_health_sweep")
        assertTrue(run is DirectCommandOrchestrator.ParsedIntent.ToolCall)
        run as DirectCommandOrchestrator.ParsedIntent.ToolCall
        assertEquals(ClawTool.RUN_AGENT, run.tool)
        assertEquals("runtime_health_sweep", run.arguments["agent_id"])
    }

    @Test
    fun parseTaskCommandsBySlashAndKeyword() {
        val list = DirectCommandOrchestrator.parse("/task_list")
        assertTrue(list is DirectCommandOrchestrator.ParsedIntent.ToolCall)
        list as DirectCommandOrchestrator.ParsedIntent.ToolCall
        assertEquals(ClawTool.TASK_LIST, list.tool)

        val cancel = DirectCommandOrchestrator.parse("取消 runtime 任务 task-123")
        assertTrue(cancel is DirectCommandOrchestrator.ParsedIntent.ToolCall)
        cancel as DirectCommandOrchestrator.ParsedIntent.ToolCall
        assertEquals(ClawTool.TASK_CANCEL, cancel.tool)
        assertEquals("task-123", cancel.arguments["task_id"])

        val submit = DirectCommandOrchestrator.parse("/task_submit demo-1 ping")
        assertTrue(submit is DirectCommandOrchestrator.ParsedIntent.ToolCall)
        submit as DirectCommandOrchestrator.ParsedIntent.ToolCall
        assertEquals(ClawTool.TASK_SUBMIT, submit.tool)
        assertEquals("demo-1", submit.arguments["task_id"])
        assertTrue(submit.arguments["steps_json"].orEmpty().contains("\"action\":\"ping\""))
    }
}
