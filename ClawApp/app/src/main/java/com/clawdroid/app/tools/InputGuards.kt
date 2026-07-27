package com.clawdroid.app.tools

/**
 * Shared edge validation for chat / tool / MCP inputs.
 * Defense-in-depth: Runtime still enforces shell templates and file allowlists.
 */
object InputGuards {
    const val MAX_PROMPT_CHARS = 8_192
    const val MAX_TOOL_STRING_CHARS = 4_096
    const val MAX_SHELL_COMMAND_CHARS = 512
    const val MAX_PATH_CHARS = 1_024
    const val MAX_FILE_WRITE_CHARS = 1_024 * 1_024
    const val MAX_FILE_REPLACE_FIND_CHARS = 16_384

    data class ValidationError(
        val code: String,
        val message: String
    )

    fun rejectNullOrControl(text: String, field: String = "input"): ValidationError? {
        if (text.indexOf('\u0000') >= 0) {
            return ValidationError("null_byte", "$field 包含非法空字节")
        }
        // Reject C0 controls except tab/LF/CR for multi-line text fields.
        for (ch in text) {
            val code = ch.code
            if (code in 0x00..0x1F && ch != '\t' && ch != '\n' && ch != '\r') {
                return ValidationError("control_char", "$field 包含非法控制字符")
            }
            if (code == 0x7F) {
                return ValidationError("control_char", "$field 包含非法控制字符")
            }
        }
        return null
    }

    fun sanitizePromptInput(raw: String): Pair<String, ValidationError?> {
        if (raw.indexOf('\u0000') >= 0) {
            return "" to ValidationError("null_byte", "输入包含非法空字节")
        }
        val cleaned = buildString(raw.length.coerceAtMost(MAX_PROMPT_CHARS)) {
            for (ch in raw) {
                val code = ch.code
                if (code in 0x00..0x08 || code == 0x0B || code == 0x0C ||
                    code in 0x0E..0x1F || code == 0x7F
                ) {
                    continue
                }
                append(ch)
                if (length >= MAX_PROMPT_CHARS) break
            }
        }
        val truncated = cleaned.length >= MAX_PROMPT_CHARS && raw.length > MAX_PROMPT_CHARS
        return cleaned to if (truncated) {
            ValidationError("truncated", "输入已截断至 $MAX_PROMPT_CHARS 字符")
        } else {
            null
        }
    }

    fun validatePromptForSubmit(text: String): ValidationError? {
        rejectNullOrControl(text, "prompt")?.let { return it }
        if (text.trim().length > MAX_PROMPT_CHARS) {
            return ValidationError(
                "too_long",
                "输入内容过长，请控制在 $MAX_PROMPT_CHARS 字符以内"
            )
        }
        return null
    }

    /**
     * Align with ClawRuntime exec_shell_limited whitelist:
     * allow exact templates + parameterized `pm path` / `dumpsys package` / `pidof` / `am force-stop`.
     * Reject shell metacharacters only when the command is not an allowlisted form.
     */
    fun validateShellCommand(command: String): ValidationError? {
        rejectNullOrControl(command, "command")?.let { return it }
        val normalized = normalizeShellCommand(command)
        if (normalized.isEmpty()) {
            return ValidationError("missing_command", "command 不能为空")
        }
        if (normalized.length > MAX_SHELL_COMMAND_CHARS) {
            return ValidationError(
                "command_too_long",
                "command 过长（最多 $MAX_SHELL_COMMAND_CHARS 字符）"
            )
        }
        if (isAllowedLimitedShellCommand(normalized)) {
            return null
        }
        if (SHELL_METACHAR.containsMatchIn(normalized)) {
            return ValidationError(
                "shell_metachar",
                "command 含管道/重定向等元字符且不在受限白名单。" +
                    "检测 Termux 请用: pm path com.termux 或 pm list packages com.termux；" +
                    "不要用 grep/管道。常用: wm size / id / getenforce / pidof <name>"
            )
        }
        return ValidationError(
            "shell_not_allowlisted",
            "command 不在 execute_shell_limited 白名单。" +
                "可用: wm size, pm path <pkg>, pm list packages <filter>, " +
                "ls /data/data/<pkg>, dumpsys package <pkg>, pidof <name>, id, getenforce 等"
        )
    }

    fun normalizeShellCommand(command: String): String {
        return command.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Mirrors ClawRuntime resolveShellTemplate allowlist (fixed + parameterized).
     */
    fun isAllowedLimitedShellCommand(command: String): Boolean {
        val normalized = normalizeShellCommand(command)
        if (normalized in FIXED_SHELL_COMMANDS) return true
        return PARAMETERIZED_SHELL_SPECS.any { spec ->
            if (!normalized.startsWith(spec.prefix)) return@any false
            val rest = normalized.removePrefix(spec.prefix).trim()
            spec.accept(rest)
        }
    }

    fun validatePath(path: String): ValidationError? {
        rejectNullOrControl(path, "path")?.let { return it }
        val trimmed = path.trim()
        if (trimmed.isEmpty()) {
            return ValidationError("missing_path", "path 不能为空")
        }
        if (trimmed.length > MAX_PATH_CHARS) {
            return ValidationError("path_too_long", "path 过长（最多 $MAX_PATH_CHARS 字符）")
        }
        if (trimmed.contains("..")) {
            return ValidationError("path_traversal", "path 不允许包含 ..")
        }
        return null
    }

    fun validateFileWriteContent(content: String): ValidationError? {
        if (content.indexOf('\u0000') >= 0) {
            return ValidationError("null_byte", "content 包含非法空字节")
        }
        if (content.length > MAX_FILE_WRITE_CHARS) {
            return ValidationError(
                "content_too_large",
                "写入内容过大（最多 ${MAX_FILE_WRITE_CHARS / 1024} KiB）"
            )
        }
        return null
    }

    fun validateReplaceFind(find: String): ValidationError? {
        if (find.isEmpty()) {
            return ValidationError("empty_find", "find 不能为空")
        }
        if (find.indexOf('\u0000') >= 0) {
            return ValidationError("null_byte", "find 包含非法空字节")
        }
        if (find.length > MAX_FILE_REPLACE_FIND_CHARS) {
            return ValidationError("find_too_long", "find 过长")
        }
        return null
    }

    fun validateToolStringArg(value: String, field: String): ValidationError? {
        rejectNullOrControl(value, field)?.let { return it }
        if (value.length > MAX_TOOL_STRING_CHARS) {
            return ValidationError(
                "arg_too_long",
                "$field 过长（最多 $MAX_TOOL_STRING_CHARS 字符）"
            )
        }
        return null
    }

    fun toToolResult(error: ValidationError): ClawToolCallResult {
        return ClawToolCallResult(
            success = false,
            output = "失败: ${error.message}",
            error = error.code
        )
    }

    private data class ParameterizedShellSpec(
        val prefix: String,
        val accept: (String) -> Boolean
    )

    private val ANDROID_PACKAGE = Regex("""^[A-Za-z]\w*(\.[A-Za-z]\w*)+$""")
    private val PACKAGE_FILTER = Regex("""^[A-Za-z0-9._]+$""")
    private val SHELL_METACHAR = Regex("""[;|&`$<>]""")

    private val FIXED_SHELL_COMMANDS = setOf(
        "cmd overlay list",
        "dumpsys activity top",
        "dumpsys window windows",
        "dumpsys activity activities",
        "dumpsys notification",
        "getenforce",
        "getprop ro.build.version.release",
        "getprop ro.build.version.sdk",
        "getprop ro.hardware",
        "getprop ro.product.manufacturer",
        "getprop ro.product.model",
        "id",
        "ls /data/adb/modules/clawruntime",
        "cat /data/adb/modules/clawruntime/webroot/status.json",
        "cat /data/adb/modules/clawruntime/webroot/verify.json",
        "pidof clawdroid-runtime",
        "settings get secure accessibility_enabled",
        "settings get secure enabled_accessibility_services",
        "wm density",
        "wm size",
        "cat /proc/uptime",
        "df /data",
        "pm list packages -3",
        // Safe Termux presence checks (no shell metacharacters).
        "pm path com.termux",
        "pm list packages com.termux",
        "cmd package path com.termux",
        "ls /data/data/com.termux",
        "ls /data/data/com.termux/files",
        "ls /data/data/com.termux/files/usr/bin",
        "reboot",
        "svc power reboot"
    )

    private val PARAMETERIZED_SHELL_SPECS = listOf(
        ParameterizedShellSpec("am force-stop ") { ANDROID_PACKAGE.matches(it) },
        ParameterizedShellSpec("pm path ") { ANDROID_PACKAGE.matches(it) },
        ParameterizedShellSpec("cmd package path ") { ANDROID_PACKAGE.matches(it) },
        ParameterizedShellSpec("dumpsys package ") { ANDROID_PACKAGE.matches(it) },
        ParameterizedShellSpec("pm list packages ") { rest ->
            rest.isNotEmpty() &&
                !rest.startsWith("-") &&
                PACKAGE_FILTER.matches(rest) &&
                !SHELL_METACHAR.containsMatchIn(rest)
        },
        ParameterizedShellSpec("ls /data/data/") { rest ->
            ANDROID_PACKAGE.matches(rest) && !rest.contains("..") && !rest.contains('/')
        },
        ParameterizedShellSpec("pidof ") { rest ->
            rest.isNotEmpty() &&
                !rest.startsWith("-") &&
                !rest.any { it.isWhitespace() } &&
                !SHELL_METACHAR.containsMatchIn(rest) &&
                !rest.contains('(') &&
                !rest.contains(')') &&
                !rest.contains('{') &&
                !rest.contains('}') &&
                !rest.contains('\\')
        }
    )
}
