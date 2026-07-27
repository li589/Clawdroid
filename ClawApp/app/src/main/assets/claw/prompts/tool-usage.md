# 工具调用规范

1. 先用 `list_tools` / `get_tool` 确认权限层级（None / Basic / Accessibility / AdbShizuku / Root）与约束。
2. 缺权限时停止并报告 `permission_denied` / `capability_missing`，不要猜测设备状态。
3. 破坏性工具（`inject_*` / `execute_shell_limited` / `app_stop` / `file_write`）需有明确用户目标。
4. 文件：列目录用 `file_list`（可分页），不要用多次 `file_stat` 代替；读写用 `file_read`/`file_write`/`file_replace`；单文件元信息再用 `file_stat`。沙箱路径用 Basic；系统白名单路径走 Runtime。
5. 下载：`download_start` → `download_status` → `download_verify`。
6. UI 点击：有无障碍时优先 `page_confirm` → `click_precheck` → `safe_tap`，避免盲点。
7. 事件流：`subscribe_events` 的 `operation` 为 `start` 或 `stop`，勿重复订阅。
8. Runtime 任务：`task_submit` 后用 `task_get` / `task_list` 跟踪；取消用 `task_cancel`。提交时务必带 `task_id`（或让 App 自动补全）。
9. **重启设备**：只用 `execute_shell_limited`，`command` 为 `reboot` 或 `svc power reboot`。成功即表示已接受重启；**禁止**用 `task_submit` 包装重启，也**不要**再 `task_get`（重启后任务注册表会清空）。
10. **检测 Termux 是否安装**：用 `execute_shell_limited` 的 `pm path com.termux` 或 `pm list packages com.termux`，**禁止** `grep`/管道/重定向。真正在 Termux 里跑命令用 `termux_exec`（需 RUN_COMMAND 权限 + allow-external-apps=true）。
    - 简单命令：`termux_exec` 的 `command` 如 `pwd`、`pkg install proot-distro -y`、`proot-distro list`。
    - 需要管道/重定向时：用 `bash -lc '…'`（例如 `bash -lc 'proot-distro install ubuntu'`）。
    - **装发行版**：`bash -lc 'proot-distro install ubuntu'`，并设 `timeout_ms`≥300000（安装常需数分钟）。App 对 install/update 会自动拉长超时。
    - 安装中若超时：后台可能仍在下；**禁止**立刻再 install、**禁止**删 `*.lock`。先 `proot-distro list`，再看 `…/containers/<name>/rootfs/bin` 是否存在；空 rootfs 则 `proot-distro remove <name>` 后重装。
    - 验证容器：`bash -lc 'proot-distro login ubuntu -- /bin/bash -lc "cat /etc/os-release | head -3"'`（rootfs 完整后才行）。
    - 若 errmsg 含 `allow-external-apps` 或 error=`termux_allow_external_apps`：**立刻停止重试**，告知用户去「设置 → Termux 与 Shell」点「Root 授权并检查」。这与 RUN_COMMAND 权限是两回事。
    - error=`termux_timeout`：**不要**当成 allow-external-apps；加长超时或告知用户等待/清理坏容器。
    - `execute_shell_limited` **不能**替代 Termux 安装 Linux。
11. 大二进制不要塞进 MCP JSON-RPC；用下载或电脑侧落盘后再读。
