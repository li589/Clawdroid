package com.clawdroid.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewControllerFormattingTest {
    @Test
    fun buildStartupCheckSummaryForRootSuccessContainsDiagnosisAndRestoreSummary() {
        val summary = buildStartupCheckSummary(
            rootRequested = true,
            rootResult = rootActionResult(success = true, output = "ok"),
            finalStatus = overviewEnvironmentStatus(
                rootGranted = true,
                notificationPermissionGranted = true,
                writeSettingsGranted = true,
                allFilesAccessGranted = true,
                accessibilityEnabled = true,
                magiskDaemonRunning = true,
                magiskModuleInstalled = true,
                magiskModuleEnabled = true,
                runtimeDaemonRunning = true
            ),
            autoGrantMessages = listOf("自动恢复一键权限")
        )

        assertTrue(summary.contains("启动自检: Root 会话正常"))
        assertTrue(summary.contains("环境诊断: Root 与模块链路正常"))
        assertTrue(summary.contains("自动恢复一键权限"))
    }

    @Test
    fun buildStartupCheckSummaryForRootTimeoutContainsRetryHint() {
        val summary = buildStartupCheckSummary(
            rootRequested = true,
            rootResult = rootActionResult(success = false, output = "timeout", timedOut = true),
            finalStatus = overviewEnvironmentStatus(rootGranted = false),
            autoGrantMessages = emptyList()
        )

        assertTrue(summary.contains("等待 Magisk Root 授权超时"))
        assertTrue(summary.contains("无需自动恢复授权"))
    }

    @Test
    fun createInitialOverviewUiStateUsesExpectedDefaults() {
        val state = createInitialOverviewUiState(createRuntimeClientMock())

        assertEquals("尚未检测本地环境", state.permissionState.localEnvironmentSummary)
        assertEquals("未执行权限修复", state.permissionState.permissionActionStatus)
        assertEquals("尚未建立会话", state.runtimeState.session.summary)
        assertEquals("未读取", state.runtimeState.capabilityStatus)
        assertEquals(defaultShellCommandOptions().first(), state.runtimeState.shell.selectedCommand)
        assertEquals("session=Disconnected, auth=未协商, events=idle", state.connectionSummary)
    }
}
