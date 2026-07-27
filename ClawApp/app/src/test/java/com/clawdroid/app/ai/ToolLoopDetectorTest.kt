package com.clawdroid.app.ai

import com.clawdroid.app.tools.ClawTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopDetectorTest {

    @Test
    fun continueWhenFreshStep() {
        val decision = ToolLoopDetector.evaluate(
            steps = emptyList(),
            nextTool = ClawTool.FILE_LIST,
            nextArguments = mapOf("path" to "/sdcard")
        )
        assertEquals(ToolLoopDecision.Continue, decision)
    }

    @Test
    fun reusePriorSuccessfulSameArgs() {
        val steps = listOf(
            AiToolStepRecord(
                tool = ClawTool.FILE_LIST,
                arguments = mapOf("path" to "/sdcard", "limit" to "100"),
                success = true,
                output = "path=/sdcard\ntotal=3"
            )
        )
        val decision = ToolLoopDetector.evaluate(
            steps = steps,
            nextTool = ClawTool.FILE_LIST,
            nextArguments = mapOf("path" to "/sdcard", "limit" to "100")
        )
        assertTrue(decision is ToolLoopDecision.ReusePriorResult)
    }

    @Test
    fun allowSameToolDifferentPath() {
        val steps = listOf(
            AiToolStepRecord(
                tool = ClawTool.FILE_LIST,
                arguments = mapOf("path" to "/sdcard"),
                success = true,
                output = "ok"
            )
        )
        val decision = ToolLoopDetector.evaluate(
            steps = steps,
            nextTool = ClawTool.FILE_LIST,
            nextArguments = mapOf("path" to "/sdcard/Download")
        )
        assertEquals(ToolLoopDecision.Continue, decision)
    }

    @Test
    fun hardStopOnConsecutiveFailures() {
        val args = mapOf("path" to "/missing")
        val steps = listOf(
            AiToolStepRecord(ClawTool.FILE_STAT, args, false, "fail1"),
            AiToolStepRecord(ClawTool.FILE_STAT, args, false, "fail2")
        )
        val decision = ToolLoopDetector.evaluate(
            steps = steps,
            nextTool = ClawTool.FILE_STAT,
            nextArguments = args
        )
        assertTrue(decision is ToolLoopDecision.HardStop)
    }

    @Test
    fun softWarnOnNoProgressFingerprint() {
        val steps = (1..3).map {
            AiToolStepRecord(
                tool = ClawTool.FILE_STAT,
                arguments = mapOf("path" to "/a$it"),
                success = true,
                output = "same-fingerprint-body"
            )
        }
        val decision = ToolLoopDetector.evaluate(
            steps = steps,
            nextTool = ClawTool.FILE_LIST,
            nextArguments = mapOf("path" to "/sdcard")
        )
        assertTrue(decision is ToolLoopDecision.SoftWarn)
    }

    @Test
    fun softWarnIsSkipSignalDistinctFromContinue() {
        // AgentToolLoopController must treat SoftWarn like ReusePriorResult:
        // synthesize a step and return@repeat without dispatcher.execute.
        val steps = (1..3).map {
            AiToolStepRecord(
                tool = ClawTool.FILE_STAT,
                arguments = mapOf("path" to "/x$it"),
                success = true,
                output = "identical-output"
            )
        }
        val decision = ToolLoopDetector.evaluate(
            steps = steps,
            nextTool = ClawTool.FILE_LIST,
            nextArguments = mapOf("path" to "/sdcard")
        )
        assertTrue(decision is ToolLoopDecision.SoftWarn)
        assertTrue(decision !is ToolLoopDecision.Continue)
        val afterSkip = steps + AiToolStepRecord(
            tool = ClawTool.FILE_LIST,
            arguments = mapOf("path" to "/sdcard"),
            success = true,
            output = "【系统】无进展：${(decision as ToolLoopDecision.SoftWarn).message}（已跳过本次工具执行，请换策略）"
        )
        val next = ToolLoopDetector.evaluate(
            steps = afterSkip,
            nextTool = ClawTool.FILE_READ,
            nextArguments = mapOf("path" to "/sdcard/a.txt")
        )
        assertEquals(ToolLoopDecision.Continue, next)
    }
}
