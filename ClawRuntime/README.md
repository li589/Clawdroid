# ClawRuntime

Root 执行侧：Go 守护进程 + Magisk 模块打包。

## 布局

| 路径 | 内容 |
|------|------|
| `runtime/` | Go module（`cmd/runtime`、`internal/ipc`、`internal/server`…） |
| `magisk/` | 模块源：`module.prop`、`service.sh`、`config/`、`bin/`、`webroot/` |
| `scripts/` | `build-runtime.ps1`、`sync-shared-secret.ps1`、`build-magisk.ps1` |
| `dist/` | 输出 `ClawRuntime-magisk.zip` |

脚本细节：[scripts/README.md](scripts/README.md)。

## 构建

```powershell
cd ClawRuntime
.\scripts\sync-shared-secret.ps1   # 从仓库根 local.properties 生成 runtime.generated.yaml
.\scripts\build-runtime.ps1        # → magisk/bin/clawdroid-runtime (android/arm64)
.\scripts\build-magisk.ps1         # → dist/ClawRuntime-magisk.zip
```

密钥源与 App 相同：`clawdroid.runtime.sharedSecret` / `CLAWDROID_RUNTIME_SHARED_SECRET`。  
发布包建议：`.\scripts\build-magisk.ps1 -RequireAllowedSignatures`。

## IPC

- 动作 SSOT：`runtime/internal/ipc/actions.go`
- 与 App `RuntimeActionCatalog` 必须一致；CI：`scripts/check_runtime_catalog.py`
- 协议：[../Docs/protocol.md](../Docs/protocol.md)

改文件桥 / 动作后必须重装 Magisk 模块；仅更新 APK 不会更新守护进程。

App 侧可对 `actions`/protocol 做对齐检查（概览/诊断 banner）；协议与超时语义见 [Docs/protocol.md](../Docs/protocol.md) §9.3（含 `exec_shell_limited` / `capture_screen` 清除连接 deadline）。

## 任务并行 / Shell 监视 / 日志

- `runtime.yaml`：`max_concurrent_tasks`（默认 8）、`max_inflight_tasks`（默认 32）；超额 `task_submit` → `7005`
- 事件 `task_state_changed` 含 `active_tasks[]`；`exec_shell_limited` 返回 `job_id`，可用 `shell_job_list` / `shell_job_get`
- 审计 JSONL：约 10MB 轮转并保留最近 3 个分片；`service.sh` 在服务日志 >5MB 时轮转
- `log_level`：`debug|info|warn|error`（ERROR 始终输出）

## 设备验证

```sh
adb shell su -c 'sh /data/adb/modules/clawruntime/verify.sh'
# 诊断快照：/data/adb/modules/clawruntime/webroot/status.json
```

验收清单：[../Docs/真机安装验收清单.md](../Docs/真机安装验收清单.md)。
