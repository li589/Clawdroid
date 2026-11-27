package com.clawdroid.app.ui.task

import com.clawdroid.app.agent.task.ExecutionTask
import com.clawdroid.app.agent.task.MutableTaskEventBus
import com.clawdroid.app.agent.task.TaskEngine
import com.clawdroid.app.agent.task.TaskEvent
import com.clawdroid.app.agent.task.TaskState
import com.clawdroid.app.data.InMemoryTaskStore
import com.clawdroid.app.ui.MainDispatcherRule
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskCenterViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun selectingSameTaskTogglesDetailsClosed() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = TaskCenterViewModel(
            InMemoryTaskStore(),
            MutableTaskEventBus(),
            mockk(relaxed = true)
        )

        viewModel.select("t1")
        assertEquals("t1", viewModel.uiState.value.selectedTaskId)
        viewModel.select("t1")
        assertNull(viewModel.uiState.value.selectedTaskId)
    }

    @Test
    fun taskCenterReflectsEngineEvents() = runTest(mainDispatcherRule.dispatcher) {
        val store = InMemoryTaskStore()
        val bus = MutableTaskEventBus()
        val engine = mockk<TaskEngine>(relaxed = true)
        store.save(ExecutionTask("t1", "demo", "run", emptyList(), TaskState.Running))
        val viewModel = TaskCenterViewModel(store, bus, engine)

        assertEquals("demo", viewModel.uiState.value.activeTasks.single().title)

        store.save(ExecutionTask("t1", "demo", "run", emptyList(), TaskState.Succeeded))
        bus.publish(TaskEvent("t1", TaskState.Succeeded, "done"))
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.activeTasks.size)
        assertEquals("demo", viewModel.uiState.value.history.single().title)
    }
}
