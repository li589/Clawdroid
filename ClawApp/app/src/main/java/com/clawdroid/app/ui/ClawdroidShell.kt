package com.clawdroid.app.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clawdroid.app.BuildConfig
import com.clawdroid.app.env.AppPermissionManager
import com.clawdroid.app.runtime.ClawRuntimeClient
import com.clawdroid.app.tools.ClawToolExecutor

private val floatingNavReservedHeight = 108.dp

@Composable
internal fun ClawdroidShell(
    runtimeClient: ClawRuntimeClient,
    toolExecutor: ClawToolExecutor,
    previewLimitBytes: Int,
    debugSeedLongOverview: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val overviewController = rememberOverviewController(context, runtimeClient, toolExecutor, previewLimitBytes)
    val overviewUiState by overviewController.uiState.collectAsStateWithLifecycle()
    val overviewDashboardMetrics by overviewController.dashboardMetrics.collectAsStateWithLifecycle()
    val overviewCapturePreview by overviewController.latestCapturePreview.collectAsStateWithLifecycle()
    val automationUiState by overviewController.automationController.state.collectAsStateWithLifecycle()
    val chatViewModel = rememberChatViewModel(context, overviewController)
    val chatUiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val settingsViewModel = rememberSettingsViewModel(context)
    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val navigationViewModel = rememberNavigationViewModel()
    val navigationUiState by navigationViewModel.uiState.collectAsStateWithLifecycle()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val label = uri?.lastPathSegment ?: uri?.toString()
        if (!label.isNullOrBlank()) {
            chatViewModel.onImagePicked(label)
        }
    }

    val voiceInputLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val transcript = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (transcript.isNotBlank()) {
            chatViewModel.applyVoiceTranscript(transcript)
        }
    }

    val systemSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        overviewController.handleSystemSettingsReturned()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        overviewController.handleNotificationPermissionResult(granted)
    }

    LaunchedEffect(navigationUiState.currentPage) {
        overviewController.setOverviewActive(navigationUiState.currentPage == ConsolePage.Overview)
    }

    LaunchedEffect(debugSeedLongOverview) {
        if (BuildConfig.DEBUG && debugSeedLongOverview) {
            overviewController.applyDebugLongOverviewSeed()
        }
    }

    val connectionSummary = overviewUiState.connectionSummary
    val overviewPermissionState = overviewUiState.permissionState
    val overviewPermissionActions = overviewController.buildPermissionActions(
        onRequestNotificationPermission = {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                overviewController.markNotificationPermissionNotRequired()
            } else {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onOpenAccessibilitySettings = {
            overviewController.markOpeningAccessibilitySettings()
            systemSettingsLauncher.launch(AppPermissionManager.accessibilitySettingsIntent(context))
        },
        onOpenWriteSettings = {
            overviewController.markOpeningWriteSettings()
            systemSettingsLauncher.launch(AppPermissionManager.writeSettingsIntent(context))
        },
        onOpenAllFilesAccess = {
            overviewController.markOpeningAllFilesAccess()
            systemSettingsLauncher.launch(AppPermissionManager.allFilesAccessIntent(context))
        }
    )
    val overviewAutomationActions = overviewController.automationController.buildOverviewAutomationActions()
    val overviewRuntimeState = overviewUiState.runtimeState
    val overviewRuntimeActions = overviewController.buildRuntimeActions()
    val overviewEventState = overviewUiState.eventState
    val overviewEventActions = overviewController.buildEventActions()
    val settingsScreenState = buildSettingsScreenState(
        versionName = BuildConfig.VERSION_NAME,
        packageName = context.packageName,
        socketName = runtimeClient.socketDisplayName(),
        connectionSummary = connectionSummary,
        runtimeVersionStatus = overviewRuntimeState.versionStatus,
        runtimeHealthStatus = overviewRuntimeState.healthStatus,
        runtimeLastErrorStatus = overviewRuntimeState.lastErrorStatus,
        runtimeConfigSummary = overviewRuntimeState.runtimeConfigSummary,
        settingsState = settingsUiState
    )
    val settingsScreenActions = settingsViewModel.buildSettingsScreenActions()
    val chatConsoleState = buildChatConsoleState(
        chatState = chatUiState,
        modelSettings = settingsUiState.modelSettings,
        eventStreaming = overviewEventState.eventStreaming,
        connectionSummary = connectionSummary
    )
    val chatConsoleActions = chatViewModel.buildChatConsoleActions(
        modelSettings = settingsUiState.modelSettings,
        eventStreaming = overviewEventState.eventStreaming,
        onModelCallSuccess = settingsViewModel::markLatestModelCallSuccess,
        onVoiceClick = {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PROMPT, "说出你要执行的指令")
            }
            voiceInputLauncher.launch(intent)
        },
        onImageClick = { imagePickerLauncher.launch("image/*") }
    )

    ClawdroidTheme(themeMode = settingsUiState.themeMode) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        // Reserve viewport space so cards never render beneath the floating nav.
                        .padding(bottom = floatingNavReservedHeight),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 12.dp,
                        end = 16.dp,
                        bottom = 28.dp
                    )
                ) {
                    when (navigationUiState.currentPage) {
                        ConsolePage.Chat -> {
                            chatConsoleScreen(
                                state = chatConsoleState,
                                actions = chatConsoleActions
                            )
                        }

                        ConsolePage.Overview -> {
                            statusOverviewScreen(
                                permissionState = overviewPermissionState,
                                permissionActions = overviewPermissionActions,
                                automationState = automationUiState,
                                automationActions = overviewAutomationActions,
                                runtimeState = overviewRuntimeState,
                                dashboardMetrics = overviewDashboardMetrics,
                                latestCapturePreview = overviewCapturePreview,
                                runtimeActions = overviewRuntimeActions,
                                eventState = overviewEventState,
                                eventActions = overviewEventActions,
                                debugHighlightLongContent = BuildConfig.DEBUG && debugSeedLongOverview
                            )
                        }

                        ConsolePage.Settings -> {
                            settingsScreen(
                                state = settingsScreenState,
                                actions = settingsScreenActions
                            )
                        }
                    }
                }
                FloatingBottomNavBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp),
                    currentPage = navigationUiState.currentPage,
                    onPageSelected = navigationViewModel::selectPage
                )
            }
        }
    }
}
