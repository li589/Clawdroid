# Clawdroid Release Notes

## Unreleased（`main` post-`v0.1.0`）

> 相对已推送的 `v0.1.0` 基线，本地 `main` 已包含下列能力。正式打 tag 前应以 [下一步计划.md](下一步计划.md) P0/P1 验收为准。

### 新增与增强

- **AI Agent**：`AiAgentOrchestrator` 计划 → 工具环 → 总结；聊天侧 Model API 多供应商（OpenAI 兼容 / Anthropic / Gemini / DeepSeek / Kimi / Qwen 等）
- **工具地基**：`ClawToolCatalog` / `ClawToolDispatcher` / `ToolServiceRegistry`；权限层级 `None` → `Root`；live capabilities 与事件解耦
- **域工具**：`file_*`、`app_*`、`download_*`、`notification_list`、`web_preview` / `web_search`、`sandbox_shell`、`camera_capture` / `camera_record`、`sensor_read`、`gpu_npu_probe`、`ftp_transfer`
- **Assist MCP**：手机 MCP Server（`clawdroid-assist`）+ 电脑协助端点客户端；见 [assist-mcp.md](assist-mcp.md)
- **Shizuku**：状态 / 授权请求 / 白名单短命令执行
- **Xposed 适配器**：Settings / Browser / Launcher / 自进程 marker；微信 `wechat_detail` 默认关闭；focus schema v2 + 浅层 view dump + ContentProvider 推送；见 [xposed-adapters.md](xposed-adapters.md)
- **Runtime**：审查修复；`write_file_limited` / `stat_file_limited`；`report_xposed_*` 与 `task_*` 动作目录对齐
- **工程**：`ClawdroidShell` 抽取；Overview 事件解耦；故障隔离与若干安全缺陷修复

### 已知限制（Unreleased）

- Assist MCP / 域工具联调清单尚未系统性勾选
- 兼容矩阵仍仅一台完整真机证据
- Agent 仍为短工具环，未完成与 Runtime `task_*` 的任务台闭环
- CI App 任务当前以 `compileDebugKotlin` 为主，unit test 未强制进流水线

### 建议验收入口

- [下一步计划.md](下一步计划.md) P0
- [assist-mcp.md](assist-mcp.md) 联调验收清单
- [真机安装验收清单.md](真机安装验收清单.md)

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
