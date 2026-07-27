# Clawdroid Release Notes

## Unreleased（`main` post-`v0.1.0`）

> 相对已推送的 `v0.1.0` 基线，本地 `main` 已包含下列能力。正式打 tag 前应以 [下一步计划.md](下一步计划.md) P0/P1 验收为准。

### 新增与增强

- **可靠性六项（2026-07-27）**：
  - App↔Runtime **能力对齐 banner**（`RuntimeCompatSnapshot`：协议/缺失动作 → 概览 + 运行诊断）
  - **IPC 分动作读超时**（shell=`timeout_ms+2s`、截图≈45s 等）；Runtime 对 `exec_shell_limited`/`capture_screen` **清除连接 deadline**
  - Agent **SoftWarn 跳过工具执行**（无进展指纹不再 `dispatcher.execute`）
  - Termux **坏容器清理**（设置页一键 `proot-distro remove` + 白名单 lock）
  - 聊天 **当前轮附图多模态**（OpenAI `image_url` / Anthropic `image`；Custom 明确拒绝）
  - App 工具 **`task_wait`** + 取消补 `task_cancel` + detach 软状态（非 IPC action）
- **AI Agent**：`AiAgentOrchestrator` 计划 → 工具环 → 总结；`ToolLoopDetector`（连续失败 / 无进展跳过 / 成功同参复用）；可配置循环步数与会话 API 预算（「继续」重置）；上下文压缩；`AgentRunEvent` 时间线；`run_agents_parallel`
- **工具地基**：`ClawToolCatalog` / `ClawToolDispatcher` / `ToolServiceRegistry`；权限层级 `None` → `Root`；live capabilities 与事件解耦；工具允许列表
- **域工具**：`file_*`（含 **`file_list`**）、`app_*`、`download_*`、`notification_list`、`web_preview` / `web_search`、`sandbox_shell`（`:sandbox` 进程）、`termux_exec`、`camera_*`、`sensor_read`、`gpu_npu_probe`、`ftp_transfer`、**`task_wait`**
- **UI**：设置 Hub → 分类详情；富文本聊天（Markdown / 代码 / KaTeX / Mermaid）；edge-to-edge；`compileSdk`/`targetSdk` 35
- **Termux**：`RUN_COMMAND` 桥 + 应用内 `TermuxConsoleScreen` + 坏容器清理
- **Assist MCP**：手机 MCP Server（`clawdroid-assist`）+ 电脑协助端点客户端；见 [assist-mcp.md](assist-mcp.md)
- **Shizuku**：状态 / 授权请求 / 白名单短命令执行
- **Xposed 适配器**：Settings / Browser / Launcher / 自进程 marker；微信 `wechat_detail` 默认关闭；focus schema v2 + 浅层 view dump + ContentProvider 推送；见 [xposed-adapters.md](xposed-adapters.md)
- **Runtime**：审查修复；`write_file_limited` / `stat_file_limited` / **`list_dir_limited`**；`report_xposed_*` 与 `task_*` 动作目录对齐；**有界多任务并行**（`max_concurrent_tasks`/`max_inflight_tasks`，满则 `7005`）；**shell 监视**（`job_id` + `shell_job_list`/`shell_job_get`）；审计 JSONL 体积轮转 + 服务日志 5MB 轮转；`log_level` 生效；长动作连接 deadline 与 App 读超时配合（见 [protocol.md](protocol.md) §9.3）
- **输入安全**：App `InputGuards`（聊天粘贴截断、Shell 元字符、路径 `..`/`\0`、写文件 1MiB、下载 dest 沙箱）
- **工程**：`ClawdroidShell` / CompositionRoot；`data` 持久化包；文档 `architecture.md` + `AGENTS.md`

### 已知限制（Unreleased）

- Assist MCP / 域工具联调清单尚未系统性勾选
- 兼容矩阵仍仅一台完整真机证据
- 可靠性六项需真机烟测勾选（见 [真机安装验收清单.md](真机安装验收清单.md) §8）；改 Go 后必须重装同轮 Magisk ZIP
- Agent ↔ Runtime 任务台仍可加深（WaitingSignal / 人工放行 IPC **未做**）
- CI App 任务当前以 `compileDebugKotlin` 为主，unit test 未强制进流水线
- Termux 需用户开启 `allow-external-apps` 并授予 `RUN_COMMAND`
- 附图仅当前轮；历史为文本摘要；需供应商支持视觉模型

### 建议验收入口

- [下一步计划.md](下一步计划.md) P0
- [真机安装验收清单.md](真机安装验收清单.md) §8 可靠性六项烟测
- [assist-mcp.md](assist-mcp.md) 联调验收清单

---

## `v0.1.0`

### 定位

`v0.1.0` 是 Clawdroid 的首个正式开源基线版本，重点不是功能暴增，而是把仓库从“内部开发与发布前收尾状态”整理成“可公开托管、可理解、可构建、可贡献”的正式开源项目。

### 包含内容

- `ClawApp` Android 客户端主工程
- `ClawRuntime` Go Root Runtime 与 Magisk 模块工程
- 架构、协议、威胁模型、验收与发布文档
- 基础构建脚本与最小 CI 验证基线

### 本版本完成的开源基线工作

- 补齐主仓 `MIT` 许可证
- 明确主仓与 `GitSource/` 第三方参考源码区的边界
- 增加开发者入口文档、贡献说明、行为准则与安全说明
- 补齐兼容矩阵模板与公开版本说明
- 将共享密钥流程改为模板化 + 本地生成模式，避免在仓库中保留真实密钥
- 为后续 PR 建立最小构建验证与测试基线
- 将发布版 `auth.allowed_signatures` 流程推进为脚本可配置、可强制校验的打包选项

### 当前已具备的能力

- `ClawRuntime` IPC 联通、能力探测、截图、文件读取、输入注入、事件订阅骨架
- `ClawApp` 概览页、运行时诊断入口、权限修复入口与聊天控制台入口
- Magisk 模块打包与本地诊断快照输出

### 已知限制

- `v0.1.0` 优先面向开发者，不是普通用户的一键安装版本
- 兼容矩阵仍需通过后续真机回归逐步填充
- 发布版虽已支持脚本强制校验 `auth.allowed_signatures`，但真实签名摘要仍需来自实际发布签名环境
- 自动发版与预编译产物发布不属于本版本必做范围

### 当前验证摘要

- `ClawRuntime/runtime` 的 `go test ./...` 已通过
- `ClawRuntime` 的 `build-runtime.ps1`、`sync-shared-secret.ps1`、`build-magisk.ps1` 已通过
- `ClawApp` 的 `:app:compileDebugKotlin` 与 `testDebugUnitTest` 已在本地 `JDK 17 + Gradle 8.13` 环境通过

### 后续方向

路线图仍按能力成熟度划分；**实现上部分条目已提前开工**，以 [下一步计划.md](下一步计划.md) 的 P0→P3 为执行顺序：

- `v0.2`：稳定性与兼容矩阵完善（第二台设备、CI unit test、冒烟收口）
- `v0.3`：Runtime 能力深化（文件桥 / shell / 事件背压与限流）
- `v0.4`：任务化执行与 Agent 闭环（聊天任务台 ↔ `task_*`）
- `v0.5`：目标应用适配与生态治理（只读页面身份优先，深度自动化默认关闭）
