package com.clawdroid.app.env

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.clawdroid.app.service.ClawAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class RootActionResult(
    val success: Boolean,
    val output: String,
    val timedOut: Boolean = false
)

data class MagiskModuleStatus(
    val magiskDaemonRunning: Boolean = false,
    val moduleInstalled: Boolean = false,
    val moduleEnabled: Boolean = false,
    val runtimeDaemonRunning: Boolean = false,
    val modulePath: String = "/data/adb/modules/clawruntime",
    val rawOutput: String = ""
)

object AppPermissionManager {
    private const val ROOT_COMMAND_TIMEOUT_SECONDS = 20L
    private const val ROOT_OUTPUT_MAX_BYTES = 65536

    fun notificationPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun writeSettingsGranted(context: Context): Boolean = Settings.System.canWrite(context)

    fun allFilesAccessGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true
        }
        return Environment.isExternalStorageManager()
    }

    fun notificationSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun accessibilitySettingsIntent(context: Context): Intent {
        return firstResolvableIntent(
            context,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
            appDetailsIntent(context)
        )
    }

    fun writeSettingsIntent(context: Context): Intent {
        return firstResolvableIntent(
            context,
            Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ),
            appDetailsIntent(context)
        )
    }

    fun allFilesAccessIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return appDetailsIntent(context)
        }
        return firstResolvableIntent(
            context,
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            appDetailsIntent(context)
        )
    }

    fun appDetailsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    suspend fun requestRootAccess(): RootActionResult {
        return runRootCommand(
            "id -u",
            successMessage = "Root 会话已建立"
        )
    }

    suspend fun runRawRootCommand(command: String): RootActionResult = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            return@withContext RootActionResult(false, "无法启动 su: ${error.message}")
        }

        try {
            if (!process.waitFor(ROOT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
                return@withContext RootActionResult(
                    success = false,
                    output = "Root 命令执行超时",
                    timedOut = true
                )
            }

            val output = readBoundedOutput(process.inputStream, ROOT_OUTPUT_MAX_BYTES)
            return@withContext if (process.exitValue() == 0) {
                RootActionResult(true, output.trim())
            } else {
                RootActionResult(false, output.trim().ifBlank { "Root 命令执行失败，exit=${process.exitValue()}" })
            }
        } catch (error: Exception) {
            RootActionResult(false, "Root 命令执行异常: ${error.message}")
        } finally {
            process.destroy()
        }
    }

    private fun readBoundedOutput(inputStream: java.io.InputStream, maxBytes: Int): String {
        val buffer = ByteArray(maxBytes + 1)
        var totalRead = 0
        var singleRead: Int
        while (totalRead < maxBytes) {
            singleRead = inputStream.read(buffer, totalRead, maxBytes - totalRead)
            if (singleRead < 0) break
            totalRead += singleRead
        }
        val truncated = totalRead >= maxBytes
        val result = String(buffer, 0, totalRead, Charsets.UTF_8)
        return if (truncated) {
            result + " [...output truncated at ${maxBytes} bytes...]"
        } else {
            result
        }
    }

    suspend fun grantNotificationViaRoot(context: Context): RootActionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return RootActionResult(true, "当前系统无需单独授予通知权限")
        }
        return runRootCommand(
            """
            pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS
            """.trimIndent(),
            successMessage = "已通过 Root 授予通知权限"
        )
    }

    suspend fun grantWriteSettingsViaRoot(context: Context): RootActionResult {
        return runRootCommand(
            """
            if cmd appops set --uid ${context.packageName} android:write_settings allow >/dev/null 2>&1 \
              || cmd appops set ${context.packageName} android:write_settings allow >/dev/null 2>&1 \
              || appops set --uid ${context.packageName} WRITE_SETTINGS allow >/dev/null 2>&1 \
              || appops set ${context.packageName} WRITE_SETTINGS allow >/dev/null 2>&1; then
              mode=${'$'}((cmd appops get --uid ${context.packageName} android:write_settings 2>/dev/null \
                || cmd appops get ${context.packageName} android:write_settings 2>/dev/null \
                || appops get --uid ${context.packageName} WRITE_SETTINGS 2>/dev/null \
                || appops get ${context.packageName} WRITE_SETTINGS 2>/dev/null) | tr '[:upper:]' '[:lower:]')
              case "${'$'}mode" in
                *allow*) echo "WRITE_SETTINGS granted" ;;
                *) echo "WRITE_SETTINGS verify failed: ${'$'}mode"; exit 1 ;;
              esac
            else
              exit 1
            fi
            """.trimIndent(),
            successMessage = "已通过 Root 授予修改系统设置权限"
        )
    }

    suspend fun grantAllFilesAccessViaRoot(context: Context): RootActionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return RootActionResult(true, "当前系统无需单独授予全部文件访问权限")
        }
        return runRootCommand(
            """
            if cmd appops set --uid ${context.packageName} android:manage_external_storage allow >/dev/null 2>&1 \
              || cmd appops set ${context.packageName} android:manage_external_storage allow >/dev/null 2>&1 \
              || appops set --uid ${context.packageName} MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 \
              || appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1; then
              mode=${'$'}((cmd appops get --uid ${context.packageName} android:manage_external_storage 2>/dev/null \
                || cmd appops get ${context.packageName} android:manage_external_storage 2>/dev/null \
                || appops get --uid ${context.packageName} MANAGE_EXTERNAL_STORAGE 2>/dev/null \
                || appops get ${context.packageName} MANAGE_EXTERNAL_STORAGE 2>/dev/null) | tr '[:upper:]' '[:lower:]')
              case "${'$'}mode" in
                *allow*) echo "MANAGE_EXTERNAL_STORAGE granted" ;;
                *) echo "MANAGE_EXTERNAL_STORAGE verify failed: ${'$'}mode"; exit 1 ;;
              esac
            else
              exit 1
            fi
            """.trimIndent(),
            successMessage = "已通过 Root 授予全部文件访问权限"
        )
    }

    suspend fun enableAccessibilityViaRoot(context: Context): RootActionResult {
        val componentName = accessibilityServiceComponent(context)
        val component = componentName.flattenToString()
        val shortComponent = componentName.flattenToShortString()
        return runRootCommand(
            """
            current=${'$'}(settings get secure enabled_accessibility_services 2>/dev/null)
            case ":${'$'}current:" in
              *":$component:"*) updated="${'$'}current" ;;
              *":$shortComponent:"*) updated="${'$'}current" ;;
              ":null:"|":-1:"|"::") updated="$component" ;;
              *) updated="${'$'}current:$component" ;;
            esac
            settings put secure enabled_accessibility_services "${'$'}updated"
            settings put secure accessibility_enabled 1
            enabled=${'$'}(settings get secure accessibility_enabled 2>/dev/null)
            current=${'$'}(settings get secure enabled_accessibility_services 2>/dev/null)
            case ":${'$'}current:" in
              *":$component:"*|*":$shortComponent:"*)
                [ "${'$'}enabled" = "1" ] && echo "Accessibility enabled for $component" || exit 1
                ;;
              *)
                echo "Accessibility verify failed: ${'$'}current"
                exit 1
                ;;
            esac
            """.trimIndent(),
            successMessage = "已通过 Root 启用无障碍服务"
        )
    }

    suspend fun grantAutomationPermissionsViaRoot(context: Context): RootActionResult {
        val componentName = accessibilityServiceComponent(context)
        val component = componentName.flattenToString()
        val shortComponent = componentName.flattenToShortString()
        val notificationGrant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || exit 1"
        } else {
            "echo notification_skip >/dev/null"
        }
        val allFilesGrant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "(cmd appops set --uid ${context.packageName} android:manage_external_storage allow >/dev/null 2>&1 || cmd appops set ${context.packageName} android:manage_external_storage allow >/dev/null 2>&1 || appops set --uid ${context.packageName} MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1) || exit 1"
        } else {
            "echo all_files_skip >/dev/null"
        }
        return runRootCommand(
            """
            $notificationGrant
            (cmd appops set --uid ${context.packageName} android:write_settings allow >/dev/null 2>&1 || cmd appops set ${context.packageName} android:write_settings allow >/dev/null 2>&1 || appops set --uid ${context.packageName} WRITE_SETTINGS allow >/dev/null 2>&1 || appops set ${context.packageName} WRITE_SETTINGS allow >/dev/null 2>&1) || exit 1
            $allFilesGrant
            current=${'$'}(settings get secure enabled_accessibility_services 2>/dev/null)
            case ":${'$'}current:" in
              *":$component:"*) updated="${'$'}current" ;;
              *":$shortComponent:"*) updated="${'$'}current" ;;
              ":null:"|":-1:"|"::") updated="$component" ;;
              *) updated="${'$'}current:$component" ;;
            esac
            settings put secure enabled_accessibility_services "${'$'}updated"
            settings put secure accessibility_enabled 1
            write_mode=${'$'}((cmd appops get --uid ${context.packageName} android:write_settings 2>/dev/null || cmd appops get ${context.packageName} android:write_settings 2>/dev/null || appops get --uid ${context.packageName} WRITE_SETTINGS 2>/dev/null || appops get ${context.packageName} WRITE_SETTINGS 2>/dev/null) | tr '[:upper:]' '[:lower:]')
            if [ "${Build.VERSION.SDK_INT}" -ge "${Build.VERSION_CODES.R}" ]; then
              files_mode=${'$'}((cmd appops get --uid ${context.packageName} android:manage_external_storage 2>/dev/null || cmd appops get ${context.packageName} android:manage_external_storage 2>/dev/null || appops get --uid ${context.packageName} MANAGE_EXTERNAL_STORAGE 2>/dev/null || appops get ${context.packageName} MANAGE_EXTERNAL_STORAGE 2>/dev/null) | tr '[:upper:]' '[:lower:]')
            else
              files_mode="allow"
            fi
            enabled=${'$'}(settings get secure accessibility_enabled 2>/dev/null)
            current=${'$'}(settings get secure enabled_accessibility_services 2>/dev/null)
            case "${'$'}write_mode" in *allow*) : ;; *) echo "WRITE_SETTINGS verify failed: ${'$'}write_mode"; exit 1 ;; esac
            case "${'$'}files_mode" in *allow*) : ;; *) echo "MANAGE_EXTERNAL_STORAGE verify failed: ${'$'}files_mode"; exit 1 ;; esac
            case ":${'$'}current:" in
              *":$component:"*|*":$shortComponent:"*) [ "${'$'}enabled" = "1" ] || exit 1 ;;
              *) echo "Accessibility verify failed: ${'$'}current"; exit 1 ;;
            esac
            """.trimIndent(),
            successMessage = "已通过 Root 完成通知、系统设置、全部文件访问与无障碍授权"
        )
    }

    suspend fun chmodPathViaRoot(
        path: String,
        mode: String
    ): RootActionResult {
        val cleanedPath = path.trim()
        val cleanedMode = mode.trim()
        if (cleanedPath.isBlank()) {
            return RootActionResult(false, "目标路径不能为空")
        }
        if (!cleanedPath.startsWith("/")) {
            return RootActionResult(false, "目标路径必须是绝对路径")
        }
        if (cleanedMode.isBlank()) {
            return RootActionResult(false, "chmod 模式不能为空")
        }
        return runRootCommand(
            "chmod ${shellQuote(cleanedMode)} ${shellQuote(cleanedPath)}",
            successMessage = "已更新文件权限: $cleanedMode $cleanedPath"
        )
    }

    suspend fun chownPathViaRoot(
        path: String,
        ownerSpec: String
    ): RootActionResult {
        val cleanedPath = path.trim()
        val cleanedOwner = ownerSpec.trim()
        if (cleanedPath.isBlank()) {
            return RootActionResult(false, "目标路径不能为空")
        }
        if (!cleanedPath.startsWith("/")) {
            return RootActionResult(false, "目标路径必须是绝对路径")
        }
        if (cleanedOwner.isBlank()) {
            return RootActionResult(false, "chown 属主不能为空，例如 0:0")
        }
        return runRootCommand(
            "chown ${shellQuote(cleanedOwner)} ${shellQuote(cleanedPath)}",
            successMessage = "已更新文件属主: $cleanedOwner $cleanedPath"
        )
    }

    suspend fun inspectMagiskModule(moduleId: String = "clawruntime"): MagiskModuleStatus =
        withContext(Dispatchers.IO) {
            val modulePath = "/data/adb/modules/$moduleId"
            val command = """
                module_path=$modulePath
                if pidof magiskd >/dev/null 2>&1; then magisk=1; else magisk=0; fi
                if [ -d "${'$'}module_path" ]; then installed=1; else installed=0; fi
                if [ -d "${'$'}module_path" ] && [ ! -f "${'$'}module_path/disable" ] && [ ! -f "${'$'}module_path/remove" ]; then enabled=1; else enabled=0; fi
                if pidof clawdroid-runtime >/dev/null 2>&1; then runtime=1; else runtime=0; fi
                echo "magisk=${'$'}magisk|installed=${'$'}installed|enabled=${'$'}enabled|runtime=${'$'}runtime|path=${'$'}module_path"
            """.trimIndent()

            val process = try {
                ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
            } catch (error: Exception) {
                return@withContext MagiskModuleStatus(
                    modulePath = modulePath,
                    rawOutput = error.message.orEmpty()
                )
            }

            try {
                if (!process.waitFor(ROOT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroy()
                    return@withContext MagiskModuleStatus(
                        modulePath = modulePath,
                        rawOutput = "timeout"
                    )
                }

                val output = readBoundedOutput(process.inputStream, ROOT_OUTPUT_MAX_BYTES)
                if (process.exitValue() != 0) {
                    return@withContext MagiskModuleStatus(
                        modulePath = modulePath,
                        rawOutput = output.trim()
                    )
                }
                return@withContext parseMagiskModuleStatus(output.trim())
            } catch (error: Exception) {
                return@withContext MagiskModuleStatus(
                    modulePath = modulePath,
                    rawOutput = error.message.orEmpty()
                )
            } finally {
                process.destroy()
            }
        }

    private suspend fun runRootCommand(
        command: String,
        successMessage: String
    ): RootActionResult = withContext(Dispatchers.IO) {
        val raw = runRawRootCommand(command)
        if (!raw.success) {
            return@withContext raw
        }
        val suffix = raw.output.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        RootActionResult(true, successMessage + suffix)
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun accessibilityServiceComponent(context: Context): ComponentName {
        return ComponentName(context, ClawAccessibilityService::class.java)
    }

    private fun firstResolvableIntent(context: Context, vararg intents: Intent): Intent {
        val resolved = intents.firstOrNull { candidate ->
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            candidate.resolveActivity(context.packageManager) != null
        }
        return resolved ?: intents.last().apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

internal fun parseMagiskModuleStatus(output: String): MagiskModuleStatus {
    fun parseFlag(key: String): Boolean {
        return Regex("""(?:^|\|)$key=(\d+)""").find(output)?.groupValues?.getOrNull(1) == "1"
    }

    val modulePath = Regex("""(?:^|\|)path=([^|]+)""").find(output)?.groupValues?.getOrNull(1)
        ?: "/data/adb/modules/clawruntime"

    return MagiskModuleStatus(
        magiskDaemonRunning = parseFlag("magisk"),
        moduleInstalled = parseFlag("installed"),
        moduleEnabled = parseFlag("enabled"),
        runtimeDaemonRunning = parseFlag("runtime"),
        modulePath = modulePath,
        rawOutput = output
    )
}
