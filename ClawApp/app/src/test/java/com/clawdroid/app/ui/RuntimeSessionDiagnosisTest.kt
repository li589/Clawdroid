package com.clawdroid.app.ui

import com.clawdroid.app.runtime.ClawRuntimeConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSessionDiagnosisTest {
    @Test
    fun extractRuntimeErrorCodeFromBracketedCode() {
        assertEquals(1002, extractRuntimeErrorCode("request failed: [1002] signature mismatch"))
    }

    @Test
    fun describeSignatureMismatchGivesActionableHint() {
        val diagnosis = describeRuntimeErrorCode(1002)!!
        assertEquals("签名摘要不匹配", diagnosis.title)
        assertTrue(diagnosis.actionHint.contains("allowed_signatures"))
        assertTrue(diagnosis.asMultilineString().contains("错误码: 1002"))
    }

    @Test
    fun buildRuntimeSessionDiagnosisPrefersErrorCodeOverGenericClosedState() {
        val diagnosis = buildRuntimeSessionDiagnosis(
            localStatus = overviewEnvironmentStatus(
                rootGranted = true,
                magiskDaemonRunning = true,
                magiskModuleInstalled = true,
                magiskModuleEnabled = true,
                runtimeDaemonRunning = true
            ),
            runtimeState = OverviewRuntimeState(
                latestDaemonMetrics = "等待事件流",
                latestRuntimeProcessMetrics = "等待事件流",
                latestWindowSummary = "unknown",
                session = SessionInfo(
                    state = ClawRuntimeConnectionState.Closed,
                    trace = "Disconnected -> Closed",
                    authMode = "failed",
                    summary = "handshake failed: [1002] signature mismatch",
                    runtimeLoaded = false,
                    runtimeProcess = "",
                    runtimeLoadedAtEpochMs = 0L,
                    degradedReason = "",
                    runtimeSocketDisplay = "sock",
                    packageDisplay = "pkg",
                    signatureDigestDisplay = "sha256:x"
                ),
                shell = ShellState(
                    status = "未执行 Shell",
                    output = "暂无输出",
                    selectedCommand = "wm size",
                    dropdownExpanded = false,
                    commandOptions = listOf("wm size")
                ),
                pingStatus = "未执行",
                versionStatus = "未执行",
                healthStatus = "未执行",
                runtimeStatus = "未执行",
                lastErrorStatus = "未执行",
                runtimeConfigSummary = "未读取",
                capabilityStatus = "未执行",
                captureStatus = "未执行",
                readFileStatus = "未执行",
                tapStatus = "未执行",
                swipeStatus = "未执行",
                keyeventStatus = "未执行",
                capture = CaptureState(
                    latestPath = "",
                    latestFormat = "",
                    latestDimensions = "",
                    latestFileSize = 0L
                )
            )
        )

        assertEquals("签名摘要不匹配", diagnosis.title)
        assertEquals(1002, diagnosis.errorCode)
    }
}
