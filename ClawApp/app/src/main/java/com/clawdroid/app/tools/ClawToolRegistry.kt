package com.clawdroid.app.tools

import com.clawdroid.app.runtime.ClawRuntimeConnectionState

/**
 * 工具注册表：能力枚举 + 展示信息。
 * JSON Schema / MCP 导出见 [ClawToolCatalog]；统一执行见 [ClawToolDispatcher]。
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
    GET_RUNTIME_STATUS(
        "get_runtime_status",
        "读取 Runtime/模块统一状态",
        "一次性读取 Magisk 模块状态、守护健康、动作目录、Shell/按键白名单"
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
    INJECT_KEYEVENT(
        "inject_keyevent",
        "按键注入",
        "通过 ClawRuntime 注入白名单内按键（BACK/HOME/ENTER 等）"
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
    ),
    SAFE_TAP(
        "safe_tap",
        "安全点击",
        "使用点击前检查解析出的坐标执行 Runtime 点击"
    ),
    LIST_SKILLS(
        "list_skills",
        "列出 Skills",
        "列出手机侧可用的 Skill（Cursor 风格指导文档）"
    ),
    GET_SKILL(
        "get_skill",
        "读取 Skill",
        "读取指定 Skill 的 SKILL.md 内容"
    ),
    LIST_AGENTS(
        "list_agents",
        "列出 Agents",
        "列出可执行的多步手机 Agent 工作流"
    ),
    RUN_AGENT(
        "run_agent",
        "运行 Agent",
        "按 Agent id 执行多步工具编排（体检、截图预览、安全点击等）"
    ),
    TASK_SUBMIT(
        "task_submit",
        "提交 Runtime 任务",
        "向 ClawRuntime 提交多步 task（task.manage）"
    ),
    TASK_GET(
        "task_get",
        "查询 Runtime 任务",
        "按 task_id 读取 Runtime 任务状态快照"
    ),
    TASK_LIST(
        "task_list",
        "列出 Runtime 任务",
        "列出当前会话的 Runtime 任务列表"
    ),
    TASK_CANCEL(
        "task_cancel",
        "取消 Runtime 任务",
        "取消正在运行的 Runtime 任务"
    ),

    // ========== 工具发现 ==========
    LIST_TOOLS(
        "list_tools",
        "列出工具",
        "按 id/tag/tier 查询本机工具目录（含权限与调用规范）"
    ),
    GET_TOOL(
        "get_tool",
        "读取工具说明",
        "读取单个工具的介绍、权限、输入输出与调用规范"
    ),

    // ========== 协助 MCP（手机 → 电脑）==========
    ASSIST_PING(
        "assist_ping",
        "协助 MCP 探测",
        "探测电脑协助 MCP 端点是否可达"
    ),
    ASSIST_LIST_TOOLS(
        "assist_list_tools",
        "列出电脑工具",
        "列出电脑协助 MCP 暴露的工具"
    ),
    ASSIST_CALL_TOOL(
        "assist_call_tool",
        "调用电脑工具",
        "调用电脑协助 MCP 上的工具并返回结果"
    ),
    ASSIST_STATUS(
        "assist_status",
        "协助 MCP 状态",
        "返回协助 MCP 客户端配置、隧道与最近错误"
    ),

    // ========== 文件 ==========
    FILE_READ(
        "file_read",
        "读取文件",
        "读取指定文件；支持按字节/按行偏移与行号索引"
    ),
    FILE_WRITE(
        "file_write",
        "写入文件",
        "覆盖或追加写入白名单/应用沙箱路径"
    ),
    FILE_REPLACE(
        "file_replace",
        "替换文件内容",
        "按字面量或正则替换文件内容，可限定行范围"
    ),
    FILE_STAT(
        "file_stat",
        "文件元信息",
        "读取文件大小、mtime、sha256 等"
    ),

    // ========== 应用 ==========
    APP_LIST(
        "app_list",
        "列出应用",
        "列出已安装应用包，可按关键字过滤"
    ),
    APP_LAUNCH(
        "app_launch",
        "启动应用",
        "按 package 或 action/intent 启动应用"
    ),
    APP_STOP(
        "app_stop",
        "停止应用",
        "通过 Runtime 受限 shell 对目标包执行 force-stop"
    ),
    APP_INFO(
        "app_info",
        "应用信息",
        "查询包版本、启动 Activity、是否可能在运行等"
    ),

    // ========== 下载 ==========
    DOWNLOAD_START(
        "download_start",
        "开始下载",
        "从 URL 下载到目标路径；支持期望 sha256 与断点续传"
    ),
    DOWNLOAD_STATUS(
        "download_status",
        "下载状态",
        "查询下载任务进度与状态"
    ),
    DOWNLOAD_CANCEL(
        "download_cancel",
        "取消下载",
        "取消进行中的下载任务"
    ),
    DOWNLOAD_VERIFY(
        "download_verify",
        "校验下载文件",
        "对比本地文件 sha256"
    ),

    // ========== 通知 / 网页 ==========
    NOTIFICATION_LIST(
        "notification_list",
        "列出通知",
        "读取通知监听缓存；需开启系统通知使用权"
    ),
    WEB_PREVIEW(
        "web_preview",
        "网页预览",
        "拉取 URL 并提取标题、纯文本与图片链接（无 WebView）"
    ),
    WEB_SEARCH(
        "web_search",
        "网页搜索",
        "关键词搜索（Wikipedia OpenSearch / DuckDuckGo HTML），返回标题链接摘要"
    ),
    SANDBOX_SHELL(
        "sandbox_shell",
        "沙箱 Shell",
        "在应用 filesDir/sandbox 下执行白名单短命令（无 Root/Shizuku）"
    ),

    // ========== 硬件 ==========
    CAMERA_CAPTURE(
        "camera_capture",
        "摄像头拍照",
        "Camera2 静帧 JPEG 拍照，保存到应用 cache/camera"
    ),
    SENSOR_READ(
        "sensor_read",
        "传感器读数",
        "列出传感器或读取加速度计/陀螺仪/光感/距离/磁场等短采样"
    ),
    CAMERA_RECORD(
        "camera_record",
        "摄像头短录像",
        "Camera2+MediaRecorder 静音短 MP4，并抽取首帧 JPEG 预览"
    ),
    FTP_TRANSFER(
        "ftp_transfer",
        "FTP/SFTP 传输",
        "FTP 或 SFTP list/get/put；本地路径限应用沙箱"
    ),
    GPU_NPU_PROBE(
        "gpu_npu_probe",
        "GPU/NPU 探测",
        "只读探测 GLES/Vulkan/NNAPI 能力（无算力调用）"
    ),

    // ========== Shizuku ==========
    SHIZUKU_STATUS(
        "shizuku_status",
        "Shizuku 状态",
        "探测 Shizuku Manager、Binder 与授权状态"
    ),
    SHIZUKU_REQUEST(
        "shizuku_request",
        "请求 Shizuku 授权",
        "在 Binder 可用时弹出 Shizuku 授权请求"
    ),
    SHIZUKU_EXEC(
        "shizuku_exec",
        "Shizuku 受限 Shell",
        "通过 Shizuku 执行白名单内的短命令（Adb/Shizuku 权限）"
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
    val allowedShellCommands: List<String>? = null,
    val runtimeTaskId: String? = null,
    val taskSnapshot: com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot? = null,
    val taskSnapshots: List<com.clawdroid.app.ipc.ClawRuntimeTaskSnapshot>? = null,
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
