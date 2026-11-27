package com.clawdroid.app.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clawdroid.app.BuildConfig
import com.clawdroid.app.env.AppPermissionManager
import com.clawdroid.app.env.ShizukuSupport
import com.clawdroid.app.mcp.McpServerController
import com.clawdroid.app.mcp.assist.AssistMcpController
import com.clawdroid.app.runtime.ClawRuntimeClient
import com.clawdroid.app.tools.ClawToolExecutor
import com.clawdroid.app.tools.LiveToolCapabilityStore

private val floatingNavPillHeight = 68.dp
private val floatingNavGap = 2.dp
private val imeVisibleThreshold = 64.dp

@Composable
internal fun ClawdroidShell(
    runtimeClient: ClawRuntimeClient,
    toolExecutor: ClawToolExecutor,
    previewLimitBytes: Int,
    debugSeedLongOverview: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val graph = rememberClawdroidCompositionRoot(
        runtimeClient = runtimeClient,
        toolExecutor = toolExecutor,
        previewLimitBytes = previewLimitBytes
    )
    val overviewController = graph.overviewController
    val chatViewModel = graph.chatViewModel
    val settingsViewModel = graph.settingsViewModel
    val navigationViewModel = graph.navigationViewModel
    val assistController = graph.assistController
    val mcpController = graph.mcpController

    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val navigationUiState by navigationViewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, overviewController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overviewController.onHostStarted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri) ?: "image/*"
            chatViewModel.onImagePicked(uri, mimeType)
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

    val permissionActions = remember(overviewController, context, systemSettingsLauncher) {
        overviewController.buildPermissionActions(
            onRequestNotificationPermission = {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    overviewController.markNotificationPermissionNotRequired()
                } else {
                    overviewController.markOpeningNotificationSettings()
                    systemSettingsLauncher.launch(AppPermissionManager.notificationSettingsIntent(context))
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
            },
            onOpenNotificationListenerSettings = {
                overviewController.markOpeningNotificationListenerSettings()
                systemSettingsLauncher.launch(AppPermissionManager.notificationListenerSettingsIntent(context))
            },
            onRequestShizukuPermission = {
                overviewController.requestShizukuPermission(
                    openManager = {
                        systemSettingsLauncher.launch(AppPermissionManager.shizukuManagerIntent(context))
                    }
                )
            }
        )
    }

    val latestVoiceClick = rememberUpdatedState {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出你要执行的指令")
        }
        voiceInputLauncher.launch(intent)
    }
    val latestImageClick = rememberUpdatedState {
        imagePickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    LaunchedEffect(Unit) {
        ShizukuSupport.ensureInitialized {
            overviewController.refreshLocalEnvironment()
        }
        ShizukuSupport.addPermissionResultListener { _, _ ->
            overviewController.refreshLocalEnvironment()
        }
    }

    val currentPage = navigationUiState.currentPage
    LaunchedEffect(currentPage) {
        overviewController.setOverviewActive(currentPage == ConsolePage.Overview)
        if (currentPage != ConsolePage.Settings) {
            settingsViewModel.navigateHub()
        }
    }

    LaunchedEffect(debugSeedLongOverview) {
        if (BuildConfig.DEBUG && debugSeedLongOverview) {
            overviewController.applyDebugLongOverviewSeed()
        }
    }

    val navBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val isImeVisible = imeBottom > imeVisibleThreshold
    val contentBottomPad = if (isImeVisible) 4.dp else navBarsBottom + floatingNavPillHeight + floatingNavGap

    ClawdroidTheme(themeMode = settingsUiState.themeMode) {
        Surface(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                    )
                ),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (currentPage) {
                    ConsolePage.Overview -> OverviewRoute(
                        overviewController = overviewController,
                        mcpController = mcpController,
                        assistController = assistController,
                        permissionActions = permissionActions,
                        debugHighlightLongContent = BuildConfig.DEBUG && debugSeedLongOverview,
                        modifier = Modifier.padding(bottom = contentBottomPad)
                    )

                    ConsolePage.Chat -> ChatRoute(
                        chatViewModel = chatViewModel,
                        overviewController = overviewController,
                        settingsViewModel = settingsViewModel,
                        modelSettings = settingsUiState.modelSettings,
                        onVoiceClick = { latestVoiceClick.value.invoke() },
                        onImageClick = { latestImageClick.value.invoke() },
                        modifier = Modifier.padding(bottom = contentBottomPad)
                    )

                    ConsolePage.Settings -> SettingsRoute(
                        settingsViewModel = settingsViewModel,
                        overviewController = overviewController,
                        navigationViewModel = navigationViewModel,
                        mcpController = mcpController,
                        assistController = assistController,
                        runtimeClient = runtimeClient,
                        modifier = Modifier.padding(bottom = contentBottomPad)
                    )
                }

                if (!isImeVisible) {
                    FloatingBottomNavBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        currentPage = currentPage,
                        onPageSelected = navigationViewModel::selectPage
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewRoute(
    overviewController: OverviewController,
    mcpController: McpServerController,
    assistController: AssistMcpController,
    permissionActions: OverviewPermissionActions,
    debugHighlightLongContent: Boolean,
    modifier: Modifier
) {
    val overviewUiState by overviewController.uiState.collectAsStateWithLifecycle()
    val dashboardMetrics by overviewController.dashboardMetrics.collectAsStateWithLifecycle()
    val capturePreview by overviewController.latestCapturePreview.collectAsStateWithLifecycle()
    val automationState by overviewController.automationController.state.collectAsStateWithLifecycle()
    val mcpState by mcpController.state.collectAsStateWithLifecycle()
    val assistState by assistController.state.collectAsStateWithLifecycle()
    val automationActions = remember(overviewController) {
        overviewController.automationController.buildOverviewAutomationActions()
    }
    val runtimeActions = remember(overviewController) { overviewController.buildRuntimeActions() }
    val eventActions = remember(overviewController) { overviewController.buildEventActions() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 20.dp)
    ) {
        item(key = "overview-header") {
            BrandPageHeader(subtitle = "状态与快捷操作")
        }
        statusOverviewScreen(
            permissionState = overviewUiState.permissionState,
            permissionActions = permissionActions,
            automationState = automationState,
            automationActions = automationActions,
            runtimeState = overviewUiState.runtimeState,
            dashboardMetrics = dashboardMetrics,
            latestCapturePreview = capturePreview,
            runtimeActions = runtimeActions,
            eventState = overviewUiState.eventState,
            eventActions = eventActions,
            assistMcpStatus = AssistMcpOverviewStatus(
                phoneServerRunning = mcpState.running,
                phoneServerStatus = mcpState.statusText,
                assistClientEnabled = assistState.enabled,
                assistClientStatus = assistState.statusText,
                assistLastError = assistState.lastError,
                liveCapabilityCount = LiveToolCapabilityStore.snapshot().size
            ),
            debugHighlightLongContent = debugHighlightLongContent
        )
    }
}

@Composable
private fun ChatRoute(
    chatViewModel: ChatViewModel,
    overviewController: OverviewController,
    settingsViewModel: SettingsViewModel,
    modelSettings: com.clawdroid.app.data.model.ModelSettings,
    onVoiceClick: () -> Unit,
    onImageClick: () -> Unit,
    modifier: Modifier
) {
    val chatUiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val overviewUiState by overviewController.uiState.collectAsStateWithLifecycle()
    val connectionSummary = overviewUiState.connectionSummary
    val eventStreaming = overviewUiState.eventState.eventStreaming
    val state = remember(chatUiState, modelSettings, eventStreaming, connectionSummary) {
        buildChatConsoleState(
            chatState = chatUiState,
            modelSettings = modelSettings,
            eventStreaming = eventStreaming,
            connectionSummary = connectionSummary
        )
    }
    val latestVoiceClick = rememberUpdatedState(onVoiceClick)
    val latestImageClick = rememberUpdatedState(onImageClick)
    val actions = remember(chatViewModel, modelSettings, eventStreaming) {
        chatViewModel.buildChatConsoleActions(
            modelSettings = modelSettings,
            eventStreaming = eventStreaming,
            onModelCallSuccess = settingsViewModel::markLatestModelCallSuccess,
            onVoiceClick = { latestVoiceClick.value.invoke() },
            onImageClick = { latestImageClick.value.invoke() }
        )
    }

    ChatPage(
        state = state,
        actions = actions,
        modifier = modifier.fillMaxSize()
    )
}

@Composable
private fun SettingsRoute(
    settingsViewModel: SettingsViewModel,
    overviewController: OverviewController,
    navigationViewModel: NavigationViewModel,
    mcpController: McpServerController,
    assistController: AssistMcpController,
    runtimeClient: ClawRuntimeClient,
    modifier: Modifier
) {
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val overviewState by overviewController.uiState.collectAsStateWithLifecycle()
    val mcpState by mcpController.state.collectAsStateWithLifecycle()
    val assistState by assistController.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(enabled = true) {
        when (settingsState.settingsNav) {
            is SettingsNav.Category -> settingsViewModel.navigateHub()
            SettingsNav.Hub -> navigationViewModel.selectPage(ConsolePage.Overview)
        }
    }

    val screenState = buildSettingsScreenState(
        versionName = BuildConfig.VERSION_NAME,
        packageName = context.packageName,
        socketName = runtimeClient.socketDisplayName(),
        connectionSummary = overviewState.connectionSummary,
        runtimeVersionStatus = overviewState.runtimeState.versionStatus,
        runtimeHealthStatus = overviewState.runtimeState.healthStatus,
        runtimeLastErrorStatus = overviewState.runtimeState.lastErrorStatus,
        runtimeConfigSummary = overviewState.runtimeState.runtimeConfigSummary,
        runtimeCompatBanner = overviewState.runtimeState.compatBanner,
        settingsState = settingsState,
        mcpState = mcpState,
        assistState = assistState
    )
    val actions = remember(settingsViewModel, mcpController, assistController, overviewController) {
        settingsViewModel.buildSettingsScreenActions(
            onMcpEnabledChanged = mcpController::setEnabled,
            onMcpPortChanged = mcpController::updatePort,
            onMcpRegenerateToken = mcpController::regenerateToken,
            onAssistEnabledChanged = assistController::setEnabled,
            onAssistHostUrlChanged = assistController::updateHostUrl,
            onAssistTokenChanged = assistController::updateToken,
            onAssistProbe = assistController::probe,
            onGetVersion = overviewController::getVersion,
            onGetHealth = overviewController::getHealth,
            onGetLastError = overviewController::getLastError
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 20.dp)
    ) {
        item(key = "settings-header") {
            val category = (settingsState.settingsNav as? SettingsNav.Category)?.id
            val onBack: (() -> Unit)? = when (settingsState.settingsNav) {
                is SettingsNav.Category -> ({ settingsViewModel.navigateHub() })
                SettingsNav.Hub -> ({ navigationViewModel.selectPage(ConsolePage.Overview) })
            }
            if (category != null) {
                BrandPageHeader(
                    title = category.title,
                    subtitle = category.subtitle,
                    onBackClick = onBack,
                    compactTitle = true
                )
            } else {
                BrandPageHeader(
                    title = "设置",
                    subtitle = "模型、工具与诊断",
                    onBackClick = onBack
                )
            }
        }
        settingsScreen(state = screenState, actions = actions)
    }
}
