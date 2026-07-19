# LSPosed / Xposed 适配器契约

> 回归与优先级见 [下一步计划.md](下一步计划.md) P0 / P3。路径根目录：`/data/local/tmp/clawdroid/xposed/`

## adapters.json

设备可覆盖白名单配置（缺失则用内置默认）。

| 字段 | 说明 |
|------|------|
| `enabled_adapters` | 开启的 adapterId 列表 |
| `activity_focus_packages` / `settings_packages` / `launcher_packages` / `browser_packages` / `wechat_packages` | 包名白名单 |
| `fuse_after_failures` | 失败次数熔断阈值（默认 5） |
| `adapter_version_gates` | 可选 `{ "<adapterId>": { "min_version_code", "max_version_code" } }` |

## 焦点快照（schema v2）

| 文件 | 说明 |
|------|------|
| `focus_latest.json` | 最近一次焦点（原子 rename 写入） |
| `focus.json` | 兼容旧探测的副本（同样原子 rename） |
| `focus_ring/focus_<package>_<epoch>.json` | 按包覆盖旧条，全局保留最近 N=8 |

顶层字段：`schema_version`, `adapter_id`, `package_name`, `process_name`, `activity_class`, `loaded_at_epoch_ms`, `active`, 可选 title/action/data。  
Intent 扩展一律放在嵌套对象 `extras`（含 `settings_fragment` 等）。

生命周期：`onResume` → `active=true`；`onPause` → `active=false`。

## 熔断

进程内失败计数达阈值时，`AdapterRegistry` 跳过该 adapter 的 install；**进程重启后计数清零**。  
`fuse_<adapterId>.json` 仅为诊断/审计标记，**不**跨重启阻断 install（可人工删除）。  
View dump 失败记入 `fuse_<adapterId>_view.json`（`AdapterFuse.recordViewFailure`），**不**熔断主 adapter 焦点 hook。

## 探测

`LocalEnvironmentProbe`：进程内 marker **或** 24h 内持久化 `xposed_runtime_marker.json` 视为已注入；摘要含 `injected_source=process|marker|none`。

## 焦点推送（ContentProvider）

| 组件 | 说明 |
|------|------|
| Authority | `${applicationId}.focus` |
| Method | `focus_snapshot`，extras key `json` = schema v2 全文 |
| 接收 | `ClawFocusContentProvider` → `LiveXposedFocusStore` |
| 调用方 | 仅本包或已启用 adapter 白名单包（`FocusCallerGate`） |
| 探测 | 优先 live store，回退读 `focus_latest.json` |
| Overview | 订阅 store listener，实时刷新「目标焦点」 |

Push 失败不熔断；文件桥仍是审计/冷启动源。未知调用方 `ok=false`，不写 store。Live 路径用 `org.json`（`ClawJsonLite`）校验字段。

## View 层次快照（schema v1，Settings-first）

| 文件 | 说明 |
|------|------|
| `view_latest.json` | Settings `onResume` 浅层 decorView 元数据（原子写入） |

硬上限：`max_depth=4`、`max_nodes=32`；节点字段：`class`/`id`/`text`/`desc`/`sem`/`bounds`/`visibility`/`child_count`。  
`bounds` 为屏幕坐标（`getLocationOnScreen` + 宽高）。  
顶层另含 `compose_surface`（检测到 ComposeView / AndroidComposeView）。  
`sem` 来自 ComposeSemanticsProbe：AccessibilityNodeInfo + 可选反射 SemanticsOwner（无编译期 Compose 依赖）。  
不写 bitmap / 密码框内容 / WebView 内部。失败记入 `*_view` fuse，不阻断焦点快照。

推送：`view_snapshot` → `LiveXposedViewStore` → `report_xposed_view` → `xposed_view_changed`。  
探测：优先 live store，回退文件；Overview「View 层次」可实时刷新。  
Runtime：`captureXposed*` 对内存与文件按 `reported_at` / `loaded_at` newer-wins；`report_xposed_*` 会唤醒 subscribe（2s ticker 兜底）。

## 浏览器适配（browser_detail）

| 字段 | 说明 |
|------|------|
| `browser_url` | Intent data / URL-like extras |
| `browser_text` | 非 URL 的 EXTRA_TEXT |
| `browser_action` / `browser_app_id` | 动作与浏览器应用 id |

默认包：Chrome / Browser / Samsung / MI / Huawei，以及 Edge / Firefox / Opera / UC / Vivo / ColorOS / HeyTap / Honor / QQBrowser。resume 时同样写浅层 view dump。Overview 摘要含截断 `browser_url=`。

## 微信适配（wechat_detail，默认关闭）

**默认不启用。** 需在 `adapters.json` 的 `enabled_adapters` 中显式加入 `wechat_detail`。

| 字段 | 说明 |
|------|------|
| `wechat_page` | 页面标签：`home` / `chat` / `moments` / `contact` / … |
| `wechat_activity` | Activity 简单类名（如 `LauncherUI`） |
| `wechat_ctrl_*` | chrome 角色定位（`send`/`input`/`back`/`more`/`voice`/`emoji`/`tab`）= `id\|bounds`；仅 `chat`/`home`/`contacts`/`search`/`moments` |

范围：Activity 页面身份 + chrome 控件角色 + 浅层 view dump（`chat`/`moments` 脱敏节点 `text`）。  
明确不做：聊天正文、通讯录抓取、支付/红包自动化、私有 API hook。支付相关页不写 `wechat_ctrl_*`。

启用示例：

```json
{
  "enabled_adapters": ["self_runtime_marker", "activity_focus", "settings_detail", "launcher_focus", "browser_detail", "wechat_detail"],
  "wechat_packages": ["com.tencent.mm"],
  "adapter_version_gates": {
    "wechat_detail": { "min_version_code": 1, "max_version_code": 99999999 }
  },
  "fuse_after_failures": 5
}
```

回退：熔断后跳过 install（至进程重启）；编排侧应回退无障碍/人工确认（见 threat-model）。

## 第三方包

加包：编辑设备 `adapters.json` 白名单。深适配需独立威胁面说明（微信见上）。

## 再下一期

更多 OEM 私有 Intent 语义；真机 LSPosed 兼容矩阵填充。

## Runtime 事件

| 动作 / 事件 | 说明 |
|------|------|
| `report_xposed_focus` | App → Runtime，args.`focus_json` |
| `xposed_focus_changed` | `subscribe_events` 允许事件；约 2s 差分推送 |
| `report_xposed_view` | App → Runtime，args.`view_json` |
| `xposed_view_changed` | View 层次差分事件 |
