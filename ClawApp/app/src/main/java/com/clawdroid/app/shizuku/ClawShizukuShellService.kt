package com.clawdroid.app.shizuku

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Runs in a Shizuku UserService process (shell/root uid). Keep logic minimal and allowlist-gated
 * by the app-side caller before invoking [exec].
 */
class ClawShizukuShellService : IClawShizukuShell.Stub {
    constructor() {
        Log.i(TAG, "constructor")
    }

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) {
        Log.i(TAG, "constructor with Context")
    }

    override fun destroy() {
        Log.i(TAG, "destroy")
        System.exit(0)
    }

    override fun exec(command: String?): String {
        val cmd = command?.trim().orEmpty()
        if (cmd.isBlank()) {
            return "exit=-1\nstdout=\nstderr=empty_command"
        }
        val process = ProcessBuilder("sh", "-c", cmd)
            .redirectErrorStream(false)
            .start()
        return try {
            val finished = process.waitFor(12, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return "exit=-1\nstdout=\nstderr=timeout"
            }
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            buildString {
                appendLine("exit=${process.exitValue()}")
                appendLine("stdout=${stdout.take(8_000)}")
                append("stderr=${stderr.take(2_000)}")
            }
        } finally {
            runCatching { process.destroy() }
        }
    }

    companion object {
        private const val TAG = "ClawShizukuShell"
    }
}
