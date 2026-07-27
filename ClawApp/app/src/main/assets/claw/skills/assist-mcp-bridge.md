# Assist MCP Bridge

手机 Agent 需要电脑侧 MCP，或诊断 ADB 协助隧道时使用。

## 前置

1. 电脑：Host MCP 已监听（Cursor MCP / 本地 HTTP MCP）。
2. `adb reverse tcp:<port> tcp:<port>`，手机可访问 `http://127.0.0.1:<port>/mcp`。
3. Clawdroid「设置 → 协助 MCP」：启用客户端，填写 Host URL + Token。

## 流程

1. `assist_status` — 启用状态 / 最近错误
2. `assist_ping` — 连通性
3. `assist_list_tools` — 发现 host 工具
4. `assist_call_tool`：`{ "name", "arguments_json" }`
5. `tunnel_down` / 超时：请用户重新 `adb reverse`

## 规则

- UI / Runtime / 沙箱文件优先本机工具。
- 失败对照 assist 工具的 `errorCode`。
- 不要臆造 host 工具名；先 list。
