# Clawdroid Release Notes

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

- `v0.2`：稳定性与兼容矩阵完善
- `v0.3`：Runtime 能力深化
- `v0.4`：任务化执行与 Agent 闭环
- `v0.5`：目标应用适配与生态治理
