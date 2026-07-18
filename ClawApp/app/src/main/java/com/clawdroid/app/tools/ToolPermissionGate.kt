package com.clawdroid.app.tools

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.clawdroid.app.env.ShizukuSupport
import com.clawdroid.app.service.ClawAccessibilityService
import java.io.File

/**
 * Pre-execute permission / capability gate for [ClawToolDispatcher].
 */
class ToolPermissionGate(
    private val context: Context?,
    private val assistEnabled: () -> Boolean = { false },
    private val knownCapabilities: () -> Set<String> = { emptySet() }
) {
    fun evaluate(spec: ClawToolSpec): ToolGateDecision {
        if (spec.status == ToolAvailability.Planned) {
            return ToolGateDecision(
                allowed = false,
                errorCode = "tool_planned",
                message = "工具 ${spec.id} 仅在蓝图中，尚未实现执行器"
            )
        }
        if (context != null && !ClawAssetPromptStore.isToolEnabled(context, spec.id, default = true)) {
            return ToolGateDecision(
                allowed = false,
                errorCode = "tool_disabled",
                message = "工具 ${spec.id} 已在 catalog overlay 中禁用"
            )
        }

        when (spec.tier) {
            ToolPermissionTier.None, ToolPermissionTier.Basic -> Unit
            ToolPermissionTier.Accessibility -> {
                if (!isAccessibilityEnabled()) {
                    return ToolGateDecision(
                        allowed = false,
                        errorCode = "permission_denied",
                        message = "需要无障碍权限（Accessibility）才能调用 ${spec.id}"
                    )
                }
            }
            ToolPermissionTier.AdbShizuku -> {
                if (ToolPermissionGrant.ASSIST_MCP_CLIENT in spec.grants && !assistEnabled()) {
                    return ToolGateDecision(
                        allowed = false,
                        errorCode = "assist_disabled",
                        message = "协助 MCP 客户端未启用；请在设置中配置电脑端点并执行 adb reverse"
                    )
                }
                if (ToolPermissionGrant.SHIZUKU in spec.grants &&
                    ToolPermissionGrant.ASSIST_MCP_CLIENT !in spec.grants
                ) {
                    val ctx = context
                    if (ctx != null && !ShizukuSupport.isManagerInstalled(ctx) &&
                        !ShizukuSupport.isBinderAlive()
                    ) {
                        return ToolGateDecision(
                            allowed = false,
                            errorCode = "shizuku_unavailable",
                            message = "未检测到 Shizuku；请安装并启动 Shizuku 后再调用 ${spec.id}"
                        )
                    }
                    if (spec.tool != ClawTool.SHIZUKU_REQUEST &&
                        spec.tool != ClawTool.SHIZUKU_STATUS &&
                        !ShizukuSupport.permissionGranted()
                    ) {
                        return ToolGateDecision(
                            allowed = false,
                            errorCode = "shizuku_permission_denied",
                            message = "Shizuku 未授权；请先调用 shizuku_request 或在 Shizuku 中授权"
                        )
                    }
                    if (spec.tool == ClawTool.SHIZUKU_REQUEST && !ShizukuSupport.isBinderAlive()) {
                        return ToolGateDecision(
                            allowed = false,
                            errorCode = "shizuku_binder_dead",
                            message = "Shizuku Binder 未连接；请先启动 Shizuku 服务"
                        )
                    }
                }
            }
            ToolPermissionTier.Root -> {
                if (!looksLikeRootRuntimePresent() && ToolPermissionGrant.RUNTIME_IPC in spec.grants) {
                    // Soft warning path: still allow attempt so Runtime can return precise errors,
                    // except when we know accessibility-only device with no module path.
                    if (!File("/data/adb/modules/clawruntime").exists() &&
                        !File("/data/local/tmp/clawdroid").exists()
                    ) {
                        // Do not hard-fail: magisk path may be invisible without root.
                    }
                }
            }
        }

        val caps = knownCapabilities()
        if (spec.requiredCapabilities.isNotEmpty() && caps.isNotEmpty()) {
            val missing = spec.requiredCapabilities.filterNot { it in caps }
            if (missing.isNotEmpty()) {
                return ToolGateDecision(
                    allowed = false,
                    errorCode = "capability_missing",
                    message = "缺少 Runtime 能力: ${missing.joinToString()}"
                )
            }
        }
        return ToolGateDecision(allowed = true)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val ctx = context ?: return true
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        if (!am.isEnabled) return false
        val expected = "${ctx.packageName}/${ClawAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        if (enabled.split(':').any { it.equals(expected, ignoreCase = true) }) {
            return true
        }
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val id = info.resolveInfo?.serviceInfo?.let { si ->
                    "${si.packageName}/${si.name}"
                }.orEmpty()
                id.equals(expected, ignoreCase = true)
            }
    }

    private fun looksLikeRootRuntimePresent(): Boolean {
        return File("/data/adb/modules/clawruntime").exists()
    }
}
