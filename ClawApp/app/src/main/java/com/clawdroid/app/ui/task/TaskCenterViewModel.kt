package com.clawdroid.app.ui.task

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawdroid.app.agent.task.ExecutionTask
import com.clawdroid.app.agent.task.TaskEngine
import com.clawdroid.app.agent.task.TaskEventBus
import com.clawdroid.app.agent.task.TaskStateMachine
import com.clawdroid.app.data.TaskStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class TaskCenterUiState(
    val activeTasks: List<ExecutionTask> = emptyList(),
    val history: List<ExecutionTask> = emptyList(),
    val selectedTaskId: String? = null
) {
    val selectedTask: ExecutionTask?
        get() = (activeTasks + history).firstOrNull { it.id == selectedTaskId }
}

internal class TaskCenterViewModel(
    private val store: TaskStore,
    eventBus: TaskEventBus,
    private val engine: TaskEngine
) : ViewModel() {
    private val _uiState = MutableStateFlow(TaskCenterUiState())
    val uiState: StateFlow<TaskCenterUiState> = _uiState.asStateFlow()

    init {
        reload()
        viewModelScope.launch {
            eventBus.events().collect { reload() }
        }
    }

    fun select(taskId: String) {
        _uiState.update {
            it.copy(selectedTaskId = taskId.takeUnless { selected -> selected == it.selectedTaskId })
        }
    }

    fun pause(taskId: String) = viewModelScope.launch { engine.pause(taskId) }

    fun resume(taskId: String) = viewModelScope.launch { engine.resume(taskId) }

    fun retry(taskId: String) = viewModelScope.launch { engine.retry(taskId) }

    fun cancel(taskId: String) = viewModelScope.launch { engine.cancel(taskId) }

    private fun reload() {
        val tasks = store.load().sortedByDescending { it.updatedAtEpochMs }
        _uiState.update { current ->
            current.copy(
                activeTasks = tasks.filterNot { TaskStateMachine.isTerminal(it.state) },
                history = tasks.filter { TaskStateMachine.isTerminal(it.state) }
            )
        }
    }
}

@Composable
internal fun rememberTaskCenterViewModel(engine: TaskEngine): TaskCenterViewModel {
    val factory = remember(engine) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(TaskCenterViewModel::class.java))
                return TaskCenterViewModel(engine.store, engine.eventBus, engine) as T
            }
        }
    }
    return viewModel(factory = factory)
}
