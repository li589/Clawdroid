package com.clawdroid.app.skills

import com.clawdroid.app.chat.ChatTaskAction
import com.clawdroid.app.chat.toAgentDefinition
import com.clawdroid.app.chat.toAgentId
import com.clawdroid.app.mcp.McpJsonRpcHandler
import com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot
import com.clawdroid.app.tools.ClawAssetPromptStore
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolCatalog
import com.clawdroid.app.tools.ClawToolDispatcher
import com.clawdroid.app.tools.ClawToolExecutor
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClawSkillCatalogTest {
    @Test
    fun skillsHaveCursorStyleMarkdown() {
        ClawSkillCatalog.all().forEach { skill ->
            val md = ClawSkillCatalog.toSkillMd(skill)
            assertTrue(md.contains("name: ${skill.id}"))
            assertTrue(md.contains(skill.description))
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ClawAgentRunnerTest {
    @Test
    fun runtimeHealthSweepStopsWhenTaskSubmitFails() = runTest {
        val executor = mockk<ClawToolExecutor>(relaxed = true)
        coEvery { executor.taskSubmit(any()) } returns ClawToolCallResult(
            success = false,
            output = "ping-fail",
            error = "submit_failed"
        )
        val runner = ClawAgentRunner(ClawToolDispatcher(executor))
        val result = runner.run("runtime_health_sweep")
        assertFalse(result.success)
        assertTrue(result.output.contains("ping-fail"))
        assertTrue(result.output.contains("RuntimeTask"))
    }

    @Test
    fun runtimeHealthSweepCompletesViaTaskSubmit() = runTest {
        val executor = mockk<ClawToolExecutor>(relaxed = true)
        var submittedId = ""
        coEvery { executor.taskSubmit(any()) } coAnswers {
            val task = firstArg<Map<String, Any?>>()
            submittedId = task["task_id"]?.toString().orEmpty()
            ClawToolCallResult(
                success = true,
                output = "submitted",
                runtimeTaskId = submittedId,
                taskSnapshot = ClawRuntimeTaskSnapshot(
                    taskId = submittedId,
                    state = "Running",
                    totalSteps = 3,
                    currentStep = 0,
                    completedSteps = 0,
                    name = "运行时体检"
                )
            )
        }
        coEvery { executor.taskGet(any()) } coAnswers {
            val taskId = firstArg<String>()
            ClawToolCallResult(
                success = true,
                output = "done",
                runtimeTaskId = taskId,
                taskSnapshot = ClawRuntimeTaskSnapshot(
                    taskId = taskId,
                    state = "Succeeded",
                    totalSteps = 3,
                    currentStep = 2,
                    completedSteps = 3,
                    name = "运行时体检"
                )
            )
        }
        val runner = ClawAgentRunner(ClawToolDispatcher(executor))
        val job = async { runner.run("runtime_health_sweep") }
        advanceTimeBy(600)
        val result = job.await()
        assertTrue(result.success)
        assertTrue(result.output.contains("Agent completed successfully"))
        assertTrue(submittedId.isNotBlank())
        assertEquals(submittedId, result.runtimeTaskId)
    }

    @Test
    fun runtimeHealthSweepDetachesOnPollTimeout() = runTest {
        val executor = mockk<ClawToolExecutor>(relaxed = true)
        coEvery { executor.taskSubmit(any()) } coAnswers {
            val task = firstArg<Map<String, Any?>>()
            val taskId = task["task_id"]?.toString().orEmpty()
            ClawToolCallResult(
                success = true,
                output = "submitted",
                runtimeTaskId = taskId,
                taskSnapshot = ClawRuntimeTaskSnapshot(
                    taskId = taskId,
                    state = "Running",
                    totalSteps = 3,
                    currentStep = 0,
                    completedSteps = 0,
                    name = "运行时体检"
                )
            )
        }
        coEvery { executor.taskGet(any()) } coAnswers {
            val taskId = firstArg<String>()
            ClawToolCallResult(
                success = true,
                output = "still-running",
                runtimeTaskId = taskId,
                taskSnapshot = ClawRuntimeTaskSnapshot(
                    taskId = taskId,
                    state = "Running",
                    totalSteps = 3,
                    currentStep = 0,
                    completedSteps = 0,
                    name = "运行时体检"
                )
            )
        }
        val runner = ClawAgentRunner(ClawToolDispatcher(executor))
        val job = async { runner.run("runtime_health_sweep") }
        advanceTimeBy(61_000)
        val result = job.await()
        assertTrue(result.success)
        assertEquals(ClawAgentRunner.ERROR_RUNTIME_TASK_DETACHED, result.error)
        assertEquals("Running", result.taskSnapshot?.state)
        assertTrue(result.output.contains("事件跟踪"))
    }

    @Test
    fun agentsExposeStepTitlesAlignedWithSteps() {
        ClawAgentCatalog.all().forEach { agent ->
            assertEquals(agent.steps.size, agent.stepTitles.size)
            assertTrue(agent.stepTitles.all { it.isNotBlank() })
        }
    }

    @Test
    fun chatTaskActionsMapToCatalogAgents() {
        ChatTaskAction.entries.forEach { action ->
            val agent = action.toAgentDefinition()
            assertEquals(action.toAgentId(), agent.id)
            assertEquals(agent.steps.size, agent.stepTitles.size)
        }
    }

    @Test
    fun stepListenerReceivesStartAndFinishEventsForInAppAgent() = runTest {
        val executor = mockk<ClawToolExecutor>(relaxed = true)
        coEvery { executor.probeSession() } returns ClawToolCallResult(success = true, output = "probe-ok")
        coEvery { executor.getCapabilities() } returns ClawToolCallResult(success = true, output = "caps-ok")
        val events = mutableListOf<String>()
        val runner = ClawAgentRunner(ClawToolDispatcher(executor))
        val result = runner.run(
            agentId = "probe_then_capabilities",
            stepListener = AgentStepListener { index, stepId, title, started, stepResult ->
                events += if (started) {
                    "start:$index:$stepId:$title"
                } else {
                    "end:$index:$stepId:$title:${stepResult?.success}"
                }
            }
        )
        assertTrue(result.success)
        assertEquals(
            listOf(
                "start:0:probe_session:Runtime Probe",
                "end:0:probe_session:Runtime Probe:true",
                "start:1:get_capabilities:获取能力",
                "end:1:get_capabilities:获取能力:true"
            ),
            events
        )
    }
}

class McpSkillAgentProtocolTest {
    @Test
    fun initializeAdvertisesPromptsAndResources() = runBlocking {
        val handler = McpJsonRpcHandler(ClawToolDispatcher(mockk(relaxed = true)))
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""
        )!!
        val caps = JSONObject(response).getJSONObject("result").getJSONObject("capabilities")
        assertTrue(caps.has("tools"))
        assertTrue(caps.has("prompts"))
        assertTrue(caps.has("resources"))
    }

    @Test
    fun promptsListReturnsSkills() = runBlocking {
        val handler = McpJsonRpcHandler(ClawToolDispatcher(mockk(relaxed = true)))
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":2,"method":"prompts/list","params":{}}"""
        )!!
        val prompts = JSONObject(response).getJSONObject("result").getJSONArray("prompts")
        // prompts/list 合并输出 skill prompts（来自 ClawSkillCatalog）
        // 与 builtin asset prompts（来自 ClawAssetPromptStore，当前 2 个：assist-mcp / tool-usage）。
        assertEquals(
            ClawSkillCatalog.all().size + ClawAssetPromptStore.builtinPromptIds().size,
            prompts.length()
        )
    }

    @Test
    fun resourcesReadSkillMarkdown() = runBlocking {
        val handler = McpJsonRpcHandler(ClawToolDispatcher(mockk(relaxed = true)))
        val skillId = ClawSkillCatalog.all().first().id
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"clawdroid://skill/$skillId"}}"""
        )!!
        val text = JSONObject(response)
            .getJSONObject("result")
            .getJSONArray("contents")
            .getJSONObject(0)
            .getString("text")
        assertTrue(text.contains("name: $skillId"))
    }

    @Test
    fun toolsListIncludesRunAgent() {
        assertNotNull(ClawToolCatalog.definition(ClawTool.RUN_AGENT.toolId))
        assertNotNull(ClawTool.byToolId("safe_tap"))
        assertEquals(ClawAgentCatalog.all().isNotEmpty(), true)
    }
}
