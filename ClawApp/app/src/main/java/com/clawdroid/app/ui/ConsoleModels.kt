package com.clawdroid.app.ui

internal enum class ConsolePage {
    Overview,
    Chat,
    Settings
}

// ---------------------------------------------------------------------------
// 设置导航
// ---------------------------------------------------------------------------
internal enum class SettingsCategoryId(val title: String, val subtitle: String) {
    Appearance("外观", "主题与显示"),
    Model("模型接入", "供应商、密钥与上下文参数"),
    AgentTools("Agent 与工具", "循环步数、API 预算、权限与命令审查"),
    PromptsSkills("提示词与 Skills", "编排文案与技能包"),
    AssistMcp("协助 MCP", "电脑侧 MCP 桥接"),
    TermuxShell("Termux 与 Shell", "终端会话与沙箱"),
    Diagnostics("运行诊断", "探测、权限与就绪状态")
}

internal sealed interface SettingsNav {
    data object Hub : SettingsNav
    data class Category(val id: SettingsCategoryId) : SettingsNav
}
