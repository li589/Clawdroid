# Agent 能力集

通过 `list_agents` / `run_agent` 调用。多步固定流程优先 Agent，零散单步用原子工具。

| id | 名称 | 步骤 | 适用 |
|----|------|------|------|
| `runtime_health_sweep` | 运行时体检 | ping → runtime_status → capabilities | 能力/连通/模块总览 |
| `probe_then_capabilities` | 运行时状态检查 | probe_session → capabilities | 会话探测后读能力 |
| `capture_then_preview` | 截图并预览 | capture → read_latest | 看一眼当前屏幕 |
| `swipe_then_capture` | 滑动后截图 | swipe → capture → preview | 翻页后再看 |
| `confirm_then_safe_tap` | 页面确认后安全点击 | page_confirm → precheck → safe_tap | 确认目标页再点 |
| `assist_then_runtime` | 协助连通后体检 | assist_ping → runtime_status | 先通电脑 MCP 再查 Runtime |

## 选用建议

- 「有哪些能力 / 模块正常吗」→ `runtime_health_sweep`
- 「截个图看看」→ `capture_then_preview`
- 「滑一下再截图」→ `swipe_then_capture`
- 「确认是设置页再点」→ `confirm_then_safe_tap`
- 「电脑协助通了吗」→ `assist_then_runtime`
