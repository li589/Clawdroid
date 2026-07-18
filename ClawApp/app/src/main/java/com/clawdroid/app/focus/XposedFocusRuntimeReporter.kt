package com.clawdroid.app.focus

import com.clawdroid.app.fault.safeLaunch
import com.clawdroid.app.runtime.ClawRuntimeClient
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicReference

/**
 * Best-effort bridge: [LiveXposedFocusStore] → Runtime `report_xposed_focus`
 * so subscribers receive `xposed_focus_changed`. Failures never clear the live store.
 */
class XposedFocusRuntimeReporter(
    private val runtimeClient: ClawRuntimeClient,
    private val scope: CoroutineScope
) {
    private val lastReportedJson = AtomicReference<String?>(null)
    private var started = false

    private val listener: (String) -> Unit = {
        reportCurrent()
    }

    fun start() {
        if (started) return
        started = true
        LiveXposedFocusStore.addListener(listener)
        if (LiveXposedFocusStore.hasLive()) {
            reportCurrent()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        LiveXposedFocusStore.removeListener(listener)
    }

    private fun reportCurrent() {
        val json = LiveXposedFocusStore.readRaw()?.trim().orEmpty()
        if (json.isBlank()) return
        if (json == lastReportedJson.get()) return
        scope.safeLaunch("focus:runtime-report") {
            val result = runtimeClient.reportXposedFocus(json)
            if (result.isSuccess) {
                lastReportedJson.set(json)
            }
        }
    }
}
