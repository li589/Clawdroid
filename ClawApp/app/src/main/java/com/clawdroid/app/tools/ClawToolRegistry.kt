package com.clawdroid.app.tools

import com.clawdroid.app.runtime.ClawRuntimeConnectionState

/**
 * 工具注册表，用于把当前可用的能力统一描述为“工具”，方便后续接入 AI 编排层或 MCP-like 形态。
 *
 * 当前处于 MVP 阶段：仅枚举工具列表；后续可补参数字段、权限声明、风险标签等。
 */
enum class ClawTool(
    val toolId: String,
    val displayName: String,
    val description: String
) {
    // ========== 纯本地感知工具 ==========
    PAGE_CONFIRM(
        "page_confirm",
        "确认页面",
        "基于无障碍服务感知当前页面的包名、可见文本与 viewId，判断是否符合预期"
    ),
    CLICK_PRECHECK(
        "click_precheck",
        "点击前检查",
        "先执行页面确认，再检查当前可点击目标中是否包含期望的文本或 viewId"
    ),

    // ========== 运行时调用工具 ==========
    RUNTIME_PING(
        "runtime_ping",
        "ClawRuntime 连通性测试",
        "向 ClawRuntime 发送 Ping 请求，测试 UDS 连接与协议握手，返回延迟与版本信息"
    ),
    GET_VERSION(
        "get_version",
        "读取版本信息",
        "读取 ClawRuntime 的版本、协议版本、Socket 名称和日志级别"
    ),
    GET_HEALTH(
        "get_health",
        "读取健康状态",
        "读取 ClawRuntime 当前的运行状态、限流配置、白名单和可用能力"
    ),
    GET_LAST_ERROR(
        "get_last_error",
        "读取最近错误",
        "读取 ClawRuntime 最近错误、最近限流记录和只读白名单摘要"
    ),
    PROBE_SESSION(
        "probe_session",
        "执行会话探测",
        "一次性完成 Ping、握手、能力同步和最终连接状态判定"
    ),
    GET_CAPABILITIES(
        "get_capabilities",
        "读取能力列表",
        "读取 Root、无障碍、LSPosed、截图、文件桥接等能力开关"
    ),
    CAPTURE_SCREEN(
        "capture_screen",
        "屏幕截图",
        "通过 ClawRuntime 执行屏幕截图，保存到审计目录，可读取并预览结果"
    ),
    READ_LATEST_CAPTURE(
        "read_latest_capture",
        "预览最近截图",
        "读取最近一次截图文件并在 App 内尝试直接预览"
    ),
    READ_FILE_LIMITED(
        "read_file_limited",
        "受限文件读取",
        "通过 ClawRuntime 读取白名单路径下的文件内容，仅支持只读操作"
    ),
    INJECT_TAP(
        "inject_tap",
        "坐标点击",
        "通过 ClawRuntime 在指定坐标执行点击操作（非安全点击，不做前置校验）"
    ),
    INJECT_SWIPE(
        "inject_swipe",
        "坐标滑动",
        "通过 ClawRuntime 执行从起点到终点的滑动操作"
    ),
    EXECUTE_SHELL_LIMITED(
        "execute_shell_limited",
        "受限 Shell 执行",
        "通过 ClawRuntime 执行低风险 Shell 命令，输出会被审计"
    ),
    SUBSCRIBE_EVENTS(
        "subscribe_events",
        "事件流订阅",
        "通过 ClawRuntime 订阅事件流，包含窗口切换、指标变化、心跳等"
    );

    companion object {
        fun byToolId(id: String): ClawTool? = entries.find { it.toolId == id }
    }
}

data class ClawToolCallResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val runtimeConfigSummary: String? = null,
    val shellOutput: String? = null,
    val captureArtifact: ClawCaptureArtifact? = null,
    val previewBytes: ByteArray? = null,
    val sessionSnapshot: ClawSessionSnapshot? = null
)

data class ClawCaptureArtifact(
    val imagePath: String,
    val format: String,
    val width: Int,
    val height: Int,
    val fileSize: Long,
    val sha256: String
)

data class ClawSessionSnapshot(
    val sessionState: ClawRuntimeConnectionState,
    val sessionTrace: String,
    val authMode: String,
    val runtimeLoaded: Boolean? = null,
    val runtimeProcess: String = "",
    val runtimeLoadedAtEpochMs: Long = 0L,
    val degradedReason: String = ""
)
