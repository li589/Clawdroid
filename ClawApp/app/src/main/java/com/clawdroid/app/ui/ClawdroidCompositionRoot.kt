package com.clawdroid.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.clawdroid.app.ai.AiAgentOrchestrator
import com.clawdroid.app.automation.AccessibilityObservationSource
import com.clawdroid.app.automation.LsposedFocusObservationSource
import com.clawdroid.app.automation.LsposedViewObservationSource
import com.clawdroid.app.automation.ObservationPort
import com.clawdroid.app.agent.task.StepKind
import com.clawdroid.app.agent.task.TaskEngine
import com.clawdroid.app.agent.task.TaskStateMachine
import com.clawdroid.app.data.AppSettingsStore
import com.clawdroid.app.data.JsonTaskStore
import com.clawdroid.app.data.SharedPreferencesTaskStorage
import com.clawdroid.app.focus.XposedFocusRuntimeReporter
import com.clawdroid.app.focus.XposedViewRuntimeReporter
import com.clawdroid.app.mcp.McpJsonRpcHandler
import com.clawdroid.app.mcp.McpServerController
import com.clawdroid.app.mcp.assist.AssistMcpController
import com.clawdroid.app.runtime.ClawRuntimeClient
import com.clawdroid.app.runtime.RuntimeEventService
import com.clawdroid.app.skills.ClawSkillCatalog
import com.clawdroid.app.tools.CapabilityProbe
import com.clawdroid.app.tools.ClawToolDispatcher
import com.clawdroid.app.tools.ClawToolExecutor
import com.clawdroid.app.tools.LiveToolCapabilityStore
import com.clawdroid.app.tools.RuntimeEventToolBridge
import com.clawdroid.app.tools.ToolPermissionGate
import com.clawdroid.app.tools.ToolServiceRegistry
import com.clawdroid.app.tools.adapters.AutomationAdapter
import com.clawdroid.app.tools.adapters.LocalToolAdapter
import com.clawdroid.app.tools.adapters.RuntimeTaskAdapter
import com.clawdroid.app.ui.task.TaskCenterViewModel
import com.clawdroid.app.ui.task.rememberTaskCenterViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Long-lived App graph: Runtime event service, tool stack, MCP/Assist, Xposed reporters.
 * UI shell should only consume this graph + ViewModels — not construct tools inline.
 */
internal data class ClawdroidGraph(
    val runtimeEventService: RuntimeEventService,
    val toolServices: ToolServiceRegistry,
    val toolDispatcher: ClawToolDispatcher,
    val taskEngine: TaskEngine,
    val assistController: AssistMcpController,
    val mcpController: McpServerController,
    val overviewController: OverviewController,
    val chatViewModel: ChatViewModel,
    val taskCenterViewModel: TaskCenterViewModel,
    val settingsViewModel: SettingsViewModel,
    val navigationViewModel: NavigationViewModel
)

@Composable
internal fun rememberClawdroidCompositionRoot(
    runtimeClient: ClawRuntimeClient,
    toolExecutor: ClawToolExecutor,
    previewLimitBytes: Int
): ClawdroidGraph {
    val context = LocalContext.current
    val appContext = context.applicationContext
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

    val assistController = remember(appContext) {
        AssistMcpController(appContext)
    }
    val toolServices = remember(appContext, runtimeClient, assistController) {
        ToolServiceRegistry.create(
            context = appContext,
            runtimeClient = runtimeClient,
            assist = assistController
        )
    }

    LaunchedEffect(toolExecutor) {
        runCatching { CapabilityProbe(toolExecutor).refreshIfStale() }
    }
    LaunchedEffect(appContext) {
        ClawSkillCatalog.bindContext(appContext)
        AiAgentOrchestrator.bindContext(appContext)
    }

    val toolDispatcher = remember(
        toolExecutor,
        runtimeEventService,
        previewLimitBytes,
        toolServices,
        appContext,
        assistController
    ) {
        ClawToolDispatcher(
            executor = toolExecutor,
            previewLimitBytes = previewLimitBytes,
            permissionGate = ToolPermissionGate(
                context = appContext,
                assistEnabled = { assistController.isEnabled() },
                knownCapabilities = { LiveToolCapabilityStore.snapshot() },
                isToolAllowed = { toolId ->
                    AppSettingsStore.loadAgentOrchestrationSettings(appContext).isToolAllowed(toolId)
                },
                agentCallsEnabled = {
                    AppSettingsStore.loadAgentOrchestrationSettings(appContext).agentCallsEnabled
                }
            ),
            appContext = appContext,
            services = toolServices,
            eventBridge = RuntimeEventToolBridge(runtimeEventService)
        )
    }

    val observationPort = remember {
        ObservationPort(
            lsposedFocus = LsposedFocusObservationSource(),
            lsposedView = LsposedViewObservationSource(),
            accessibility = AccessibilityObservationSource()
        )
    }
    val automationAdapter = remember(observationPort, toolDispatcher) {
        AutomationAdapter(observationPort, toolDispatcher)
    }
    val taskEngine = remember(toolDispatcher, observationPort, automationAdapter, eventScope) {
        TaskEngine(
            store = JsonTaskStore(SharedPreferencesTaskStorage(appContext)),
            adapters = mapOf(
                StepKind.Tool to LocalToolAdapter(toolDispatcher),
                StepKind.RuntimeTask to RuntimeTaskAdapter(toolDispatcher),
                StepKind.ObservePage to automationAdapter,
                StepKind.ConfirmPage to automationAdapter,
                StepKind.ResolveTarget to automationAdapter,
                StepKind.SafeTap to automationAdapter
            ),
            scope = eventScope
        )
    }
    LaunchedEffect(toolDispatcher, taskEngine) {
        toolDispatcher.bindTaskEngine(taskEngine)
        taskEngine.restoreAll()
        overviewController.updateActiveExecution(
            taskEngine.store.load()
                .filterNot { TaskStateMachine.isTerminal(it.state) }
                .maxByOrNull { it.updatedAtEpochMs }
        )
    }
    val taskCenterViewModel = rememberTaskCenterViewModel(taskEngine)

    DisposableEffect(overviewController, toolDispatcher) {
        overviewController.setCaptureArtifactListener { artifact ->
            toolDispatcher.rememberCapture(artifact)
        }
        onDispose {
            overviewController.setCaptureArtifactListener(null)
        }
    }

    val chatViewModel = rememberChatViewModel(context, overviewController, toolDispatcher, taskEngine)
    LaunchedEffect(taskEngine, chatViewModel, overviewController) {
        taskEngine.events.collect { event ->
            val tasks = taskEngine.store.load()
            overviewController.updateActiveExecution(
                tasks.filterNot { TaskStateMachine.isTerminal(it.state) }
                    .maxByOrNull { it.updatedAtEpochMs }
            )
            tasks.firstOrNull { it.id == event.taskId }?.let(chatViewModel::onExecutionTaskEvent)
        }
    }
    DisposableEffect(overviewController, chatViewModel, taskEngine) {
        overviewController.setRuntimeTaskEventListener { snapshot ->
            chatViewModel.onRuntimeTaskEvent(snapshot)
            eventScope.launch { taskEngine.applyRuntimeSnapshot(snapshot) }
        }
        onDispose {
            overviewController.setRuntimeTaskEventListener(null)
        }
    }

    val settingsViewModel = rememberSettingsViewModel(context)
    val navigationViewModel = rememberNavigationViewModel()

    val mcpController = remember(appContext, toolDispatcher, toolExecutor) {
        McpServerController(
            appContext = appContext,
            handlerFactory = {
                McpJsonRpcHandler(
                    dispatcher = toolDispatcher,
                    appContext = appContext,
                    capabilityProbe = CapabilityProbe(toolExecutor)
                )
            }
        )
    }
    DisposableEffect(mcpController) {
        mcpController.restoreIfEnabled()
        onDispose { mcpController.pause() }
    }

    return ClawdroidGraph(
        runtimeEventService = runtimeEventService,
        toolServices = toolServices,
        toolDispatcher = toolDispatcher,
        taskEngine = taskEngine,
        assistController = assistController,
        mcpController = mcpController,
        overviewController = overviewController,
        chatViewModel = chatViewModel,
        taskCenterViewModel = taskCenterViewModel,
        settingsViewModel = settingsViewModel,
        navigationViewModel = navigationViewModel
    )
}
