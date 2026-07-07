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

详细目录说明见 [目录说明.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/%E7%9B%AE%E5%BD%95%E8%AF%B4%E6%98%8E.md)。

## 许可证与边界

- 主仓代码与文档默认使用 [MIT License](file:///d:/temp_desktop/Proj/Clawdroid/LICENSE)
- `GitSource/` 是第三方参考源码区，保留其各自上游许可证，不受主仓 MIT 重新授权
- 第三方边界说明见 [THIRD_PARTY_NOTICES.md](file:///d:/temp_desktop/Proj/Clawdroid/THIRD_PARTY_NOTICES.md)

## 当前状态

当前仓库已完成从内部收尾到正式开源基线的第一轮收口，已落地的基础包括：

- 已完成 `Brain / ClawBrain -> ClawRuntime` 命名迁移
- 已完成 `ClawRuntime` Go 运行时、Magisk 模块与构建脚本收口
- 已完成 `ClawApp` 的 Runtime IPC、截图预览、输入注入、事件订阅与权限修复入口
- 已完成概览页实时仪表盘、聊天工具入口、Root 一键修复与模块 WebUI 排查面板
- 已完成 `verify.sh`、真机安装验收清单和发布前清单整理
- 已移除仓内旧模块 ID、旧脚本名、旧配置名及协议指标兼容残留

下一阶段优先级集中在：

1. 公开仓库的开发者开箱体验
2. 仓库去敏与配置模板化
3. 最小 CI 与测试基线
4. 真机兼容矩阵与稳定性回归

## 快速开始

### 1. 环境要求

- Windows + PowerShell 7 或等效 PowerShell 环境
- Android Studio / Android SDK
- Go
- 一台已 Root 的 Android 设备用于真机验收

### 2. 本地初始化

1. 准备 Android SDK 路径
   - 复制 [ClawApp/local.properties.example](file:///d:/temp_desktop/Proj/Clawdroid/ClawApp/local.properties.example) 到 `ClawApp/local.properties`
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

- Runtime 与模块发布前检查见 [发布前清单.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/%E5%8F%91%E5%B8%83%E5%89%8D%E6%B8%85%E5%8D%95.md)
- 真机安装与回归步骤见 [真机安装验收清单.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/%E7%9C%9F%E6%9C%BA%E5%AE%89%E8%A3%85%E9%AA%8C%E6%94%B6%E6%B8%85%E5%8D%95.md)
- Runtime 脚本说明见 [README.md](file:///d:/temp_desktop/Proj/Clawdroid/ClawRuntime/scripts/README.md)

LSPosed 相关说明：

- `verify.sh` 只覆盖 `ClawRuntime` 模块完整性、自检结果与运行态快照，不等价于 LSPosed 注入成功
- 若需验证 `lsposed_runtime_loaded=true`，请按 [真机安装验收清单.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/%E7%9C%9F%E6%9C%BA%E5%AE%89%E8%A3%85%E9%AA%8C%E6%94%B6%E6%B8%85%E5%8D%95.md) 中的“LSPosed 专项验收”执行模块启用、作用域配置与 Marker 校验
- 调试包默认按当前安装包名做作用域验证，例如 `com.clawdroid.app.debug -> com.clawdroid.app.debug`

## 风险提示

- 本项目涉及 Root、LSPosed、输入注入、截图、文件桥接等高权限能力
- 当前主线优先保证“开发者可理解、可构建、可验证”，不承诺对所有 ROM / Root 方案提供完全等价行为
- 发布版必须配置非空 `auth.allowed_signatures`，开发联调阶段允许留空
- 发布版签名白名单可通过仓库根 `local.properties` 的 `clawdroid.runtime.allowedSignatures` 或环境变量 `CLAWDROID_RUNTIME_ALLOWED_SIGNATURES` 注入
- 安全边界与威胁假设见 [threat-model.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/threat-model.md)

## 文档导航

- [基础方案设计.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/%E5%9F%BA%E7%A1%80%E6%96%B9%E6%A1%88%E8%AE%BE%E8%AE%A1.md)
- [protocol.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/protocol.md)
- [threat-model.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/threat-model.md)
- [目录说明.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/%E7%9B%AE%E5%BD%95%E8%AF%B4%E6%98%8E.md)
- [真机安装验收清单.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/%E7%9C%9F%E6%9C%BA%E5%AE%89%E8%A3%85%E9%AA%8C%E6%94%B6%E6%B8%85%E5%8D%95.md)
- [发布前清单.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/%E5%8F%91%E5%B8%83%E5%89%8D%E6%B8%85%E5%8D%95.md)
- [compatibility-matrix.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/compatibility-matrix.md)
- [release-notes.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/release-notes.md)

## 参与贡献

- 贡献规范见 [CONTRIBUTING.md](file:///d:/temp_desktop/Proj/Clawdroid/CONTRIBUTING.md)
- 行为准则见 [CODE_OF_CONDUCT.md](file:///d:/temp_desktop/Proj/Clawdroid/CODE_OF_CONDUCT.md)
- 安全问题提交方式见 [SECURITY.md](file:///d:/temp_desktop/Proj/Clawdroid/SECURITY.md)
