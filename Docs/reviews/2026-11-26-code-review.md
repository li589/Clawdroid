# Clawdroid 全面代码审查与进度核对

- 审查日期：2026-11-26
- 代码基线：`main@1c3ac60698aa7514db1df6aec573dd3565f1ba61`
- 审查方式：只读代码审查、静态扫描、单元测试、竞争检测、Lint、Git 与 GitHub CLI 核对
- CodeGraph：已启用，可作为符号与调用图辅助；本文代码证据以 Read/Grep 和实际源码为准

## 修复状态（2026-11-26）

以下 Critical/High/Medium 项已在工作树完成代码修复，并补回归测试：

- 构建链：`go build` 退出码、临时二进制、内嵌来源 trailer、原子替换、默认拒绝空签名名单、release 签名 fail-closed、密钥格式与 UTF-8 同步。
- Runtime：稳定 principal、任务深快照、排队/运行取消、per-step timeout、进程组取消、shutdown 有界收尾、TTL/容量清理。
- Runtime 安全：全局限流、时间戳、原子重放、连接/订阅配额、事件 TTL、focus/view 新鲜度、任务步骤审计、文件桥配置白名单与 fd 复核、审计轮转死锁与完整摘要。
- App：API 26/27 兼容、SVG WebView XSS 移除、SSRF 公共 URL 策略、SFTP known_hosts、`TASK_CANCEL` 非可取消上下文、并行 Agent 上限与任务卡隔离、SandboxShell 有界排空、下载资源限制、密钥加密失败显式化、MCP 危险工具确认。
- 工程：协议示例与实现对齐、catalog 校验扩展到 capability/handler/protocol、CI 增加 App 单测/Lint/Go race/Python 脚本测试、P0 冒烟不再把失败改为 PASS。
- 终审补漏：非幂等 replay 记录在 TTL 内改为不可容量淘汰的墓碑；MCP 启用确认时拒绝 `task_submit` / `run_agent` / `run_agents_parallel` 包装工具；`known_hosts` 损坏时 fail-closed。

仍需真机或专项验证的残余风险：Android 进程组与 Root 行为、Windows 子进程树清理、DNS rebinding、SFTP 首次连接 TOFU、真实 release 签名与 Magisk 安装、长期稳定性。

### 最终本地验证

- Go 1.26.4：`go test -count=1 ./...`、`go test -race -count=1 ./...`、`go vet ./...` 全部通过。
- 仓库脚本：catalog 21 动作/35 错误码/capability/handler/protocol 对齐通过；50 个 Python 测试通过；PowerShell、Shell、Python 语法通过。
- Gradle 8.13 + JDK 17：独立源码副本中 `compileDebugKotlin`、`lintDebug` 全部通过，Lint 0 errors；最终工作树全量单测 355 个全部通过。
- `git diff --check` 通过；未提交、未推送。

## 1. 执行摘要

Clawdroid 已完成双主体底座、Runtime 基础 IPC、Compose 三页壳、Agent 工具环和单真机基础闭环，但当前代码仍存在多项影响发布、安全性和稳定性的高优先级缺陷。

最重要的结论：

1. Runtime 构建链可能把旧二进制打入新 ZIP；现有本地 ZIP 内二进制来自 `277e1de`，落后当前 HEAD。
2. Runtime 的限流、重放、任务取消、审计、文件白名单和并发保护存在实质缺陷。
3. App 存在 Android 8–9 API 崩溃、SVG XSS、SSRF、SFTP 主机密钥不校验和任务取消不下发问题。
4. 普通 Go 测试和 App 单测通过，但 `go test -race` 与 Android Lint 均失败，说明现有 CI 不足以覆盖关键风险。
5. 本地 `main` 与 GitHub `origin/main` 完全同步；开放 PR/Issue 为 0；最新 CI 成功；正式发布仍只有 `v0.1.0`。

## 2. 审查范围与方法

### 2.1 范围

- `ClawRuntime/runtime`：IPC、鉴权、能力门禁、server、task、shell、filebridge、capture、audit、events。
- `ClawApp/app`：Agent、工具调度、Handler、InputGuards、IPC client、数据存储、ViewModel、Compose、MCP、Termux、Shizuku、Xposed/focus、网络工具。
- `scripts/`、`ClawRuntime/scripts/`、`.github/workflows/`、Gradle 配置。
- `Docs/architecture.md`、`Docs/protocol.md`、`Docs/下一步计划.md`、`Docs/release-notes.md`、`Docs/compatibility-matrix.md`。

未逐行审查 `GitSource/` 第三方参考代码和构建产物。

### 2.2 方法

- 关键路径使用 Read/Grep 逐段核对。
- 使用 `code-reviewer` skill 做 Python 脚本辅助扫描；该工具不识别 Kotlin/Go/PowerShell/YAML，相关“空指针”告警经源码核对为误报。
- 使用 Go 测试、`go vet`、`go test -race`、覆盖率、catalog 校验。
- 使用 Gradle 编译、Kotlin 单元测试和 Android Lint。
- 使用 `gh` 只读检查 GitHub 仓库、PR、Issue、Actions、tags 和 releases。

## 3. 验证记录

### 3.1 指定本地工具链

- Go：`C:\FreeRulesPrograms\BasicToolkits\Go\1.26.4`，`go1.26.4 windows/amd64`
- Gradle：`C:\FreeRulesPrograms\BasicToolkits\Gradle\gradle-8.13`
- JDK：`C:\FreeRulesPrograms\BasicToolkits\Java\jdk-17.0.19.10-hotspot`，`17.0.19`
- Android SDK：`D:\myPrograms\AndroidDevelop\Android-SDK`
- Android Studio：`D:\myPrograms\AndroidDevelop\Android Studio`

### 3.2 本地验证结果

| 检查 | 结果 | 说明 |
|---|---|---|
| `go test -count=1 ./...` | 通过 | audit/config/server/task 均通过 |
| `go vet ./...` | 通过 | 无输出 |
| `python scripts/check_runtime_catalog.py` | 通过 | 21 个动作、35 个错误码 |
| `go test -race -count=1 ./internal/task` | 失败 | 8 个 scheduler 测试触发数据竞争 |
| App 编译 + 单元测试 | 通过 | Gradle 8.13 + JDK 17 独立源码副本：335 tests、0 failures；Gradle 8.10.2 亦通过 |
| Android Lint | 失败 | Gradle 8.13 + JDK 17：6 errors、32 warnings |
| Python 语法 | 通过 | 9 个脚本 |
| PowerShell 语法 | 通过 | 仅解析，不执行 |
| Magisk Shell 语法 | 通过 | `bash -n` |
| 工作树 | 审查前仅有 `.codegraph/` 未跟踪 | 本轮新增 `.gitignore` 和本文档变更 |

> 仓库工作树内 Gradle 8.13 首次并行运行 compile/test 与 lint 时，两个 Gradle 进程并发写同一 `build/`，导致 `mergeDebugResources` 中间文件缺失；清理时又被 IDE Gradle Language Server 锁住。该失败属于本地构建目录占用，不是源码错误。随后把当前 `ClawApp` 源码复制到临时独立目录，使用 Gradle 8.13 + JDK 17 顺序完成编译和 335 个单元测试，全部通过。

## 4. Critical

### C1 Runtime 构建链可能产出旧二进制制品

**证据**

- `ClawRuntime/scripts/build-runtime.ps1:50`：`go build` 后未检查 `$LASTEXITCODE`。
- `ClawRuntime/scripts/build-magisk.ps1:30-35`：只检查二进制和配置文件是否存在。
- 现有 `ClawRuntime/dist/ClawRuntime-magisk.zip` 内二进制 `go version -m`：
  - `vcs.revision=277e1dec94f5ccc450c48b5d33cc8caa7b75e4e2`
  - `vcs.modified=true`
  - 当前 HEAD 为 `1c3ac60`。

**影响**

构建失败后仍可能打印成功并继续打包；现有 ZIP 不包含后续 capability gate、shell、scheduler 等修复，真机安装后可能仍运行旧代码。

**修复建议**

- 检查原生命令退出码。
- 构建到临时文件，成功后原子替换。
- 打包前校验二进制 commit/SHA-256。
- 发布流程要求干净工作树，并把来源元数据写入制品清单。

## 5. High

### H1 发布鉴权默认 fail-open

**证据**

- `ClawRuntime/runtime/internal/server/auth.go:204-217`：`allowed_signatures` 为空时返回 `true`。
- `local.properties.example:5`：占位符 `<64-char-random-secret>` 会被当作合法密钥。
- `ClawRuntime/runtime/internal/config/config.go:252-268`：只拒绝空值和旧占位符，不校验长度/格式。
- `ClawApp/app/build.gradle.kts:44-100`：缺签名配置时 release 回退为未签名。

**影响**

按示例初始化会嵌入公开已知 HMAC 密钥；发布包可能失去证书绑定或未签名。

**修复建议**

release 默认拒绝空签名白名单、占位符和短密钥；校验完整 SHA-256；release 构建缺签名时直接失败。

### H2 Runtime 限流、重放和连接保护可被绕过

**证据**

- `ClawRuntime/runtime/internal/server/server.go:197`：每个连接创建新 session。
- `ClawRuntime/runtime/internal/server/session.go:97-115`：限流仅按 session。
- `ClawRuntime/runtime/internal/server/idempotency.go:47-89`：只缓存幂等动作，key 包含 session ID。
- 请求帧 `timestamp` 未校验。
- `ClawRuntime/runtime/internal/server/server.go:150-173`：连接无全局上限。
- `ClawRuntime/runtime/internal/server/events.go:132-157`：订阅循环不检查 session TTL。

**影响**

重连可绕过 120/min 限流；非幂等高危动作缺少跨连接重放保护；本地进程可造成连接/goroutine 激增。

**修复建议**

按 UID+包名全局限流；校验请求时间戳；对非幂等动作使用 `request_id+参数摘要` 重放缓存；增加连接/订阅配额和 TTL 重新认证。

### H3 Task 步骤绕过审计、限流、重试边界，取消和超时无效

**证据**

- `ClawRuntime/runtime/internal/server/server.go:487-508`：`ExecuteStep` 直接调用 `handleRequest`，绕过限流/审计，并忽略 `ctx`、`timeoutMS`。
- `ClawRuntime/runtime/internal/server/handlers.go:447-459`：客户端可设置无上限 `max_attempts`。
- 步骤数、递归 `task_submit`、补偿并发无限制。
- `ClawRuntime/runtime/internal/task/scheduler.go:164-173`：只为运行中任务注册 cancel，排队任务无法取消。

**影响**

高危步骤缺少审计；模型可触发无界重试；取消后真实 Shell/截图/注入仍可能继续。

**修复建议**

统一受控执行入口；逐步审计；限制步骤数、重试次数和延迟；透传 context；为排队任务注册取消函数。

### H4 Session/Task 数据竞争

**证据**

- `ClawRuntime/runtime/internal/task/registry.go:146-155`：`List` 返回实时指针。
- `ClawRuntime/runtime/internal/server/handlers.go:351-355`、`events.go:235-283`：锁外读取任务字段。
- `ClawRuntime/runtime/internal/task/scheduler.go:134`：注册表锁外写 `QueuedAt`。
- session 结构无互斥保护。
- `go test -race` 在 8 个 scheduler 测试中复现竞争。

**影响**

任务快照不一致、潜在 panic/不稳定行为；生产 `task_list` 与事件路径使用相同不安全模式。

**修复建议**

`List` 返回深拷贝；所有 Task 字段写入统一进入注册表锁；session 增加同步保护；把 `-race` 加入 CI。

### H5 文件桥白名单失效，审计轮转可死锁

**证据**

- `ClawRuntime/runtime/internal/server/filebridge.go:596-619`：无条件加入 `/sdcard`、审计目录、Magisk 模块树和 webroot。
- `ClawRuntime/runtime/internal/audit/record.go:231-255`：轮转持有 `fl.mu`，重开失败后调用 `setLastError`。
- `ClawRuntime/runtime/internal/audit/record.go:285-290`：`setLastError` 再次获取 `fl.mu`，形成自死锁。
- 轮转文件名只使用秒级时间戳，可能覆盖。

**影响**

收紧配置仍可读取密钥/审计文件；审计盘满或重开失败可能阻塞后续请求。

**修复建议**

严格以配置白名单为准并排除密钥/审计；使用唯一分片名；禁止嵌套加锁。

### H6 任务生命周期绑定临时会话且历史不回收

**证据**

- `ClawRuntime/runtime/internal/server/handlers.go:282`：任务绑定提交连接的 session ID。
- `ClawRuntime/runtime/internal/server/server.go:497-504`：原 session 关闭后借用任意活跃 session。
- `ClawRuntime/runtime/internal/task/registry.go:231-240`：终态任务没有持续 TTL 清理。

**影响**

App 每次 IPC 新建连接后 `task_list` 可能为空；任务身份借用；长连接场景内存持续增长。

**修复建议**

使用稳定调用主体；任务归属稳定身份；增加历史 TTL/容量上限和关闭清理。

### H7 Android 8–9 API 不兼容崩溃

**证据**

- `ClawApp/app/build.gradle.kts:63`：`minSdk=26`。
- `ClawApp/app/src/main/java/com/clawdroid/app/tools/AppToolService.kt:130`：使用 API 28 的 `longVersionCode`。
- `ClawApp/app/src/main/java/com/clawdroid/app/mcp/McpHttpServer.kt:504`：使用 API 33 的 `URLDecoder.decode(String, Charset)`。

**影响**

Android 8–9 调用 `app_info` 或带 query 的 MCP 请求时可能抛出 `NoSuchMethodError`；当前 `catch (Exception)` 无法兜底。

**修复建议**

旧版本使用 `versionCode` 和 `URLDecoder.decode(value, "UTF-8")`；补 API 26/27 仪器测试。

### H8 SVG 富文本预览 XSS

**证据**

- `ClawApp/app/src/main/java/com/clawdroid/app/ui/rich/RichMessageContent.kt:89-95`：SVG 默认预览。
- `ClawApp/app/src/main/assets/richchat/viewer.html:95-104`：未净化 SVG 直接 `innerHTML`。
- `ClawApp/app/src/main/java/com/clawdroid/app/ui/rich/RichWebViewHost.kt:58-64`：JavaScript 和 FileAccess 开启。

**影响**

模型输出可包含带事件处理器的 SVG，存在脚本执行和本地文件读取风险。

**修复建议**

严格 SVG 白名单净化；禁用不需要的 JS/FileAccess；或改原生安全渲染。

### H9 网络工具 SSRF 与 SFTP 中间人风险

**证据**

- `ClawApp/app/src/main/java/com/clawdroid/app/tools/WebPreviewService.kt:19-31`：任意 HTTP/HTTPS、自动重定向、无私网/loopback 限制。
- `ClawApp/app/src/main/java/com/clawdroid/app/tools/ToolDownloadService.kt:63-67`：同样接受任意 URL。
- `ClawApp/app/src/main/java/com/clawdroid/app/tools/FtpTransferService.kt:164-171`：`StrictHostKeyChecking=no`。

**影响**

模型/MCP 可访问设备内网或本地服务；SFTP 凭据和内容可被中间人窃取。

**修复建议**

统一 URL 安全策略，每次重定向后重新校验；SFTP 使用 known_hosts 或固定密钥指纹。

### H10 App 协程取消时实际不会发送 `TASK_CANCEL`

**证据**

- `ClawApp/app/src/main/java/com/clawdroid/app/skills/RuntimeTaskPoller.kt:110-116`：在 `CancellationException` 中调用 `dispatcher.execute(TASK_CANCEL)`。
- `ClawApp/app/src/main/java/com/clawdroid/app/tools/ClawToolDispatcher.kt:130-135`：执行前 `ensureActive()`，已取消协程立即再次取消。
- `ClawApp/app/src/test/java/com/clawdroid/app/skills/RuntimeTaskPollerTest.kt:76-106`：使用 mock Dispatcher，绕过真实 `ensureActive`。

**影响**

用户停止任务时 Runtime 可能继续执行；现有单测产生假阳性。

**修复建议**

在 `withContext(NonCancellable)` 或独立 scope 中发送取消；增加真实 Dispatcher 回归测试。

## 6. Medium

### M1 协议线格式与冻结文档漂移

- `Docs/protocol.md:734-741` 的 `task_submit` 是平铺参数，实现要求 `args.task`。
- `write_file_limited` 文档要求 `content_base64/max_bytes`，实现读取原始 `content`。
- `capture_screen` 文档返回 `image_base64`，实现返回 `image_path`。
- 排队/终态取消错误码与文档不一致。

**建议：**统一 Runtime/App/文档，并增加线格式契约测试。

### M2 资源与路径边界不完整

- `ClawRuntime/runtime/internal/server/shell.go:584-601`：输出先写入无界 `bytes.Buffer`。
- `ClawRuntime/runtime/internal/server/capture.go:149-200`：截图子进程无超时，文件名可能碰撞，无 TTL 清理。
- `ClawRuntime/runtime/internal/server/filebridge.go:568-594`：路径校验与打开之间存在 TOCTOU。
- `ClawApp/app/src/main/java/com/clawdroid/app/tools/SandboxShellEngine.kt:36-42`：先 `waitFor` 再读管道，大输出可死锁；`readText()` 无上限。
- `ClawApp/app/src/main/java/com/clawdroid/app/tools/ToolDownloadService.kt:80,230-247`：活跃下载无并发上限、大小无上限。
- `ClawApp/app/src/main/java/com/clawdroid/app/tools/ToolDownloadService.kt:372-375`：`download_verify` 可校验沙箱外路径。

**建议：**有界 tail writer、并发配额、context 超时、唯一临时文件、dirfd/openat2、下载大小/并发限制。

### M3 并行 Agent 资源与 UI 状态冲突

- `ClawApp/app/src/main/java/com/clawdroid/app/ai/AgentToolLoopController.kt:641-688`：Agent 数量无上限。
- `ClawApp/app/src/main/java/com/clawdroid/app/ui/ChatViewModel.kt:965-973`：并行 Agent 仍更新共享任务步骤。
- `ClawApp/app/src/main/java/com/clawdroid/app/chat/ChatTaskExecutionController.kt:309-369`：多 Agent 按相同索引互相覆盖。

**建议：**限制并行数量；为每个 Agent 使用独立步骤命名空间/任务卡。

### M4 密钥加密失败静默丢数据

`ClawApp/app/src/main/java/com/clawdroid/app/data/AppSecretCipher.kt:22-34` 加密失败返回空串；调用方会覆盖 API Key/密码并删除旧明文。

**建议：**加密失败抛出明确错误，禁止用空串覆盖原值。

### M5 MCP 危险工具绕过聊天确认

- `ClawApp/app/src/main/java/com/clawdroid/app/mcp/McpJsonRpcHandler.kt:135-138` 直接调用 Dispatcher。
- `ClawApp/app/src/main/java/com/clawdroid/app/data/model/SettingsModels.kt:355` 命令确认默认关闭。

**影响：**持有 MCP token 的调用方可绕过聊天 HITL 执行 Termux、文件写入、相机等工具。

**建议：**把危险工具确认下沉到统一执行入口，或明确 MCP 为高信任边界并强制二次认证。

### M6 CI 与测试覆盖不足

- `.github/workflows/ci.yml:47-49` 只编译 App，不运行单测和 Lint。
- `scripts/check_runtime_catalog.py:59-68` 只比较常量集合。
- Go 总覆盖率约 30.4%，server 包约 19.5%。
- `scripts/p0_mcp_smoke.py:116-120` 会把 `app_stop` 白名单失败强制改为 PASS。

**建议：**CI 增加 App 单测、Lint、Go `-race`、协议契约测试；冒烟脚本不得把真实失败改为通过。

### M7 工程脚本一致性风险

- `ClawRuntime/scripts/sync-shared-secret.ps1:57` 以 ASCII 重写整个 `local.properties`。
- 脚本只读仓库根配置，而 Gradle 还读 App 本地配置和 Gradle property。
- `ClawRuntime/scripts/pack_magisk_zip.py:68-81` 缺少 `service.sh` 等必需文件断言，并在验证前删除旧 ZIP。

**建议：**统一密钥解析；UTF-8 保留原文件；必需文件清单；临时 ZIP 原子替换。

### M8 调试入口与发布权限

- `ClawApp/app/src/main/AndroidManifest.xml:61-67` 的 Debug Activity/Receiver 导出；release 有 `BuildConfig.DEBUG` 保护，但 debug 包仍可被同设备应用触发。
- `AndroidManifest.xml:9-11` 声明 `WRITE_SETTINGS`、`QUERY_ALL_PACKAGES`，存在发布策略风险。

**建议：**debug 入口增加签名级 permission；release 移除不需要的权限或说明必要性。

## 7. 主要测试缺口

- 真 UDS 握手、包绑定、重放、跨连接限流、TTL、连接/订阅压力测试。
- `-race` 下的 IPC/Task/Event 并发。
- 排队取消、真实步骤超时、补偿并发上限。
- Shell 白名单、输出上限、超时、截图命名/清理。
- 文件嵌套穿越、TOCTOU、空白名单、密钥/审计排除。
- 审计并发轮转、重开失败、写失败、全动作覆盖。
- Android API 26/27 仪器测试。
- SVG XSS、SSRF、SFTP 主机密钥、真实 Dispatcher 取消回归。

## 8. 本地进度

- 分支：`main`
- 本地提交数：26
- 相对 `v0.1.0`：领先 9 个提交
- HEAD：`1c3ac60698aa7514db1df6aec573dd3565f1ba61`
- 提交时间：2026-08-04 03:29:10 +08:00
- 工作树：本轮前仅 `.codegraph/` 未跟踪；已添加 `.codegraph/` 到 `.gitignore`
- 本地 APK：`ClawApp/app/build/outputs/apk/debug/app-debug.apk`，时间 2026-08-04 02:35，早于 HEAD，不能证明包含最新源码
- 本地 Runtime ZIP：`ClawRuntime/dist/ClawRuntime-magisk.zip`，时间 2026-08-04 02:33；内二进制来源 `277e1de`、`vcs.modified=true`
- 代码状态：v0.1.0 基线已收口，Unreleased 功能已落地但 P0 验收、P1 CI/第二设备仍未收口

## 9. GitHub 进度

- 仓库：`li589/Clawdroid`，公开仓库
- 默认分支：`main`
- 远端 HEAD：`1c3ac60698aa7514db1df6aec573dd3565f1ba61`
- 本地/远端差异：`git rev-list --left-right --count main...origin/main` = `0 0`
- 开放 PR：0
- 开放 Issue：0
- 最新 CI：run `30845991547`，成功，对应 HEAD `1c3ac60`
- 历史失败：
  - run `30842061148`（`5579e07`）：`repo-check` 缺文件失败，后续提交修复。
  - run `30839786749`（`277e1de`）：`repo-check` 失败；日志已过期，无法读取详细原因。
- 最新 release：`v0.1.0`，发布于 2026-07-19
  - `ClawApp-release-v0.1.0.apk`，下载 96 次
  - `ClawRuntime-magisk-v0.1.0.zip`，下载 5 次
- tag `v0.1.0` 指向 `1248c7d`，落后 main 9 个提交

## 10. 本地与远端对比

| 维度 | 本地 | GitHub | 结论 |
|---|---|---|---|
| main 提交 | `1c3ac60` | `1c3ac60` | 完全同步 |
| 未提交变更 | `.gitignore`、本文档 | 无 | 本地文档/配置待提交 |
| 开放 PR/Issue | 不适用 | 0/0 | 无外部协作进行中 |
| CI | 本地 race/lint 失败 | 最新 CI 成功 | CI 覆盖面不足 |
| 发布 | 本地制品早于 HEAD | 仅 v0.1.0 | Unreleased 未发布 |
| 真机矩阵 | 一台设备 | 文档同步 | 第二设备未验证 |

## 11. 修复优先级

### P0：发布前必须修复

1. Runtime 构建退出码、原子构建和制品来源校验。
2. release 签名、共享密钥和签名白名单 fail-closed。
3. Runtime 数据竞争、任务取消/超时、审计死锁、文件白名单、限流/重放。
4. App Android API 兼容、SVG XSS、SSRF、SFTP 主机密钥、`TASK_CANCEL` 不下发。

### P1：稳定性与质量门禁

1. 统一协议线格式并增加契约测试。
2. CI 增加 App 单测、Lint、Go `-race`。
3. 任务稳定身份、历史 TTL、排队取消、真实执行器超时。
4. Shell/截图/下载资源上限和文件 TOCTOU 加固。

### P2：工程债与体验

1. 并行 Agent 数量限制和任务卡隔离。
2. 密钥加密失败处理。
3. 脚本 UTF-8/原子化、必需文件校验。
4. Debug 入口权限、发布权限整理、文档回填。

## 12. CodeGraph 使用约定

- CodeGraph 索引目录为 `.codegraph/`，已加入 `.gitignore`，不提交。
- CodeGraph 可用于符号搜索、调用路径和影响面辅助。
- 涉及安全、协议、并发和正确性的最终结论必须以 Read/Grep 读取的源码、实际测试输出和 Git/GitHub 状态为准。
