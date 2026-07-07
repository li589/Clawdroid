package com.clawdroid.app

import android.app.Application
import com.clawdroid.app.env.LocalRuntimeStatus

class ClawdroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LocalRuntimeStatus.synchronizeMarker(this)
    }
}
