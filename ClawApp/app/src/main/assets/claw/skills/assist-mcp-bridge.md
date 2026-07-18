# Assist MCP Bridge

Use when the phone agent needs a computer-side MCP tool, or when diagnosing ADB assist tunnels.

## Preconditions

1. On PC: host MCP listening (e.g. Cursor MCP / local HTTP MCP).
2. `adb reverse tcp:<port> tcp:<port>` so the phone can reach `http://127.0.0.1:<port>/mcp`.
3. In Clawdroid Settings → 协助 MCP: enable client, set Host URL + Token.

## Workflow

1. `assist_status` — check enabled / last error
2. `assist_ping` — connectivity
3. `assist_list_tools` — discover host tools
4. `assist_call_tool` with `{ "name", "arguments_json" }`
5. On `tunnel_down` / timeout: ask user to re-run `adb reverse`

## Rules

- Prefer on-device tools for UI/runtime/file sandbox work.
- Correlate failures with `errorCode` from assist tools.
- Never invent host tool names; list first.
