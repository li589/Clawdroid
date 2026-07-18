package com.clawdroid.app.env

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.clawdroid.app.BuildConfig
import com.clawdroid.app.shizuku.ClawShizukuShellService
import com.clawdroid.app.shizuku.IClawShizukuShell
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * Thin wrapper around Shizuku binder. Safe when the API jar is present but service is down.
 */
object ShizukuSupport {
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** Fixed + parameterized allowlist for [execShell]. */
    private val fixedCommands = setOf(
        "id",
        "whoami",
        "uname -a",
        "getprop ro.build.version.release",
        "getprop ro.product.model"
    )
    private val parameterizedPatterns = listOf(
        Pattern.compile("^pm path [a-zA-Z0-9._]+$"),
        Pattern.compile("^dumpsys package [a-zA-Z0-9._]+$"),
        Pattern.compile("^pidof [a-zA-Z0-9._]+$")
    )

    @Volatile
    private var cachedShell: IClawShizukuShell? = null

    private val bindLock = Any()

    fun isManagerInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun permissionGranted(): Boolean {
        return runCatching {
            isBinderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun requestPermission(requestCode: Int = 1001): Result<String> = runCatching {
        check(isBinderAlive()) { "shizuku_binder_dead" }
        if (permissionGranted()) {
            return@runCatching "already_granted"
        }
        Shizuku.requestPermission(requestCode)
        "permission_requested"
    }

    fun statusSummary(context: Context): String {
        return buildString {
            appendLine("manager_installed=${isManagerInstalled(context)}")
            appendLine("binder_alive=${isBinderAlive()}")
            appendLine("permission_granted=${permissionGranted()}")
            appendLine("user_service_bound=${cachedShell != null}")
            append(
                "uid=${runCatching { if (isBinderAlive()) Shizuku.getUid() else -1 }.getOrDefault(-1)}"
            )
        }
    }

    fun isCommandAllowed(command: String): Boolean {
        val trimmed = command.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isBlank() || trimmed.contains('\n') || trimmed.contains(';') ||
            trimmed.contains('|') || trimmed.contains('&') || trimmed.contains('`') ||
            trimmed.contains('$') || trimmed.contains('>') || trimmed.contains('<')
        ) {
            return false
        }
        if (trimmed in fixedCommands) return true
        return parameterizedPatterns.any { it.matcher(trimmed).matches() }
    }

    /**
     * Execute an allowlisted shell command via Shizuku UserService.
     */
    fun execShell(command: String, timeoutMs: Long = 12_000): Result<String> = runCatching {
        check(permissionGranted()) { "shizuku_permission_denied" }
        val trimmed = command.trim().replace(Regex("\\s+"), " ")
        check(isCommandAllowed(trimmed)) { "shizuku_command_not_allowlisted" }
        val shell = obtainShell(timeoutMs)
        shell.exec(trimmed) ?: error("shizuku_exec_null")
    }

    private fun obtainShell(timeoutMs: Long): IClawShizukuShell {
        cachedShell?.let { return it }
        synchronized(bindLock) {
            cachedShell?.let { return it }
            val holder = AtomicReference<IClawShizukuShell?>()
            val latch = CountDownLatch(1)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    holder.set(IClawShizukuShell.Stub.asInterface(service))
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    cachedShell = null
                }
            }
            val args = Shizuku.UserServiceArgs(
                ComponentName(BuildConfig.APPLICATION_ID, ClawShizukuShellService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("claw-shizuku")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)
            Shizuku.bindUserService(args, connection)
            val ok = latch.await(timeoutMs.coerceAtLeast(3_000), TimeUnit.MILLISECONDS)
            check(ok) { "shizuku_user_service_timeout" }
            val shell = holder.get() ?: error("shizuku_user_service_null")
            cachedShell = shell
            return shell
        }
    }
}
