package com.clawdroid.app.ui

import com.clawdroid.app.env.LocalEnvironmentStatus
import com.clawdroid.app.runtime.ClawRuntimeConnectionState
import com.clawdroid.app.runtime.RuntimeErrorCodes

/**
 * Maps Runtime session / IPC failure text into a user-facing diagnosis card.
 */
internal data class RuntimeSessionDiagnosis(
    val title: String,
    val detail: String,
    val actionHint: String,
    val errorCode: Int? = null
) {
    fun asMultilineString(): String {
        return buildString {
            append(title)
            append('\n')
            append(detail)
            if (errorCode != null && errorCode != 0) {
                append('\n')
                append("错误码: ")
                append(errorCode)
            }
            append('\n')
            append(actionHint)
        }
    }
}

internal fun extractRuntimeErrorCode(text: String): Int? {
    // 前三条显式形式（[N] / code=N / error_code=N）允许 1~4 位数字，
    // 可覆盖 ERR_ROOT_UNAVAILABLE(8) 等单 digit 错误码且不会误匹配普通文本。
    // 兜底正则只匹配 4 位区间码，避免 `\b8\b` 在中文文本（如「重试 8 次」）中
    // 因 Java/Kotlin \w 仅识别 ASCII 字符而误判为限流。
    val patterns = listOf(
        Regex("""\[(\d{1,4})]"""),
        Regex("""\bcode\s*[=:]\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE),
        Regex("""\berror[_ ]?code\s*[=:]\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(100[1-5]|200[1-4]|300[1-6]|400[1-3]|500[1-3]|700[1-5])\b""")
    )
    for (pattern in patterns) {
        val match = pattern.find(text) ?: continue
        return match.groupValues.last().toIntOrNull()
    }
    return null
}

internal fun describeRuntimeErrorCode(code: Int): RuntimeSessionDiagnosis? {
    if (code == RuntimeErrorCodes.OK || code !in RuntimeErrorCodes.allCodes) {
        return null
    }
    return when (code) {
        RuntimeErrorCodes.ERR_RATE_LIMITED -> RuntimeSessionDiagnosis(
            title = "请求被限流",
            detail = "Runtime 在短时间内拒绝了过多同类请求（${RuntimeErrorCodes.message(code)}）。",
            actionHint = "降低调用频率后重试，避免短时间连续提交高危动作。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_PEER_VERIFY_FAILED -> RuntimeSessionDiagnosis(
            title = "对端凭证校验失败",
            detail = "Runtime 拒绝了当前 App 进程身份，通常是 UID/包名校验未通过。",
            actionHint = "确认安装的是当前调试包，并检查设备端 allowed_packages 是否包含本包名。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_SIGNATURE_MISMATCH -> RuntimeSessionDiagnosis(
            title = "签名摘要不匹配",
            detail = "App 上报的签名摘要与 Runtime 白名单不一致。",
            actionHint = "把当前 debug/release APK 的真实签名摘要同步到 runtime.yaml 的 auth.allowed_signatures，然后重启模块。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_CHALLENGE_FAILED -> RuntimeSessionDiagnosis(
            title = "挑战应答失败",
            detail = "握手阶段的 HMAC/挑战校验没有通过。",
            actionHint = "确认 App 与 Magisk 模块使用同一份 shared secret，并重新执行 sync-shared-secret 后重装模块。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_SESSION_EXPIRED -> RuntimeSessionDiagnosis(
            title = "会话已过期",
            detail = "旧会话令牌失效，后续动作会被拒绝。",
            actionHint = "重新执行 Runtime Probe 建立新会话后再试。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_CAPABILITY_TOKEN_INVALID -> RuntimeSessionDiagnosis(
            title = "能力令牌无效",
            detail = "当前请求携带的能力令牌缺失、过期或不匹配。",
            actionHint = "重新 Probe 同步能力画像，再发起对应高危动作。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_ROOT_UNAVAILABLE -> RuntimeSessionDiagnosis(
            title = "Root 不可用",
            detail = "Runtime 侧判定当前环境没有可用 Root。",
            actionHint = "在 Magisk/狐狸面具中授权本 App，并确认 su 会话可用。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_ACCESSIBILITY_UNAVAILABLE -> RuntimeSessionDiagnosis(
            title = "无障碍不可用",
            detail = "请求依赖无障碍，但当前未授权或服务未连接。",
            actionHint = "开启 Clawdroid 无障碍服务，或使用 Root 一键恢复后再刷新。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_SCREEN_CAPTURE_UNAVAILABLE -> RuntimeSessionDiagnosis(
            title = "截图能力不可用",
            detail = "当前能力画像未开放截图，或截图通道被关闭。",
            actionHint = "检查 runtime 配置中的 screenshot_enabled，以及设备侧截图权限/策略。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_CAPABILITY_NOT_GRANTED -> RuntimeSessionDiagnosis(
            title = "能力未授权",
            detail = "请求的能力项不在当前会话授权范围内。",
            actionHint = "先 get_capabilities 确认能力列表，再只调用已授权动作。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_INPUT_INJECT_FAILED -> RuntimeSessionDiagnosis(
            title = "输入注入失败",
            detail = "点击/滑动请求未被系统接受。",
            actionHint = "确认 input.inject 已授权，并检查坐标是否落在当前屏幕范围内。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_SHELL_DENIED,
        RuntimeErrorCodes.ERR_SHELL_EXEC_FAILED -> RuntimeSessionDiagnosis(
            title = "受限 Shell 执行失败",
            detail = "命令不在白名单中，或执行过程失败。",
            actionHint = "改用允许列表内命令（如 wm size），并检查 timeout 与 SELinux 拒绝日志。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_FILE_OUT_OF_SCOPE,
        RuntimeErrorCodes.ERR_FILE_READ_FAILED -> RuntimeSessionDiagnosis(
            title = "文件桥接失败",
            detail = "目标路径越界或读取失败。",
            actionHint = "确认路径落在 readonly_whitelist 内，并检查文件权限与大小限制。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_FILE_WRITE_FAILED -> RuntimeSessionDiagnosis(
            title = "文件写入失败",
            detail = "受限文件写入被拒绝或失败（${RuntimeErrorCodes.message(code)}）。",
            actionHint = "确认路径落在可写白名单、磁盘空间充足，并检查 SELinux/权限。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_ADAPTER_NOT_AVAILABLE -> RuntimeSessionDiagnosis(
            title = "适配模块不可用",
            detail = "目标应用的 LSPosed/适配器未加载。",
            actionHint = "检查 LSPosed 作用域与模块启用状态，必要时重启目标应用。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_TARGET_VERSION_UNSUPPORTED -> RuntimeSessionDiagnosis(
            title = "目标版本不受支持",
            detail = "当前目标 App 版本超出适配范围。",
            actionHint = "升级 Clawdroid 适配或回退目标应用版本。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_TARGET_UI_CHANGED -> RuntimeSessionDiagnosis(
            title = "目标页面结构变化",
            detail = "页面语义/控件结构与适配预期不一致。",
            actionHint = "刷新页面后重试，或更新对应 Xposed 适配。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_SELINUX_DENIED -> RuntimeSessionDiagnosis(
            title = "SELinux 拒绝",
            detail = "系统策略拦截了 Runtime 所需访问。",
            actionHint = "检查模块 sepolicy.rule 与 webroot/status.json，必要时按最小权限补策略后重启。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_DAEMON_UNHEALTHY -> RuntimeSessionDiagnosis(
            title = "Runtime 守护异常",
            detail = "守护进程存在但不健康。",
            actionHint = "查看 verify.sh / status.json / 审计日志，必要时重装模块并重启设备。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_ROM_UNSUPPORTED -> RuntimeSessionDiagnosis(
            title = "ROM 不兼容",
            detail = "当前系统行为与 Runtime 假设不兼容。",
            actionHint = "对照兼容性矩阵确认机型/ROM，或改用已验证构建。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_TASK_NOT_FOUND -> RuntimeSessionDiagnosis(
            title = "任务不存在",
            detail = "指定的 Runtime task_id 未找到。",
            actionHint = "先 task_list 确认任务仍在队列，或重新 task_submit。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_TASK_STATE_INVALID -> RuntimeSessionDiagnosis(
            title = "任务状态非法",
            detail = "请求的任务状态转换不被允许。",
            actionHint = "仅对 Running/Queued 任务执行 cancel，并对已结束任务改用 task_get。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_TASK_SUBMIT_FAILED -> RuntimeSessionDiagnosis(
            title = "任务提交失败",
            detail = "Runtime 未能接受 task_submit。",
            actionHint = "检查 steps JSON、能力授权与守护健康状态后重试。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_TASK_CANCEL_FAILED -> RuntimeSessionDiagnosis(
            title = "任务取消失败",
            detail = "取消请求未被 Runtime 接受。",
            actionHint = "确认 task_id 正确且任务尚未进入终态。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_TASK_QUEUE_FULL -> RuntimeSessionDiagnosis(
            title = "任务队列已满",
            detail = "Runtime 任务队列达到上限。",
            actionHint = "取消或等待已有任务完成后再提交。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_TIMEOUT -> RuntimeSessionDiagnosis(
            title = "请求超时",
            detail = "Runtime 未在动作默认超时内返回。",
            actionHint = "检查守护负载与设备性能，必要时增大超时或拆分步骤。",
            errorCode = code
        )
        RuntimeErrorCodes.ERR_PAYLOAD_TOO_LARGE -> RuntimeSessionDiagnosis(
            title = "负载过大",
            detail = "请求帧超过 Runtime 允许的最大负载。",
            actionHint = "缩小参数或分片读取/写入后再试。",
            errorCode = code
        )
        else -> RuntimeSessionDiagnosis(
            title = "Runtime 错误",
            detail = RuntimeErrorCodes.message(code),
            actionHint = "对照 Docs/protocol.md 错误码表排查，并查看 Runtime 审计日志。",
            errorCode = code
        )
    }
}

internal fun buildRuntimeSessionDiagnosis(
    localStatus: LocalEnvironmentStatus,
    runtimeState: OverviewRuntimeState
): RuntimeSessionDiagnosis {
    val summaryText = listOf(
        runtimeState.session.summary,
        runtimeState.session.degradedReason,
        runtimeState.lastErrorStatus,
        runtimeState.pingStatus,
        runtimeState.capabilityStatus
    ).joinToString("\n")
    val errorCode = extractRuntimeErrorCode(summaryText)
    errorCode?.let { describeRuntimeErrorCode(it) }?.let { return it }

    return when {
        localStatus.rootGranted != true ||
            !localStatus.magiskDaemonRunning ||
            !localStatus.magiskModuleInstalled ||
            !localStatus.magiskModuleEnabled ||
            !localStatus.runtimeDaemonRunning -> {
            val local = com.clawdroid.app.env.buildLocalEnvironmentDiagnosis(localStatus)
            RuntimeSessionDiagnosis(
                title = local.title,
                detail = local.detail,
                actionHint = local.actionHint
            )
        }

        runtimeState.session.state == ClawRuntimeConnectionState.Ready &&
            runtimeState.session.runtimeLoaded == false -> RuntimeSessionDiagnosis(
            title = "Runtime 已连通，但 LSPosed 注入未落地",
            detail = "会话轨迹: ${runtimeState.session.trace}；进程=${runtimeState.session.runtimeProcess.ifBlank { "unknown" }}",
            actionHint = "检查 xposed_runtime_marker、LSPosed 作用域与自进程注入，授权调整后需重启设备再冷启动 App。"
        )

        runtimeState.session.state == ClawRuntimeConnectionState.Ready -> RuntimeSessionDiagnosis(
            title = "Runtime 会话正常",
            detail = "状态轨迹: ${runtimeState.session.trace}",
            actionHint = "可继续做截图、滑动、Shell 与事件闭环验证。"
        )

        runtimeState.session.state == ClawRuntimeConnectionState.Degraded -> RuntimeSessionDiagnosis(
            title = "Runtime 已连通但处于降级态",
            detail = "降级原因: ${runtimeState.session.degradedReason.ifBlank { runtimeState.session.summary.ifBlank { "未提供" } }}",
            actionHint = "优先检查 runtime 配置、授权白名单与能力开关，再决定是否继续高危动作。"
        )

        runtimeState.session.state == ClawRuntimeConnectionState.Closed -> RuntimeSessionDiagnosis(
            title = "Runtime 会话已关闭或鉴权失败",
            detail = "状态轨迹: ${runtimeState.session.trace}；摘要: ${runtimeState.session.summary}",
            actionHint = "确认 App 与模块来自同一轮构建，重点核对 shared secret、签名白名单和设备端 runtime.yaml。"
        )

        runtimeState.session.state == ClawRuntimeConnectionState.Disconnected &&
            runtimeState.session.summary == "尚未建立会话" -> RuntimeSessionDiagnosis(
            title = "Runtime 守护可见，但尚未建立会话",
            detail = "本地模块链路已具备，仍缺一次正式握手。",
            actionHint = "执行 Runtime Probe 或 Capabilities，确认最终状态是 Ready、Degraded 还是 Closed。"
        )

        else -> RuntimeSessionDiagnosis(
            title = "Runtime 会话仍在协商",
            detail = "当前状态: ${runtimeState.session.state}；轨迹: ${runtimeState.session.trace}",
            actionHint = "等待启动自检完成，或手动再点一次 Runtime Probe。"
        )
    }
}
