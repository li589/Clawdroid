# Clawdroid 文本包索引

统一放置 AI / Skill / Agent 辅助文案。应用内入口：设置 →「提示词与 Skills」。

| 路径 | 用途 |
|------|------|
| `prompts/orchestrator.md` | 主编排系统提示（工具决策） |
| `prompts/continue.md` | 多步工具循环续写提示 |
| `prompts/tool-reflection.md` | 工具结果总结提示 |
| `prompts/tool-usage.md` | 权限与调用规范 |
| `prompts/assist-mcp.md` | 协助 MCP 使用指南 |
| `prompts/context-compress.md` | 长对话上下文压缩 |
| `prompts/chat-welcome.md` | 聊天欢迎语 |
| `prompts/chat-suggestions.txt` | 聊天建议 chip（一行一条） |
| `helpers/routing-hints.md` | 自然语言路由 / 意图辅助 |
| `helpers/agent-phrases.md` | 工具执行前短句 |
| `agents/catalog.md` | Agent 能力说明与选用建议 |
| `skills/*.md` | Skill 正文（与 list_skills 对齐） |
| `tools/catalog.overlay.json` | 工具摘要覆盖 / 蓝图 |

缺失文件时使用 Kotlin 内置回退文案。
