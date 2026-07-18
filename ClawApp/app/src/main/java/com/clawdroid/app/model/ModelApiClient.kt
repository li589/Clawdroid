package com.clawdroid.app.model

import com.clawdroid.app.ui.ApiPathStyle
import com.clawdroid.app.ui.ModelProvider
import com.clawdroid.app.ui.ModelSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * 模型 API 客户端
 *
 * 支持的协议：
 * - OpenAI 兼容协议（/chat/completions）
 * - Anthropic Messages API（/messages）
 * - 自定义协议（完全由用户控制 URL 和路径）
 * - Claude Code / Codex 等专用工具接口
 * - 本地模型（Ollama / LM Studio / vLLM）
 */
internal object ModelApiClient {

    // -------------------------------------------------------------------------
    // 证书锁定
    // -------------------------------------------------------------------------
    private val pinnedCertDigests = mapOf(
        "anthropic.com" to setOf(
            "b8:25:b5:78:3b:0e:2f:1d:2e:30:52:8b:9d:39:ab:28:4d:5e:2c:1e:5b:bf:78:c7:6a:0d:5b:2e:9f:6f:71:2f"
        ),
        "api.anthropic.com" to setOf(
            "b8:25:b5:78:3b:0e:2f:1d:2e:30:52:8b:9d:39:ab:28:4d:5e:2c:1e:5b:bf:78:c7:6a:0d:5b:2e:9f:6f:71:2f"
        ),
        "api.openai.com" to setOf(
            "32:a1:2e:1c:d5:24:65:4e:26:7d:0f:92:8e:0f:2f:87:4e:84:8c:fd:b3:8a:db:11:05:79:4a:8e:2b:ef:d6:52"
        ),
        "generativelanguage.googleapis.com" to setOf(
            "43:bb:57:94:19:3c:40:1a:7a:3c:0e:3f:5f:6c:8b:8d:7c:22:9e:0d:2f:71:5c:3f:21:29:5b:47:0d:4e:6e:6a"
        )
    )

    private fun createPinnedSslFactory(host: String): javax.net.ssl.SSLSocketFactory? {
        val pinnedDigests = pinnedCertDigests.entries.find { host.endsWith(it.key) }?.value ?: return null
        try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val trustManagers = tmf.trustManagers
            val defaultTm = trustManagers.firstOrNull() as? X509TrustManager ?: return null
            val pinnedTm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    defaultTm.checkClientTrusted(chain, authType)
                }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val cert = chain.firstOrNull() ?: throw java.security.cert.CertificateException("no server certificate")
                    val md = MessageDigest.getInstance("SHA-256")
                    val digest = md.digest(cert.encoded).joinToString(":") { "%02x".format(it) }
                    if (pinnedDigests.none { pd -> digest.equals(pd, ignoreCase = true) }) {
                        throw java.security.cert.CertificateException("certificate pin verification failed for $host: $digest")
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTm.acceptedIssuers
            }
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, arrayOf<X509TrustManager>(pinnedTm), null)
            return sc.socketFactory
        } catch (_: Exception) {
            return null
        }
    }

    // -------------------------------------------------------------------------
    // 公共 API
    // -------------------------------------------------------------------------

    /**
     * 生成回复（无系统提示词）
     */
    suspend fun generateReply(settings: ModelSettings, prompt: String): Result<String> {
        return generateReply(settings = settings, prompt = prompt, systemPrompt = null)
    }

    /**
     * 生成回复（带系统提示词）
     */
    suspend fun generateReply(
        settings: ModelSettings,
        prompt: String,
        systemPrompt: String?
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                validateSettings(settings)
                when (settings.provider.apiPathStyle) {
                    ApiPathStyle.Anthropic -> executeAnthropicMessages(settings, prompt, systemPrompt ?: settings.contextSettings.systemPrompt)
                    ApiPathStyle.OpenAI, ApiPathStyle.Custom -> executeOpenAiCompatibleChat(settings, prompt, systemPrompt ?: settings.contextSettings.systemPrompt)
                }
            }
        }
    }

    /**
     * 测试模型连接
     */
    suspend fun testConnection(settings: ModelSettings): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                validateSettings(settings)
                val reply = when (settings.provider.apiPathStyle) {
                    ApiPathStyle.Anthropic -> executeAnthropicMessages(settings, "Reply with OK only.", null)
                    ApiPathStyle.OpenAI, ApiPathStyle.Custom -> executeOpenAiCompatibleChat(settings, "Reply with OK only.", null)
                }
                "连接成功: ${settings.provider.displayName} / ${reply.take(80)}"
            }
        }
    }

    /**
     * 查询模型列表（OpenAI/Anthropic 兼容中转站、硅基流动、NewAPI、Ollama 等）
     */
    suspend fun listModels(settings: ModelSettings): Result<List<String>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(settings.resolvedEndpoint().isNotBlank()) { "API Base URL 不能为空" }
                if (settings.provider != ModelProvider.Local) {
                    require(settings.apiKey.isNotBlank()) { "API Key 不能为空" }
                }

                val candidates = ModelApiUrlBuilder.buildModelsUrlCandidates(settings)
                require(candidates.isNotEmpty()) { "无法构造模型列表 URL，请检查 Base URL" }

                var lastError: Throwable? = null
                for (endpoint in candidates) {
                    try {
                        val headers = buildAuthHeaders(settings)
                        val response = executeRawRequest(settings, endpoint, "GET", headers)
                        return@runCatching ModelApiUrlBuilder.parseModelList(response)
                    } catch (error: Throwable) {
                        lastError = error
                    }
                }
                throw lastError ?: IllegalStateException("获取模型列表失败")
            }
        }
    }

    // -------------------------------------------------------------------------
    // 设置验证
    // -------------------------------------------------------------------------

    private fun validateSettings(settings: ModelSettings) {
        when (settings.provider) {
            ModelProvider.Local -> {
                require(settings.localEndpoint.isNotBlank()) { "本地模型接口地址不能为空" }
                require(settings.localModelName.isNotBlank()) { "本地模型名称不能为空" }
            }
            ModelProvider.Custom -> {
                // Custom 模式下用户完全控制 URL，不做强制验证
            }
            else -> {
                require(settings.resolvedEndpoint().isNotBlank()) { "API Base URL 不能为空" }
                require(settings.resolvedModelName().isNotBlank()) { "模型名称不能为空" }
                require(settings.apiKey.isNotBlank()) { "API Key 不能为空" }
            }
        }
    }

    // -------------------------------------------------------------------------
    // OpenAI 兼容接口（/chat/completions）
    // -------------------------------------------------------------------------

    private fun executeOpenAiCompatibleChat(
        settings: ModelSettings,
        prompt: String,
        systemPrompt: String?
    ): String {
        val endpoint = ModelApiUrlBuilder.buildChatUrl(settings)
        val headers = buildAuthHeaders(settings)
        val ctx = settings.contextSettings

        val messages = JSONArray().apply {
            systemPrompt?.takeIf { it.isNotBlank() }?.let { content ->
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", content)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val payload = JSONObject().apply {
            put("model", settings.resolvedModelName())
            put("messages", messages)
            put("temperature", ctx.temperature.toDouble())
            put("max_tokens", ctx.maxTokens)
            if (ctx.topP < 1.0f) put("top_p", ctx.topP.toDouble())
            if (ctx.stopSequences.isNotEmpty()) put("stop", JSONArray(ctx.stopSequences))
        }

        val response = executeJsonRequest(settings, endpoint, "POST", headers, payload.toString())
        return parseOpenAiChatResponse(response)
    }

    private fun parseOpenAiChatResponse(response: String): String {
        val root = JSONObject(response)
        val choices = root.optJSONArray("choices")
            ?: error("模型响应缺少 choices 字段")
        val first = choices.optJSONObject(0)
            ?: error("模型响应 choices 为空")
        return first.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: error("模型响应中没有可用内容")
    }

    // -------------------------------------------------------------------------
    // Anthropic Messages API（/messages）
    // -------------------------------------------------------------------------

    private fun executeAnthropicMessages(
        settings: ModelSettings,
        prompt: String,
        systemPrompt: String?
    ): String {
        val endpoint = ModelApiUrlBuilder.buildChatUrl(settings)
        val headers = buildAnthropicHeaders(settings)
        val ctx = settings.contextSettings

        val payload = JSONObject().apply {
            put("model", settings.resolvedModelName())
            put("max_tokens", ctx.maxTokens)
            if (systemPrompt?.isNotBlank() == true) put("system", systemPrompt)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    }
                )
            )
            put("temperature", ctx.temperature.toDouble())
            if (ctx.topP < 1.0f) put("top_p", ctx.topP.toDouble())
            ctx.topK?.let { put("top_k", it) }
            if (ctx.stopSequences.isNotEmpty()) put("stop_sequences", JSONArray(ctx.stopSequences))
            // Claude 3.7+ extended thinking
            ctx.thinkingBudget?.let { put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", it)) }
        }

        val response = executeJsonRequest(settings, endpoint, "POST", headers, payload.toString())
        return parseAnthropicMessagesResponse(response)
    }

    private fun parseAnthropicMessagesResponse(response: String): String {
        val root = JSONObject(response)
        val content = root.optJSONArray("content")
            ?: error("Anthropic 响应缺少 content 字段")
        val first = content.optJSONObject(0)
            ?: error("Anthropic 响应内容为空")
        return first.optString("text")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: error("Anthropic 响应中没有文本内容")
    }

    // -------------------------------------------------------------------------
    // 请求头构建
    // -------------------------------------------------------------------------

    private fun buildAuthHeaders(settings: ModelSettings): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        when {
            settings.provider.usesAnthropicKeyHeader() -> {
                // 官方 Anthropic / 兼容代理：x-api-key；部分中转也接受 Bearer，优先规范头
                if (settings.apiKey.isNotBlank()) {
                    headers["x-api-key"] = settings.apiKey
                    // 兼容部分仅认 Authorization 的 Claude 中转
                    headers["Authorization"] = "Bearer ${settings.apiKey}"
                }
            }
            settings.provider == ModelProvider.Local -> {
                if (settings.apiKey.isNotBlank()) {
                    val key = settings.apiKey.trim()
                    headers["Authorization"] = if (key.startsWith("Bearer ", ignoreCase = true)) {
                        key
                    } else {
                        "Bearer $key"
                    }
                }
            }
            else -> {
                if (settings.apiKey.isNotBlank()) {
                    val prefix = settings.provider.authHeaderPrefix
                    headers[settings.provider.authHeaderName] =
                        if (prefix.isNotBlank()) "$prefix ${settings.apiKey}" else settings.apiKey
                }
            }
        }
        // 部分聚合网关建议带上引用站信息
        if (settings.provider == ModelProvider.OpenRouter) {
            headers.putIfAbsent("HTTP-Referer", "https://clawdroid.app")
            headers.putIfAbsent("X-Title", "Clawdroid")
        }
        return headers
    }

    private fun buildAnthropicHeaders(settings: ModelSettings): Map<String, String> {
        val headers = buildAuthHeaders(settings).toMutableMap()
        headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json"
        headers["anthropic-version"] = "2023-06-01"
        return headers
    }

    // -------------------------------------------------------------------------
    // HTTP 请求执行
    // -------------------------------------------------------------------------

    private fun executeJsonRequest(
        settings: ModelSettings,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String? = null
    ): String {
        return executeRawRequest(settings, url, method, headers, body)
    }

    private fun executeRawRequest(
        settings: ModelSettings,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String? = null
    ): String {
        val urlObj = URL(url)
        val connection = NetworkProxySupport.openConnection(url, settings.proxySettings).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            NetworkProxySupport.applyProxyAuthorization(this, settings.proxySettings)
            headers.forEach { (key, value) ->
                setRequestProperty(key, value)
            }
            doInput = true
            if (!body.isNullOrBlank()) {
                doOutput = true
            }
            if (this is HttpsURLConnection) {
                createPinnedSslFactory(urlObj.host)?.let { sslFactory ->
                    sslSocketFactory = sslFactory
                }
            }
        }
        return connection.useConnection {
            if (!body.isNullOrBlank()) {
                outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(body)
                }
            }
            val stream = if (responseCode in 200..299) {
                inputStream
            } else {
                errorStream ?: inputStream
            }
            val raw = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
            if (responseCode !in 200..299) {
                val message = runCatching {
                    val json = JSONObject(raw)
                    json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: json.optJSONObject("error")?.optString("msg")?.takeIf { it.isNotBlank() }
                        ?: json.optString("error").takeIf { it.isNotBlank() && !it.startsWith("{") }
                        ?: json.optString("message").takeIf { it.isNotBlank() }
                        ?: json.optString("msg").takeIf { it.isNotBlank() }
                }.getOrNull().orEmpty()
                error("HTTP $responseCode ${if (message.isNotBlank()) message else raw.take(240)}")
            }
            raw
        }
    }

    private inline fun <T> HttpURLConnection.useConnection(block: HttpURLConnection.() -> T): T {
        return try {
            block()
        } finally {
            disconnect()
        }
    }
}
