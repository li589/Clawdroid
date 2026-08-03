# Clawdroid Agent / Contributor Guide

> AI / 开发者统一约定见 `ai-docs/AI.md`（gitignored，不提交 GitHub）。本文件（Claude Code / Cursor 入口）与 `AGENTS.md` 内容同源，均为根入口；改约定时以 `AGENTS.md` 为单一事实源。

## 双主体

| 单元 | 目录 | 职责 |
|------|------|------|
| Clawdroid App | `ClawApp/` | UI、Agent 编排、工具调度、无障碍 / LSPosed / Termux / MCP |
| ClawRuntime | `ClawRuntime/` | Root 守护进程、Magisk 模块、UDS IPC 服务端 |

App↔Runtime **只**走 JSON-over-UDS（见 `Docs/protocol.md`）。进程内 AIDL（sandbox / Shizuku）不替代 Runtime IPC。

## 包边界（App）

| 包 | 放什么 | 不放什么 |
|----|--------|----------|
| `ui` | Compose、ViewModel、页面状态 | 持久化 Store、巨型工具 `when`、长工具循环 |
| `data` | `*Store`、`MemoryFacade`、密钥加密 prefs 封装 | Compose UI |
| `agent` | Session / Goal / Plan / Run / Policy / WorldSnapshot / `AgentKernel` | Compose UI、Runtime IPC 帧 |
| `ai` / `chat` / `orchestrator` / `skills` | 模型回合、工具循环、压缩、路由、Skill/Catalog Agent | 直接画 UI |
| `tools` | 工具契约、Dispatcher、ToolHandler、域服务 | 聊天气泡 |
| `runtime` / `ipc` | Runtime 客户端与帧协议 | 业务编排 |
| `termux` / `mcp` / `xposed` / `focus` | 各自边车通道 | 跨层上帝对象 |

新增能力时：**先选边界包**，再写实现。不要把新业务 thrash 进 `ChatViewModel` / `OverviewController` / `SettingsScreen`。

## 改代码 checklist

### 新增工具（App）

1. `ClawTool` 枚举 + `ClawToolDefinitions` + `ClawToolCatalog` schema
2. 实现 `ToolHandler` 并在 `tools/handlers/*` 域注册表中登记（勿继续膨胀 Dispatcher 巨型 `when`）
3. 权限：`ToolPermissionGate` / `ToolPermissionGrant`
4. **输入校验**：在 Handler 入口调用 `InputGuards`（长度、`\0`/控制字符、路径、`..`、Shell 元字符、写文件大小）；勿只依赖 AI `validateToolArguments`（MCP 会绕过）
5. 若需进入模型可见列表：更新 `AgentOrchestrationSettings.defaultAllowlist()` 或设置页允许列表
6. 提示/Skill：必要时更新 `assets/claw/`（见该目录 `INDEX.md`）
7. 单测或 catalog 冒烟

### 新增 Runtime IPC action

1. Go：`ClawRuntime/runtime/internal/ipc/actions.go` + handler + audit
2. App：`RuntimeActionCatalog.kt` + `ClawRuntimeIpcClient` 方法
3. 更新 `Docs/protocol.md`
4. 跑 `python scripts/check_runtime_catalog.py`
5. 重建 Magisk ZIP（`ClawRuntime/scripts/build-runtime.ps1` → `build-magisk.ps1`）——**仅改 APK 不够**
6. 任务并发 / 日志：改 `max_concurrent_tasks`、`log_level`、审计轮转时同步 `runtime.yaml` 与文档

### 改 Agent 循环

- 停机逻辑放 `ToolLoopDetector` / `AgentToolLoopController`，不要在 UI 层硬编码「任意历史同参即停」
- SoftWarn（无进展指纹）必须**跳过** `dispatcher.execute`（合成系统步骤后继续），与 ReusePriorResult 同形
- 步数 / API 预算走 `AgentOrchestrationSettings`（设置 → Agent 与工具）
- Session/Goal/Run/Policy 走 `agent.AgentKernel`；记忆检索走 `data.MemoryFacade`；未完成 Run 走 `data.AgentRunStore`
- Goal 高风险确认仅用 App 内 HITL（`PendingCommandReview.isGoalConfirm`），勿伪实现 Runtime 放行 IPC
- 结构化反思：`ai.ToolReflectionCritique`（失败启发式 + 总结 JSON）
- 多 Agent 并行依赖 Runtime `task_*` 有界并发 + App `run_agents_parallel`；勿在 App 侧无界狂打 `task_submit`
- 等待 Runtime 终态用 App 工具 `task_wait`（轮询 `task_get`），不要伪实现未齐的放行 IPC

## 文档权威顺序

1. `Docs/architecture.md` — 当前架构
2. `Docs/protocol.md` — IPC 契约
3. `Docs/基础方案设计.md` — 原则与路线
4. `Docs/下一步计划.md` — 执行优先级
5. `Docs/archive/` — **非权威**历史记录

## 验证最低线

```powershell
# App
cd ClawApp; .\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest

# Catalog
cd ..; python scripts/check_runtime_catalog.py

# Runtime（改 Go 时）
cd ClawRuntime; go test ./...
# 然后：.\scripts\build-runtime.ps1; .\scripts\build-magisk.ps1 —— 仅改 APK 不够
```

密钥：`local.properties` 的 `clawdroid.runtime.sharedSecret` 必须与 Magisk 模块 YAML 同源。

## 当前优先级（基准 2026-08-03）

- **P0（验收收口）**：Assist MCP 联调清单；`list_dir_limited` 本轮 IPC deadline；`file_list` / `termux_exec` / `shizuku_*` 冒烟；重装 Magisk ZIP 验证。
- **P1（稳定性 + CI）**：第二台真机兼容矩阵；CI 强制 `:app:testDebugUnitTest`。
- **P2（阶段 C，待办）**：Supervisor / Role Agent；聊天任务台与 `task_*` 深同步；多 Agent 并行。

## 关键入口与文件

- Compose 组合根：`ClawApp/app/src/main/java/com/clawdroid/app/ui/ClawdroidApp.kt`
- Agent 内核（已合入工作树）：`agent/AgentKernel.kt`（Session/Run 生命周期 + 策略判定，含 `restoreRun`「继续」恢复）、`agent/AgentModels.kt`、`agent/AgentPolicy.kt`
- 工具调度：`tools/ClawToolDispatcher` → `ToolHandler` → `tools/handlers/*` 域注册表
- Runtime IPC：`ClawRuntime/runtime/internal/ipc/actions.go`（Go 动作 + handler + audit）与 App `runtime/RuntimeActionCatalog.kt` + `ClawRuntimeIpcClient`
- 协议校验：`scripts/check_runtime_catalog.py`（actions.go 与 RuntimeActionCatalog.kt 集合相等 + 错误码对齐）

## 铁律速记

1. 协议变更 = 同步 Go + Kotlin + `Docs/protocol.md` + `scripts/check_runtime_catalog.py`。
2. 新功能落正确包边界，禁止回流上帝类（`ChatViewModel` / `OverviewController` / `SettingsScreen`）。
3. 改 Runtime Go = 必须重装 Magisk ZIP（仅改 APK 不更新守护进程）。
4. `local.properties` 的 `clawdroid.runtime.sharedSecret` 与 Magisk 模块 YAML 同源。
5. MCP `tools/call` 绕过 AI 校验 → Handler `InputGuards` 必须兜底（长度、`\0`/控制字符、路径、`..`、Shell 元字符、写文件大小）。
