package com.clawdroid.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class NavigationUiState(
    val currentPage: ConsolePage = ConsolePage.Overview
)

internal class NavigationViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(NavigationUiState())
    val uiState: StateFlow<NavigationUiState> = _uiState.asStateFlow()

    fun selectPage(page: ConsolePage) {
        _uiState.update { it.copy(currentPage = page) }
    }
}

@Composable
internal fun rememberNavigationViewModel(): NavigationViewModel {
    return viewModel()
}
