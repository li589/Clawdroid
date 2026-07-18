package com.clawdroid.app.tools

import com.clawdroid.app.runtime.RuntimeActionCatalog

/**
 * Canonical permissioned metadata for every [ClawTool].
 */
object ClawToolDefinitions {
    private val byId: Map<ClawTool, ClawToolSpec> = buildMap {
        fun put(spec: ClawToolSpec) {
            put(spec.tool, spec)
        }

        put(
            ClawToolSpec(
                tool = ClawTool.PAGE_CONFIRM,
                tier = ToolPermissionTier.Accessibility,
                grants = setOf(ToolPermissionGrant.ACCESSIBILITY_SERVICE),
                backend = ToolBackend.Local,
                risk = ToolRisk.Read,
                tags = setOf("ui", "a11y"),
                callNotes = "先确认页面再点击；期望字段可部分填写",
                constraints = "需要本应用无障碍服务已开启"
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.CLICK_PRECHECK,
                tier = ToolPermissionTier.Accessibility,
                grants = setOf(ToolPermissionGrant.ACCESSIBILITY_SERVICE),
                backend = ToolBackend.Local,
                risk = ToolRisk.Read,
                tags = setOf("ui", "a11y"),
                callNotes = "解析可点击目标，供 safe_tap 使用"
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.SAFE_TAP,
                tier = ToolPermissionTier.Accessibility,
                grants = setOf(
                    ToolPermissionGrant.ACCESSIBILITY_SERVICE,
                    ToolPermissionGrant.INPUT_INJECT,
                    ToolPermissionGrant.RUNTIME_IPC
                ),
                backend = ToolBackend.Hybrid,
                risk = ToolRisk.Destructive,
                tags = setOf("ui", "input"),
                requiredCapabilities = setOf(RuntimeActionCatalog.CAPABILITY_INPUT_INJECT),
                callNotes = "必须先 click_precheck"
            )
        )

        val inspect = setOf(ToolPermissionGrant.RUNTIME_IPC)
        listOf(
            ClawTool.RUNTIME_PING to RuntimeActionCatalog.CAPABILITY_SYSTEM_PING,
            ClawTool.GET_VERSION to RuntimeActionCatalog.CAPABILITY_SYSTEM_INSPECT,
            ClawTool.GET_HEALTH to RuntimeActionCatalog.CAPABILITY_SYSTEM_INSPECT,
            ClawTool.GET_RUNTIME_STATUS to RuntimeActionCatalog.CAPABILITY_SYSTEM_INSPECT,
            ClawTool.GET_LAST_ERROR to RuntimeActionCatalog.CAPABILITY_SYSTEM_INSPECT,
            ClawTool.PROBE_SESSION to RuntimeActionCatalog.CAPABILITY_SYSTEM_PING,
            ClawTool.GET_CAPABILITIES to RuntimeActionCatalog.CAPABILITY_SYSTEM_INSPECT
        ).forEach { (tool, cap) ->
            put(
                ClawToolSpec(
                    tool = tool,
                    tier = ToolPermissionTier.Root,
                    grants = inspect,
                    backend = ToolBackend.Runtime,
                    risk = ToolRisk.Read,
                    tags = setOf("runtime", "inspect"),
                    requiredCapabilities = setOf(cap)
                )
            )
        }

        put(
            ClawToolSpec(
                tool = ClawTool.CAPTURE_SCREEN,
                tier = ToolPermissionTier.Root,
                grants = setOf(ToolPermissionGrant.RUNTIME_IPC, ToolPermissionGrant.SCREEN_CAPTURE),
                backend = ToolBackend.Runtime,
                risk = ToolRisk.Read,
                tags = setOf("screen", "capture"),
                requiredCapabilities = setOf(RuntimeActionCatalog.CAPABILITY_SCREEN_CAPTURE)
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.READ_LATEST_CAPTURE,
                tier = ToolPermissionTier.Root,
                grants = setOf(ToolPermissionGrant.RUNTIME_IPC, ToolPermissionGrant.FILE_BRIDGE_READ),
                backend = ToolBackend.Runtime,
                risk = ToolRisk.Read,
                tags = setOf("screen", "file"),
                requiredCapabilities = setOf(RuntimeActionCatalog.CAPABILITY_FILE_READ_LIMITED)
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.READ_FILE_LIMITED,
                tier = ToolPermissionTier.Root,
                grants = setOf(ToolPermissionGrant.RUNTIME_IPC, ToolPermissionGrant.FILE_BRIDGE_READ),
                backend = ToolBackend.Runtime,
                risk = ToolRisk.Read,
                tags = setOf("file"),
                requiredCapabilities = setOf(RuntimeActionCatalog.CAPABILITY_FILE_READ_LIMITED),
                deprecatedBy = "file_read",
                callNotes = "兼容旧工具；新代码请用 file_read"
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.INJECT_TAP,
                tier = ToolPermissionTier.Root,
                grants = setOf(ToolPermissionGrant.RUNTIME_IPC, ToolPermissionGrant.INPUT_INJECT),
                backend = ToolBackend.Runtime,
                risk = ToolRisk.Destructive,
                tags = setOf("input"),
                requiredCapabilities = setOf(RuntimeActionCatalog.CAPABILITY_INPUT_INJECT)
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.INJECT_KEYEVENT,
                tier = ToolPermissionTier.Root,
                grants = setOf(ToolPermissionGrant.RUNTIME_IPC, ToolPermissionGrant.INPUT_INJECT),
                backend = ToolBackend.Runtime,
                risk = ToolRisk.Destructive,
                tags = setOf("input"),
                requiredCapabilities = setOf(RuntimeActionCatalog.CAPABILITY_INPUT_INJECT)
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.INJECT_SWIPE,
                tier = ToolPermissionTier.Root,
                grants = setOf(ToolPermissionGrant.RUNTIME_IPC, ToolPermissionGrant.INPUT_INJECT),
                backend = ToolBackend.Runtime,
                risk = ToolRisk.Destructive,
                tags = setOf("input"),
                requiredCapabilities = setOf(RuntimeActionCatalog.CAPABILITY_INPUT_INJECT)
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.EXECUTE_SHELL_LIMITED,
                tier = ToolPermissionTier.Root,
                grants = setOf(ToolPermissionGrant.RUNTIME_IPC, ToolPermissionGrant.SHELL_ALLOWLIST),
                backend = ToolBackend.Runtime,
                risk = ToolRisk.Destructive,
                tags = setOf("shell"),
                requiredCapabilities = setOf(RuntimeActionCatalog.CAPABILITY_SHELL_EXEC_LIMITED),
                constraints = "仅允许白名单模板命令"
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.SUBSCRIBE_EVENTS,
                tier = ToolPermissionTier.Root,
                grants = setOf(ToolPermissionGrant.RUNTIME_IPC),
                backend = ToolBackend.Runtime,
                risk = ToolRisk.Write,
                tags = setOf("events"),
                requiredCapabilities = setOf(RuntimeActionCatalog.CAPABILITY_EVENT_SUBSCRIBE)
            )
        )

        listOf(ClawTool.LIST_SKILLS, ClawTool.GET_SKILL, ClawTool.LIST_AGENTS).forEach { tool ->
            put(
                ClawToolSpec(
                    tool = tool,
                    tier = ToolPermissionTier.None,
                    backend = ToolBackend.Local,
                    risk = ToolRisk.Read,
                    tags = setOf("meta", "skills")
                )
            )
        }
        put(
            ClawToolSpec(
                tool = ClawTool.RUN_AGENT,
                tier = ToolPermissionTier.Basic,
                backend = ToolBackend.Hybrid,
                risk = ToolRisk.Destructive,
                tags = setOf("agent"),
                callNotes = "Agent 内部步骤仍受各自工具权限约束"
            )
        )
        listOf(ClawTool.TASK_GET, ClawTool.TASK_LIST).forEach { tool ->
            put(
                ClawToolSpec(
                    tool = tool,
                    tier = ToolPermissionTier.Root,
                    grants = inspect,
                    backend = ToolBackend.Runtime,
                    risk = ToolRisk.Read,
                    tags = setOf("task"),
                    requiredCapabilities = setOf(RuntimeActionCatalog.CAPABILITY_TASK_MANAGE)
                )
            )
        }
        listOf(ClawTool.TASK_SUBMIT, ClawTool.TASK_CANCEL).forEach { tool ->
            put(
                ClawToolSpec(
                    tool = tool,
                    tier = ToolPermissionTier.Root,
                    grants = inspect,
                    backend = ToolBackend.Runtime,
                    risk = ToolRisk.Destructive,
                    tags = setOf("task"),
                    requiredCapabilities = setOf(RuntimeActionCatalog.CAPABILITY_TASK_MANAGE)
                )
            )
        }

        put(
            ClawToolSpec(
                tool = ClawTool.LIST_TOOLS,
                tier = ToolPermissionTier.None,
                backend = ToolBackend.Local,
                risk = ToolRisk.Read,
                tags = setOf("meta", "discovery"),
                callNotes = "支持 filter: tag / tier / id_prefix；include_planned 默认 false"
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.GET_TOOL,
                tier = ToolPermissionTier.None,
                backend = ToolBackend.Local,
                risk = ToolRisk.Read,
                tags = setOf("meta", "discovery")
            )
        )

        put(
            ClawToolSpec(
                tool = ClawTool.ASSIST_STATUS,
                tier = ToolPermissionTier.None,
                backend = ToolBackend.Assist,
                risk = ToolRisk.Read,
                tags = setOf("assist", "mcp"),
                callNotes = "不要求客户端已启用；用于诊断配置与最近错误"
            )
        )
        listOf(ClawTool.ASSIST_PING, ClawTool.ASSIST_LIST_TOOLS).forEach { tool ->
            put(
                ClawToolSpec(
                    tool = tool,
                    tier = ToolPermissionTier.AdbShizuku,
                    grants = setOf(ToolPermissionGrant.ASSIST_MCP_CLIENT, ToolPermissionGrant.INTERNET),
                    backend = ToolBackend.Assist,
                    risk = ToolRisk.Read,
                    tags = setOf("assist", "mcp")
                )
            )
        }
        put(
            ClawToolSpec(
                tool = ClawTool.ASSIST_CALL_TOOL,
                tier = ToolPermissionTier.AdbShizuku,
                grants = setOf(ToolPermissionGrant.ASSIST_MCP_CLIENT, ToolPermissionGrant.INTERNET),
                backend = ToolBackend.Assist,
                risk = ToolRisk.Destructive,
                tags = setOf("assist", "mcp"),
                callNotes = "arguments 为 JSON 对象；需先 adb reverse",
                examples = listOf("""{"name":"browser_navigate","arguments":{"url":"https://example.com"}}""")
            )
        )

        put(
            ClawToolSpec(
                tool = ClawTool.FILE_READ,
                tier = ToolPermissionTier.Basic,
                grants = setOf(ToolPermissionGrant.STORAGE_READ, ToolPermissionGrant.FILE_BRIDGE_READ),
                backend = ToolBackend.Hybrid,
                risk = ToolRisk.Read,
                tags = setOf("file"),
                callNotes = "应用沙箱路径走 Basic；白名单系统路径走 Runtime",
                examples = listOf("""{"path":"/sdcard/Download/a.txt","mode":"lines","line_start":1,"line_limit":50}""")
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.FILE_WRITE,
                tier = ToolPermissionTier.Basic,
                grants = setOf(ToolPermissionGrant.STORAGE_WRITE, ToolPermissionGrant.FILE_BRIDGE_WRITE),
                backend = ToolBackend.Hybrid,
                risk = ToolRisk.Write,
                tags = setOf("file"),
                constraints = "沙箱外路径需要 Root/Runtime write 白名单"
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.FILE_REPLACE,
                tier = ToolPermissionTier.Basic,
                grants = setOf(ToolPermissionGrant.STORAGE_WRITE, ToolPermissionGrant.FILE_BRIDGE_WRITE),
                backend = ToolBackend.Hybrid,
                risk = ToolRisk.Write,
                tags = setOf("file")
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.FILE_STAT,
                tier = ToolPermissionTier.Basic,
                grants = setOf(ToolPermissionGrant.STORAGE_READ, ToolPermissionGrant.FILE_BRIDGE_READ),
                backend = ToolBackend.Hybrid,
                risk = ToolRisk.Read,
                tags = setOf("file")
            )
        )

        put(
            ClawToolSpec(
                tool = ClawTool.APP_LIST,
                tier = ToolPermissionTier.Basic,
                grants = setOf(ToolPermissionGrant.PACKAGE_QUERY),
                backend = ToolBackend.Local,
                risk = ToolRisk.Read,
                tags = setOf("app")
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.APP_LAUNCH,
                tier = ToolPermissionTier.Basic,
                grants = setOf(ToolPermissionGrant.PACKAGE_LAUNCH),
                backend = ToolBackend.Local,
                risk = ToolRisk.Write,
                tags = setOf("app")
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.APP_STOP,
                tier = ToolPermissionTier.Root,
                grants = setOf(ToolPermissionGrant.RUNTIME_IPC, ToolPermissionGrant.SHELL_ALLOWLIST),
                backend = ToolBackend.Runtime,
                risk = ToolRisk.Destructive,
                tags = setOf("app"),
                requiredCapabilities = setOf(RuntimeActionCatalog.CAPABILITY_SHELL_EXEC_LIMITED),
                callNotes = "使用 am force-stop <package> 白名单模板"
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.APP_INFO,
                tier = ToolPermissionTier.Basic,
                grants = setOf(ToolPermissionGrant.PACKAGE_QUERY),
                backend = ToolBackend.Local,
                risk = ToolRisk.Read,
                tags = setOf("app")
            )
        )

        put(
            ClawToolSpec(
                tool = ClawTool.DOWNLOAD_START,
                tier = ToolPermissionTier.Basic,
                grants = setOf(ToolPermissionGrant.INTERNET, ToolPermissionGrant.STORAGE_WRITE),
                backend = ToolBackend.Local,
                risk = ToolRisk.Write,
                tags = setOf("network", "download"),
                callNotes = "默认下载到应用缓存 downloads/；支持 Range 断点与 expected_sha256"
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.DOWNLOAD_STATUS,
                tier = ToolPermissionTier.None,
                backend = ToolBackend.Local,
                risk = ToolRisk.Read,
                tags = setOf("network", "download")
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.DOWNLOAD_CANCEL,
                tier = ToolPermissionTier.None,
                backend = ToolBackend.Local,
                risk = ToolRisk.Write,
                tags = setOf("network", "download")
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.DOWNLOAD_VERIFY,
                tier = ToolPermissionTier.None,
                backend = ToolBackend.Local,
                risk = ToolRisk.Read,
                tags = setOf("network", "download")
            )
        )

        put(
            ClawToolSpec(
                tool = ClawTool.NOTIFICATION_LIST,
                tier = ToolPermissionTier.Basic,
                grants = setOf(ToolPermissionGrant.NOTIFICATION_ACCESS),
                backend = ToolBackend.Local,
                risk = ToolRisk.Read,
                tags = setOf("app", "notification"),
                callNotes = "需在系统设置中开启通知使用权；未授权时仍返回状态提示"
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.WEB_PREVIEW,
                tier = ToolPermissionTier.Basic,
                grants = setOf(ToolPermissionGrant.INTERNET),
                backend = ToolBackend.Local,
                risk = ToolRisk.Read,
                tags = setOf("network", "web"),
                callNotes = "仅 http/https；提取 title/text/images，不做 JS 渲染",
                examples = listOf("""{"url":"https://example.com","include_images":true}""")
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.WEB_SEARCH,
                tier = ToolPermissionTier.Basic,
                grants = setOf(ToolPermissionGrant.INTERNET),
                backend = ToolBackend.Local,
                risk = ToolRisk.Read,
                tags = setOf("network", "web", "search"),
                callNotes = "provider=auto|wikipedia|ddg；可再对结果 URL 调用 web_preview",
                examples = listOf("""{"query":"Android accessibility","max_results":5,"provider":"auto"}""")
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.SANDBOX_SHELL,
                tier = ToolPermissionTier.Basic,
                grants = setOf(ToolPermissionGrant.SHELL_ALLOWLIST),
                backend = ToolBackend.Local,
                risk = ToolRisk.Write,
                tags = setOf("sandbox", "shell"),
                callNotes = "cwd=filesDir/sandbox；白名单: pwd/id/uname/ls/cat/head/tail/wc/mkdir/echo",
                examples = listOf("""{"command":"ls"}""", """{"command":"echo hello"}""")
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.CAMERA_CAPTURE,
                tier = ToolPermissionTier.Basic,
                grants = setOf(ToolPermissionGrant.CAMERA),
                backend = ToolBackend.Local,
                risk = ToolRisk.Write,
                tags = setOf("hardware", "camera"),
                callNotes = "需 CAMERA 运行时权限；facing=back|front；静帧 JPEG（录像见 camera_record）",
                examples = listOf("""{"facing":"back","max_dimension":1280}""")
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.SENSOR_READ,
                tier = ToolPermissionTier.Basic,
                backend = ToolBackend.Local,
                risk = ToolRisk.Read,
                tags = setOf("hardware", "sensor"),
                callNotes = "op=list|read；read 时 type=accelerometer|gyroscope|light|proximity|magnetic_field",
                examples = listOf(
                    """{"op":"list"}""",
                    """{"op":"read","type":"accelerometer","max_samples":3}"""
                )
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.CAMERA_RECORD,
                tier = ToolPermissionTier.Basic,
                grants = setOf(ToolPermissionGrant.CAMERA),
                backend = ToolBackend.Local,
                risk = ToolRisk.Write,
                tags = setOf("hardware", "camera"),
                callNotes = "需 CAMERA；duration_ms 500-15000；静音 MP4 + preview JPEG；无实时预览 UI",
                examples = listOf("""{"facing":"back","duration_ms":3000,"max_dimension":1280}""")
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.FTP_TRANSFER,
                tier = ToolPermissionTier.Basic,
                grants = setOf(ToolPermissionGrant.INTERNET),
                backend = ToolBackend.Local,
                risk = ToolRisk.Write,
                tags = setOf("network", "ftp"),
                callNotes = "protocol=ftp|sftp；op=list|get|put；local_path 限 filesDir/cacheDir；SFTP 仅密码认证（StrictHostKeyChecking=no）",
                examples = listOf(
                    """{"op":"list","host":"ftp.example.com","remote_path":"/"}""",
                    """{"op":"get","protocol":"sftp","host":"sftp.example.com","port":22,"user":"u","password":"p","remote_path":"/a.bin","local_path":"ftp/a.bin"}"""
                )
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.GPU_NPU_PROBE,
                tier = ToolPermissionTier.Basic,
                backend = ToolBackend.Local,
                risk = ToolRisk.Read,
                tags = setOf("hardware", "gpu", "npu"),
                callNotes = "只读 GLES/Vulkan/NNAPI 清单；无算力调用",
                examples = listOf("""{}""")
            )
        )

        put(
            ClawToolSpec(
                tool = ClawTool.SHIZUKU_STATUS,
                tier = ToolPermissionTier.None,
                grants = setOf(ToolPermissionGrant.SHIZUKU),
                backend = ToolBackend.Local,
                risk = ToolRisk.Read,
                tags = setOf("shizuku", "adb"),
                callNotes = "不强制要求已授权；用于探测 Manager/Binder/permission"
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.SHIZUKU_REQUEST,
                tier = ToolPermissionTier.AdbShizuku,
                grants = setOf(ToolPermissionGrant.SHIZUKU),
                backend = ToolBackend.Local,
                risk = ToolRisk.Write,
                tags = setOf("shizuku", "adb"),
                callNotes = "Binder 存活且未授权时触发系统授权弹窗"
            )
        )
        put(
            ClawToolSpec(
                tool = ClawTool.SHIZUKU_EXEC,
                tier = ToolPermissionTier.AdbShizuku,
                grants = setOf(ToolPermissionGrant.SHIZUKU, ToolPermissionGrant.SHELL_ALLOWLIST),
                backend = ToolBackend.Local,
                risk = ToolRisk.Destructive,
                tags = setOf("shizuku", "shell"),
                callNotes = "仅允许固定白名单短命令（id/pm path/dumpsys package/pidof 等）",
                examples = listOf("""{"command":"id"}""")
            )
        )
    }

    fun spec(tool: ClawTool): ClawToolSpec =
        byId[tool] ?: ClawToolSpec(tool = tool, tier = ToolPermissionTier.Basic)

    fun all(): List<ClawToolSpec> = ClawTool.entries.map { spec(it) }

    fun find(
        tag: String? = null,
        tier: ToolPermissionTier? = null,
        idPrefix: String? = null,
        includePlanned: Boolean = false
    ): List<ClawToolSpec> {
        return all().filter { spec ->
            if (!includePlanned && spec.status == ToolAvailability.Planned) return@filter false
            if (tag != null && tag.isNotBlank() && tag.lowercase() !in spec.tags.map { it.lowercase() }) {
                return@filter false
            }
            if (tier != null && spec.tier != tier) return@filter false
            if (idPrefix != null && idPrefix.isNotBlank() &&
                !spec.id.startsWith(idPrefix.trim(), ignoreCase = true)
            ) {
                return@filter false
            }
            true
        }
    }
}
