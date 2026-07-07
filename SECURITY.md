# Security Policy

Clawdroid 涉及 Root、输入注入、截图、文件桥接与本地 IPC 等高权限能力。若你发现潜在安全问题，请优先采用非公开方式报告。

## 报告范围

欢迎报告以下问题：

- 未授权访问 `ClawRuntime`
- IPC 鉴权绕过
- 会话、挑战、签名或能力令牌校验缺陷
- 文件读取、Shell、截图、输入注入的越权问题
- 可能导致高危误操作或敏感数据泄露的问题

## 不建议公开提交的内容

在问题修复前，请不要在公开 Issue 或公开讨论中直接发布以下内容：

- 可复现的高危利用链细节
- 真实设备密钥、签名、敏感截图或个人数据
- 可直接武器化的 PoC

## 报告方式

当前仓库尚未建立专用安全邮箱。请在私下渠道中向维护者提供以下信息：

- 问题摘要
- 影响范围
- 复现步骤
- 预期行为与实际行为
- 如有必要，可附最小化日志或脱敏截图

## 处理原则

- 维护者会先确认问题是否可复现
- 高危问题优先私下处理，再视情况公开修复说明
- 涉及协议或安全边界的修复，应同步更新 [threat-model.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/threat-model.md) 与 [protocol.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/protocol.md)

## 相关文档

- [threat-model.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/threat-model.md)
- [protocol.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/protocol.md)
- [发布前清单.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/%E5%8F%91%E5%B8%83%E5%89%8D%E6%B8%85%E5%8D%95.md)
