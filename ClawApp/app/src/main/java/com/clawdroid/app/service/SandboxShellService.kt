package com.clawdroid.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.clawdroid.app.tools.ISandboxShell
import com.clawdroid.app.tools.SandboxShellEngine

/**
 * Runs sandbox shell execution in a dedicated [:sandbox] process to isolate ProcessBuilder.
 */
class SandboxShellService : Service() {
    private val engine by lazy { SandboxShellEngine(applicationContext) }

    private val binder = object : ISandboxShell.Stub() {
        override fun exec(command: String?, timeoutMs: Long): String {
            val result = engine.exec(command.orEmpty(), timeoutMs)
            return SandboxShellEngine.encodeResult(result)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
