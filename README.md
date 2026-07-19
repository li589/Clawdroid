# Clawdroid

Clawdroid 是一个面向 Android 8 至 Android 16 的智能执行底座项目。项目当前采用 `Clawdroid App + ClawRuntime` 双主体结构：

- `ClawApp`
  - Android 客户端
  - 负责 UI、任务编排、无障碍能力、LSPosed/Xposed 入口和 `ClawRuntime` IPC 客户端
- `ClawRuntime`
  - Root 执行侧
  - 负责运行时守护进程、Magisk 模块打包、启动脚本、配置和高权限执行宿主

当前仓库定位为 `v0.1.0` 开源基线，目标受众优先是开发者与贡献者，而不是普通终端用户的一键安装场景。

## 项目目标

Clawdroid 的目标不是单纯提供提权能力，而是建立一套可治理、可扩展、可降级的 Android Agent 底座，用于承接：

- 多模态模型驱动的移动端任务编排
- 基于无障碍和 LSPosed 的环境感知
- 基于 Root/Magisk 的高权限执行能力
- 结构化协议、审计和安全边界控制

## 仓库组成

- `ClawApp/`
  - 独立 Gradle Android 工程
  - 包含 `app/` 与 `xposed-stubs/`
- `ClawRuntime/`
  - Go 守护进程工程
  - Magisk 模块工程
  - 打包与辅助脚本目录
- `Docs/`
  - 架构设计、协议定义、威胁模型、验收与发布文档
- `GitSource/`
  - 外部参考源码与调研资料
  - 不属于主工程实现目录，不作为主仓构建前提

详细目录说明见 [目录说明.md](Docs/目录说明.md)。

## 许可证与边界

- 主仓代码与文档默认使用 [MIT License](LICENSE)
- `GitSource/` 是第三方参考源码区，保留其各自上游许可证，不受主仓 MIT 重新授权
- 第三方边界说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## 当前状态

`v0.1.0` 开源基线已收口；其后在本地 `main` 上继续推进了 Agent / 工具 / MCP / Xposed 能力面（详见 [Docs/release-notes.md](Docs/release-notes.md) 的 Unreleased 段与 [Docs/下一步计划.md](Docs/下一步计划.md)）。

**底座（已可用）**

- `Brain / ClawBrain -> ClawRuntime` 命名迁移与双主体工程收口
- `ClawRuntime` Go 守护进程、Magisk 模块、`verify.sh` 与 webroot 诊断快照
- Runtime IPC：握手 → Ready、截图、文件桥、输入注入、受限 Shell、事件订阅、`task_*` / `report_xposed_*`
- `ClawApp` 概览 / 聊天 / 设置 Compose 壳，Root 修复与 Runtime 诊断入口
- 首台真机闭环已写入兼容矩阵（小米平板 6 / Android 13 / Magisk / LSPosed）

**基线后已落地、待验收收口**

- AI Agent 编排与多供应商 Model API
- 本地工具地基（文件 / App / 下载 / 通知 / Web / 沙箱 Shell / 相机 / 传感器 / FTP 等）
- Assist MCP 双向调试桥（ADB forward / reverse）
- LSPosed 适配器与 focus/view 推送（Settings / Browser / Launcher；微信默认关闭）
- Shizuku 中层提权路径

**下一阶段优先级（先验收、再加深）**

1. Assist MCP + 工具冒烟联调，勾选 [Docs/assist-mcp.md](Docs/assist-mcp.md) 验收清单
2. 兼容矩阵扩到第二台设备，并加强 CI（App unit test）
3. 聊天 → Runtime `task_*` 任务化 Agent 闭环
4. Runtime / Xposed 硬化与适配治理（不做深度业务自动化默开）

完整排序与完成标准见 [Docs/下一步计划.md](Docs/下一步计划.md)。

## 快速开始

### 1. 环境要求

- Windows + PowerShell 7 或等效 PowerShell 环境
- Android Studio / Android SDK
- Go
- 一台已 Root 的 Android 设备用于真机验收

### 2. 本地初始化

1. 准备 Android SDK 路径
   - 复制 [ClawApp/local.properties.example](ClawApp/local.properties.example) 到 `ClawApp/local.properties`
   - 按本机环境设置 `sdk.dir`
2. 准备共享密钥
   - 推荐在仓库根 `local.properties` 中设置 `clawdroid.runtime.sharedSecret`
   - 也可以使用环境变量 `CLAWDROID_RUNTIME_SHARED_SECRET`
   - 若准备对外测试包或发布包，额外设置 `clawdroid.runtime.allowedSignatures`
3. 构建前同步 Runtime 配置
   - 在 `ClawRuntime/` 目录执行 `.\scripts\sync-shared-secret.ps1`

## 构建流程

### `ClawApp`

- 作为独立 Gradle 工程使用
- Android Studio 建议直接打开 `ClawApp/`
- 构建前需提供 Runtime 共享密钥：
  - 环境变量 `CLAWDROID_RUNTIME_SHARED_SECRET`
  - 或仓库根 `local.properties` 中的 `clawdroid.runtime.sharedSecret`
- `app/build.gradle.kts` 会将该密钥写入 `BuildConfig.CLAW_RUNTIME_SHARED_SECRET`

常用命令：

```powershell
cd ClawApp
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

### `ClawRuntime`

- 不依赖 Gradle
- 使用 `Go + PowerShell` 脚本构建 `ClawRuntime` 与 Magisk ZIP
- 推荐顺序为 `scripts/build-runtime.ps1` -> `scripts/build-magisk.ps1`
- `scripts/build-magisk.ps1` 会自动执行 `scripts/sync-shared-secret.ps1`
- Magisk 模块当前会输出 `webroot/status.json`、`webroot/verify.json` 与日志 tail 供本地排查
- 发布包场景建议使用 `.\scripts\build-magisk.ps1 -RequireAllowedSignatures`

常用命令：

```powershell
cd ClawRuntime
.\scripts\build-runtime.ps1
.\scripts\build-magisk.ps1
```

## 验证入口

- Runtime 与模块发布前检查见 [发布前清单.md](Docs/发布前清单.md)
- 真机安装与回归步骤见 [真机安装验收清单.md](Docs/真机安装验收清单.md)
- Runtime 脚本说明见 [README.md](ClawRuntime/scripts/README.md)

LSPosed 相关说明：

- `verify.sh` 只覆盖 `ClawRuntime` 模块完整性、自检结果与运行态快照，不等价于 LSPosed 注入成功
- 若需验证 `lsposed_runtime_loaded=true`，请按 [真机安装验收清单.md](Docs/真机安装验收清单.md) 中的“LSPosed 专项验收”执行模块启用、作用域配置与 Marker 校验
- 调试包默认按当前安装包名做作用域验证，例如 `com.clawdroid.app.debug -> com.clawdroid.app.debug`

## 风险提示

- 本项目涉及 Root、LSPosed、输入注入、截图、文件桥接等高权限能力
- 当前主线优先保证“开发者可理解、可构建、可验证”，不承诺对所有 ROM / Root 方案提供完全等价行为
- 发布版必须配置非空 `auth.allowed_signatures`，开发联调阶段允许留空
- 发布版签名白名单可通过仓库根 `local.properties` 的 `clawdroid.runtime.allowedSignatures` 或环境变量 `CLAWDROID_RUNTIME_ALLOWED_SIGNATURES` 注入
- 安全边界与威胁假设见 [threat-model.md](Docs/threat-model.md)

## 文档导航

- [下一步计划.md](Docs/下一步计划.md) — 当前优先级与完成标准
- [基础方案设计.md](Docs/基础方案设计.md)
- [protocol.md](Docs/protocol.md)
- [threat-model.md](Docs/threat-model.md)
- [assist-mcp.md](Docs/assist-mcp.md)
- [xposed-adapters.md](Docs/xposed-adapters.md)
- [目录说明.md](Docs/目录说明.md)
- [真机安装验收清单.md](Docs/真机安装验收清单.md)
- [发布前清单.md](Docs/发布前清单.md)
- [compatibility-matrix.md](Docs/compatibility-matrix.md)
- [release-notes.md](Docs/release-notes.md)

## 参与贡献

- 贡献规范见 [CONTRIBUTING.md](CONTRIBUTING.md)
- 行为准则见 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
- 安全问题提交方式见 [SECURITY.md](SECURITY.md)
