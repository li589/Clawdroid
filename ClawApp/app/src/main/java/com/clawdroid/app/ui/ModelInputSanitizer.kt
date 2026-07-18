package com.clawdroid.app.ui

import java.net.URI

/**
 * 模型接入输入消毒：拦截换行注入、危险 scheme、过长字段等。
 * 不做网络侧 SSRF 阻断（用户可能合法使用局域网网关），但拒绝明显危险协议与控制字符。
 */
internal object ModelInputSanitizer {
    private const val MAX_URL_CHARS = 2_048
    private const val MAX_KEY_CHARS = 8_192
    private const val MAX_MODEL_CHARS = 256
    private const val MAX_PATH_CHARS = 512

    private val CONTROL_OR_SEPARATOR = Regex("[\\u0000-\\u001F\\u007F\\r\\n\\t]")
    private val DANGEROUS_SCHEMES = setOf(
        "javascript", "data", "file", "content", "about", "blob", "ws", "wss"
    )

    data class SanitizedModelSettings(
        val settings: ModelSettings,
        val warnings: List<String> = emptyList()
    )

    fun sanitize(settings: ModelSettings): SanitizedModelSettings {
        val warnings = mutableListOf<String>()
        val baseUrl = sanitizeUrl(settings.baseUrl, field = "API Base URL", warnings)
        val localEndpoint = sanitizeUrl(settings.localEndpoint, field = "本地接口", warnings, allowEmpty = true)
        val apiKey = sanitizeSecret(settings.apiKey, field = "API Key", warnings)
        val modelName = sanitizeToken(settings.modelName, MAX_MODEL_CHARS, "模型名称", warnings)
        val localModelName = sanitizeToken(settings.localModelName, MAX_MODEL_CHARS, "本地模型", warnings)
        val customApiPath = sanitizePath(settings.customApiPath, warnings)
        val proxySettings = sanitizeProxy(settings.proxySettings, warnings)

        return SanitizedModelSettings(
            settings = settings.copy(
                baseUrl = baseUrl,
                localEndpoint = localEndpoint.ifBlank { "http://127.0.0.1:11434/v1" },
                apiKey = apiKey,
                modelName = modelName,
                localModelName = localModelName,
                customApiPath = customApiPath.ifBlank { "/chat/completions" },
                proxySettings = proxySettings
            ),
            warnings = warnings
        )
    }

    fun validationError(settings: ModelSettings): String? {
        val result = sanitize(settings)
        if (result.warnings.isNotEmpty()) {
            return result.warnings.first()
        }
        val s = result.settings
        if (s.proxySettings.isCustomProxy()) {
            if (s.proxySettings.host.isBlank()) return "代理主机不能为空"
            if (s.proxySettings.port !in 1..65535) return "代理端口无效"
        }
        return when (s.provider) {
            ModelProvider.Local -> when {
                s.localEndpoint.isBlank() -> "本地接口地址不能为空"
                !isHttpUrl(s.localEndpoint) -> "本地接口必须是 http/https URL"
                s.localModelName.isBlank() -> "本地模型名称不能为空"
                else -> null
            }
            ModelProvider.Custom -> when {
                s.baseUrl.isBlank() -> "API URL 不能为空"
                !isHttpUrl(s.baseUrl) -> "API URL 必须是 http/https"
                s.modelName.isBlank() -> "模型名称不能为空"
                else -> null
            }
            else -> when {
                s.baseUrl.isBlank() -> "API Base URL 不能为空"
                !isHttpUrl(s.baseUrl) -> "API Base URL 必须是 http/https"
                s.modelName.isBlank() -> "模型名称不能为空"
                s.apiKey.isBlank() -> "API Key 不能为空"
                else -> null
            }
        }
    }

    fun sanitizeUrl(
        raw: String,
        field: String = "URL",
        warnings: MutableList<String> = mutableListOf(),
        allowEmpty: Boolean = false
    ): String {
        var value = stripControls(raw).trim().trim('"', '\'')
        if (value.isEmpty()) {
            if (!allowEmpty) warnings += "$field 不能为空"
            return ""
        }
        if (value.length > MAX_URL_CHARS) {
            warnings += "$field 过长"
            value = value.take(MAX_URL_CHARS)
        }
        // 阻止把换行塞进 URL 造成请求走私/头注入
        if (value.contains('\n') || value.contains('\r')) {
            warnings += "$field 含有非法换行"
            value = value.replace("\r", "").replace("\n", "")
        }
        val uri = runCatching { URI(value) }.getOrElse {
            warnings += "$field 不是合法 URL"
            return value
        }
        val scheme = uri.scheme?.lowercase().orEmpty()
        when {
            scheme.isBlank() -> {
                // 允许用户只填 host/path，后面由 URL builder 处理；这里补 https
                value = "https://${value.trimStart('/')}"
            }
            scheme in DANGEROUS_SCHEMES -> {
                warnings += "$field 使用了不安全协议: $scheme"
                return ""
            }
            scheme != "http" && scheme != "https" -> {
                warnings += "$field 仅支持 http/https"
                return ""
            }
        }
        // 拒绝奇怪的 userinfo 注入
        if (!uri.rawUserInfo.isNullOrBlank()) {
            warnings += "$field 不应包含用户名密码片段"
            val cleaned = runCatching {
                URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
            }.getOrDefault(value)
            value = cleaned
        }
        return value.trimEnd()
    }

    fun sanitizeSecret(
        raw: String,
        field: String = "Secret",
        warnings: MutableList<String> = mutableListOf()
    ): String {
        var value = stripControls(raw).trim()
        if (value.length > MAX_KEY_CHARS) {
            warnings += "$field 过长"
            value = value.take(MAX_KEY_CHARS)
        }
        return value
    }

    fun sanitizeToken(
        raw: String,
        maxChars: Int,
        field: String,
        warnings: MutableList<String> = mutableListOf()
    ): String {
        var value = stripControls(raw).trim()
        if (value.length > maxChars) {
            warnings += "$field 过长"
            value = value.take(maxChars)
        }
        return value
    }

    fun sanitizePath(
        raw: String,
        warnings: MutableList<String> = mutableListOf()
    ): String {
        var value = stripControls(raw).trim()
        if (value.length > MAX_PATH_CHARS) {
            warnings += "自定义路径过长"
            value = value.take(MAX_PATH_CHARS)
        }
        // 阻止绝对 URL 塞进 path，以及 schema 注入
        if (value.contains("://") || value.startsWith("//")) {
            warnings += "自定义路径不能是完整 URL"
            value = "/chat/completions"
        }
        while (value.contains("..")) {
            warnings += "自定义路径不能包含 .."
            value = value.replace("..", "")
        }
        if (value.isNotBlank() && !value.startsWith("/")) {
            value = "/$value"
        }
        return value.ifBlank { "/chat/completions" }
    }

    fun sanitizeProxy(
        proxy: NetworkProxySettings,
        warnings: MutableList<String> = mutableListOf()
    ): NetworkProxySettings {
        val hostRaw = sanitizeToken(proxy.host, 253, "代理主机", warnings).trim('.')
        val host = when {
            hostRaw.contains("://") -> {
                warnings += "代理主机不能是完整 URL"
                "127.0.0.1"
            }
            hostRaw.isBlank() -> "127.0.0.1"
            else -> hostRaw
        }
        val port = proxy.port.coerceIn(1, 65535)
        val username = sanitizeSecret(proxy.username, "代理用户名", warnings).take(128)
        val password = sanitizeSecret(proxy.password, "代理密码", warnings).take(256)
        return proxy.copy(
            host = host,
            port = port,
            username = username,
            password = password
        )
    }

    private fun stripControls(raw: String): String {
        return CONTROL_OR_SEPARATOR.replace(raw, "")
    }

    private fun isHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }
}
