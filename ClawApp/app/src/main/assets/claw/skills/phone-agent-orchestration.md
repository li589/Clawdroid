# Agent 编排

需要多步 Agent / Skill，而不是手搓大量底层工具时使用。

## 发现

- `list_tools` / `get_tool` — 带权限的工具目录
- `list_skills` / `get_skill` — 指导文档
- `list_agents` — 可执行多步工作流

## 执行

- `run_agent` 传入 `list_agents` 中的 id
- Agent 支持时传入 swipe / page 等可选参数

## 常用 Agent

- `runtime_health_sweep`
- `probe_then_capabilities`
- `capture_then_preview`
- `swipe_then_capture`
- `confirm_then_safe_tap`
- `assist_then_runtime`

重复工作流优先 Agent；一次性动作用原子工具。本机优先；电脑 MCP 用 `assist_*`。
