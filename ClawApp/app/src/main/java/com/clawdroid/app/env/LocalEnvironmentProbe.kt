package com.clawdroid.app.env

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.clawdroid.app.service.ClawAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class LocalEnvironmentStatus(
    val rootGranted: Boolean?,
    val accessibilityEnabled: Boolean,
    val notificationPermissionGranted: Boolean,
    val writeSettingsGranted: Boolean,
    val allFilesAccessGranted: Boolean,
    val magiskDaemonRunning: Boolean,
    val magiskModuleInstalled: Boolean,
    val magiskModuleEnabled: Boolean,
    val runtimeDaemonRunning: Boolean,
    val lsposedManagerInstalled: Boolean,
    val xposedInjected: Boolean,
    val xposedProcessName: String = "",
    val xposedLoadedAtEpochMs: Long = 0L
)

internal data class LocalEnvironmentDiagnosis(
    val title: String,
    val detail: String,
    val actionHint: String
) {
    fun asMultilineString(): String {
        return buildString {
            append(title)
            append('\n')
            append(detail)
            append('\n')
            append("建议: ")
            append(actionHint)
        }
    }
}

object LocalRuntimeStatus {
    @Volatile
    private var xposedInjected: Boolean = false

    @Volatile
    private var xposedProcessName: String = ""

    @Volatile
    private var xposedLoadedAtEpochMs: Long = 0L

    private const val XPOSED_MARKER_FILE = "xposed_runtime_marker.json"

    fun markXposedInjected(context: Context?, processName: String) {
        xposedInjected = true
        xposedProcessName = processName
        xposedLoadedAtEpochMs = System.currentTimeMillis()
        if (context != null) {
            persistMarker(context)
        }
    }

    fun isXposedInjected(): Boolean = xposedInjected

    fun currentProcessName(): String = xposedProcessName

    fun loadedAtEpochMs(): Long = xposedLoadedAtEpochMs

    fun clearPersistedMarker(context: Context) {
        markerFile(context).delete()
    }

    fun synchronizeMarker(context: Context) {
        if (xposedInjected) {
            persistMarker(context)
        }
    }

    fun readPersistedMarker(context: Context): LocalEnvironmentStatus {
        val file = markerFile(context)
        if (!file.exists()) {
            return emptyEnvironmentStatus()
        }

        return parsePersistedMarkerContent(file.readText())
    }

    private fun persistMarker(context: Context) {
        markerFile(context).writeText(
            buildPersistedMarkerContent(
                xposedInjected = xposedInjected,
                processName = xposedProcessName,
                loadedAtEpochMs = xposedLoadedAtEpochMs,
                packageName = context.packageName
            )
        )
    }

    private fun markerFile(context: Context): File = File(context.filesDir, XPOSED_MARKER_FILE)
}

object LocalEnvironmentProbe {
    private val knownLsposedPackages = listOf(
        "org.lsposed.manager",
        "io.github.lsposed.manager"
    )

    suspend fun probe(context: Context, includeRootCheck: Boolean): LocalEnvironmentStatus {
        val rootGranted = if (includeRootCheck) {
            detectRootGranted()
        } else {
            null
        }
        val magiskModuleStatus = if (includeRootCheck && rootGranted == true) {
            AppPermissionManager.inspectMagiskModule()
        } else {
            MagiskModuleStatus()
        }

        val persistedMarker = LocalRuntimeStatus.readPersistedMarker(context)
        val writeSettingsGranted = AppPermissionManager.writeSettingsGranted(context) || (
            includeRootCheck && rootGranted == true &&
                detectAppOpAllowed(
                    context.packageName,
                    listOf("android:write_settings", "WRITE_SETTINGS")
                )
            )
        val allFilesAccessGranted = AppPermissionManager.allFilesAccessGranted() || (
            includeRootCheck && rootGranted == true &&
                detectAppOpAllowed(
                    context.packageName,
                    listOf("android:manage_external_storage", "MANAGE_EXTERNAL_STORAGE")
                )
            )
        return LocalEnvironmentStatus(
            rootGranted = rootGranted,
            accessibilityEnabled = detectAccessibilityEnabled(context),
            notificationPermissionGranted = AppPermissionManager.notificationPermissionGranted(context),
            writeSettingsGranted = writeSettingsGranted,
            allFilesAccessGranted = allFilesAccessGranted,
            magiskDaemonRunning = magiskModuleStatus.magiskDaemonRunning,
            magiskModuleInstalled = magiskModuleStatus.moduleInstalled,
            magiskModuleEnabled = magiskModuleStatus.moduleEnabled,
            runtimeDaemonRunning = magiskModuleStatus.runtimeDaemonRunning,
            lsposedManagerInstalled = detectLsposedManagerInstalled(context),
            xposedInjected = LocalRuntimeStatus.isXposedInjected(),
            xposedProcessName = resolveXposedProcessName(
                LocalRuntimeStatus.isXposedInjected(),
                LocalRuntimeStatus.currentProcessName(),
                persistedMarker.xposedProcessName
            ),
            xposedLoadedAtEpochMs = resolveXposedLoadedAtEpochMs(
                LocalRuntimeStatus.isXposedInjected(),
                LocalRuntimeStatus.loadedAtEpochMs(),
                persistedMarker.xposedLoadedAtEpochMs
            )
        )
    }

    private suspend fun detectRootGranted(): Boolean = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder("su", "-c", "id -u")
                .redirectErrorStream(true)
                .start()
        } catch (_: Exception) {
            return@withContext false
        }

        try {
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                process.destroy()
                return@withContext false
            }

            if (process.exitValue() != 0) {
                return@withContext false
            }

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText().trim() == "0"
            }
        } catch (_: Exception) {
            false
        } finally {
            process.destroy()
        }
    }

    private fun detectAccessibilityEnabled(context: Context): Boolean {
        val component = ComponentName(context, ClawAccessibilityService::class.java)
        val enabledFlag = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        val services = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        return hasAccessibilityServiceEnabled(
            enabledFlag = enabledFlag,
            enabledServices = services,
            fullComponentName = component.flattenToString(),
            shortComponentName = component.flattenToShortString()
        )
    }

    private fun detectLsposedManagerInstalled(context: Context): Boolean {
        val packageManager = context.packageManager
        return knownLsposedPackages.any { packageName ->
            runCatching {
                packageManager.getPackageInfo(packageName, 0)
            }.isSuccess
        }
    }

    private suspend fun detectAppOpAllowed(packageName: String, operationNames: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            operationNames.any { operation ->
                val command = """
                    output=${'$'}((cmd appops get --uid $packageName $operation 2>/dev/null \
                      || cmd appops get $packageName $operation 2>/dev/null \
                      || appops get --uid $packageName $operation 2>/dev/null \
                      || appops get $packageName $operation 2>/dev/null) | tr '[:upper:]' '[:lower:]')
                    case "${'$'}output" in
                      *allow*) echo allow ;;
                      *) echo deny ;;
                    esac
                """.trimIndent()
                val process = try {
                    ProcessBuilder("su", "-c", command)
                        .redirectErrorStream(true)
                        .start()
                } catch (_: Exception) {
                    return@any false
                }

                try {
                    if (!process.waitFor(1500, TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                        return@any false
                    }
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        isAppOpAllowedOutput(reader.readText())
                    }
                } catch (_: Exception) {
                    false
                } finally {
                    process.destroy()
                }
            }
        }
}

internal fun emptyEnvironmentStatus(): LocalEnvironmentStatus = LocalEnvironmentStatus(
    rootGranted = null,
    accessibilityEnabled = false,
    notificationPermissionGranted = false,
    writeSettingsGranted = false,
    allFilesAccessGranted = false,
    magiskDaemonRunning = false,
    magiskModuleInstalled = false,
    magiskModuleEnabled = false,
    runtimeDaemonRunning = false,
    lsposedManagerInstalled = false,
    xposedInjected = false
)

internal fun parsePersistedMarkerContent(content: String): LocalEnvironmentStatus {
    val normalized = content.trim()
    if (!normalized.startsWith("{") || !normalized.endsWith("}")) {
        return emptyEnvironmentStatus()
    }

    return emptyEnvironmentStatus().copy(
        xposedInjected = extractBooleanField(normalized, "xposed_injected") ?: false,
        xposedProcessName = extractStringField(normalized, "process_name").orEmpty(),
        xposedLoadedAtEpochMs = extractLongField(normalized, "loaded_at_epoch_ms") ?: 0L
    )
}

internal fun hasAccessibilityServiceEnabled(
    enabledFlag: Int,
    enabledServices: String?,
    fullComponentName: String,
    shortComponentName: String
): Boolean {
    if (enabledFlag != 1 || enabledServices.isNullOrBlank()) {
        return false
    }

    return enabledServices.split(':').any { entry ->
        val normalized = entry.trim()
        normalized.equals(fullComponentName, ignoreCase = true) ||
            normalized.equals(shortComponentName, ignoreCase = true)
    }
}

internal fun resolveXposedProcessName(
    xposedInjected: Boolean,
    currentProcessName: String,
    persistedProcessName: String
): String {
    return if (xposedInjected) currentProcessName else persistedProcessName
}

internal fun resolveXposedLoadedAtEpochMs(
    xposedInjected: Boolean,
    currentLoadedAtEpochMs: Long,
    persistedLoadedAtEpochMs: Long
): Long {
    return if (xposedInjected) currentLoadedAtEpochMs else persistedLoadedAtEpochMs
}

internal fun isAppOpAllowedOutput(output: String): Boolean {
    return output.trim().contains("allow", ignoreCase = true)
}

internal fun buildLocalEnvironmentDiagnosis(status: LocalEnvironmentStatus): LocalEnvironmentDiagnosis {
    return when (status.rootGranted) {
        null -> LocalEnvironmentDiagnosis(
            title = "Root 状态尚未确认",
            detail = "当前仅拿到本地静态状态，还没有完成 App 进程内的 Root 会话探测。",
            actionHint = "先执行一次本地环境刷新或启动自检，再判断 Magisk/Kitsune 与模块状态。"
        )

        false -> LocalEnvironmentDiagnosis(
            title = "等待 Root 授权",
            detail = "App 进程内还没有拿到 Root，会导致模块检测、Runtime 运行态和自动恢复授权都只能看到不完整结果。",
            actionHint = "在狐狸面具或 Magisk 的超级用户列表中允许当前 App，再重新执行本地环境刷新。"
        )

        true -> when {
            !status.magiskDaemonRunning -> LocalEnvironmentDiagnosis(
                title = "Root 已建立，但未检测到 Magisk/Kitsune 守护",
                detail = "这通常意味着当前 Root 管理器未正常工作，或 App 进程拿到的 Root 会话与外部 shell 环境不一致。",
                actionHint = "先确认设备侧 `magiskd` 或对应守护仍在运行，再继续检查模块目录。"
            )

            !status.magiskModuleInstalled -> LocalEnvironmentDiagnosis(
                title = "Root 已建立，但未检测到 ClawRuntime 模块",
                detail = "当前 App 可以执行 Root，但设备上没有发现 `clawruntime` 模块目录。",
                actionHint = "重装最新 `ClawRuntime-magisk.zip`，并在重启后重新刷新本地环境。"
            )

            !status.magiskModuleEnabled -> LocalEnvironmentDiagnosis(
                title = "模块已安装，但当前未启用",
                detail = "已检测到模块目录，但模块被禁用或正处于待移除状态，Runtime 不会正常拉起。",
                actionHint = "在模块管理器中重新启用 `clawruntime`，确认没有 `disable/remove` 标记后再重启设备。"
            )

            !status.runtimeDaemonRunning -> LocalEnvironmentDiagnosis(
                title = "模块已启用，但 Runtime 守护未运行",
                detail = "设备已具备 Root 和模块，但 `clawdroid-runtime` 进程当前不可见，后续握手一定会失败。",
                actionHint = "检查模块 `service.sh`、`verify.sh` 与 `webroot/status.json`，必要时重装模块并重启。"
            )

            else -> {
                val lsposedTail = when {
                    status.xposedInjected -> "LSPosed 当前进程已注入。"
                    status.lsposedManagerInstalled -> "LSPosed Manager 已安装，但当前进程尚未注入。"
                    else -> "LSPosed 是否启用需继续结合框架目录、作用域与 marker 判断。"
                }
                LocalEnvironmentDiagnosis(
                    title = "Root 与模块链路正常",
                    detail = "App 内 Root、模块目录、启用状态和 Runtime 守护都已可见，当前更适合继续排查 IPC 会话、能力同步或 LSPosed 注入状态。$lsposedTail",
                    actionHint = "继续执行 Runtime probe / capabilities / capture / swipe / shell / events 做业务闭环复测。"
                )
            }
        }
    }
}

private fun buildPersistedMarkerContent(
    xposedInjected: Boolean,
    processName: String,
    loadedAtEpochMs: Long,
    packageName: String
): String {
    return buildString {
        append("{")
        append("\"xposed_injected\":").append(xposedInjected)
        append(",\"process_name\":\"").append(escapeJsonString(processName)).append("\"")
        append(",\"loaded_at_epoch_ms\":").append(loadedAtEpochMs)
        append(",\"package_name\":\"").append(escapeJsonString(packageName)).append("\"")
        append("}")
    }
}

private fun escapeJsonString(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("\b", "\\b")
        .replace("\u000c", "\\f")
}

private fun extractBooleanField(content: String, key: String): Boolean? {
    val match = Regex("""\"$key\"\s*:\s*(true|false)""", RegexOption.IGNORE_CASE).find(content)
        ?: return null
    return match.groupValues[1].equals("true", ignoreCase = true)
}

private fun extractStringField(content: String, key: String): String? {
    val match = Regex("""\"$key\"\s*:\s*\"([^\"]*)\"""").find(content) ?: return null
    return match.groupValues[1]
}

private fun extractLongField(content: String, key: String): Long? {
    val match = Regex("""\"$key\"\s*:\s*(-?\d+)""").find(content) ?: return null
    return match.groupValues[1].toLongOrNull()
}
