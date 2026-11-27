# Clawdroid 兼容矩阵

## 说明

本文件用于记录 Clawdroid 在真实设备和真实组合环境下的验证结果。

当前 `v0.1.0` 的目标不是一次性填满所有 ROM 和 Root 方案，而是先建立一份统一、可持续扩展的公开矩阵模板，供后续真机回归与问题定位使用。

## 建议记录字段

- Android 版本
- ROM / 厂商版本
- Root 方案与版本
- 模块管理器版本
- LSPosed 状态
- ClawRuntime 安装结果
- Runtime Probe
- Capabilities
- 截图
- 文件读取预览
- 输入注入
- 事件订阅
- 备注 / 已知限制

## 矩阵

| 状态 | Android | ROM / 厂商 | Root 方案 | 模块管理器 | LSPosed | ClawRuntime 安装 | Runtime Probe | Capabilities | 截图 | 文件读取 | 输入注入 | 事件订阅 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 已验证 | Android 13 / SDK 33 | Xiaomi Pad 6 (`23043RP34C`, `pipa`) / MIUI `V14.0.10.0.TMZCNXM` | Magisk `27001`（`su` 上下文 `u:r:magisk:s0`） | Magisk App 已安装；代码侧已审计确认 Runtime 鉴权与模块结构不依赖特定 Root 管理器实现，因此对 Kitsune Mask 手动授权模式保持协议兼容 | Zygisk LSPosed 已安装，标准 Manager 包未稳定检测到；通过当前设备可用管理入口完成模块启用、自作用域授权并重启后，已验证 `lsposed_runtime_loaded=true`，进程 `com.clawdroid.app.debug` | 已安装，`verify.sh` 通过，运行时状态 `running`；最新模块已额外修复 `webroot/verify.json` 文本字段为空问题 | 成功，状态轨迹 `Disconnected -> SocketConnected -> PeerVerified -> ChallengeIssued -> Authenticated -> CapabilitySynced -> Ready` | 成功，初始 `accessibility=false`，启用无障碍后复验为 `root=true`、`accessibility=true`、`lsposed=true`；完成当前设备的 LSPosed 正确授权并重启后，进一步复验为 `lsposed_runtime_loaded=true`、`lsposed_runtime_process="com.clawdroid.app.debug"` | 成功，`capture_screen` 返回 `1440x2304` PNG，`14803` bytes | 成功，`read_file_limited` 读取最近截图前 `4096` bytes / 总大小 `14803` bytes | 成功，`inject_tap(540,1200)` 与 `inject_swipe(540,1800 -> 540,400,350ms)` 均返回 `accepted=true`；`exec_shell_limited("wm size")` 返回 `Physical size: 1800x2880` | 成功，2.5 秒连续订阅收到 `4` 帧；10 秒稳定性订阅收到 `7` 帧；在 `lsposed_runtime_loaded=true` 后再次订阅收到 `4` 帧，`capability_changed` 明确返回 `lsposed_runtime_loaded=true` | `7710a01f`；设备上同时安装了 KernelSU App，但当前 `su` 命令表现为 Magisk 环境。代码审计结论：ClawRuntime 协议与模块结构兼容 Kitsune/手动授权模式，真正的失败边界主要在“App 进程内 Root 未放行”“模块配置未与当前 App 的密钥/签名同步”“Runtime 已运行但会话尚未 Ready”。当前只对 MIUI `V14` 真机完成闭环验证；`MIUI 12` 尚未拿到同等真机证据，不能直接视为已验证 |
| 已验证 | Android 13 / SDK 33 | Redmi 22041216C (`xaga`) / MIUI `V130 (V13.2.3.0.TLOCNXM)` | KernelSU / SukiSU Ultra，App Root 手动授权 | SukiSU Ultra (`com.sukisu.ultra`) | Zygisk LSPosed + 用户 LSPosed Fix (`com.lsposed.fix`)；Clawdroid 已启用，作用域为 `com.clawdroid.app.debug` 自身 + `system`，重启后 `lsposed_runtime_loaded=true` | 已安装，`verify.sh` 23 项 PASS，运行时 `running` | 成功，`finalState=Ready`，`degradedReason=""` | `root=true`、`accessibility=false`、`lsposed=true`、`lsposed_runtime_loaded=true`；任务/截图/输入/Shell/文件桥 capabilities 均启用 | 成功，1080×2460 PNG | 成功，读取截图前 4096 bytes | 本轮未执行输入注入真机回归 | 成功，continuous 模式收到 daemon/capability/window 事件，关闭原因 `client_stop` | `EAZD4DFIHU5XUGOJ`；SukiSU 通常不弹自动授权框，需手动放行；LSPosed Fix 是用户自有管理工具；无障碍和修改系统设置仍未授权，不影响 Runtime 主链路 |
| 待验证 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | `v0.1.0` 公开模板，待补真机回归数据 |

## 记录规范

- 每新增一轮真机回归，优先补充一行真实设备结果
- 对失败项使用简短、可检索的描述，不写模糊结论
- 若问题只在特定组合下出现，应在备注中注明触发条件
- 发布前确认 `auth.allowed_signatures` 是否已按发布版要求配置

## 回归批次模板

每次补矩阵时，建议同时补一段可检索的批次摘要，避免只留下表格结论而缺少上下文。

```md
### 回归批次：YYYY-MM-DD / device-name

- 设备：厂商 + 机型
- Android / ROM：Android X / ROM 版本
- Root 方案：Magisk X.Y.Z / KernelSU X.Y.Z / 其他
- 模块管理器：Magisk App X.Y.Z / APatch / 其他
- LSPosed：已安装 / 未安装 / 版本号
- Runtime 安装：成功 / 失败，若失败附错误关键词
- `get_capabilities` 摘要：root / accessibility / lsposed / capabilities
- 回归动作：截图、文件读取、输入注入、事件订阅
- 已知限制：仅记录本轮真实触发的问题
```

## 最低记录要求

- 成功行至少填写 `Runtime Probe` 与 `Capabilities` 的实际返回摘要
- 失败行至少写明失败阶段，例如 `模块刷入失败`、`socket 握手失败`、`输入注入失败`
- 若 `lsposed_runtime_loaded` 为 `true`，备注中补充 `process` 或触发场景
- 若仅完成本地构建验证而未上真机，不写入矩阵表格，统一记到“当前构建验证摘要”

## 当前构建验证摘要

- `ClawRuntime/runtime`：`go test ./...` 已通过
- `ClawRuntime`：`build-runtime.ps1`、`sync-shared-secret.ps1`、`build-magisk.ps1` 已通过
- `ClawApp`：`:app:compileDebugKotlin` 与 `testDebugUnitTest` 已在本地 `JDK 17 + Gradle 8.13` 环境通过
- `v0.2`：已补齐 `session`、`events`、`capability_probe` 的纯逻辑回归测试，用于稳定性收口
- `ClawRuntime/runtime`：`go test -race ./...`、`go vet ./...`、`GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build ./cmd/runtime` 已通过
- `ClawRuntime/scripts`：51 个构建/打包回归测试通过；Runtime catalog 检查通过

## 首个真机回归记录

### 回归批次：2026-07-07 / 7710a01f

- 设备：Xiaomi Pad 6（`23043RP34C` / `pipa`）
- Android / ROM：Android `13` / MIUI `V14.0.10.0.TMZCNXM`
- Root 方案：Magisk `27001`，`su -c id` 返回 `uid=0(root)`，上下文 `u:r:magisk:s0`
- 模块管理器：Magisk App 已安装；设备上同时存在 KernelSU App 包，但本轮未作为生效 Root 方案使用
- LSPosed：`/data/adb/lspd` 与 `/data/adb/modules/zygisk_lsposed` 存在，判定已安装；未检测到 LSPosed Manager 包
- ClawRuntime 安装：成功，`/data/adb/modules/clawruntime` 存在，`verify.sh` 最终通过，`webroot/status.json` 显示 `runtime_state=running`
- App 构建来源：本地 `ClawApp` 已通过 `:app:testDebugUnitTest`，真机已安装并启动 `com.clawdroid.app.debug`
- Runtime 包来源：本地 `ClawRuntime/dist/ClawRuntime-magisk.zip` 已安装并完成重启后回归
- `auth.allowed_signatures`：已切换为当前 debug APK 的真实签名摘要，设备端 `runtime.yaml` 已同步
- 备注：本轮先后暴露并修复了 `DashboardMetricsCollector` 直接读取 `/proc/stat` 导致的真机启动崩溃、客户端签名摘要缺少 `sha256:` 前缀导致的 `1002 signature mismatch`、`verify.sh` 将 installer-only `customize.sh` 误判为安装后缺失的问题，以及 `webroot/verify.json` 文本字段为空的问题

### 本轮真实结果

- `get_capabilities` 摘要：初始为 `root=true`、`accessibility=false`、`lsposed=true`、`lsposed_runtime_loaded=false`；通过 Root 写入 `enabled_accessibility_services` 与 `accessibility_enabled=1` 后，复验结果为 `root=true`、`accessibility=true`、`lsposed=true`、`lsposed_runtime_loaded=false`；继续补齐 LSPosed 模块启用与 `com.clawdroid.app.debug -> com.clawdroid.app.debug` 自作用域后，最终复验结果为 `root=true`、`accessibility=true`、`lsposed=true`、`lsposed_runtime_loaded=true`、`lsposed_runtime_process="com.clawdroid.app.debug"`、`degraded_reason=""`
- `capabilities` 列表：`system.ping`、`system.inspect`、`event.subscribe`、`screen.capture`、`input.inject`、`shell.exec.limited`、`file.read.limited`
- Runtime Probe：成功，会话最终状态 `Ready`，握手轨迹为 `Disconnected -> SocketConnected -> PeerVerified -> ChallengeIssued -> Authenticated -> CapabilitySynced -> Ready`
- 截图：成功，返回文件 `/data/local/tmp/clawdroid/captures/capture-0-1783362625194.png`，格式 `png`，尺寸 `1440x2304`，文件大小 `14803` bytes
- 文件读取：成功，`read_file_limited` 读取最近截图前 `4096` bytes，报告总大小 `14803` bytes
- 输入注入：成功，`inject_tap(540,1200)` 与 `inject_swipe(540,1800 -> 540,400,350ms)` 均返回 `accepted=true`
- 受限 Shell：成功，`exec_shell_limited("wm size")` 返回 `Physical size: 1800x2880`，退出码 `0`，`timedOut=false`；允许命令白名单已由运行时返回确认
- 事件订阅：成功，连续模式订阅 `daemon_status_changed`、`capability_changed`、`window_changed`；2.5 秒回归收到 `4` 帧，10 秒稳定性回归收到 `7` 帧，关闭原因均为 `client_stop`
- LSPosed 注入：成功，App 启动后生成 `/data/user/0/com.clawdroid.app.debug/files/xposed_runtime_marker.json`，记录 `xposed_injected=true`、`process_name="com.clawdroid.app.debug"`、`loaded_at_epoch_ms=1783365311247`
- 事件流摘要：`daemon_status_changed` 持续显示 `runtime_pid=3141`、`version=0.2.0`；`capability_changed` 先前已切换为 `accessibility=true`，在 LSPosed 注入复验后进一步显示 `lsposed_runtime_loaded=true`、`lsposed_runtime_process="com.clawdroid.app.debug"`；`window_changed` 焦点窗口仍为 `com.miui.home/.launcher.Launcher`
- 当前设备成功路径：在这台设备上，仅检测到 LSPosed 框架目录并不足以视为“已可用”；需要先通过当前设备可用的 LSPosed 管理入口显式启用 `ClawApp` 模块，再为 `com.clawdroid.app.debug` 添加自作用域，随后重启设备并冷启动 App，才会生成 marker 并把 `lsposed_runtime_loaded` 拉到 `true`
- 日志与错误关键词：已消除早期 `MODULE_DIR_MISSING` / `VERIFY_FAILED`；当前事件流中可见一次历史 `broken pipe` 记录，但不影响本轮回归通过

### 补充回归：2026-07-07 / 重装后桥接复测

- 安装态核对：设备端 `base.apk` 与本地 `ClawApp/app/build/outputs/apk/debug/app-debug.apk` 的 SHA-256 一致，均为 `091fde417cdb6f65e2fa63394afb49a3cc71cf4bb5e5567f49713a7fff8e4d32`
- 桥接入口：显式启动 `DebugRuntimeBridgeActivity` 成功；显式广播 `DebugRuntimeBridgeReceiver` 也可正常回写 `files/debug-runtime-result.json`
- Runtime 会话：`signature`、`ping`、`probe`、`capabilities` 全部成功；`authMode=hmac-sha256-local-v1`，`finalState=Ready`，`degradedReason=""`
- 当前能力快照：`root=true`、`accessibility=true`、`lsposed=true`、`lsposed_runtime_loaded=false`、`screenshotEnabled=true`、`fileBridgeEnabled=true`
- 动作闭环：`capture_and_read` 成功返回 `1440x2304` PNG 与 `272674` bytes 总大小；`swipe` 返回 `accepted=true`；`exec_shell_limited("wm size")` 返回 `Physical size: 1800x2880`
- 事件订阅：2.5 秒复测收到 `4` 帧，包含 `daemon_status_changed`、`capability_changed`、`window_changed`；关闭原因为 `client_stop`
- 聊天链路：通过桥接 Receiver 触发 `chat_prompt("获取能力")` 成功返回能力摘要，证明“聊天输入 -> 工具执行 -> 助手回包”链路在当前设备已恢复
- 调试注意：直接用 `adb shell am start/broadcast` 传递带空格的 extras 时，`wm size`、`/shell wm size` 这类字符串会被宿主 shell 拆参；若只收到 `wm` 或 `/shell`，应先按“命令行传参错误”排查，而不要误判为 Runtime 或聊天业务失败

### 回归批次：2026-11-27 / EAZD4DFIHU5XUGOJ

- 设备：Redmi 22041216C（`xaga`），Android `13` / MIUI `V130 (V13.2.3.0.TLOCNXM)`，1080×2460 / density 440。
- Root：KernelSU / SukiSU Ultra（`com.sukisu.ultra`）；该方案通常不弹自动授权框，需在管理器手动允许 `com.clawdroid.app.debug`。
- LSPosed：`zygisk_lsposed` 已安装；用户自有 LSPosed Fix（`com.lsposed.fix`）中 Clawdroid 已启用，作用域为 `com.clawdroid.app.debug` 自身 + `system`。重启后 marker 为 `xposed_injected=true`，`lsposed_runtime_loaded=true`。
- App：`com.clawdroid.app.debug` / `0.1.0-debug`，APK SHA-256 `c0e6df750e2f45096af2f4dd42ba6cd88cfb5e74d9fca91103a7312943429408`。
- Runtime：模块 `clawruntime` / `0.2.0`，验收包 SHA-256 `4df7e23d1dd7aa17d734108d6ac64f8af50195a22ecacfb8e1d462c2ef183995`；`verify.sh` 23 项 PASS，`get_runtime_status.module.runtime_pid` 与真实进程一致。
- 真实结果：Runtime Probe `Ready`；Capabilities 正常；截图 1080×2460 PNG 与文件桥读取成功；`exec_shell_limited("wm size")` 返回 `Physical size: 1080x2460`；continuous 事件订阅收到 daemon/capability/window 事件；聊天页任务列表、提交、查询通过，取消已完成任务正确返回 `[7002]`。
- 本轮修复：首次 `ping` 在能力同步前被 2004 拒绝，根因是会话 capabilities 被初始化为非 nil 空切片；模块状态文件中的字符串 `runtime_pid` 未被解析。两项均补回归测试并真机复验。
- 已知限制：无障碍服务和修改系统设置仍未授权；活跃任务取消与失败重试由 Go 单测/竞态测试覆盖，真机验证了取消接口及终态拒绝逻辑。

### 下一步扩展清单

- 将下一台设备加入矩阵，优先覆盖非 MIUI 或非 Magisk 组合
- 同设备补一轮 Unreleased 能力冒烟：Assist MCP、域工具、Xposed focus/view（见 [下一步计划.md](下一步计划.md) P0）
  - **2026-07-18 P0 部分完成**：Assist MCP + 多数域工具已在 `7710a01f` 通过；Xposed 因 APK 重装后未重新授权而未验；细节见 [assist-mcp.md](assist-mcp.md)
- 将设备端 Magisk Runtime 升级到与当前源码一致（补齐 `am force-stop` 参数化白名单与扩展 capability）
- CI 强制 `:app:testDebugUnitTest` 后，在本摘要更新构建验证一行
