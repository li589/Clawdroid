# 设备 UI 自动化

点击、滑动、页面确认，或通过无障碍 + Runtime inject 驱动界面时使用。

## 安全点击路径

1. `page_confirm`（期望 package / text / viewId）
2. `click_precheck`
3. `safe_tap`（使用解析到的坐标）

## 直接注入

- 已知坐标或按键时用 `inject_tap` / `inject_swipe` / `inject_keyevent`
- `inject_keyevent` 优先命名键：`BACK` / `HOME` / `ENTER`

## 规则

- 有无障碍时优先 `safe_tap`，避免盲点。
- `page_confirm` 失败则不要继续点击。
- 多步流程优先 `run_agent`（如 `confirm_then_safe_tap`、`swipe_then_capture`）。
