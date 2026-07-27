package com.clawdroid.app.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.clawdroid.app.env.ShizukuSupport
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clawdroid.app.BuildConfig
import com.clawdroid.app.env.AppPermissionManager
import com.clawdroid.app.runtime.ClawRuntimeClient
import com.clawdroid.app.tools.ClawToolExecutor
import com.clawdroid.app.tools.LiveToolCapabilityStore
import kotlinx.coroutines.delay

/** Visible floating pill height (excludes system nav inset). Real pill ~68dp; keep buffer. */
private val floatingNavPillHeight = 68.dp
private val floatingNavGap = 2.dp
private const val chatNavAutoHideMs = 2800L
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

    val overviewUiState by overviewController.uiState.collectAsStateWithLifecycle()
    val overviewDashboardMetrics by overviewController.dashboardMetrics.collectAsStateWithLifecycle()
    val overviewCapturePreview by overviewController.latestCapturePreview.collectAsStateWithLifecycle()
    val automationUiState by overviewController.automationController.state.collectAsStateWithLifecycle()
    val chatUiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val navigationUiState by navigationViewModel.uiState.collectAsStateWithLifecycle()
    val mcpUiState by mcpController.state.collectAsStateWithLifecycle()
    val assistUiState by assistController.state.collectAsStateWithLifecycle()
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
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            chatViewModel.onImagePicked(uri)
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

    LaunchedEffect(Unit) {
        ShizukuSupport.ensureInitialized {
            overviewController.refreshLocalEnvironment()
        }
        ShizukuSupport.addPermissionResultListener { _, _ ->
            overviewController.refreshLocalEnvironment()
        }
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
                // Prefer system notification settings page for toggle UX consistency.
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
        runtimeCompatBanner = overviewRuntimeState.compatBanner,
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
        onAssistProbe = assistController::probe,
        onGetVersion = overviewController::getVersion,
        onGetHealth = overviewController::getHealth,
        onGetLastError = overviewController::getLastError
    )
    val chatConsoleState = remember(
        chatUiState,
        settingsUiState.modelSettings,
        overviewEventState.eventStreaming,
        connectionSummary
    ) {
        buildChatConsoleState(
            chatState = chatUiState,
            modelSettings = settingsUiState.modelSettings,
            eventStreaming = overviewEventState.eventStreaming,
            connectionSummary = connectionSummary
        )
    }
    val latestVoiceClick = rememberUpdatedState(newValue = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出你要执行的指令")
        }
        voiceInputLauncher.launch(intent)
    })
    val latestImageClick = rememberUpdatedState(newValue = {
        imagePickerLauncher.launch("image/*")
    })
    val chatConsoleActions = remember(
        chatViewModel,
        settingsUiState.modelSettings,
        overviewEventState.eventStreaming
    ) {
        chatViewModel.buildChatConsoleActions(
            modelSettings = settingsUiState.modelSettings,
            eventStreaming = overviewEventState.eventStreaming,
            onModelCallSuccess = settingsViewModel::markLatestModelCallSuccess,
            onVoiceClick = { latestVoiceClick.value.invoke() },
            onImageClick = { latestImageClick.value.invoke() }
        )
    }

    val currentPage = navigationUiState.currentPage
    var bottomNavVisible by remember { mutableStateOf(true) }
    var navRevealToken by remember { mutableStateOf(0) }
    val navBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val isImeVisible = imeBottom > imeVisibleThreshold
    // While IME is open: hide floating nav and do not reserve pill space.
    val showFloatingNav = bottomNavVisible && !isImeVisible
    val contentBottomPad = when {
        isImeVisible -> 4.dp
        showFloatingNav -> navBarsBottom + floatingNavPillHeight + floatingNavGap
        else -> navBarsBottom + 4.dp
    }

    BackHandler(enabled = currentPage == ConsolePage.Settings) {
        when (settingsUiState.settingsNav) {
            is SettingsNav.Category -> settingsViewModel.navigateHub()
            SettingsNav.Hub -> navigationViewModel.selectPage(ConsolePage.Overview)
        }
    }

    LaunchedEffect(currentPage) {
        bottomNavVisible = true
        navRevealToken += 1
        if (currentPage != ConsolePage.Settings) {
            settingsViewModel.navigateHub()
        }
    }

    LaunchedEffect(currentPage, bottomNavVisible, navRevealToken, isImeVisible) {
        if (currentPage != ConsolePage.Chat || !bottomNavVisible || isImeVisible) return@LaunchedEffect
        delay(chatNavAutoHideMs)
        bottomNavVisible = false
    }

    fun revealBottomNav() {
        if (isImeVisible) return
        bottomNavVisible = true
        navRevealToken += 1
    }

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
                    ConsolePage.Chat -> {
                        ChatPage(
                            state = chatConsoleState,
                            actions = chatConsoleActions,
                            onScrollTowardBottom = { bottomNavVisible = false },
                            onScrollTowardTop = { revealBottomNav() },
                            onComposerInteract = { revealBottomNav() },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = contentBottomPad)
                        )
                    }

                    ConsolePage.Overview, ConsolePage.Settings -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = contentBottomPad),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                top = 10.dp,
                                end = 16.dp,
                                bottom = 20.dp
                            )
                        ) {
                            item(key = "brand-header") {
                                val settingsCategory = (settingsUiState.settingsNav as? SettingsNav.Category)?.id
                                val settingsBackClick: (() -> Unit)? =
                                    if (currentPage != ConsolePage.Settings) {
                                        null
                                    } else {
                                        when (settingsUiState.settingsNav) {
                                            is SettingsNav.Category -> {
                                                { settingsViewModel.navigateHub() }
                                            }
                                            SettingsNav.Hub -> {
                                                { navigationViewModel.selectPage(ConsolePage.Overview) }
                                            }
                                        }
                                    }
                                when (currentPage) {
                                    ConsolePage.Settings -> {
                                        if (settingsCategory != null) {
                                            BrandPageHeader(
                                                title = settingsCategory.title,
                                                subtitle = settingsCategory.subtitle,
                                                onBackClick = settingsBackClick,
                                                compactTitle = true
                                            )
                                        } else {
                                            BrandPageHeader(
                                                subtitle = "模型、提示词与协助接入",
                                                onBackClick = settingsBackClick
                                            )
                                        }
                                    }
                                    ConsolePage.Overview -> BrandPageHeader(
                                        subtitle = "状态与快捷操作"
                                    )
                                    else -> BrandPageHeader()
                                }
                            }
                            when (currentPage) {
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

                                ConsolePage.Chat -> Unit
                            }
                        }
                    }
                }

                if (!showFloatingNav && currentPage == ConsolePage.Chat && !isImeVisible) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(navBarsBottom + 18.dp)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { revealBottomNav() }
                    )
                }

                AnimatedVisibility(
                    visible = showFloatingNav,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it }
                ) {
                    FloatingBottomNavBar(
                        currentPage = currentPage,
                        onPageSelected = { page ->
                            revealBottomNav()
                            navigationViewModel.selectPage(page)
                        }
                    )
                }
            }
        }
    }
}
