# 截图与文件检查

截图、预览、读写白名单文件时使用。

## 截图

- 用户要「看看」时：`capture_screen` 且 `read_after_capture=true`
- 或 `run_agent` + `capture_then_preview`

## 文件

- 列目录用 `file_list`（支持 offset/limit 分页），不要用多次 `file_stat` 摸结构
- 读写用 `file_read` / `file_write` / `file_replace`；单文件元信息再用 `file_stat`
- 沙箱路径 Basic；系统白名单路径需 Runtime
- `read_file_limited` 仅兼容旧调用

## 规则

- 截图成功不等于预览成功；两边输出都要看。
