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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.clawdroid.app.mcp.McpJsonRpcHandler
import com.clawdroid.app.mcp.McpServerController
import com.clawdroid.app.mcp.assist.AssistMcpController
import com.clawdroid.app.runtime.ClawRuntimeClient
import com.clawdroid.app.focus.XposedFocusRuntimeReporter
import com.clawdroid.app.focus.XposedViewRuntimeReporter
import com.clawdroid.app.runtime.RuntimeEventService
import com.clawdroid.app.ai.AiAgentOrchestrator
import com.clawdroid.app.skills.ClawSkillCatalog
import com.clawdroid.app.tools.CapabilityProbe
import com.clawdroid.app.tools.ClawToolDispatcher
import com.clawdroid.app.tools.ClawToolExecutor
import com.clawdroid.app.tools.LiveToolCapabilityStore
import com.clawdroid.app.tools.RuntimeEventToolBridge
import com.clawdroid.app.tools.ToolPermissionGate
import com.clawdroid.app.tools.ToolServiceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
    val eventScope = remember {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
    DisposableEffect(eventScope) {
        onDispose { eventScope.cancel() }
    }
    val runtimeEventService = remember(runtimeClient, eventScope) {
        RuntimeEventService(runtimeClient, eventScope)
    }
    DisposableEffect(runtimeEventService) {
        onDispose { runtimeEventService.shutdown() }
    }
    val xposedFocusReporter = remember(runtimeClient, eventScope) {
        XposedFocusRuntimeReporter(runtimeClient, eventScope)
    }
    DisposableEffect(xposedFocusReporter) {
        xposedFocusReporter.start()
        onDispose { xposedFocusReporter.stop() }
    }
    val xposedViewReporter = remember(runtimeClient, eventScope) {
        XposedViewRuntimeReporter(runtimeClient, eventScope)
    }
    DisposableEffect(xposedViewReporter) {
        xposedViewReporter.start()
        onDispose { xposedViewReporter.stop() }
    }
    val overviewController = rememberOverviewController(
        context = context,
        runtimeClient = runtimeClient,
        toolExecutor = toolExecutor,
        previewLimitBytes = previewLimitBytes,
        eventService = runtimeEventService
    )
    val overviewUiState by overviewController.uiState.collectAsStateWithLifecycle()
    val overviewDashboardMetrics by overviewController.dashboardMetrics.collectAsStateWithLifecycle()
    val overviewCapturePreview by overviewController.latestCapturePreview.collectAsStateWithLifecycle()
    val automationUiState by overviewController.automationController.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    val assistController = remember(context) {
        AssistMcpController(context.applicationContext)
    }
    val toolServices = remember(context, runtimeClient, assistController) {
        ToolServiceRegistry.create(
            context = context.applicationContext,
            runtimeClient = runtimeClient,
            assist = assistController
        )
    }
    LaunchedEffect(toolExecutor) {
        runCatching { CapabilityProbe(toolExecutor).refreshIfStale() }
    }
    LaunchedEffect(context) {
        ClawSkillCatalog.bindContext(context.applicationContext)
        AiAgentOrchestrator.bindContext(context.applicationContext)
    }
    val toolDispatcher = remember(
        toolExecutor,
        runtimeEventService,
        previewLimitBytes,
        toolServices
    ) {
        ClawToolDispatcher(
            executor = toolExecutor,
            previewLimitBytes = previewLimitBytes,
            permissionGate = ToolPermissionGate(
                context = context.applicationContext,
                assistEnabled = { assistController.isEnabled() },
                knownCapabilities = { LiveToolCapabilityStore.snapshot() }
            ),
            appContext = context.applicationContext,
            services = toolServices,
            eventBridge = RuntimeEventToolBridge(runtimeEventService)
        )
    }
    DisposableEffect(overviewController, toolDispatcher) {
        overviewController.setCaptureArtifactListener { artifact ->
            toolDispatcher.rememberCapture(artifact)
        }
        onDispose {
            overviewController.setCaptureArtifactListener(null)
        }
    }
    val chatViewModel = rememberChatViewModel(context, overviewController, toolDispatcher)
    DisposableEffect(overviewController, chatViewModel) {
        overviewController.setRuntimeTaskEventListener { snapshot ->
            chatViewModel.onRuntimeTaskEvent(snapshot)
        }
        onDispose {
            overviewController.setRuntimeTaskEventListener(null)
        }
    }
    val chatUiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val settingsViewModel = rememberSettingsViewModel(context)
    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val navigationViewModel = rememberNavigationViewModel()
    val navigationUiState by navigationViewModel.uiState.collectAsStateWithLifecycle()

    val mcpController = remember(context, toolDispatcher) {
        McpServerController(
            appContext = context.applicationContext,
            handlerFactory = {
                McpJsonRpcHandler(
                    dispatcher = toolDispatcher,
                    appContext = context.applicationContext,
                    capabilityProbe = CapabilityProbe(toolExecutor)
                )
            }
        )
    }
    val mcpUiState by mcpController.state.collectAsStateWithLifecycle()
    val assistUiState by assistController.state.collectAsStateWithLifecycle()

    DisposableEffect(mcpController) {
        mcpController.restoreIfEnabled()
        onDispose { mcpController.pause() }
    }

    DisposableEffect(lifecycleOwner, overviewController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                overviewController.onHostStarted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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
        settingsState = settingsUiState,
        mcpState = mcpUiState,
        assistState = assistUiState
    )
    val settingsScreenActions = settingsViewModel.buildSettingsScreenActions(
        onMcpEnabledChanged = mcpController::setEnabled,
        onMcpPortChanged = mcpController::updatePort,
        onMcpRegenerateToken = mcpController::regenerateToken,
        onAssistEnabledChanged = assistController::setEnabled,
        onAssistHostUrlChanged = assistController::updateHostUrl,
        onAssistTokenChanged = assistController::updateToken,
        onAssistProbe = assistController::probe
    )
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
                                assistMcpStatus = AssistMcpOverviewStatus(
                                    phoneServerRunning = mcpUiState.running,
                                    phoneServerStatus = mcpUiState.statusText,
                                    assistClientEnabled = assistUiState.enabled,
                                    assistClientStatus = assistUiState.statusText,
                                    assistLastError = assistUiState.lastError,
                                    liveCapabilityCount = LiveToolCapabilityStore.snapshot().size
                                ),
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
