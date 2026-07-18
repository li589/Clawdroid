package com.clawdroid.app.tools

/**
 * Permission tiers for the structured tool catalog.
 * Finer grants refine what a tier needs beyond the coarse level.
 */
enum class ToolPermissionTier(val displayName: String) {
    None("无权限"),
    Basic("基本权限"),
    Accessibility("无障碍权限"),
    AdbShizuku("Shizuku/ADB 级权限"),
    Root("Root 级权限")
}

enum class ToolPermissionGrant {
    STORAGE_READ,
    STORAGE_WRITE,
    INTERNET,
    PACKAGE_QUERY,
    PACKAGE_LAUNCH,
    NOTIFICATION_ACCESS,
    ACCESSIBILITY_SERVICE,
    SHELL_ALLOWLIST,
    FILE_BRIDGE_READ,
    FILE_BRIDGE_WRITE,
    INPUT_INJECT,
    SCREEN_CAPTURE,
    RUNTIME_IPC,
    ASSIST_MCP_CLIENT,
    SHIZUKU,
    CAMERA
}

enum class ToolBackend {
    Local,
    Runtime,
    Hybrid,
    Assist
}

enum class ToolRisk {
    Read,
    Write,
    Destructive
}

enum class ToolAvailability {
    Available,
    Planned
}

/**
 * Rich metadata for discovery, MCP export, and pre-execute gates.
 */
data class ClawToolSpec(
    val tool: ClawTool,
    val tier: ToolPermissionTier,
    val grants: Set<ToolPermissionGrant> = emptySet(),
    val backend: ToolBackend = ToolBackend.Local,
    val risk: ToolRisk = ToolRisk.Read,
    val tags: Set<String> = emptySet(),
    val callNotes: String = "",
    val constraints: String = "",
    val requiredCapabilities: Set<String> = emptySet(),
    val examples: List<String> = emptyList(),
    val status: ToolAvailability = ToolAvailability.Available,
    val deprecatedBy: String? = null
) {
    val id: String get() = tool.toolId
    val displayName: String get() = tool.displayName
    val summary: String get() = tool.description
}

data class ToolGateDecision(
    val allowed: Boolean,
    val errorCode: String? = null,
    val message: String = ""
)
