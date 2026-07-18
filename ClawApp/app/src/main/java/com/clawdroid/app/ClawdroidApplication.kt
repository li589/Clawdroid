package com.clawdroid.app

import android.app.Application
import com.clawdroid.app.env.LocalRuntimeStatus
import com.clawdroid.app.fault.FaultIsolation

/**
 * Process entry. L6 fault isolation installs an uncaught-exception logger that
 * writes [files/fault-crash.log] then forwards to the previous handler — it is a
 * diagnostic net, not a substitute for L1–L4 request/tool guards.
 */
class ClawdroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FaultIsolation.installUncaughtExceptionHandler(this)
        LocalRuntimeStatus.synchronizeMarker(this)
    }
}
