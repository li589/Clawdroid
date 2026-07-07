# Contributing to Clawdroid

感谢你关注 Clawdroid。

当前仓库的首要目标是让外部开发者能够稳定地理解、构建、验证并逐步参与贡献，因此提交内容请尽量保持聚焦、可验证和可审阅。

## 贡献范围

欢迎以下类型的贡献：

- 文档改进
- 构建流程与开发者体验优化
- Runtime 协议、鉴权、审计与诊断能力完善
- App 侧状态展示、工具编排、环境探测与权限流程优化
- 测试补充与兼容矩阵整理

当前不建议直接发起的大改动：

- 未经讨论的大范围架构重写
- 未建立安全边界说明的高危能力扩展
- 缺少验证步骤的复杂适配逻辑一次性合入

## 提交原则

- 一次提交只解决一个清晰问题
- 代码改动与文档改动尽量同行
- 涉及协议、权限、安全边界的变更，必须同步更新对应文档
- 不要提交本地私有配置、真实共享密钥、崩溃日志或临时构建产物

## 开发前准备

1. 阅读 [README.md](file:///d:/temp_desktop/Proj/Clawdroid/README.md)
2. 阅读 [基础方案设计.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/%E5%9F%BA%E7%A1%80%E6%96%B9%E6%A1%88%E8%AE%BE%E8%AE%A1.md)
3. 阅读 [protocol.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/protocol.md)
4. 阅读 [threat-model.md](file:///d:/temp_desktop/Proj/Clawdroid/Docs/threat-model.md)

## 最低验证要求

### Runtime 相关改动

- 在 `ClawRuntime/runtime` 执行 `go test ./...`
- 如修改了打包或配置流程，在 `ClawRuntime` 执行：
  - `.\scripts\build-runtime.ps1`
  - `.\scripts\build-magisk.ps1`

### App 相关改动

- 在 `ClawApp` 执行 `.\gradlew.bat :app:compileDebugKotlin`
- 若增加了纯逻辑单测，执行 `.\gradlew.bat testDebugUnitTest`

### 文档或流程改动

- 确认 README 与 Docs 中的路径、文件名、命令保持一致
- 确认新增文件已加入正确的导航入口

## Pull Request 检查清单

提交 PR 前请至少确认：

- 改动目标单一且说明清晰
- 没有提交真实 `auth.shared_secret`
- 没有提交 `local.properties`、`.gradle`、`hs_err_pid*`、`replay_pid*` 等本地文件
- 相关测试或编译验证已经执行
- 协议、安全、发布流程变更已同步更新文档

## 安全问题

若发现高危安全问题，请不要直接公开细节。请改为阅读并遵循 [SECURITY.md](file:///d:/temp_desktop/Proj/Clawdroid/SECURITY.md) 中的提交流程。
