# 设备 Runtime 运维

在连接 ClawRuntime、检查模块健康或诊断 Agent 就绪时使用。优先本机工具，不要猜测设备状态。

## 流程

1. `runtime_ping` 或 `probe_session`
2. `get_runtime_status` 查看 Magisk / 模块 / daemon
3. `get_capabilities` 读取能力开关
4. 失败时 `get_last_error`

## 规则

- 不要臆造 Root / Accessibility / LSPosed 状态。
- 鉴权或 IPC 失败时停止，并回报工具原文。
- 完整体检优先 `run_agent` + `runtime_health_sweep`。
