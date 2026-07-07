package com.clawdroid.app.model

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

internal object ModelApiClient {
    suspend fun generateReply(settings: ModelSettings, prompt: String): Result<String> {
        return generateReply(settings = settings, prompt = prompt, systemPrompt = null)
    }

    suspend fun generateReply(
        settings: ModelSettings,
        prompt: String,
        systemPrompt: String?
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                validateSettings(settings, requireApiKey = settings.provider != ModelProvider.Local)
                when (settings.provider) {
                    ModelProvider.Anthropic -> executeAnthropicMessages(settings, prompt, systemPrompt)
                    ModelProvider.OpenAI,
                    ModelProvider.Gemini,
                    ModelProvider.OpenAICompatible,
                    ModelProvider.Custom,
                    ModelProvider.Local -> executeOpenAiCompatibleChat(settings, prompt, systemPrompt)
                }
            }
        }
    }

    suspend fun testConnection(settings: ModelSettings): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                validateSettings(settings, requireApiKey = settings.provider != ModelProvider.Local)
                val reply = when (settings.provider) {
                    ModelProvider.Anthropic -> executeAnthropicMessages(
                        settings,
                        "Reply with OK only.",
                        systemPrompt = null
                    )
                    ModelProvider.OpenAI,
                    ModelProvider.Gemini,
                    ModelProvider.OpenAICompatible,
                    ModelProvider.Custom,
                    ModelProvider.Local -> executeOpenAiCompatibleChat(
                        settings,
                        "Reply with OK only.",
                        systemPrompt = null
                    )
                }
                "连接成功: ${settings.provider.name} / ${reply.take(80)}"
            }
        }
    }

    private fun validateSettings(settings: ModelSettings, requireApiKey: Boolean) {
        if (settings.provider == ModelProvider.Local) {
            require(settings.localEndpoint.isNotBlank()) { "本地模型接口地址不能为空" }
            require(settings.localModelName.isNotBlank()) { "本地模型名称不能为空" }
            return
        }
        require(settings.baseUrl.isNotBlank()) { "API Base URL 不能为空" }
        require(settings.modelName.isNotBlank()) { "模型名称不能为空" }
        if (requireApiKey) {
            require(settings.apiKey.isNotBlank()) { "API Key 不能为空" }
        }
    }

    private fun executeOpenAiCompatibleChat(
        settings: ModelSettings,
        prompt: String,
        systemPrompt: String?
    ): String {
        val endpointBase = when (settings.provider) {
            ModelProvider.Local -> settings.localEndpoint
            else -> settings.baseUrl
        }.trimEnd('/')
        val modelName = when (settings.provider) {
            ModelProvider.Local -> settings.localModelName
            else -> settings.modelName
        }
        val messages = JSONArray().apply {
            systemPrompt
                ?.takeIf { it.isNotBlank() }
                ?.let { content ->
                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", content)
                        }
                    )
                }
            put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }
            )
        }
        val payload = JSONObject().apply {
            put("model", modelName)
            put("messages", messages)
            put("temperature", 0.4)
            put("max_tokens", 256)
        }
        val response = executeJsonRequest(
            url = "$endpointBase/chat/completions",
            method = "POST",
            headers = buildMap {
                if (settings.provider != ModelProvider.Local && settings.apiKey.isNotBlank()) {
                    put("Authorization", "Bearer ${settings.apiKey}")
                }
            },
            body = payload.toString()
        )
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

    private fun executeAnthropicMessages(
        settings: ModelSettings,
        prompt: String,
        systemPrompt: String?
    ): String {
        val endpointBase = settings.baseUrl.trimEnd('/')
        val payload = JSONObject().apply {
            put("model", settings.modelName)
            put("max_tokens", 256)
            systemPrompt
                ?.takeIf { it.isNotBlank() }
                ?.let { put("system", it) }
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    }
                )
            )
        }
        val response = executeJsonRequest(
            url = "$endpointBase/messages",
            method = "POST",
            headers = mapOf(
                "x-api-key" to settings.apiKey,
                "anthropic-version" to "2023-06-01"
            ),
            body = payload.toString()
        )
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

    private fun executeJsonRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String? = null
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (key, value) ->
                setRequestProperty(key, value)
            }
            doInput = true
            if (!body.isNullOrBlank()) {
                doOutput = true
            }
        }
        return connection.useConnection {
            if (!body.isNullOrBlank()) {
                outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(body)
                }
            }
            val stream = if (responseCode in 200..299) inputStream else errorStream
            val raw = BufferedReader(InputStreamReader(stream ?: inputStream, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
            if (responseCode !in 200..299) {
                val message = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
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
