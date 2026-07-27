package com.clawdroid.app.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.clawdroid.app.service.SandboxShellService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Main-process facade that forwards sandbox shell calls to [SandboxShellService] in [:sandbox].
 */
class SandboxShellClient(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val shellRef = AtomicReference<ISandboxShell?>()
    private var bindLatch = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellRef.set(ISandboxShell.Stub.asInterface(service))
            bindLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellRef.set(null)
            bindRequested = false
            bindLatch = CountDownLatch(1)
        }
    }

    @Volatile
    private var bindRequested = false

    fun exec(command: String, timeoutMs: Long = 8_000): ClawToolCallResult {
        val shell = ensureBound()
            ?: return ClawToolCallResult(false, "沙箱 Shell 未连接", error = "sandbox_unavailable")
        return runCatching {
            SandboxShellEngine.decodeResult(shell.exec(command, timeoutMs))
        }.getOrElse { error ->
            ClawToolCallResult(false, "沙箱 Shell 调用失败: ${error.message}", error = error.message)
        }
    }

    fun isCommandAllowed(command: String): Boolean =
        SandboxShellEngine(appContext).isCommandAllowed(command)

    private fun ensureBound(): ISandboxShell? {
        shellRef.get()?.let { return it }
        synchronized(this) {
            shellRef.get()?.let { return it }
            if (!bindRequested) {
                bindRequested = true
                appContext.bindService(
                    Intent(appContext, SandboxShellService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            }
        }
        bindLatch.await(3, TimeUnit.SECONDS)
        return shellRef.get()
    }
}
