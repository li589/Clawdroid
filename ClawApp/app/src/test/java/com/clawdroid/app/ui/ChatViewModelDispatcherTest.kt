package com.clawdroid.app.ui

import com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot
import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolDispatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelDispatcherTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun rejectConcurrentSubmitWhileDispatcherBusy() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val dispatcher = fakeDispatcher()
        coEvery { dispatcher.execute(ClawTool.RUNTIME_PING, any()) } coAnswers {
            gate.await()
            ClawToolCallResult(success = true, output = "pong-ok")
        }

        val viewModel = createViewModel(dispatcher)
        advanceUntilIdle()

        viewModel.submitPrompt("ping", ModelSettings())
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.chatBusy)

        viewModel.submitPrompt("ping", ModelSettings())
        advanceUntilIdle()

        val busyReplies = viewModel.uiState.value.messages.filter {
            it.role == ChatRole.Assistant && it.content.contains("当前仍有指令在执行")
        }
        assertEquals(1, busyReplies.size)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.chatBusy)
        assertTrue(
            viewModel.uiState.value.messages.any {
                it.role == ChatRole.Assistant && it.content.contains("pong-ok")
            }
        )
    }

    @Test
    fun cancelLocalJobStopsAgentPolling() = runTest(mainDispatcherRule.dispatcher) {
        val getGate = CompletableDeferred<Unit>()
        val dispatcher = fakeDispatcher()
        var submittedTaskId = ""
        coEvery { dispatcher.execute(ClawTool.TASK_SUBMIT, any()) } coAnswers {
            val args = secondArg<Map<String, Any?>>()
            submittedTaskId = args["task_id"]?.toString().orEmpty()
            ClawToolCallResult(
                success = true,
                output = "submitted",
                runtimeTaskId = submittedTaskId,
                taskSnapshot = ClawRuntimeTaskSnapshot(
                    taskId = submittedTaskId,
                    state = "Running",
                    totalSteps = 3,
                    currentStep = 0,
                    completedSteps = 0,
                    name = "运行时体检"
                )
            )
        }
        coEvery { dispatcher.execute(ClawTool.TASK_GET, any()) } coAnswers {
            getGate.await()
            ClawToolCallResult(
                success = true,
                output = "done",
                runtimeTaskId = submittedTaskId,
                taskSnapshot = ClawRuntimeTaskSnapshot(
                    taskId = submittedTaskId,
                    state = "Succeeded",
                    totalSteps = 3,
                    currentStep = 2,
                    completedSteps = 3,
                    name = "运行时体检"
                )
            )
        }
        coEvery { dispatcher.execute(ClawTool.TASK_CANCEL, any()) } coAnswers {
            val args = secondArg<Map<String, Any?>>()
            ClawToolCallResult(
                success = true,
                output = "cancelled",
                runtimeTaskId = args["task_id"]?.toString()
            )
        }

        val viewModel = createViewModel(dispatcher)
        advanceUntilIdle()

        viewModel.submitPrompt("运行时体检", ModelSettings())
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.chatBusy)
        assertEquals(
            ChatTaskProgressState.Running,
            viewModel.uiState.value.taskExecution?.status
        )
        assertTrue(submittedTaskId.isNotBlank())
        assertEquals(submittedTaskId, viewModel.uiState.value.taskExecution?.runtimeTaskId)

        viewModel.cancelCurrentTaskExecution()
        advanceUntilIdle()

        coVerify(atLeast = 1) {
            dispatcher.execute(ClawTool.TASK_CANCEL, match { args ->
                args["task_id"]?.toString() == submittedTaskId
            })
        }
        assertEquals(
            ChatTaskProgressState.Cancelled,
            viewModel.uiState.value.taskExecution?.status
        )
        assertFalse(viewModel.uiState.value.chatBusy)
        getGate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun cancelRuntimeOnlyTrackingWithoutLocalJob() = runTest(mainDispatcherRule.dispatcher) {
        val dispatcher = fakeDispatcher()
        coEvery { dispatcher.execute(ClawTool.TASK_SUBMIT, any()) } returns ClawToolCallResult(
            success = true,
            output = "submitted",
            runtimeTaskId = "rt-only-1",
            taskSnapshot = ClawRuntimeTaskSnapshot(
                taskId = "rt-only-1",
                state = "Running",
                totalSteps = 1,
                currentStep = 0,
                completedSteps = 0,
                name = "chat-submit"
            )
        )
        coEvery { dispatcher.execute(ClawTool.TASK_CANCEL, any()) } returns ClawToolCallResult(
            success = true,
            output = "cancelled",
            runtimeTaskId = "rt-only-1",
            taskSnapshot = ClawRuntimeTaskSnapshot(
                taskId = "rt-only-1",
                state = "Cancelled",
                totalSteps = 1
            )
        )

        val viewModel = createViewModel(dispatcher)
        advanceUntilIdle()

        viewModel.submitPrompt("/task_submit demo ping", ModelSettings())
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.chatBusy)
        assertEquals("rt-only-1", viewModel.uiState.value.taskExecution?.runtimeTaskId)
        assertEquals(
            ChatTaskProgressState.Running,
            viewModel.uiState.value.taskExecution?.status
        )

        viewModel.cancelCurrentTaskExecution()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            dispatcher.execute(ClawTool.TASK_CANCEL, match { args ->
                args["task_id"]?.toString() == "rt-only-1"
            })
        }
        assertEquals(
            ChatTaskProgressState.Cancelled,
            viewModel.uiState.value.taskExecution?.status
        )
    }

    @Test
    fun taskGetForOtherIdDoesNotOverwriteTrackedTask() = runTest(mainDispatcherRule.dispatcher) {
        val dispatcher = fakeDispatcher()
        coEvery { dispatcher.execute(ClawTool.TASK_SUBMIT, any()) } returns ClawToolCallResult(
            success = true,
            output = "submitted",
            runtimeTaskId = "rt-a",
            taskSnapshot = ClawRuntimeTaskSnapshot(
                taskId = "rt-a",
                state = "Running",
                totalSteps = 1,
                name = "task-a"
            )
        )
        coEvery { dispatcher.execute(ClawTool.TASK_GET, any()) } returns ClawToolCallResult(
            success = true,
            output = "other",
            runtimeTaskId = "rt-b",
            taskSnapshot = ClawRuntimeTaskSnapshot(
                taskId = "rt-b",
                state = "Cancelled",
                totalSteps = 1,
                name = "task-b"
            )
        )

        val viewModel = createViewModel(dispatcher)
        advanceUntilIdle()
        viewModel.submitPrompt("/task_submit demo ping", ModelSettings())
        advanceUntilIdle()
        assertEquals("rt-a", viewModel.uiState.value.taskExecution?.runtimeTaskId)

        viewModel.submitPrompt("/task_get rt-b", ModelSettings())
        advanceUntilIdle()

        assertEquals("rt-a", viewModel.uiState.value.taskExecution?.runtimeTaskId)
        assertEquals(
            ChatTaskProgressState.Running,
            viewModel.uiState.value.taskExecution?.status
        )
    }

    @Test
    fun runtimeEventDoesNotBindOntoInAppAgentWithoutRuntimeId() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val dispatcher = fakeDispatcher()
        coEvery {
            dispatcher.execute(match<String> { it == "probe_session" }, any())
        } coAnswers {
            gate.await()
            ClawToolCallResult(success = true, output = "probe-ok")
        }
        coEvery {
            dispatcher.execute(match<String> { it == "get_capabilities" }, any())
        } returns ClawToolCallResult(
            success = true,
            output = "caps-ok"
        )

        val viewModel = createViewModel(dispatcher)
        advanceUntilIdle()
        viewModel.submitPrompt("检查运行时状态", ModelSettings())
        advanceUntilIdle()

        assertEquals(ChatTaskProgressState.Running, viewModel.uiState.value.taskExecution?.status)
        assertTrue(viewModel.uiState.value.taskExecution?.runtimeTaskId.isNullOrBlank())

        viewModel.onRuntimeTaskEvent(
            ClawRuntimeTaskSnapshot(
                taskId = "foreign-rt",
                state = "Running",
                totalSteps = 2,
                name = "foreign"
            )
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.taskExecution?.runtimeTaskId.isNullOrBlank())
        assertEquals(ChatTaskProgressState.Running, viewModel.uiState.value.taskExecution?.status)

        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun secondTaskSubmitCancelsPreviousRuntimeTask() = runTest(mainDispatcherRule.dispatcher) {
        val dispatcher = fakeDispatcher()
        var submitCount = 0
        coEvery { dispatcher.execute(ClawTool.TASK_SUBMIT, any()) } coAnswers {
            submitCount += 1
            val id = "rt-submit-$submitCount"
            ClawToolCallResult(
                success = true,
                output = "submitted",
                runtimeTaskId = id,
                taskSnapshot = ClawRuntimeTaskSnapshot(
                    taskId = id,
                    state = "Running",
                    totalSteps = 1,
                    name = "chat-submit"
                )
            )
        }
        coEvery { dispatcher.execute(ClawTool.TASK_CANCEL, any()) } returns ClawToolCallResult(
            success = true,
            output = "cancelled"
        )

        val viewModel = createViewModel(dispatcher)
        advanceUntilIdle()
        viewModel.submitPrompt("/task_submit demo ping", ModelSettings())
        advanceUntilIdle()
        assertEquals("rt-submit-1", viewModel.uiState.value.taskExecution?.runtimeTaskId)

        viewModel.submitPrompt("/task_submit demo ping", ModelSettings())
        advanceUntilIdle()

        assertEquals("rt-submit-2", viewModel.uiState.value.taskExecution?.runtimeTaskId)
        coVerify(atLeast = 1) {
            dispatcher.execute(ClawTool.TASK_CANCEL, match { args ->
                args["task_id"]?.toString() == "rt-submit-1"
            })
        }
    }

    @Test
    fun cancelRuntimeOnlyKeepsRunningWhenCancelFails() = runTest(mainDispatcherRule.dispatcher) {
        val dispatcher = fakeDispatcher()
        coEvery { dispatcher.execute(ClawTool.TASK_SUBMIT, any()) } returns ClawToolCallResult(
            success = true,
            output = "submitted",
            runtimeTaskId = "rt-fail-cancel",
            taskSnapshot = ClawRuntimeTaskSnapshot(
                taskId = "rt-fail-cancel",
                state = "Running",
                totalSteps = 1,
                name = "chat-submit"
            )
        )
        coEvery { dispatcher.execute(ClawTool.TASK_CANCEL, any()) } returns ClawToolCallResult(
            success = false,
            output = "cancel-denied",
            error = "denied"
        )

        val viewModel = createViewModel(dispatcher)
        advanceUntilIdle()
        viewModel.submitPrompt("/task_submit demo ping", ModelSettings())
        advanceUntilIdle()

        viewModel.cancelCurrentTaskExecution()
        advanceUntilIdle()

        assertEquals(
            ChatTaskProgressState.Running,
            viewModel.uiState.value.taskExecution?.status
        )
        assertTrue(
            viewModel.uiState.value.taskExecution?.summary?.contains("取消请求失败") == true
        )
    }

    @Test
    fun restoreResyncsRuntimeBackedTaskViaTaskGet() = runTest(mainDispatcherRule.dispatcher) {
        mockkObject(ChatTaskHistoryStore)
        mockkObject(ChatHistoryStore)
        try {
            coEvery { ChatHistoryStore.load(any()) } returns emptyList()
            every { ChatHistoryStore.save(any(), any()) } just runs
            every { ChatTaskHistoryStore.load(any()) } returns PersistedChatTaskState(
                currentTask = ChatTaskExecutionState(
                    taskId = "chat-restore-1",
                    title = "Runtime 任务",
                    summary = "was running",
                    status = ChatTaskProgressState.Running,
                    steps = listOf(ChatTaskStepState(title = "步骤 1")),
                    startedAtEpochMs = 1L,
                    runtimeTaskId = "rt-restore-1"
                ),
                taskHistory = emptyList()
            )
            every { ChatTaskHistoryStore.save(any(), any(), any()) } just runs
            every { ChatTaskHistoryStore.clear(any()) } just runs

            val dispatcher = fakeDispatcher()
            coEvery { dispatcher.execute(ClawTool.TASK_GET, any()) } returns ClawToolCallResult(
                success = true,
                output = "ok",
                runtimeTaskId = "rt-restore-1",
                taskSnapshot = ClawRuntimeTaskSnapshot(
                    taskId = "rt-restore-1",
                    state = "Succeeded",
                    totalSteps = 1,
                    completedSteps = 1,
                    currentStep = 0,
                    name = "restored"
                )
            )

            val viewModel = createViewModel(dispatcher)
            advanceUntilIdle()

            assertEquals(
                ChatTaskProgressState.Succeeded,
                viewModel.uiState.value.taskExecution?.status
            )
            assertEquals("rt-restore-1", viewModel.uiState.value.taskExecution?.runtimeTaskId)
            coVerify {
                dispatcher.execute(
                    ClawTool.TASK_GET,
                    match { args -> args["task_id"]?.toString() == "rt-restore-1" }
                )
            }
        } finally {
            unmockkObject(ChatTaskHistoryStore)
            unmockkObject(ChatHistoryStore)
        }
    }

    @Test
    fun restoreCancelsLocalOnlyRunningTask() = runTest(mainDispatcherRule.dispatcher) {
        mockkObject(ChatTaskHistoryStore)
        mockkObject(ChatHistoryStore)
        try {
            coEvery { ChatHistoryStore.load(any()) } returns emptyList()
            every { ChatHistoryStore.save(any(), any()) } just runs
            every { ChatTaskHistoryStore.load(any()) } returns PersistedChatTaskState(
                currentTask = ChatTaskExecutionState(
                    taskId = "chat-local-1",
                    title = "本地任务",
                    summary = "was running",
                    status = ChatTaskProgressState.Running,
                    steps = listOf(
                        ChatTaskStepState(
                            title = "步骤 1",
                            status = ChatTaskProgressState.Running
                        )
                    ),
                    startedAtEpochMs = 1L,
                    runtimeTaskId = null
                ),
                taskHistory = emptyList()
            )
            every { ChatTaskHistoryStore.save(any(), any(), any()) } just runs

            val viewModel = createViewModel(fakeDispatcher())
            advanceUntilIdle()

            assertEquals(
                ChatTaskProgressState.Cancelled,
                viewModel.uiState.value.taskExecution?.status
            )
            assertEquals("app_restarted", viewModel.uiState.value.taskExecution?.failure?.code)
        } finally {
            unmockkObject(ChatTaskHistoryStore)
            unmockkObject(ChatHistoryStore)
        }
    }

    private fun fakeDispatcher(): ClawToolDispatcher {
        return mockk(relaxed = true) {
            every { rememberCapture(any()) } just runs
            every { peekLastCapture() } returns null
        }
    }

    private fun createViewModel(dispatcher: ClawToolDispatcher): ChatViewModel {
        val overviewController = OverviewController(
            appContext = createAppContextMock(),
            runtimeClient = createRuntimeClientMock(),
            toolExecutor = createToolExecutorMock(),
            previewLimitBytes = 1024,
            environmentGateway = FakeOverviewEnvironmentGateway(),
            autoStart = false
        )
        return ChatViewModel(
            appContext = createAppContextMock(),
            overviewController = overviewController,
            toolDispatcher = dispatcher
        )
    }
}
