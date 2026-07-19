# Debug Session: device-ui-review

- Status: OPEN
- Goal: 通过 ADB 在真机上逐页回看 Clawdroid UI，定位并修正对齐、溢出、字号、滚动与可见性问题
- Scope: `ClawApp/app` 的 Compose 页面与通用 UI 组件
- Constraints: 在拿到真机运行时证据前，不改业务逻辑；优先收集截图、窗口信息、分辨率与当前页面可视结果

## Hypotheses

1. 某些页面在真机上仍存在长文本截断或滚动区域不可见问题，静态代码检查没有完全覆盖。
2. 分组标题、按钮高度和卡片节奏虽已统一，但在真实分辨率/字体缩放下仍可能出现拥挤或断行异常。
3. 底部导航、快捷动作和输入区在真机窄宽度下可能存在点击区偏小或视觉对齐偏差。
4. 概览页的长状态卡、日志卡和聊天长消息在真机上可能出现“可滚动但不易感知”的体验问题。
5. 某些问题只会在设备当前系统字体、DPI、导航栏占位或 ROM 渲染差异下出现，需要真机截图和窗口信息确认。

## Evidence Plan

- 通过 ADB 确认设备连接状态、分辨率、DPI、前台窗口
- 构建并安装当前 `debug` 包
- 启动 App 后逐页采集截图
- 对照截图与布局代码记录问题点
- 基于证据做最小 UI 修正并回归验证

## Progress Log

- 2026-07-07: 调试会话初始化，等待 ADB 证据采集。
- 2026-07-07: 已确认设备 `7710a01f` 在线，分辨率 `1800x2880`，密度 `400`。
- 2026-07-07: 已安装并启动 `com.clawdroid.app.debug`，入口 `com.clawdroid.app.MainActivity`。
- 2026-07-07: 首轮截图被第三方自由窗 `com.free.vpn.super.hotspot.open/.ConnectReportActivity` 遮挡；经用户允许后已临时关闭该遮挡层。
- 2026-07-07: 概览页、聊天页、设置页截图一致显示底部悬浮导航压住列表底部内容，属于真实真机问题；已据此开始最小修正。
- 2026-07-07: 黑屏截图阶段的 `window_dump.xml` 仅出现 `com.android.systemui` keyguard 层，确认黑图来自设备锁屏/休眠前台而非 Compose 自身渲染纯黑。
- 2026-07-07: 重新核对 APK 后确认设备上当时并无 `com.clawdroid.app.debug`；已重新安装 `app-debug.apk`，并通过 `monkey -p com.clawdroid.app.debug -c android.intent.category.LAUNCHER 1` 恢复稳定启动。
- 2026-07-07: 第二轮复核确认此前仅增加 `LazyColumn` `contentPadding.bottom` 不能阻止当前视口延伸到悬浮导航后；改为给 `LazyColumn` 容器本身预留底部高度后，概览/聊天/设置三页首屏均不再被底部悬浮导航遮挡。
- 2026-07-07: 已重新构建、安装并启动 `debug` 包，开始第三轮“深滚动内容”回看。
- 2026-07-07: 概览页中下段的连接配置、本地状态、本地环境详情、权限修复区在深滚动后仍保持完整可见，未出现按钮或状态条压到悬浮导航后的情况。
- 2026-07-07: 聊天页深滚动后，消息卡、输入框、语音/图片按钮和发送按钮仍完整落在悬浮导航上方；当前受限于运行时未连接，仅验证到种子消息场景。
- 2026-07-07: 设置页深滚动后，本地模型配置、运行诊断、页面分工和配置分层说明等卡片均可正常显示，未发现深层滚动导致的裁剪、遮挡或尾部留白异常。
- 2026-07-07: 已确认 `debug_seed_long_overview` 会真实播种概览页长内容；首屏 `uiautomator dump` 已稳定看到长错误文本、`订阅中` 状态与 12 行长事件日志，说明无需再依赖脆弱的手势深滚动也可复现概览长内容压测。
- 2026-07-07: 为便于真机复拍，已在 `debug_seed_long_overview` 模式下将 `最近错误`、`事件订阅`、`事件流日志` 提升到概览页顶部；当前 `adb screencap` 仍偶发抓到 MIUI 桌面而非前台 App，但窗口焦点与 XML 证据均确认 Clawdroid 处于前台且长内容已进入可视区域。
- 2026-07-07: 已确认这台 MIUI 设备上 `uiautomator dump` 会直接把前台从 `com.clawdroid.app.debug` 切回 `com.miui.home`；此前“前台 XML 正确但 PNG 是桌面”的主要矛盾并非单纯截图链路错图，而是 `uiautomator dump` 本身扰动前台。
- 2026-07-07: `scripts/adb-capture-foreground.ps1` 已补齐三项修正：`exec-out` 的 PowerShell 兼容、只检查 `mCurrentFocus`/`mFocusedApp` 而非整个 `dumpsys window`、新增 `-UseRootShellCapture` 并在该模式下跳过 `uiautomator dump` 校验。
- 2026-07-07: 已用 `root shell screencap` 成功拿到前后台一致的真机截图；`foreground-capture-root-fixed.focus.txt` 在截图前后都保持 `com.clawdroid.app.debug/com.clawdroid.app.MainActivity`。
- 2026-07-07: 继续复测确认 `MainActivity` 在这台 MIUI 设备上通常只能稳定占据前台约 3 秒，随后会被 `com.miui.home/.launcher.Launcher` 顶回前台；因此截图脚本必须把“拉起 Activity -> 立即判定焦点 -> 立即截图”视为一个原子窗口。
- 2026-07-07: `adb-capture-foreground.ps1` 已进一步增强：新增 `-LaunchActivity` / `-LaunchDelayMs`，每次尝试前都会主动拉起目标 Activity；同时把焦点采样改为 `dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus=|mFocusedApp='`，显著缩短判定耗时并压掉 MIUI `dumpsys` 噪声。
- 2026-07-07: 最新复测表明，`exec-out screencap`、普通 `shell screencap`、`root shell screencap` 三条截图路径都能在“主动拉起 Activity + 跳过 UI dump 校验”条件下命中前台；此前失败的根因不是截图 API，而是 `uiautomator dump` 在当前 MIUI 设备上既会报 `theme_compatibility.xml` 异常，又会拖慢并扰动前台校验。
- 2026-07-07: 当前推荐策略已更新为：在这台设备上优先使用 `-LaunchActivity com.clawdroid.app.debug/com.clawdroid.app.MainActivity`，并在需要保前台一致性时启用 `-SkipUiDumpValidation`；`-UseRootShellCapture` 仍是最稳方案，但已不再是唯一可行路径。
- 2026-07-07: 已对启动自检链路补充 `OverviewController` 日志并真机复核：首启自动 Root 请求真实触发，但当前设备对 App 内 `su` 返回 `Permission denied`，因此 App 侧暂无法继续执行 Magisk 模块探测和自动恢复授权。
- 2026-07-07: 已将 Root 启动策略改为“每次启动都尝试建立 Root 会话”，避免一次拒绝后永久跳过自动 Root；真机二次冷启动已确认 `rootRequested=true` 仍会继续执行。
- 2026-07-07: 通过外部 `adb shell su -c` 进一步确认设备状态：`magiskd` 存活、`/data/adb/modules/clawruntime` 模块目录存在、`clawdroid-runtime` 进程存活；因此当前阻塞点不是设备缺少 Magisk/模块/Runtime，而是 Clawdroid App 进程尚未获得 Magisk Root 放行。
- 2026-07-07: 已重新构建并重装最新 `app-debug.apk`，同时通过 `magisk --install-module /data/local/tmp/ClawRuntime-magisk.zip` 替换设备上的 `clawruntime` 模块，并完成重启。
- 2026-07-07: 重启后 `verify.sh` 已通过 `0 failure(s), 0 warning(s)`；`webroot/status.json` 显示 `runtime_state=running`、`runtime_running=true`、`socket_name=clawdroid_secure_ipc`、`verify_exit_code=0`，说明最新模块已正常生效。
- 2026-07-07: App `DebugRuntimeBridgeActivity` 真机桥接复测完成；`signature` 返回 `com.clawdroid.app.debug` 的签名摘要 `sha256:c644532dbd20d94b5e97f8cece20b315a15be19e6b82bded8e23937f40924364`，与模块侧 `auth.allowed_signatures` 完全一致。
- 2026-07-07: Runtime `probe` 结果已恢复为 `finalState=Ready`，状态轨迹为 `Disconnected -> SocketConnected -> PeerVerified -> ChallengeIssued -> Authenticated -> CapabilitySynced -> Ready`；`daemonVersion=0.2.0`，`root=true`，`degradedReason` 为空。
- 2026-07-07: Runtime `capabilities` 复测通过；当前能力集包含 `screen.capture`、`input.inject`、`shell.exec.limited`、`file.read.limited`，会话状态同样为 `Ready`。当前 `lsposed=true` 但 `lsposed_runtime_loaded=false`，说明框架存在但本轮尚未进入真实注入场景。
- 2026-07-07: 受限 Shell 与截图读回已通过真机复测：`exec_shell_limited` 默认模板 `wm size` 返回 `Physical size: 1800x2880`；`capture_and_read` 成功生成 `/data/local/tmp/clawdroid/captures/capture-0-1783376768987.png` 并完成 4096 字节预读。
- 2026-07-07: 已修复 `magisk/verify.sh` 的 JSON 转义兼容问题，并重打/重装最新模块；重启后 `webroot/verify.json` 的 `module_id`、`status`、`summary` 与 `checks[*]` 文本字段均已恢复正常。
- 2026-07-07: 在用户补充 LSPosed 管理侧授权并重启设备后，`com.clawdroid.app.debug` 冷启动已成功生成 `/data/user/0/com.clawdroid.app.debug/files/xposed_runtime_marker.json`，内容包含 `xposed_injected=true`、`process_name="com.clawdroid.app.debug"`、`package_name="com.clawdroid.app.debug"`。
- 2026-07-07: 同轮真机 `probe` 与 `capabilities` 复测均已返回 `lsposed_runtime_loaded=true`；`lsposed_runtime_process="com.clawdroid.app.debug"`，同时 `accessibility=true`、`finalState=Ready`、`degradedReason=""`，说明当前 Runtime / LSPosed / 权限链路均已在该设备状态下打通。
- 2026-07-07: 已完成一轮代码与兼容性审计增强：Overview 页新增“本地环境诊断 / Runtime 连接诊断”，将 `Root 未授权`、`模块未安装`、`Runtime 未运行`、`会话 Closed/Degraded/Ready` 等情况分层表达，避免继续把多种问题统一表现成“模块连不上”。
- 2026-07-07: `DebugRuntimeBridgeActivity` 已补齐 `packageName / socketName / signatureDigest / errorMessage / lsposedRuntimeLoadedAt` 等审计字段，后续做重装与业务闭环复测时可直接把桥接 JSON 当作证据文件保存。
- 2026-07-07: 当前代码审计结论是：ClawRuntime 的协议和模块结构不依赖特定 Root 管理器实现，因此对 `Kitsune Mask` 手动授权模式保持协议兼容；真正的失败边界主要在 App 进程内 Root 未放行、模块侧配置未与当前 App 的签名/密钥同步，或 Runtime 会话未进入 `Ready`。该结论已被 MIUI `V14` 真机链路与代码审计共同支撑；`MIUI 12` 仍缺少同等级真机证据。
- 2026-07-07: 重装后的设备 `base.apk` 与本地 `app-debug.apk` 已做 SHA-256 对比，结果一致，确认本轮桥接复测使用的是最新构建产物，而不是旧安装残留。
- 2026-07-07: 显式启动 `com.clawdroid.app.DebugRuntimeBridgeActivity` 已恢复可用；`signature / ping / probe / capabilities / capture_and_read / swipe / exec_shell_limited / events` 全链路复测均成功，`probe.finalState=Ready`，`authMode=hmac-sha256-local-v1`。
- 2026-07-07: 本轮重装后桥接快照中，`root=true`、`accessibility=true`、`lsposed=true`、`lsposed_runtime_loaded=false`、`screenshotEnabled=true`、`fileBridgeEnabled=true`；说明 Runtime 基础链路已闭环，但当前桥接动作并不自动代表“已进入 LSPosed 真实注入场景”。
- 2026-07-07: 事件订阅 2.5 秒复测收到 `4` 帧，`window_changed` 焦点窗口仍为 `com.miui.home/.launcher.Launcher`；事件流中存在一条历史 `broken pipe` 记录，但不影响本轮回归通过。
- 2026-07-07: 通过 `DebugRuntimeBridgeReceiver` 触发 `chat_prompt("获取能力")` 已成功返回能力摘要，证明“聊天输入 -> 工具执行 -> 助手回包”链路在当前设备状态下可用。
- 2026-07-07: 直接用 `adb shell am start/broadcast` 发送带空格的 extras 时，宿主 shell 会把 `wm size` 或 `/shell wm size` 拆成多段；本轮曾因此误得到仅含 `wm` 或 `/shell` 的结果。后续若复测 CLI 桥接命令，应优先把这类现象归类为“ADB 传参问题”而非 Runtime/聊天逻辑回归。

## Evidence Snapshot

- Overview: `debug-artifacts/clawdroid-overview-final.png`
- Chat: `debug-artifacts/clawdroid-chat.png`
- Settings: `debug-artifacts/clawdroid-settings.png`
- Overview Deep: `debug-artifacts/clawdroid-overview-deep1.png`, `debug-artifacts/clawdroid-overview-deep2.png`
- Chat Deep: `debug-artifacts/clawdroid-chat-deep1.png`
- Settings Deep: `debug-artifacts/clawdroid-settings-deep1.png`, `debug-artifacts/clawdroid-settings-deep2.png`
- Chat Long Content: `debug-artifacts/clawdroid-chat-long.png`
- Overview Long Content XML: `debug-artifacts/overview_seed_highlight.xml`, `debug-artifacts/overview_seed_events.xml`
- Overview Long Content Screenshot Attempt: `debug-artifacts/clawdroid-overview-seed-highlight.png` (当前文件内容与前台 XML 不一致，暂不作为主证据)
- Foreground Capture Exec-Out Failure Trace: `debug-artifacts/foreground-capture-execout.focus.txt`
- Foreground Capture Shell-File Failure Trace: `debug-artifacts/foreground-capture-shellfile.focus.txt`
- Foreground Capture Root Success: `debug-artifacts/foreground-capture-root-fixed.png`, `debug-artifacts/foreground-capture-root-fixed.focus.txt`, `debug-artifacts/foreground-capture-root-fixed.xml`

## Hypothesis Status

1. 长文本/日志可读性问题：部分已缓解，未在首轮三页截图中表现为首要阻塞问题。
2. 统一后的间距与标题在真机上仍可能拥挤：当前不是主问题，标题与主块节奏基本可接受。
3. 底部导航与页面内容存在对齐/遮挡偏差：已修复，第二轮真机截图通过。
4. 可滚动区域在真机上不够直观：第三轮深滚动未见新的阻塞问题；长聊天与概览长日志现已具备可重复播种入口，截图主风险也已收敛到 MIUI ROM 对 `uiautomator dump`/普通 `screencap` 的前台扰动，而不是 Compose 布局本身不可见。
5. 问题与设备 DPI/导航栏占位相关：已确认；根因是悬浮导航覆盖下的视口保留空间不足，而非单纯列表尾部留白不足。
6. 启动 Root / Magisk / 权限自检：代码与日志链路已打通，但当前真机上的 App 内 `su` 仍被拒绝，导致 Magisk 模块探测与自动恢复授权尚未拿到“Root 已允许”场景证据；后续需要用户在设备上对 Clawdroid 的 Magisk Root 请求完成一次允许。
7. Runtime 会话探针与最新模块重装：已通过；当前设备上的最新 App 与最新 `clawruntime` 模块组合可以稳定进入 `Ready`，`probe`、`capabilities`、受限 Shell 与截图读回均已拿到真机通过证据。
8. LSPosed 真实注入场景：已通过；当前设备重启后的 `xposed_runtime_marker.json` 与 `capabilities/probe` 已同时证明 `lsposed_runtime_loaded=true`，后续可将重点转向“作用域配置流程标准化”和更多目标 App 适配验证。
