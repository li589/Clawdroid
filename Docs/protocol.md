# Clawdroid IPC Protocol Specification

## 1. Scope

本文档冻结 `Clawdroid App` 与 `ClawRuntime` 之间的本地 IPC 协议定义，作为编码、联调、测试和后续版本演进的唯一协议依据。当前仓库中的对应源码目录为 `ClawRuntime/runtime`。

协议适用范围：

- `Clawdroid App` 主进程到 `ClawRuntime` 的本地 IPC 调用
- `Clawdroid App` 中的 LSPosed 适配代码经 App 转发到 `ClawRuntime` 的能力调用
- `ClawRuntime` 向 App 侧回传事件、能力信息和任务状态

当前冻结版本：

- 协议主版本：`1`
- 编码建议：长度前缀帧 + JSON
- 传输建议：本地 UNIX Domain Socket

## 2. Design Rules

协议设计遵循以下规则：

1. **版本优先**：所有请求必须带 `version` 字段。
2. **请求可追踪**：所有请求必须带全局唯一 `request_id`。
3. **最小动作集**：只开放固定动作，不支持任意 RPC 名称扩展。
4. **强约束参数**：所有动作都定义固定参数结构、参数范围和超时。
5. **默认拒绝**：未知动作、非法参数、超长负载、越权能力一律拒绝。
6. **结构化错误**：所有失败必须返回明确错误码和可读消息。
7. **高危可审计**：所有高危动作必须记录审计日志。

## 3. Envelope

### 3.1 Request Frame

请求帧结构如下：

```json
{
  "version": 1,
  "request_id": "8b4d9d9a-7e6e-4d1f-9d2b-61a4fd6af001",
  "timestamp": 1760000000,
  "action": "inject_tap",
  "capability": "input.inject",
  "args": {
    "x": 540,
    "y": 1200,
    "display_id": 0
  }
}
```

字段定义：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `version` | integer | 是 | 协议主版本号，当前固定为 `1` |
| `request_id` | string | 是 | 请求唯一 ID，建议使用 UUID v4 |
| `timestamp` | integer | 是 | 客户端发起请求的 Unix 秒级时间戳 |
| `action` | string | 是 | 动作名，只允许固定枚举值 |
| `capability` | string | 是 | 所请求的能力标识 |
| `args` | object | 是 | 动作参数对象，无参数动作也必须传空对象 `{}` |

字段约束：

- `version` 当前仅允许 `1`
- `request_id` 长度建议不超过 `64` 字符
- `action` 必须属于动作目录中的已知动作
- `capability` 必须与 `action` 对应
- `args` 不允许为 `null`

### 3.2 Response Frame

响应帧结构如下：

```json
{
  "request_id": "8b4d9d9a-7e6e-4d1f-9d2b-61a4fd6af001",
  "ok": true,
  "code": 0,
  "message": "success",
  "data": {
    "latency_ms": 12
  }
}
```

字段定义：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `request_id` | string | 是 | 与请求帧中的 `request_id` 保持一致 |
| `ok` | boolean | 是 | 是否成功 |
| `code` | integer | 是 | 错误码或成功码，成功固定为 `0` |
| `message` | string | 是 | 面向调试和日志的人类可读消息 |
| `data` | object | 是 | 返回数据对象，失败时可为空对象 `{}` |

响应约束：

- `ok = true` 时，`code` 必须为 `0`
- `ok = false` 时，`code` 必须为非零错误码
- `data` 始终为对象，不返回 `null`

### 3.3 Event Frame

服务端主动推送事件时，建议使用如下统一结构：

```json
{
  "event": "task_state_changed",
  "timestamp": 1760000001,
  "data": {
    "task_id": "task-001",
    "state": "Running"
  }
}
```

事件推送仅用于 `subscribe_events` 建立后的会话，不替代标准请求响应流程。

## 4. Capability Mapping

| Action | Capability |
| --- | --- |
| `ping` | `system.ping` |
| `get_capabilities` | `system.inspect` |
| `get_runtime_status` | `system.inspect` |
| `capture_screen` | `screen.capture` |
| `inject_tap` | `input.inject` |
| `inject_swipe` | `input.inject` |
| `inject_keyevent` | `input.inject` |
| `read_file_limited` | `file.read.limited` |
| `write_file_limited` | `file.write.limited` |
| `stat_file_limited` | `file.read.limited` |
| `exec_shell_limited` | `shell.exec.limited` |
| `subscribe_events` | `event.subscribe` |
| `report_xposed_focus` | `event.report` |
| `report_xposed_view` | `event.report` |
| `task_submit` | `task.manage` |
| `task_get` | `task.manage` |
| `task_list` | `task.manage` |
| `task_cancel` | `task.manage` |

> 动作目录以 `ClawRuntime/runtime/internal/ipc/actions.go` 与 App 侧 `RuntimeActionCatalog` 为准（共 18 个动作）；CI 通过 `scripts/check_runtime_catalog.py` 做 set equality 校验。

## 5. Error Codes

### 5.1 Error Code Segments

| 分类 | 范围 | 说明 |
| --- | --- | --- |
| 通用错误 | `0 - 999` | 协议、参数、超时、取消等基础错误 |
| 认证错误 | `1000 - 1999` | 对端、包名、签名、令牌、会话、挑战失败 |
| 能力错误 | `2000 - 2999` | Root、无障碍、截图或能力协商缺失 |
| 执行错误 | `3000 - 3999` | 输入注入、Shell、文件读写等执行失败 |
| 适配错误 | `4000 - 4999` | Hook 失效、页面结构变化、版本不匹配 |
| 系统错误 | `5000 - 5999` | 守护进程异常、ROM 不兼容、SELinux 拒绝 |
| 任务错误 | `7000 - 7999` | 任务提交、查询、取消与队列状态 |

### 5.2 Frozen Base Error Codes

以下基础错误码在 `v1` 协议中冻结，不得随意变更语义：

| 代码 | 名称 | 分类 | 含义 |
| --- | --- | --- | --- |
| `0` | `OK` | 通用 | 成功 |
| `1` | `ERR_UNKNOWN` | 通用 | 未分类错误 |
| `2` | `ERR_INVALID_REQUEST` | 通用 | 请求结构非法或缺少必要字段 |
| `3` | `ERR_UNSUPPORTED_VERSION` | 通用 | 协议版本不兼容 |
| `4` | `ERR_TIMEOUT` | 通用 | 请求超时 |
| `5` | `ERR_CANCELLED` | 通用 | 请求被取消 |
| `6` | `ERR_PAYLOAD_TOO_LARGE` | 通用 | 请求负载超过限制 |
| `7` | `ERR_ACTION_NOT_ALLOWED` | 通用 | 动作不在允许列表中 |
| `8` | `ERR_RATE_LIMITED` | 通用 | 请求频率超过限制 |
| `1001` | `ERR_PEER_VERIFY_FAILED` | 认证 | 对端凭证校验失败 |
| `1002` | `ERR_SIGNATURE_MISMATCH` | 认证 | 包签名摘要不匹配 |
| `1003` | `ERR_CHALLENGE_FAILED` | 认证 | 挑战应答失败 |
| `1004` | `ERR_SESSION_EXPIRED` | 认证 | 会话过期或会话无效 |
| `1005` | `ERR_CAPABILITY_TOKEN_INVALID` | 认证 | 能力令牌非法、缺失或失效 |
| `2001` | `ERR_ROOT_UNAVAILABLE` | 能力 | Root 不可用 |
| `2002` | `ERR_ACCESSIBILITY_UNAVAILABLE` | 能力 | 无障碍未授权或不可用 |
| `2003` | `ERR_SCREEN_CAPTURE_UNAVAILABLE` | 能力 | 当前环境不可截图 |
| `2004` | `ERR_CAPABILITY_NOT_GRANTED` | 能力 | 请求能力未授权 |
| `3001` | `ERR_INPUT_INJECT_FAILED` | 执行 | 输入注入失败 |
| `3002` | `ERR_SHELL_DENIED` | 执行 | Shell 动作未被允许 |
| `3003` | `ERR_SHELL_EXEC_FAILED` | 执行 | Shell 执行失败 |
| `3004` | `ERR_FILE_OUT_OF_SCOPE` | 执行 | 请求路径超出白名单范围 |
| `3005` | `ERR_FILE_READ_FAILED` | 执行 | 文件读取失败 |
| `3006` | `ERR_FILE_WRITE_FAILED` | 执行 | 文件写入失败 |
| `4001` | `ERR_ADAPTER_NOT_AVAILABLE` | 适配 | 目标应用适配模块不可用 |
| `4002` | `ERR_TARGET_VERSION_UNSUPPORTED` | 适配 | 目标应用版本不受支持 |
| `4003` | `ERR_TARGET_UI_CHANGED` | 适配 | 目标页面结构变化导致适配失效 |
| `5001` | `ERR_SELINUX_DENIED` | 系统 | SELinux 拒绝访问 |
| `5002` | `ERR_DAEMON_UNHEALTHY` | 系统 | 守护进程状态异常 |
| `5003` | `ERR_ROM_UNSUPPORTED` | 系统 | 当前 ROM 行为不兼容 |
| `7001` | `ERR_TASK_NOT_FOUND` | 任务 | 任务 ID 不存在 |
| `7002` | `ERR_TASK_STATE_INVALID` | 任务 | 非法任务状态转换 |
| `7003` | `ERR_TASK_SUBMIT_FAILED` | 任务 | 任务提交失败 |
| `7004` | `ERR_TASK_CANCEL_FAILED` | 任务 | 任务取消失败 |
| `7005` | `ERR_TASK_QUEUE_FULL` | 任务 | 任务队列已满 |

> 错误码以 `ClawRuntime/runtime/internal/ipc/errors.go` 与 App 侧 `RuntimeErrorCodes` 为准；CI 同步校验。

## 6. Connection State Machine

连接状态机定义如下：

```text
Disconnected
  -> SocketConnected
  -> PeerVerified
  -> ChallengeIssued
  -> Authenticated
  -> CapabilitySynced
  -> Ready

Ready -> Degraded
Degraded -> Ready

SocketConnected -> Closed
PeerVerified -> Closed
ChallengeIssued -> Closed
Authenticated -> Closed
CapabilitySynced -> Closed
Ready -> Closed
Degraded -> Closed
```

状态说明：

| 状态 | 说明 |
| --- | --- |
| `Disconnected` | 尚未建立 Socket 连接 |
| `SocketConnected` | Socket 已连接，但尚未完成身份验证 |
| `PeerVerified` | 已完成对端凭证校验 |
| `ChallengeIssued` | 已发起挑战应答，等待客户端提交证明 |
| `Authenticated` | 已通过身份认证 |
| `CapabilitySynced` | 已完成协议版本和能力画像同步 |
| `Ready` | 会话可正常执行动作 |
| `Degraded` | 会话仍可用，但部分能力缺失或被禁用 |
| `Closed` | 会话已关闭，不再接受请求 |

状态流转约束：

- 任何状态发生认证失败、版本不兼容、会话超时，必须进入 `Closed`
- `Ready` 仅在认证和能力同步完成后才能进入
- 关键能力缺失时可进入 `Degraded`，而不是强制断开
- `Closed` 为终态，需重新连接才能回到 `Disconnected`

## 7. Task State Machine

任务状态机定义如下：

```text
Created
  -> Queued
  -> Running
  -> WaitingSignal
  -> Retrying
  -> Running

Running -> Succeeded
Running -> Failed
Running -> Cancelled
Running -> Compensating -> Failed
Running -> Compensating -> Succeeded
WaitingSignal -> Cancelled
Retrying -> Failed
```

状态说明：

| 状态 | 说明 |
| --- | --- |
| `Created` | 任务已创建，尚未进入调度队列 |
| `Queued` | 任务已排队等待执行 |
| `Running` | 任务正在执行当前步骤 |
| `WaitingSignal` | 任务正在等待页面稳定、权限结果或外部信号 |
| `Retrying` | 任务进入可恢复失败后的重试阶段 |
| `Succeeded` | 任务执行成功 |
| `Failed` | 任务执行失败，且不可恢复 |
| `Cancelled` | 任务被主动取消 |
| `Compensating` | 任务执行补偿动作以恢复环境 |

状态流转约束：

- `Created` 必须先进入 `Queued`
- `Queued` 只能进入 `Running` 或 `Cancelled`
- `Retrying` 成功后回到 `Running`
- `Compensating` 完成后只能进入 `Succeeded` 或 `Failed`

## 8. Action Definitions

本节冻结 `v1` 协议中的全部 18 个动作定义（与 `actions.go` 对齐）。

### 8.1 `ping`

- **Capability**: `system.ping`
- **用途**: 检查守护进程可达性和基本健康状态
- **参数结构**:

```json
{}
```

- **参数范围**: 无参数，`args` 必须为空对象
- **是否幂等**: 是
- **是否可取消**: 否
- **默认超时**: `1000 ms`
- **审计级别**: 低
- **成功返回示例**:

```json
{
  "daemon_status": "ok",
  "version": "1.0.0",
  "latency_ms": 8
}
```

### 8.2 `get_capabilities`

- **Capability**: `system.inspect`
- **用途**: 获取当前设备、守护进程和 App 协商后的能力画像
- **参数结构**:

```json
{}
```

- **参数范围**: 无参数，`args` 必须为空对象
- **是否幂等**: 是
- **是否可取消**: 否
- **默认超时**: `1500 ms`
- **审计级别**: 低
- **成功返回示例**:

```json
{
  "root": true,
  "accessibility": true,
  "lsposed": true,
  "capabilities": [
    "system.ping",
    "screen.capture",
    "input.inject",
    "shell.exec.limited"
  ]
}
```

### 8.3 `capture_screen`

- **Capability**: `screen.capture`
- **用途**: 获取当前屏幕截图
- **参数结构**:

```json
{
  "display_id": 0,
  "format": "png",
  "quality": 90,
  "max_width": 1440,
  "max_height": 3200
}
```

- **参数范围**:
  - `display_id`: `>= 0`
  - `format`: 仅允许 `png` 或 `jpeg`
  - `quality`: `1 - 100`，仅在 `jpeg` 时生效
  - `max_width`: `1 - 4096`
  - `max_height`: `1 - 4096`
- **是否幂等**: 否
- **是否可取消**: 是
- **默认超时**: `3000 ms`
- **审计级别**: 中
- **成功返回示例**:

```json
{
  "display_id": 0,
  "format": "png",
  "width": 1080,
  "height": 2400,
  "image_base64": "..."
}
```

### 8.4 `inject_tap`

- **Capability**: `input.inject`
- **用途**: 在指定坐标执行单次点击
- **参数结构**:

```json
{
  "x": 540,
  "y": 1200,
  "display_id": 0
}
```

- **参数范围**:
  - `x`: `>= 0` 且不得超出目标显示区域宽度
  - `y`: `>= 0` 且不得超出目标显示区域高度
  - `display_id`: `>= 0`
- **是否幂等**: 否
- **是否可取消**: 否
- **默认超时**: `1000 ms`
- **审计级别**: 高
- **成功返回示例**:

```json
{
  "accepted": true,
  "display_id": 0
}
```

### 8.5 `inject_swipe`

- **Capability**: `input.inject`
- **用途**: 在指定起点和终点之间执行滑动
- **参数结构**:

```json
{
  "x1": 540,
  "y1": 1800,
  "x2": 540,
  "y2": 400,
  "duration_ms": 350,
  "display_id": 0
}
```

- **参数范围**:
  - `x1`、`y1`、`x2`、`y2`: `>= 0` 且不得超出目标显示区域
  - `duration_ms`: `50 - 10000`
  - `display_id`: `>= 0`
- **是否幂等**: 否
- **是否可取消**: 否
- **默认超时**: `1500 ms`
- **审计级别**: 高
- **成功返回示例**:

```json
{
  "accepted": true,
  "duration_ms": 350
}
```

### 8.6 `read_file_limited`

- **Capability**: `file.read.limited`
- **用途**: 在白名单路径内读取有限大小的文件内容
- **参数结构**:

```json
{
  "path": "/sdcard/Download/example.txt",
  "offset": 0,
  "max_bytes": 65536
}
```

- **参数范围**:
  - `path`: 必须属于预设白名单路径
  - `offset`: `>= 0`
  - `max_bytes`: `1 - 1048576`
- **是否幂等**: 是
- **是否可取消**: 是
- **默认超时**: `2000 ms`
- **审计级别**: 中
- **成功返回示例**:

```json
{
  "path": "/sdcard/Download/example.txt",
  "offset": 0,
  "read_bytes": 128,
  "content_base64": "..."
}
```

### 8.6.1 `write_file_limited`

- **Capability**: `file.write.limited`
- **用途**: 在可写白名单路径内写入有限大小内容（覆盖或追加由实现约定）
- **参数结构**:

```json
{
  "path": "/data/local/tmp/clawdroid/out.txt",
  "content_base64": "...",
  "max_bytes": 65536
}
```

- **参数范围**:
  - `path`: 必须属于可写白名单
  - `content_base64`: 解码后长度受 `max_bytes` 与守护上限约束
  - `max_bytes`: `1 - 1048576`
- **是否幂等**: 否
- **是否可取消**: 否
- **默认超时**: `3000 ms`
- **审计级别**: 高
- **失败码**: 越界 `3004`，写入失败 `3006`

### 8.6.2 `stat_file_limited`

- **Capability**: `file.read.limited`
- **用途**: 在白名单路径内查询文件元数据（存在性、大小、mtime 等）
- **参数结构**:

```json
{
  "path": "/sdcard/Download/example.txt"
}
```

- **是否幂等**: 是
- **是否可取消**: 否
- **默认超时**: `1500 ms`
- **审计级别**: 中

### 8.7 `exec_shell_limited`

- **Capability**: `shell.exec.limited`
- **用途**: 执行受限命令模板，而非任意 Shell
- **参数结构**:

```json
{
  "command": "dumpsys window windows",
  "timeout_ms": 3000
}
```

- **参数范围**:
  - `command`: 必须命中白名单命令模板
  - `timeout_ms`: `100 - 10000`
- **是否幂等**: 否
- **是否可取消**: 是
- **默认超时**: `3000 ms`
- **审计级别**: 高
- **成功返回示例**:

```json
{
  "exit_code": 0,
  "stdout": "...",
  "stderr": ""
}
```

### 8.8 `subscribe_events`

- **Capability**: `event.subscribe`
- **用途**: 建立服务端主动推送事件的订阅关系
- **参数结构**:

```json
{
  "events": [
    "task_state_changed",
    "daemon_status_changed",
    "capability_changed",
    "window_changed",
    "xposed_focus_changed"
  ]
}
```

- **参数范围**:
  - `events`: 非空数组
  - 事件名仅允许 `task_state_changed`、`daemon_status_changed`、`capability_changed`、`window_changed`、`xposed_focus_changed`、`xposed_view_changed`
  - 单次订阅事件数 `1 - 16`
- **是否幂等**: 是
- **是否可取消**: 是
- **默认超时**: `5000 ms`
- **审计级别**: 低
- **成功返回示例**:

```json
{
  "subscribed": [
    "task_state_changed",
    "daemon_status_changed"
  ]
}
```

### 8.8.1 `report_xposed_focus`

- **Capability**: `event.report`
- **用途**: App 上报 LSPosed schema v2 焦点快照；守护进程缓存后通过 `xposed_focus_changed` 差分推送给订阅方
- **参数结构**:

```json
{
  "focus_json": "{\"schema_version\":2,\"package_name\":\"com.android.settings\",\"activity_class\":\"com.android.settings.Settings\",\"active\":true,\"extras\":{}}"
}
```

- **参数范围**:
  - `focus_json`: 非空 JSON 对象字符串，至少含 `package_name`、`activity_class`
- **是否幂等**: 否（覆盖最新缓存）
- **是否可取消**: 否
- **默认超时**: `2000 ms`
- **审计级别**: 低
- **成功返回关键字段**: `accepted`、`package_name`、`activity_class`、`updated_at_epoch_ms`

### 8.8.2 `report_xposed_view`

- **Capability**: `event.report`
- **用途**: App 上报 Settings 浅层 View 层次（schema v1）；推送 `xposed_view_changed`
- **参数结构**:

```json
{
  "view_json": "{\"schema_version\":1,\"package_name\":\"com.android.settings\",\"activity_class\":\"com.android.settings.Settings\",\"node_count\":8,\"compose_surface\":false,\"nodes\":[]}"
}
```

- **参数范围**:
  - `view_json`: 非空 JSON 对象字符串，至少含 `package_name`、`activity_class`
- **是否幂等**: 否
- **是否可取消**: 否
- **默认超时**: `2000 ms`
- **审计级别**: 低
- **成功返回关键字段**: `accepted`、`package_name`、`activity_class`、`node_count`、`compose_surface`、`updated_at_epoch_ms`

### 8.9 `get_runtime_status`

- **Capability**: `system.inspect`
- **用途**: 统一返回守护健康、Magisk 模块状态、动作目录、Shell/按键白名单
- **参数结构**: `{}`
- **是否幂等**: 是
- **是否可取消**: 否
- **默认超时**: `2000 ms`
- **审计级别**: 低
- **成功返回关键字段**:
  - `module`: Magisk 模块安装/启用/verify/status 摘要
  - `actions`: 当前已知动作目录
  - `allowed_shell_commands`: Shell 白名单
  - `allowed_keyevents`: 按键白名单
  - 以及 `get_capabilities` / 健康诊断同类字段

### 8.10 `inject_keyevent`

- **Capability**: `input.inject`
- **用途**: 注入白名单内系统按键（导航/编辑类）
- **参数结构**:

```json
{
  "key": "BACK",
  "display_id": 0
}
```

或：

```json
{
  "keycode": 4,
  "display_id": 0
}
```

- **参数范围**:
  - `key` / `keycode` 二选一；仅允许 `BACK`/`HOME`/`APP_SWITCH`/`ENTER`/`DEL`/`TAB`/`SPACE`/`DPAD_*`
  - `display_id`: `>= 0`
- **是否幂等**: 否
- **是否可取消**: 否
- **默认超时**: `1000 ms`
- **审计级别**: 高

### 8.11 `task_submit`

- **Capability**: `task.manage`
- **用途**: 提交多步 Runtime 任务（步骤为已知 IPC action 序列）
- **参数结构**:

```json
{
  "task_id": "demo-1",
  "name": "health",
  "steps": [
    { "action": "ping", "args": {} }
  ]
}
```

- **是否幂等**: 否（同 `task_id` 重复提交可能失败）
- **是否可取消**: 否（取消走 `task_cancel`）
- **默认超时**: `3000 ms`
- **审计级别**: 高
- **失败码**: `7003` 提交失败，`7005` 队列满

### 8.12 `task_get`

- **Capability**: `task.manage`
- **用途**: 查询单个任务快照
- **参数结构**: `{ "task_id": "demo-1" }`
- **是否幂等**: 是
- **默认超时**: `1500 ms`
- **失败码**: `7001` 未找到

### 8.13 `task_list`

- **Capability**: `task.manage`
- **用途**: 列出近期任务摘要
- **参数结构**: `{}` 或带可选 `limit`
- **是否幂等**: 是
- **默认超时**: `2000 ms`

### 8.14 `task_cancel`

- **Capability**: `task.manage`
- **用途**: 取消尚未结束的任务
- **参数结构**: `{ "task_id": "demo-1" }`
- **是否幂等**: 否
- **默认超时**: `2000 ms`
- **失败码**: `7002` 非法状态，`7004` 取消失败

## 9. Protocol Constraints

### 9.1 Idempotency

- 幂等：`ping`、`get_capabilities`、`get_runtime_status`、`read_file_limited`、`stat_file_limited`、`subscribe_events`、`task_get`、`task_list`
- 非幂等：`capture_screen`、`inject_tap`、`inject_swipe`、`inject_keyevent`、`write_file_limited`、`exec_shell_limited`、`report_xposed_focus`、`report_xposed_view`、`task_submit`、`task_cancel`

### 9.2 Cancellation

- 不可取消动作：`ping`、`get_capabilities`、`get_runtime_status`、`inject_tap`、`inject_swipe`、`inject_keyevent`、`write_file_limited`、`stat_file_limited`、`task_get`、`task_list`、`task_submit`、`task_cancel`
- 可取消动作：`capture_screen`、`read_file_limited`、`exec_shell_limited`、`subscribe_events`

### 9.3 Timeout

- 如请求未显式声明超时，按动作默认超时执行
- 超时后返回 `ERR_TIMEOUT`

### 9.4 Audit

- 低审计级别：健康检查、能力查询、事件订阅
- 中审计级别：截图、受限文件读取
- 高审计级别：输入注入、Shell 执行

## 10. Version Negotiation

版本协商规则冻结如下：

1. 主版本必须一致，否则返回 `ERR_UNSUPPORTED_VERSION`
2. 次级实现差异不影响本协议 `v1` 的字段和动作语义
3. 未识别字段必须忽略，但不得改变已定义字段语义
4. 新增动作必须通过更高协议版本发布

## 11. Implementation Notes

实施建议：

- `request_id` 用于审计、日志、链路追踪和幂等保护
- 服务端应对高危动作进行能力校验、路径校验、参数范围校验和速率限制
- `exec_shell_limited` 必须绑定白名单模板，不得退化为任意字符串执行
- `read_file_limited` 必须同时受路径白名单和读取大小上限控制

本文件自创建起作为 `v1` 协议冻结文档，除新增版本章节外，不应随意修改已冻结语义。
