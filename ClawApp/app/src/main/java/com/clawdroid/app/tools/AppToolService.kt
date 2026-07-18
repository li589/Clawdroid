package com.clawdroid.app.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.clawdroid.app.runtime.ClawRuntimeClient

class AppToolService(
    private val context: Context,
    private val runtimeClient: ClawRuntimeClient
) {
    fun list(query: String, limit: Int): ClawToolCallResult {
        val pm = context.packageManager
        val q = query.trim().lowercase()
        val max = limit.coerceIn(1, 500)
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .map { info ->
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
                info.packageName to label
            }
            .filter { (pkg, label) ->
                q.isBlank() || pkg.lowercase().contains(q) || label.lowercase().contains(q)
            }
            .sortedBy { it.first }
            .take(max)
            .toList()
        return ClawToolCallResult(
            success = true,
            output = buildString {
                appendLine("count=${apps.size} query=${query.ifBlank { "*" }}")
                apps.forEach { (pkg, label) ->
                    appendLine("- $pkg | $label")
                }
            }
        )
    }

    fun launch(packageName: String, action: String, dataUri: String): ClawToolCallResult {
        return try {
            val intent = when {
                packageName.isNotBlank() -> {
                    pmLaunchIntent(packageName)
                        ?: return ClawToolCallResult(
                            false,
                            "失败: 找不到启动 Intent: $packageName",
                            error = "no_launch_intent"
                        )
                }
                action.isNotBlank() -> Intent(action).apply {
                    if (dataUri.isNotBlank()) data = Uri.parse(dataUri)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                else -> return ClawToolCallResult(
                    false,
                    "失败: 需要 package 或 action",
                    error = "missing_target"
                )
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ClawToolCallResult(
                success = true,
                output = "成功: launched package=${intent.`package`.orEmpty()} action=${intent.action.orEmpty()}"
            )
        } catch (error: Exception) {
            ClawToolCallResult(false, "失败: ${error.message}", error = error.message)
        }
    }

    suspend fun stop(packageName: String): ClawToolCallResult {
        val pkg = packageName.trim()
        if (!PACKAGE_REGEX.matches(pkg)) {
            return ClawToolCallResult(false, "失败: 非法包名", error = "invalid_package")
        }
        val command = "am force-stop $pkg"
        val stopResult = runtimeClient.execShellLimited(command = command, timeoutMs = 5000)
            .getOrElse {
                return ClawToolCallResult(false, "失败: ${it.message}", error = it.message)
            }
        // Verify process gone via pidof (parameterized allowlist).
        val pidCheck = runtimeClient.execShellLimited(command = "pidof $pkg", timeoutMs = 3000)
            .getOrNull()
        val stillRunning = pidCheck != null &&
            pidCheck.exitCode == 0 &&
            pidCheck.stdout.trim().isNotEmpty()
        val ok = stopResult.exitCode == 0 && !stillRunning
        return ClawToolCallResult(
            success = ok,
            output = buildString {
                appendLine("force-stop $pkg exit=${stopResult.exitCode}")
                appendLine("stdout=${stopResult.stdout}")
                appendLine("stderr=${stopResult.stderr}")
                appendLine("pidof_after=${pidCheck?.stdout?.trim().orEmpty().ifBlank { "(empty)" }}")
                append("still_running=$stillRunning")
            },
            shellOutput = stopResult.stdout,
            error = when {
                ok -> null
                stillRunning -> "still_running"
                else -> "exit_${stopResult.exitCode}"
            }
        )
    }

    fun info(packageName: String): ClawToolCallResult {
        val pkg = packageName.trim()
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            val pkgInfo = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            val label = pm.getApplicationLabel(appInfo).toString()
            val launch = pm.getLaunchIntentForPackage(pkg)
            val running = isPackageRunning(pkg)
            ClawToolCallResult(
                success = true,
                output = buildString {
                    appendLine("package=$pkg")
                    appendLine("label=$label")
                    appendLine("versionName=${pkgInfo.versionName}")
                    appendLine("versionCode=${pkgInfo.longVersionCode}")
                    appendLine("debuggable=${appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0}")
                    appendLine("launchActivity=${launch?.component?.className.orEmpty()}")
                    appendLine("possiblyRunning=$running")
                }
            )
        } catch (error: Exception) {
            ClawToolCallResult(false, "失败: ${error.message}", error = error.message)
        }
    }

    private fun pmLaunchIntent(packageName: String): Intent? {
        return context.packageManager.getLaunchIntentForPackage(packageName.trim())
    }

    private fun isPackageRunning(packageName: String): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        @Suppress("DEPRECATION")
        return am.runningAppProcesses?.any { it.processName == packageName } == true
    }

    companion object {
        private val PACKAGE_REGEX = Regex("^[A-Za-z]\\w*(\\.[A-Za-z]\\w*)+$")
    }
}
