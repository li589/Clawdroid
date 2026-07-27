# ClawApp

Android Brain Host：Compose UI、Agent 编排、工具调度、无障碍 / LSPosed / Termux / MCP，以及 ClawRuntime IPC 客户端。

## 打开工程

用 Android Studio 打开 **`ClawApp/`**（不是仓库根）。模块：`:app`、`:xposed-stubs`。

## 密钥

构建需要与 Runtime **同一**共享密钥：

- 仓库根 `local.properties`：`clawdroid.runtime.sharedSecret=...`
- 或环境变量 `CLAWDROID_RUNTIME_SHARED_SECRET`
- 写入 `BuildConfig.CLAW_RUNTIME_SHARED_SECRET`

发布签名白名单（可选）：`clawdroid.runtime.allowedSignatures`。见 [keystore/README.md](keystore/README.md)。

## 常用命令

```powershell
cd ClawApp
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

`compileSdk` / `targetSdk` = 35，`minSdk` = 26。

## 包结构（摘要）

| 包 | 职责 |
|----|------|
| `ui` | 壳、页面、ViewModel |
| `data` | SharedPreferences / JSON 持久化 |
| `ai` / `chat` / `orchestrator` / `skills` | 编排与循环 |
| `tools` | 工具目录、Dispatcher、handlers、域服务 |
| `runtime` / `ipc` | Runtime 客户端 |
| `termux` / `mcp` / `xposed` / `focus` | 边车能力 |
| `service` | Accessibility、NotificationListener、`:sandbox` Shell |

资产提示包：`app/src/main/assets/claw/`。

## 与 Runtime 配对

1. 同一密钥生成 Magisk ZIP（见 [../ClawRuntime/README.md](../ClawRuntime/README.md)）
2. `adb install` APK + Magisk 安装 ZIP + 重启
3. 设置中可覆盖 Runtime 密钥（`RuntimeSecretStore`）用于重配对
4. 改 Runtime Go 后必须重装 Magisk ZIP；概览/诊断的对齐 banner 可提示模块过旧

## 相关能力入口

| 能力 | 入口 |
|------|------|
| 能力对齐 / IPC 诊断 | 概览 Runtime 磁贴、设置 → 运行诊断 |
| Termux 坏容器清理 | 设置 → Termux 与 Shell →「清理坏容器」 |
| 聊天附图 | 聊天 Composer 附图按钮（当前轮多模态） |
| `task_wait` | Agent/工具允许列表中的 `task_wait` |

架构总览：[../Docs/architecture.md](../Docs/architecture.md)。工作约定：[../AGENTS.md](../AGENTS.md)。验收入口：[../Docs/真机安装验收清单.md](../Docs/真机安装验收清单.md) §8。
