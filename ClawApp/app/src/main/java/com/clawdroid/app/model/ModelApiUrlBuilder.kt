package com.clawdroid.app.model

import com.clawdroid.app.ui.ApiPathStyle
import com.clawdroid.app.ui.ModelProvider
import com.clawdroid.app.ui.ModelSettings
import com.clawdroid.app.ui.UrlPathMode
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * OpenAI / Anthropic / 中转站（NewAPI、OneAPI、硅基流动等）URL 与模型列表解析。
 *
 * 约定：
 * - Base URL 通常填到协议根，例如 `https://host/v1` 或 `https://host`（自动补 `/v1`）
 * - 聊天：`…/chat/completions` 或 `…/messages`
 * - 列表：`…/models`
 */
internal object ModelApiUrlBuilder {

    private val OPERATION_SUFFIXES = listOf(
        "/chat/completions",
        "/completions",
        "/messages",
        "/models",
        "/api/tags"
    )

    fun buildChatUrl(settings: ModelSettings): String {
        return when (settings.provider.apiPathStyle) {
            ApiPathStyle.Anthropic -> buildOperationUrl(settings, "/messages")
            ApiPathStyle.OpenAI, ApiPathStyle.Custom -> buildOperationUrl(settings, "/chat/completions")
        }
    }

    fun buildModelsUrlCandidates(settings: ModelSettings): List<String> {
        val primary = buildOperationUrl(settings, "/models")
        val candidates = linkedSetOf(primary)

        val root = normalizeApiRoot(settings)
        if (root.isNotBlank()) {
            candidates += "$root/models"
            // 部分中转站 / NewAPI 文档写「只填域名」，客户端再补 /v1
            if (!root.endsWith("/v1") && !root.contains("/v1/") && !root.endsWith("/v2") &&
                !root.endsWith("/v4") && !root.contains("/compatible-mode/")
            ) {
                candidates += "$root/v1/models"
            }
            // Groq / 部分网关前缀
            if (!root.contains("/openai/")) {
                candidates += "$root/openai/v1/models"
            }
        }

        // Ollama 原生标签接口（本地常见）
        ollamaTagsUrl(settings.resolvedEndpoint())?.let { candidates += it }

        return candidates.filter { it.isNotBlank() }
    }

    fun buildOperationUrl(settings: ModelSettings, operationPath: String): String {
        val op = normalizePath(operationPath)
        return when (settings.urlPathMode) {
            UrlPathMode.FullUrl -> {
                val base = settings.resolvedEndpoint().trimEnd('/')
                when {
                    base.isBlank() -> op
                    endsWithAny(base, OPERATION_SUFFIXES) ->
                        replaceOperationSuffix(base, op)
                    else -> joinUrl(normalizeApiRoot(settings), op)
                }
            }
            UrlPathMode.AppendCustom -> {
                val base = settings.resolvedEndpoint().trimEnd('/')
                if (op == "/models" || op == "/api/tags") {
                    val custom = settings.customApiPath.trim()
                    val modelsPath = modelsPathFromCustom(custom)
                    joinUrl(base, modelsPath)
                } else {
                    val customPath = settings.customApiPath.trimStart('/')
                    if (customPath.isBlank()) base else joinUrl(base, "/$customPath")
                }
            }
            UrlPathMode.AutoAppend -> {
                when (settings.provider.apiPathStyle) {
                    ApiPathStyle.Custom -> {
                        if (op == "/models") {
                            joinUrl(normalizeApiRoot(settings), "/models")
                        } else {
                            val customPath = settings.customApiPath.trimStart('/')
                            val base = settings.resolvedEndpoint().trimEnd('/')
                            if (customPath.isBlank()) base else joinUrl(base, "/$customPath")
                        }
                    }
                    ApiPathStyle.OpenAI, ApiPathStyle.Anthropic ->
                        joinUrl(normalizeApiRoot(settings), op)
                }
            }
        }
    }

    /**
     * 将用户填写的地址规范为「协议根」（不含 chat/completions、models 等）。
     * 对仅域名的 OpenAI 兼容地址自动补 `/v1`（符合大多数中转站文档）。
     */
    fun normalizeApiRoot(settings: ModelSettings): String {
        var base = settings.resolvedEndpoint().trim()
        if (base.isBlank()) return ""
        base = base.trimEnd('/')
        base = stripOperationSuffix(base)
        base = rewriteKnownHostRoots(base)

        return when (settings.provider.apiPathStyle) {
            ApiPathStyle.OpenAI -> ensureVersionedRoot(base, defaultVersion = "v1")
            ApiPathStyle.Anthropic -> ensureVersionedRoot(base, defaultVersion = "v1")
            ApiPathStyle.Custom -> base
        }
    }

    fun parseModelList(response: String): List<String> {
        val trimmed = response.trim()
        require(trimmed.isNotBlank()) { "模型列表响应为空" }

        // HTML / 网关错误页
        if (trimmed.startsWith("<") || trimmed.contains("<!DOCTYPE", ignoreCase = true)) {
            error("模型列表返回了 HTML 页面，请检查 Base URL 是否指向 API（通常以 /v1 结尾）")
        }

        if (trimmed.startsWith("[")) {
            val names = extractModelNames(JSONArray(trimmed))
            return names.distinct().sorted()
        }

        val root = runCatching { JSONObject(trimmed) }.getOrElse {
            error("无法解析模型列表 JSON: ${trimmed.take(160)}")
        }

        // OpenAI / NewAPI / SiliconFlow: { "data": [ { "id": "..." } ] }
        root.optJSONArray("data")?.let { data ->
            return extractModelNames(data).distinct().sorted()
        }

        // Ollama /api/tags: { "models": [ { "name": "llama3" } ] }
        root.optJSONArray("models")?.let { models ->
            return extractModelNames(models).distinct().sorted()
        }

        // 少数网关直接 { "model": "..." } 或 { "id": "..." }
        root.optString("id").takeIf { it.isNotBlank() }?.let { return listOf(it) }
        root.optString("name").takeIf { it.isNotBlank() }?.let { return listOf(it) }
        root.optString("model").takeIf { it.isNotBlank() }?.let { return listOf(it) }

        val err = root.optJSONObject("error")?.optString("message").orEmpty()
            .ifBlank { root.optString("error") }
            .ifBlank { root.optString("message") }
        if (err.isNotBlank()) {
            error(err)
        }
        error("响应中没有 data/models 列表字段: ${trimmed.take(200)}")
    }

    fun extractModelNames(array: JSONArray): List<String> {
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.opt(i) ?: continue
            when (value) {
                is String -> if (value.isNotBlank()) out += value
                is JSONObject -> {
                    val id = value.optString("id").takeIf { it.isNotBlank() }
                        ?: value.optString("name").takeIf { it.isNotBlank() }
                        ?: value.optString("model").takeIf { it.isNotBlank() }
                    if (id != null) out += id
                }
            }
        }
        return out
    }

    fun ollamaTagsUrl(endpoint: String): String? {
        val trimmed = endpoint.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        return try {
            val uri = URI(trimmed)
            if (uri.host.isNullOrBlank()) return null
            val scheme = uri.scheme ?: "http"
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "$scheme://${uri.host}$port/api/tags"
        } catch (_: Exception) {
            null
        }
    }

    private fun modelsPathFromCustom(custom: String): String {
        val path = custom.trim().trimStart('/')
        if (path.isBlank()) return "/models"
        val lower = path.lowercase()
        return when {
            lower.endsWith("chat/completions") ->
                "/" + path.dropLast("chat/completions".length).trimEnd('/') + "/models"
            lower.endsWith("messages") ->
                "/" + path.dropLast("messages".length).trimEnd('/') + "/models"
            lower.endsWith("models") -> "/$path"
            else -> "/models"
        }.replace("//", "/")
    }

    /**
     * 修正常见官方/聚合站「只填域名」时的错误 /v1 补全。
     * 例如 OpenRouter 需要 /api/v1，而不是 /v1。
     */
    private fun rewriteKnownHostRoots(base: String): String {
        val uri = runCatching { URI(base) }.getOrNull() ?: return base
        val host = uri.host?.lowercase() ?: return base
        val scheme = (uri.scheme ?: "https").lowercase()
        if (scheme != "http" && scheme != "https") return base
        val path = uri.path.orEmpty().trimEnd('/')
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val origin = "$scheme://$host$port"

        return when {
            host == "openrouter.ai" || host.endsWith(".openrouter.ai") -> when (path) {
                "", "/", "/v1", "/api" -> "$origin/api/v1"
                else -> base
            }
            host == "api.groq.com" -> when (path) {
                "", "/", "/v1", "/openai" -> "$origin/openai/v1"
                else -> base
            }
            host == "generativelanguage.googleapis.com" -> when (path) {
                "", "/", "/v1", "/v1beta" -> "$origin/v1beta/openai"
                else -> base
            }
            host == "dashscope.aliyuncs.com" -> when (path) {
                "", "/", "/v1", "/compatible-mode" -> "$origin/compatible-mode/v1"
                else -> base
            }
            else -> base
        }
    }

    private fun ensureVersionedRoot(base: String, defaultVersion: String): String {
        if (base.isBlank()) return base
        val path = runCatching { URI(base).path.orEmpty().trimEnd('/') }.getOrDefault("")
        // 仅有域名（无 path）时补 /v1，符合 OneAPI/NewAPI/硅基流动常见写法
        if (path.isEmpty() || path == "/") {
            return "$base/$defaultVersion"
        }
        return base
    }

    private fun stripOperationSuffix(url: String): String {
        var current = url
        while (true) {
            val matched = OPERATION_SUFFIXES.firstOrNull { endsWithIgnoreCase(current, it) } ?: break
            current = current.dropLast(matched.length).trimEnd('/')
        }
        return current
    }

    private fun replaceOperationSuffix(url: String, newOperation: String): String {
        val stripped = stripOperationSuffix(url)
        return joinUrl(stripped, newOperation)
    }

    private fun endsWithAny(value: String, suffixes: List<String>): Boolean {
        return suffixes.any { endsWithIgnoreCase(value, it) }
    }

    private fun endsWithIgnoreCase(value: String, suffix: String): Boolean {
        return value.endsWith(suffix, ignoreCase = true)
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "/"
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    private fun joinUrl(base: String, path: String): String {
        val b = base.trimEnd('/')
        val p = normalizePath(path)
        return if (b.isBlank()) p else "$b$p"
    }
}

internal fun ModelProvider.usesAnthropicKeyHeader(): Boolean {
    return this == ModelProvider.Anthropic ||
        this == ModelProvider.AnthropicCompatible ||
        this == ModelProvider.ClaudeCode
}
