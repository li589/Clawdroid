# 路由与意图辅助

## 自然语言优先（交给 AI）

含「帮我 / 怎么 / 看看 / 能否 / 可以吗 / 请」等，且不是明确斜杠命令时，优先走 AI 编排，而不是短规则关键词。

## 短规则仍可直达

| 关键词 / 命令 | 倾向动作 |
|---------------|----------|
| ping / 连通 | `runtime_ping` |
| 获取能力 / capabilities | `get_capabilities` |
| 截图并预览 | agent `capture_then_preview` |
| 运行时体检 | agent `runtime_health_sweep` |
| 滑动后截图 | agent `swipe_then_capture` |
| 确认页面后安全点击 | agent `confirm_then_safe_tap` |
| `/shell …` / 执行 … | `execute_shell_limited` |
| 重启 / reboot / 重启手机 / 重启系统 | `execute_shell_limited` `{"command":"reboot"}`（禁止 `task_submit` / `task_get`） |
| 开始/停止事件订阅 | `subscribe_events` |
| `/agents` `/agent <id>` | 列出 / 运行 agent |
| `/task_*` | Runtime 任务工具 |

## 原则

- 斜杠命令与极短操作指令可规则直达。
- 含解释性、探询性措辞的长句交给 AI + tools。
