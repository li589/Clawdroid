package com.clawdroid.app.xposed

import java.io.File

/**
 * Whitelist config for LSPosed target adapters.
 *
 * Device override file (editable with root):
 * `/data/local/tmp/clawdroid/xposed/adapters.json`
 *
 * Missing, unreadable, or invalid files fall back to built-in defaults.
 * Adapter IDs not recognized by [AdapterRegistry] are ignored (not installed).
 */
internal data class AdapterVersionGate(
    val minVersionCode: Long? = null,
    val maxVersionCode: Long? = null
) {
    fun allows(versionCode: Long?): Boolean {
        if (versionCode == null) return true
        if (minVersionCode != null && versionCode < minVersionCode) return false
        if (maxVersionCode != null && versionCode > maxVersionCode) return false
        return true
    }
}

internal data class XposedAdapterConfig(
    val version: Int = 1,
    val enabledAdapters: Set<String> = DEFAULT_ENABLED_ADAPTERS,
    val activityFocusPackages: Set<String> = DEFAULT_ACTIVITY_FOCUS_PACKAGES,
    val settingsPackages: Set<String> = DEFAULT_SETTINGS_PACKAGES,
    val launcherPackages: Set<String> = DEFAULT_LAUNCHER_PACKAGES,
    val browserPackages: Set<String> = DEFAULT_BROWSER_PACKAGES,
    val wechatPackages: Set<String> = DEFAULT_WECHAT_PACKAGES,
    val fuseAfterFailures: Int = DEFAULT_FUSE_AFTER_FAILURES,
    val versionGates: Map<String, AdapterVersionGate> = emptyMap()
) {
    fun isAdapterEnabled(adapterId: String): Boolean = adapterId in enabledAdapters

    fun versionGateFor(adapterId: String): AdapterVersionGate? = versionGates[adapterId]

    fun toJson(): String {
        return buildString {
            append('{')
            append("\"version\":").append(version)
            append(",\"fuse_after_failures\":").append(fuseAfterFailures)
            append(",\"enabled_adapters\":").append(jsonStringArray(enabledAdapters.sorted()))
            append(",\"activity_focus_packages\":").append(jsonStringArray(activityFocusPackages.sorted()))
            append(",\"settings_packages\":").append(jsonStringArray(settingsPackages.sorted()))
            append(",\"launcher_packages\":").append(jsonStringArray(launcherPackages.sorted()))
            append(",\"browser_packages\":").append(jsonStringArray(browserPackages.sorted()))
            append(",\"wechat_packages\":").append(jsonStringArray(wechatPackages.sorted()))
            if (versionGates.isNotEmpty()) {
                append(",\"adapter_version_gates\":{")
                var first = true
                for ((id, gate) in versionGates.toSortedMap()) {
                    if (!first) append(',')
                    first = false
                    append('"').append(escape(id)).append("\":{")
                    var innerFirst = true
                    if (gate.minVersionCode != null) {
                        append("\"min_version_code\":").append(gate.minVersionCode)
                        innerFirst = false
                    }
                    if (gate.maxVersionCode != null) {
                        if (!innerFirst) append(',')
                        append("\"max_version_code\":").append(gate.maxVersionCode)
                    }
                    append('}')
                }
                append('}')
            }
            append('}')
        }
    }

    companion object {
        const val CONFIG_FILE_NAME = "adapters.json"
        const val DEFAULT_FUSE_AFTER_FAILURES = 5

        val DEFAULT_ENABLED_ADAPTERS: Set<String> = setOf(
            "self_runtime_marker",
            "activity_focus",
            "settings_detail",
            "launcher_focus",
            "browser_detail"
        )

        val DEFAULT_ACTIVITY_FOCUS_PACKAGES: Set<String> = setOf(
            "com.android.settings",
            "com.android.systemui"
        )

        val DEFAULT_SETTINGS_PACKAGES: Set<String> = setOf(
            "com.android.settings"
        )

        val DEFAULT_BROWSER_PACKAGES: Set<String> = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.android.browser",
            "com.sec.android.app.sbrowser",
            "com.mi.globalbrowser",
            "com.huawei.browser",
            "com.microsoft.emmx",
            "org.mozilla.firefox",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.UCMobile",
            "com.uc.browser.en",
            "com.vivo.browser",
            "com.coloros.browser",
            "com.heytap.browser",
            "com.hihonor.browserservice",
            "com.tencent.mtt"
        )

        /** Present in seed config; adapter itself is opt-in (not in DEFAULT_ENABLED_ADAPTERS). */
        val DEFAULT_WECHAT_PACKAGES: Set<String> = setOf(
            "com.tencent.mm"
        )

        val DEFAULT_LAUNCHER_PACKAGES: Set<String> = setOf(
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.sec.android.app.launcher",
            "com.oppo.launcher",
            "com.bbk.launcher2",
            "com.android.launcher"
        )

        @Volatile
        internal var configFileOverride: File? = null

        fun defaults(): XposedAdapterConfig = XposedAdapterConfig()

        fun load(): XposedAdapterConfig {
            return runCatching {
                val file = configFile()
                if (!file.exists()) {
                    defaults()
                } else {
                    parse(file.readText()).getOrElse { defaults() }
                }
            }.getOrElse { defaults() }
        }

        fun parse(raw: String): Result<XposedAdapterConfig> = runCatching {
            val normalized = raw.trim()
            require(normalized.startsWith("{") && normalized.endsWith("}")) {
                "adapters.json must be a JSON object"
            }
            val version = extractJsonLong(normalized, "version")?.toInt() ?: 1
            val fuse = extractJsonLong(normalized, "fuse_after_failures")?.toInt()
                ?: DEFAULT_FUSE_AFTER_FAILURES
            val enabled = extractJsonStringArray(normalized, "enabled_adapters")
                ?.toSet()
                ?: DEFAULT_ENABLED_ADAPTERS
            val activityFocus = extractJsonStringArray(normalized, "activity_focus_packages")
                ?.toSet()
                ?: DEFAULT_ACTIVITY_FOCUS_PACKAGES
            val settings = extractJsonStringArray(normalized, "settings_packages")
                ?.toSet()
                ?: DEFAULT_SETTINGS_PACKAGES
            val launchers = extractJsonStringArray(normalized, "launcher_packages")
                ?.toSet()
                ?: DEFAULT_LAUNCHER_PACKAGES
            val browsers = extractJsonStringArray(normalized, "browser_packages")
                ?.toSet()
                ?: DEFAULT_BROWSER_PACKAGES
            val wechat = extractJsonStringArray(normalized, "wechat_packages")
                ?.toSet()
                ?: DEFAULT_WECHAT_PACKAGES
            val gates = parseVersionGates(normalized)
            XposedAdapterConfig(
                version = version,
                enabledAdapters = enabled,
                activityFocusPackages = activityFocus,
                settingsPackages = settings,
                launcherPackages = launchers,
                browserPackages = browsers,
                wechatPackages = wechat,
                fuseAfterFailures = fuse.coerceIn(1, 100),
                versionGates = gates
            )
        }

        fun writeDefaultsIfMissing(): Boolean {
            if (hasReadableConfig()) {
                return false
            }
            return write(defaults())
        }

        fun ensureDefaultsSeeded(rootCommand: ((String) -> Boolean)? = null): Boolean {
            if (hasReadableConfig()) {
                return false
            }
            if (write(defaults()) && hasReadableConfig()) {
                return true
            }
            if (rootCommand == null) {
                return false
            }
            val ok = rootCommand(buildSeedRootCommand())
            return ok && hasReadableConfig()
        }

        fun hasReadableConfig(): Boolean {
            return runCatching {
                val file = configFile()
                file.isFile && file.canRead() && file.length() > 0L
            }.getOrDefault(false)
        }

        fun buildSeedRootCommand(config: XposedAdapterConfig = defaults()): String {
            val file = configFile()
            val dir = file.parentFile?.absolutePath ?: XposedPaths.DIR
            val path = file.absolutePath
            val json = config.toJson()
            return buildString {
                append("mkdir -p ")
                append(shellQuote(dir))
                append(" && cat > ")
                append(shellQuote(path))
                append(" <<'CLAWDROID_EOF'\n")
                append(json)
                append("\nCLAWDROID_EOF && chmod 0644 ")
                append(shellQuote(path))
            }
        }

        fun write(config: XposedAdapterConfig): Boolean {
            return runCatching {
                val file = configFile()
                val parent = file.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                file.writeText(config.toJson())
                true
            }.getOrDefault(false)
        }

        fun summaryForProbe(config: XposedAdapterConfig = load()): String {
            val focusCount = config.activityFocusPackages.size
            val settingsCount = config.settingsPackages.size
            val launcherCount = config.launcherPackages.size
            val browserCount = config.browserPackages.size
            val wechatCount = config.wechatPackages.size
            val enabled = config.enabledAdapters.sorted().joinToString(",")
            val seeded = if (hasReadableConfig()) "file" else "builtin"
            val gates = config.versionGates.size
            val wechatOn = if (config.isAdapterEnabled("wechat_detail")) "on" else "off"
            return "adapters=$enabled; focus=$focusCount; settings=$settingsCount; launcher=$launcherCount; browser=$browserCount; wechat=$wechatCount/$wechatOn; fuse=${config.fuseAfterFailures}; gates=$gates; source=$seeded"
        }

        internal fun configFile(): File {
            return configFileOverride
                ?: File(XposedPaths.DIR, CONFIG_FILE_NAME)
        }

        private fun parseVersionGates(raw: String): Map<String, AdapterVersionGate> {
            val startKey = Regex("\"adapter_version_gates\"\\s*:\\s*\\{").find(raw) ?: return emptyMap()
            val bodyStart = startKey.range.last + 1
            val body = extractBalancedObjectBody(raw, bodyStart) ?: return emptyMap()
            val out = linkedMapOf<String, AdapterVersionGate>()
            var index = 0
            while (index < body.length) {
                val idMatch = Regex("\"((?:\\\\.|[^\\\\\"])*)\"\\s*:\\s*\\{")
                    .find(body, index)
                    ?: break
                val id = unescape(idMatch.groupValues[1])
                val gateStart = idMatch.range.last + 1
                val gateBody = extractBalancedObjectBody(body, gateStart) ?: break
                val min = extractJsonLong("{$gateBody}", "min_version_code")
                val max = extractJsonLong("{$gateBody}", "max_version_code")
                if (min != null || max != null) {
                    out[id] = AdapterVersionGate(minVersionCode = min, maxVersionCode = max)
                }
                index = gateStart + gateBody.length + 1
            }
            return out
        }

        /** [openBraceIndex] points at the first char inside `{...}` (after `{`). */
        private fun extractBalancedObjectBody(raw: String, openBraceIndex: Int): String? {
            var depth = 1
            var i = openBraceIndex
            var inString = false
            var escape = false
            while (i < raw.length) {
                val c = raw[i]
                when {
                    escape -> escape = false
                    inString && c == '\\' -> escape = true
                    c == '"' -> inString = !inString
                    !inString && c == '{' -> depth++
                    !inString && c == '}' -> {
                        depth--
                        if (depth == 0) {
                            return raw.substring(openBraceIndex, i)
                        }
                    }
                }
                i++
            }
            return null
        }

        private fun shellQuote(value: String): String {
            return "'" + value.replace("'", "'\"'\"'") + "'"
        }

        private fun jsonStringArray(values: List<String>): String {
            return values.joinToString(prefix = "[", postfix = "]") { value ->
                "\"${escape(value)}\""
            }
        }

        private fun extractJsonLong(raw: String, key: String): Long? {
            return Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull()
        }

        private fun extractJsonStringArray(raw: String, key: String): List<String>? {
            val match = Regex("\"$key\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
                .find(raw)
                ?: return null
            val body = match.groupValues[1]
            return Regex("\"((?:\\\\.|[^\\\\\"])*)\"")
                .findAll(body)
                .map { unescape(it.groupValues[1]) }
                .toList()
        }

        private fun escape(value: String): String {
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
        }

        private fun unescape(value: String): String {
            return value
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
    }
}

internal object XposedPaths {
    const val DIR = "/data/local/tmp/clawdroid/xposed"
}
