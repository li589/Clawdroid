# 协助 MCP

Clawdroid 协助 MCP 是双向桥：

1. **电脑 → 手机**：`adb forward tcp:PORT tcp:PORT`，Cursor/Claude 连接手机 MCP Server（`clawdroid-assist`）。
2. **手机 → 电脑**：`adb reverse tcp:HOST_PORT tcp:HOST_PORT`，手机用 `assist_*` 工具调用电脑 MCP。

## 原则

- 本机工具优先；仅当需要电脑侧能力（浏览器自动化、本地文件、IDE 等）时使用 `assist_call_tool`。
- ADB 断开后需重新执行 forward/reverse；用 `assist_ping` / `assist_status` 诊断。
- 不要在 MCP JSON-RPC 中传输大二进制；大文件用 `download_*` 或电脑侧工具落盘后再读。
