package com.clawdroid.app.termux

import android.app.Activity
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.clawdroid.app.env.AppPermissionManager
import com.clawdroid.app.tools.ClawToolCallResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bridge to Termux [RUN_COMMAND](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).
 *
 * Requires:
 * - Termux app installed (`com.termux`)
 * - User grants `com.termux.permission.RUN_COMMAND` via system permission dialog
 *   (many OEMs do NOT show this under App Info → Additional permissions)
 * - Or Root: `pm grant <clawdroid> com.termux.permission.RUN_COMMAND`
 * - `allow-external-apps=true` in Termux `~/.termux/termux.properties`
 *   (separate from RUN_COMMAND; Termux refuses RunCommandService without it)
 */
class TermuxBridge(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val allowExternalAutoFixAttempted = AtomicBoolean(false)

    fun isTermuxInstalled(): Boolean = isTermuxInstalled(appContext)

    fun hasRunCommandPermission(): Boolean = hasRunCommandPermission(appContext)

    fun statusSummary(): String = buildString {
        appendLine("termux_installed=${isTermuxInstalled()}")
        appendLine("run_command_permission=${hasRunCommandPermission()}")
        appendLine("termux_process=${isTermuxProcessRunning(appContext)}")
        append("setup=Set allow-external-apps=true in ~/.termux/termux.properties")
    }

    suspend fun exec(
        command: String,
        workdir: String? = null,
        timeoutMs: Long = 15_000
    ): ClawToolCallResult {
        if (!isTermuxInstalled()) {
            return ClawToolCallResult(
                success = false,
                output = "失败: 未安装 Termux (com.termux)",
                error = "termux_not_installed"
            )
        }
        if (!hasRunCommandPermission()) {
            return ClawToolCallResult(
                success = false,
                output = permissionDeniedMessage(),
                error = "termux_permission_denied"
            )
        }
        val parsed = parseAllowlisted(command)
            ?: return ClawToolCallResult(
                success = false,
                output = "失败: 命令路径不在 Termux usr/bin 白名单: $command。" +
                    "可用: ls/pwd/echo/pkg/apt/proot-distro/bash -lc '…' 等。",
                error = "termux_command_not_allowlisted"
            )
        val effectiveTimeout = resolveTimeoutMs(command, timeoutMs)
        var result = execOnce(parsed, workdir, effectiveTimeout)
        if (isAllowExternalAppsBlocked(result) &&
            allowExternalAutoFixAttempted.compareAndSet(false, true)
        ) {
            val ensure = AppPermissionManager.ensureTermuxAllowExternalAppsViaRoot(
                context = appContext,
                alsoGrantRunCommand = false,
                forceStopTermux = true
            )
            if (ensure.didWriteOrConfirm) {
                ensureTermuxAwake(forceLaunch = true)
                result = execOnce(parsed, workdir, effectiveTimeout)
                if (result.success) {
                    return result.copy(
                        output = "auto_fix_allow_external_apps=ok (${ensure.allowExternal})\n" +
                            result.output
                    )
                }
                return result.copy(
                    output = buildString {
                        appendLine("auto_fix_allow_external_apps=${ensure.allowExternal}")
                        appendLine(ensure.allowExternalSummary)
                        if (ensure.forceStoppedTermux) {
                            appendLine("termux_force_stopped=true（已重载配置后重试仍失败）")
                        }
                        append(result.output)
                    },
                    error = result.error ?: "termux_allow_external_apps"
                )
            }
            return ClawToolCallResult(
                success = false,
                output = allowExternalAppsBlockedMessage(ensure.allowExternalSummary),
                error = "termux_allow_external_apps"
            )
        }
        if (isAllowExternalAppsBlocked(result)) {
            return result.copy(
                output = allowExternalAppsBlockedMessage() + "\n\n" + result.output,
                error = "termux_allow_external_apps"
            )
        }
        return result
    }

    /**
     * Remove a half-installed / broken proot-distro container, then clear its lock under the
     * Termux proot-distro locks directory (only after remove succeeds).
     */
    suspend fun cleanupBrokenDistro(name: String = "ubuntu"): ClawToolCallResult {
        val safeName = name.trim()
        if (!DISTRO_NAME_PATTERN.matches(safeName)) {
            return ClawToolCallResult(
                success = false,
                output = "失败: 容器名非法（仅允许字母数字与 ._-）: $name",
                error = "termux_distro_name_invalid"
            )
        }
        val listResult = exec(
            command = "proot-distro list",
            timeoutMs = 30_000L
        )
        val removeResult = exec(
            command = "bash -lc 'proot-distro remove $safeName'",
            timeoutMs = 120_000L
        )
        if (!removeResult.success) {
            return ClawToolCallResult(
                success = false,
                output = buildString {
                    appendLine("list:")
                    appendLine(listResult.output.take(1_200))
                    appendLine("remove:")
                    append(removeResult.output)
                },
                error = removeResult.error ?: "termux_distro_remove_failed"
            )
        }
        val lockPath = "$PROOT_DISTRO_LOCKS_DIR/$safeName.lock"
        val lockCleanup = exec(
            command = "bash -lc 'rm -f \"$lockPath\"'",
            timeoutMs = 15_000L
        )
        return ClawToolCallResult(
            success = true,
            output = buildString {
                appendLine("cleaned_distro=$safeName")
                appendLine("list_before:")
                appendLine(listResult.output.take(800))
                appendLine("remove:")
                appendLine(removeResult.output.take(800))
                appendLine("lock_cleanup=$lockPath success=${lockCleanup.success}")
                if (lockCleanup.output.isNotBlank()) {
                    appendLine(lockCleanup.output.take(400))
                }
                append("提示: 安装进行中请勿清理。清理后可用 `proot-distro install $safeName` 重装（请加大 timeout_ms）。")
            }
        )
    }

    private suspend fun execOnce(
        parsed: ParsedCommand,
        workdir: String?,
        timeoutMs: Long
    ): ClawToolCallResult {
        ensureTermuxAwake()
        val executionId = nextExecutionId()
        val deferred = CompletableDeferred<TermuxExecResult>()
        TermuxResultDispatcher.registerPending(executionId, deferred)
        return try {
            val callbackIntent = Intent(appContext, TermuxResultService::class.java)
                .putExtra(TermuxResultService.EXTRA_EXECUTION_ID, executionId)
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pendingIntent = PendingIntent.getService(
                appContext,
                executionId,
                callbackIntent,
                pendingFlags
            )
            val runIntent = Intent().apply {
                setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                action = ACTION_RUN_COMMAND
                putExtra(EXTRA_COMMAND_PATH, parsed.executablePath)
                putExtra(EXTRA_ARGUMENTS, parsed.arguments.toTypedArray())
                putExtra(EXTRA_BACKGROUND, true)
                putExtra(EXTRA_SESSION_ACTION, SESSION_ACTION_KEEP)
                putExtra(EXTRA_PENDING_INTENT, pendingIntent)
                if (!workdir.isNullOrBlank()) {
                    putExtra(EXTRA_WORKDIR, workdir)
                }
            }
            startTermuxRunCommand(runIntent)
            val result = withTimeout(effectiveTimeoutMs(timeoutMs)) {
                deferred.await()
            }
            formatExecResult(parsed, result)
        } catch (error: Exception) {
            TermuxResultDispatcher.cancel(executionId)
            val timedOut = error is kotlinx.coroutines.TimeoutCancellationException ||
                error.message.orEmpty().contains("Timed out", ignoreCase = true)
            ClawToolCallResult(
                success = false,
                output = if (timedOut) {
                    "失败: Termux 命令超时（${timeoutMs}ms）。" +
                        "pkg install / proot-distro install 常需数分钟；请加大 timeout_ms（最长 ${MAX_TIMEOUT_MS}），" +
                        "且勿在安装未完成时删 *.lock 或再次 install。可先 `proot-distro list` / 检查 rootfs 是否为空。"
                } else {
                    "失败: ${error.message}。请确认 Termux 已打开，且 RUN_COMMAND 与外部应用开关均已配置。"
                },
                error = if (timedOut) "termux_timeout" else error.message
            )
        }
    }

    private fun effectiveTimeoutMs(timeoutMs: Long): Long =
        timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    private suspend fun ensureTermuxAwake(forceLaunch: Boolean = false) {
        if (!forceLaunch && isTermuxProcessRunning(appContext)) return
        withContext(Dispatchers.Main) {
            runCatching {
                val launch = appContext.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launch != null) {
                    appContext.startActivity(launch)
                }
            }
        }
        // Give Termux time to start Application / bootstrap before RUN_COMMAND.
        repeat(10) {
            delay(300)
            if (isTermuxProcessRunning(appContext)) return
        }
        delay(500)
    }

    private fun startTermuxRunCommand(runIntent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(runIntent)
            } else {
                appContext.startService(runIntent)
            }
        } catch (_: Exception) {
            // Some OEMs reject cross-app FGS start; fall back to startService.
            appContext.startService(runIntent)
        }
    }

    private fun formatExecResult(
        parsed: ParsedCommand,
        result: TermuxExecResult
    ): ClawToolCallResult {
        val noInternalErr = result.errCode == Activity.RESULT_OK
        val ok = noInternalErr && result.exitCode == 0
        val allowBlocked = isAllowExternalAppsBlocked(result.errMsg, result.errCode)
        val hint = when {
            ok -> null
            allowBlocked ->
                "这不是 RUN_COMMAND 权限问题：Termux 要求 ~/.termux/termux.properties 含 allow-external-apps=true。" +
                    "请打开 Clawdroid「设置 → Termux 与 Shell」点「Root 授权并检查」，或手动写入后强制停止并重开 Termux。"
            !noInternalErr -> result.errMsg?.takeIf { it.isNotBlank() }
                ?: "Termux 内部错误 err=${result.errCode}"
            result.exitCode < 0 && result.stdout.isBlank() && result.stderr.isBlank() ->
                "命令未产生输出且 exit=${result.exitCode}。请先手动打开 Termux 完成初始化后重试。"
            else -> null
        }
        val output = buildString {
            appendLine("path=${parsed.executablePath}")
            appendLine("args=${parsed.arguments.joinToString(" ")}")
            appendLine("exit=${result.exitCode}")
            appendLine("err=${result.errCode}")
            if (!result.errMsg.isNullOrBlank()) {
                appendLine("errmsg=${result.errMsg}")
            }
            appendLine("stdout=${result.stdout.take(8_000)}")
            append("stderr=${result.stderr.take(2_000)}")
            if (hint != null) {
                appendLine()
                append("hint=$hint")
            }
        }
        return ClawToolCallResult(
            success = ok,
            output = output,
            error = when {
                ok -> null
                allowBlocked -> "termux_allow_external_apps"
                !noInternalErr -> result.errMsg ?: "termux_err_${result.errCode}"
                result.exitCode != 0 -> "exit_${result.exitCode}"
                else -> "termux_unknown"
            },
            shellOutput = output
        )
    }

    internal data class ParsedCommand(
        val executablePath: String,
        val arguments: List<String>
    )

    data class TermuxExecResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val errCode: Int = Activity.RESULT_OK,
        val errMsg: String? = null
    )

    internal fun parseAllowlisted(command: String): ParsedCommand? {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return null
        // bash/sh -c / -lc: allow a single script body (may contain pipes etc.)
        val shellMatch = SHELL_C_PATTERN.matchEntire(trimmed)
        if (shellMatch != null) {
            val shell = shellMatch.groupValues[1]
            val flag = shellMatch.groupValues[2]
            val script = shellMatch.groupValues[3].trim().removeSurroundingQuotes()
            if (script.isBlank() || script.length > MAX_SHELL_SCRIPT_CHARS) return null
            if (script.contains("\u0000")) return null
            return ParsedCommand(
                executablePath = "$TERMUX_BIN_PREFIX/$shell",
                arguments = listOf(flag, script)
            )
        }
        val normalized = trimmed.replace(Regex("\\s+"), " ")
        if (METACHAR.containsMatchIn(normalized)) {
            return null
        }
        val tokens = normalized.split(' ')
        val head = tokens.first()
        val args = tokens.drop(1)
        val executablePath = when {
            head.startsWith(TERMUX_BIN_PREFIX) -> head
            head.startsWith("/") -> return null
            else -> "$TERMUX_BIN_PREFIX/$head"
        }
        if (!executablePath.startsWith(TERMUX_BIN_PREFIX)) {
            return null
        }
        val name = executablePath.removePrefix("$TERMUX_BIN_PREFIX/")
        if (name.isBlank() || name.contains("..") || name.contains('/')) {
            return null
        }
        if (name !in ALLOWED_BINARIES) {
            return null
        }
        if (args.any { METACHAR.containsMatchIn(it) || it.contains("..") }) {
            return null
        }
        return ParsedCommand(executablePath = executablePath, arguments = args)
    }

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val TERMUX_BIN_PREFIX = "/data/data/com.termux/files/usr/bin"
        /** Whitelist root for residual install locks (only cleaned after remove). */
        const val PROOT_DISTRO_LOCKS_DIR =
            "/data/data/com.termux/files/usr/var/lib/proot-distro/locks"
        private val DISTRO_NAME_PATTERN = Regex("""^[a-zA-Z0-9._-]+$""")

        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        private const val SESSION_ACTION_KEEP = "0"

        // Keys from TermuxConstants.TERMUX_SERVICE (bundle attached to PendingIntent callback).
        private const val EXTRA_PLUGIN_RESULT_BUNDLE = "result"
        private const val EXTRA_PLUGIN_RESULT_STDOUT = "stdout"
        private const val EXTRA_PLUGIN_RESULT_STDERR = "stderr"
        private const val EXTRA_PLUGIN_RESULT_EXIT_CODE = "exitCode"
        private const val EXTRA_PLUGIN_RESULT_ERR = "err"
        private const val EXTRA_PLUGIN_RESULT_ERRMSG = "errmsg"

        private val METACHAR = Regex("""[;|&`$<>\n\r]""")
        /** bash|sh (-c|-lc) 'script' or "script" or unquoted rest */
        private val SHELL_C_PATTERN = Regex(
            """^(bash|sh)\s+(-lc|-c)\s+(.+)$""",
            RegexOption.DOT_MATCHES_ALL
        )
        private const val MAX_SHELL_SCRIPT_CHARS = 4_000
        private val executionCounter = AtomicInteger(1_000)

        private val ALLOWED_BINARIES = setOf(
            "bash", "sh", "ls", "cat", "pwd", "echo", "printf", "head", "tail", "wc", "mkdir",
            "id", "uname", "grep", "find", "which", "python", "python3", "node",
            "curl", "wget", "git", "pkg", "apt", "apt-get", "dpkg", "tar", "gzip", "sed", "awk",
            "sort", "uniq", "cut", "tr", "tee", "touch", "rm", "cp", "mv", "chmod", "stat",
            "date", "env", "printenv", "whoami", "dirname", "basename", "realpath", "sleep",
            "proot", "proot-distro", "login", "su", "termux-wake-lock", "termux-wake-unlock",
            "termux-setup-storage", "termux-info", "getprop"
        )

        fun nextExecutionId(): Int = executionCounter.getAndIncrement()

        fun isTermuxInstalled(context: Context): Boolean =
            runCatching {
                context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
                true
            }.getOrDefault(false)

        fun hasRunCommandPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, RUN_COMMAND_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED

        fun isTermuxProcessRunning(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            return runCatching {
                @Suppress("DEPRECATION")
                am.runningAppProcesses?.any { it.processName == TERMUX_PACKAGE } == true
            }.getOrDefault(false)
        }

        fun permissionDeniedMessage(): String =
            "失败: 未授予 com.termux.permission.RUN_COMMAND。" +
                "该权限通常不会出现在「系统设置 → 应用 → 其它权限」里。" +
                "请打开 Clawdroid「设置 → Termux 与 Shell」：" +
                "① 点击「请求 RUN_COMMAND 权限」弹出系统授权框；" +
                "② 或「Root 授权并检查」。" +
                "另外还须设置 allow-external-apps=true（与 RUN_COMMAND 是两回事）。"

        fun allowExternalAppsBlockedMessage(rootNote: String? = null): String = buildString {
            append("失败: Termux 拒绝外部调用 —— 需要 allow-external-apps=true。")
            append("这与系统「RUN_COMMAND 权限」不同；两者都要齐。")
            append("请打开 Clawdroid「设置 → Termux 与 Shell」点「Root 授权并检查」；")
            append("或手动在 Termux 执行：mkdir -p ~/.termux && echo allow-external-apps=true >> ~/.termux/termux.properties，")
            append("然后强制停止并重新打开 Termux。")
            if (!rootNote.isNullOrBlank()) {
                append(" Root 自动修复: ")
                append(rootNote)
            }
        }

        /**
         * Detect Termux property denial from plugin errmsg / structured error only.
         * Do NOT scan free-form tips (timeout text used to false-trigger on the property name).
         */
        fun isAllowExternalAppsBlocked(result: ClawToolCallResult): Boolean {
            if (result.error == "termux_allow_external_apps") return true
            if (result.error == "termux_timeout") return false
            val errmsg = result.output.lineSequence()
                .firstOrNull { it.startsWith("errmsg=") }
                ?.removePrefix("errmsg=")
                .orEmpty()
            val errLine = result.output.lineSequence()
                .firstOrNull { it.startsWith("err=") }
                ?.removePrefix("err=")
                ?.toIntOrNull()
            return isAllowExternalAppsBlocked(errmsg, errLine)
        }

        fun isAllowExternalAppsBlocked(errMsg: String?, errCode: Int?): Boolean {
            val msg = errMsg.orEmpty()
            if (msg.contains("allow-external-apps", ignoreCase = true)) return true
            // Termux ERRNO for property denial is commonly 2 in plugin results.
            return errCode == 2 && msg.contains("RunCommandService", ignoreCase = true)
        }

        private const val MIN_TIMEOUT_MS = 2_000L
        private const val MAX_TIMEOUT_MS = 600_000L
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val LONG_JOB_TIMEOUT_MS = 480_000L

        /** Bump timeout for pkg/apt/proot-distro install/update style jobs. */
        fun resolveTimeoutMs(command: String, requestedMs: Long): Long {
            val requested = if (requestedMs > 0) requestedMs else DEFAULT_TIMEOUT_MS
            val lower = command.lowercase()
            val longJob = listOf(
                "proot-distro install",
                "proot-distro reset",
                "pkg install",
                "pkg upgrade",
                "pkg update",
                "apt install",
                "apt-get install",
                "apt update",
                "apt-get update"
            ).any { lower.contains(it) }
            val floor = if (longJob) LONG_JOB_TIMEOUT_MS else requested
            return maxOf(requested, floor).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        }

        internal fun parseResultBundle(bundle: Bundle): TermuxExecResult =
            TermuxExecResult(
                stdout = readPluginText(bundle, EXTRA_PLUGIN_RESULT_STDOUT),
                stderr = readPluginText(bundle, EXTRA_PLUGIN_RESULT_STDERR),
                exitCode = if (bundle.containsKey(EXTRA_PLUGIN_RESULT_EXIT_CODE)) {
                    bundle.getInt(EXTRA_PLUGIN_RESULT_EXIT_CODE)
                } else {
                    -1
                },
                errCode = if (bundle.containsKey(EXTRA_PLUGIN_RESULT_ERR)) {
                    bundle.getInt(EXTRA_PLUGIN_RESULT_ERR)
                } else {
                    Activity.RESULT_OK
                },
                errMsg = bundle.getString(EXTRA_PLUGIN_RESULT_ERRMSG)
            )

        /**
         * Termux may return stdout/stderr as String or StringArrayList when truncated.
         */
        private fun readPluginText(bundle: Bundle, key: String): String {
            bundle.getString(key)?.let { return it }
            val list = bundle.getStringArrayList(key)
            if (list != null) return list.joinToString("")
            @Suppress("DEPRECATION")
            val legacy = bundle.get(key)
            return when (legacy) {
                is ArrayList<*> -> legacy.filterIsInstance<String>().joinToString("")
                is Array<*> -> legacy.filterIsInstance<String>().joinToString("")
                else -> ""
            }
        }

        internal fun resultBundleKey(): String = EXTRA_PLUGIN_RESULT_BUNDLE

        private fun String.removeSurroundingQuotes(): String {
            if (length >= 2) {
                val a = first()
                val b = last()
                if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                    return substring(1, length - 1)
                }
            }
            return this
        }
    }
}
