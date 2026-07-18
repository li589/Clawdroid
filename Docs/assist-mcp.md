# 协助 MCP

Clawdroid 协助 MCP 提供双向调试桥：

| 方向 | 隧道 | 角色 |
|------|------|------|
| 电脑 → 手机 | `adb forward tcp:PORT tcp:PORT` | 手机 MCP Server（`clawdroid-assist`） |
| 手机 → 电脑 | `adb reverse tcp:HOST_PORT tcp:HOST_PORT` | 手机 `assist_*` 客户端 |

## 手机侧服务（电脑调手机）

1. 设置 → **协助 MCP · 手机侧服务**：开启服务，记下端口与 Token。
2. 电脑执行：

```bash
adb forward tcp:8765 tcp:8765
```

3. Cursor `mcp.json` 示例：

```json
{
  "mcpServers": {
    "clawdroid-assist": {
      "url": "http://127.0.0.1:8765/sse",
      "headers": {
        "Authorization": "Bearer <token>"
      }
    }
  }
}
```

也可 `POST http://127.0.0.1:8765/mcp` 直连 JSON-RPC。

### 协议要点

- Server name: `clawdroid-assist`
- `initialize.serverInfo.roles`: `phone-server`, `phone-client`
- `tools/call` 结果 `_meta` 含 `requestId` / `correlationId`
- 鉴权：`Authorization: Bearer` 或 `X-Clawdroid-Token`
- ADB 断开后需重新 `adb forward`；设置页状态会提示隧道说明

## 电脑协助端点（手机调电脑）

1. 电脑侧 MCP 监听（例如 `http://127.0.0.1:8766/mcp`）。
2. 电脑执行：

```bash
adb reverse tcp:8766 tcp:8766
```

3. 设置 → **协助 MCP · 电脑协助端点**：填写 Host URL / Token，开启并「探测连通」。
4. 工具：

| tool | 说明 |
|------|------|
| `assist_status` | 配置与最近错误 |
| `assist_ping` | 探测 |
| `assist_list_tools` | 列出电脑工具 |
| `assist_call_tool` | `{ name, arguments_json }` 调用 |

错误码：`tunnel_down` / `auth_failed` / `timeout` / `protocol_error` / `remote_error` / `assist_disabled`。

## 本地工具地基（摘要）

- 权限层级：`None` / `Basic` / `Accessibility` / `AdbShizuku` / `Root`
- 发现：`list_tools` / `get_tool`；资源 `clawdroid://catalog/tools`
- Live capabilities：`get_capabilities` / probe / runtime_status / get_health 成功后写入；事件流 `capability_changed` 同步刷新；`tools/list` 带 `clawdroid.available` / `unavailableReason`
- Runtime 共享密钥：默认 `BuildConfig`；设置页可写设备覆盖（`RuntimeSecretStore`），需与 Magisk `runtime.yaml` 一致，改后重启 App
- AI 参数：`AiAgentOrchestrator` 以 `ClawToolCatalog.allowedArgumentKeys` 为真相源（空 schema 透传）
- 组合根：`ToolServiceRegistry.create(...)` 注入 Dispatcher；只读工具可并行，变更类 / 硬件独占 / Agent / `subscribe_events` 走 serialize mutex（Agent 嵌套可重入）
- 事件：`RuntimeEventService` 进程级订阅；Overview UI 与 MCP `subscribe_events` 共用；`capability_changed` 统一写入 `LiveToolCapabilityStore`
- Live capabilities：默认 60s stale；Shell 启动 / `list_tools` / MCP `tools/list` 前 `CapabilityProbe` best-effort 刷新
- 蓝图：`list_tools include_planned=true` 或 `get_tool` 可读 overlay `planned`（无执行器）
- MCP prompts：`assist-mcp`、`tool-usage`；resources：`clawdroid://prompt/...`
- 第一批：`file_*`、`app_*`、`download_*`
- 加深：`download_start` 多线程 Range；`file_read` `mode=columns`；Runtime shell 白名单扩展；`app_stop` 后 `pidof` 校验
- 域工具：`notification_list`（通知使用权）、`web_preview`（HTTP 抽取 title/text/images）、`web_search`（Wikipedia/DDG）、`sandbox_shell`（filesDir 白名单命令）
- 硬件：`camera_capture`（静帧 JPEG）、`camera_record`（静音短 MP4 + 首帧 preview）、`sensor_read`、`gpu_npu_probe`（GLES/Vulkan/NNAPI 只读）
- 网络：`ftp_transfer`（`protocol=ftp|sftp`；list/get/put；本地限沙箱；SFTP 仅密码认证）
- Shizuku：`shizuku_status` / `shizuku_request` / `shizuku_exec`（白名单短命令；环境探测写入概览）
- Xposed：Settings/Browser；微信 `wechat_detail` 默认关闭（页面身份 only）；见 [xposed-adapters.md](xposed-adapters.md) / [threat-model.md](threat-model.md)
- 配置/提示词：`assets/claw/{tools,prompts,skills}`
- 概览页展示协助 MCP 手机服务 / 电脑端点状态与 live capability 计数
- **平台加固（v0.2.x）：** 事件解耦 Overview、caps 心跳、并发策略固化、Runtime 密钥威胁模型对齐

## 联调验收清单

- [ ] forward 后电脑 `tools/list` 可见 `list_tools` / `assist_status` / `file_read`
- [ ] reverse 后手机 `assist_ping` 成功
- [ ] `assist_call_tool` 能调用电脑工具并回显
- [ ] 沙箱路径 `file_write` + `file_read`（lines / columns 模式）
- [ ] `app_list` / `app_launch` / `app_info` / `app_stop`
- [ ] `download_start`（threads>1）→ `download_status` → `download_verify`
- [ ] 开启通知使用权后 `notification_list` 有数据
- [ ] `web_preview` 对公开 URL 返回 title/text
- [ ] `web_search` 返回至少一条结果（wikipedia 或 ddg）
- [ ] `sandbox_shell` 执行 `pwd` / `echo hello`；拒绝 `cat ../x`
- [ ] 授予 CAMERA 后 `camera_capture` 返回 JPEG path；未授权返回 `camera_permission_denied`
- [ ] `camera_record` 返回 mp4 `path` + `preview_path`；未授权 `camera_permission_denied`
- [ ] `sensor_read` `op=list` 有列表；`op=read type=accelerometer` 有 samples
- [ ] `ftp_transfer` FTP/SFTP `list`/`get`/`put`；拒绝沙箱外 `local_path`
- [ ] `gpu_npu_probe` 返回非空 `gles.renderer`（或明确 error）；含 vulkan/nnapi 字段
- [ ] 安装并授权 Shizuku 后 `shizuku_status` / `shizuku_exec id` 成功
- [ ] 不在 Overview 页时 MCP `subscribe_events start/stop` 仍可用（`RuntimeEventService`）
- [ ] caps stale 后 `list_tools` / `tools/list` 触发刷新；`capability_changed` 更新 `LiveToolCapabilityStore`
- [ ] USB 断开后状态提示；重跑 forward/reverse 恢复

## 不在本期

PC 协助守护进程、scrcpy、摄像头实时预览 UI、SFTP 密钥文件认证、GPU/NPU 算力调用、完整网页渲染。
