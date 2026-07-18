# 工具调用规范

1. 先用 `list_tools` / `get_tool` 确认权限层级（None/Basic/Accessibility/AdbShizuku/Root）与约束。
2. 缺权限时停止并报告 `permission_denied` / `capability_missing`，不要猜测设备状态。
3. 破坏性工具（inject/shell/app_stop/file_write）需有明确用户目标。
4. 文件：沙箱路径用 Basic；系统白名单路径走 Runtime。
5. 下载：`download_start` → `download_status` → `download_verify`。
