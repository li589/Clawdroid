package com.clawdroid.app.focus

import com.clawdroid.app.fault.safeLaunch
import com.clawdroid.app.runtime.ClawRuntimeClient
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicReference

/**
 * Best-effort bridge: [LiveXposedViewStore] → Runtime `report_xposed_view`.
 */
class XposedViewRuntimeReporter(
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
        LiveXposedViewStore.addListener(listener)
        if (LiveXposedViewStore.hasLive()) {
            reportCurrent()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        LiveXposedViewStore.removeListener(listener)
    }

    private fun reportCurrent() {
        val json = LiveXposedViewStore.readRaw()?.trim().orEmpty()
        if (json.isBlank()) return
        if (json == lastReportedJson.get()) return
        scope.safeLaunch("view:runtime-report") {
            val result = runtimeClient.reportXposedView(json)
            if (result.isSuccess) {
                lastReportedJson.set(json)
            }
        }
    }
}
