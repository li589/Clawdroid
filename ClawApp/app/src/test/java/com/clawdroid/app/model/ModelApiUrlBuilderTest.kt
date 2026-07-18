package com.clawdroid.app.model

import com.clawdroid.app.ui.ModelProvider
import com.clawdroid.app.ui.ModelSettings
import com.clawdroid.app.ui.UrlPathMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelApiUrlBuilderTest {

    @Test
    fun autoAppendBuildsModelsNotChatCompletions() {
        val settings = ModelSettings(
            provider = ModelProvider.OpenAICompatible,
            baseUrl = "https://relay.example.com/v1",
            urlPathMode = UrlPathMode.AutoAppend
        )
        assertEquals(
            "https://relay.example.com/v1/models",
            ModelApiUrlBuilder.buildOperationUrl(settings, "/models")
        )
        assertEquals(
            "https://relay.example.com/v1/chat/completions",
            ModelApiUrlBuilder.buildChatUrl(settings)
        )
    }

    @Test
    fun bareDomainGetsV1Prefix() {
        val settings = ModelSettings(
            provider = ModelProvider.OpenAICompatible,
            baseUrl = "https://relay.example.com",
            urlPathMode = UrlPathMode.AutoAppend
        )
        assertEquals(
            "https://relay.example.com/v1/models",
            ModelApiUrlBuilder.buildOperationUrl(settings, "/models")
        )
        assertEquals(
            "https://relay.example.com/v1/chat/completions",
            ModelApiUrlBuilder.buildChatUrl(settings)
        )
    }

    @Test
    fun fullChatUrlRewritesToModels() {
        val settings = ModelSettings(
            provider = ModelProvider.OpenAICompatible,
            baseUrl = "https://relay.example.com/v1/chat/completions",
            urlPathMode = UrlPathMode.FullUrl
        )
        assertEquals(
            "https://relay.example.com/v1/models",
            ModelApiUrlBuilder.buildOperationUrl(settings, "/models")
        )
    }

    @Test
    fun appendCustomChatPathStillListsModels() {
        val settings = ModelSettings(
            provider = ModelProvider.OpenAICompatible,
            baseUrl = "https://relay.example.com",
            customApiPath = "/v1/chat/completions",
            urlPathMode = UrlPathMode.AppendCustom
        )
        assertEquals(
            "https://relay.example.com/v1/models",
            ModelApiUrlBuilder.buildOperationUrl(settings, "/models")
        )
        assertEquals(
            "https://relay.example.com/v1/chat/completions",
            ModelApiUrlBuilder.buildChatUrl(settings)
        )
    }

    @Test
    fun siliconFlowDefaultListsModels() {
        val settings = ModelSettings(
            provider = ModelProvider.SiliconFlow,
            baseUrl = ModelProvider.SiliconFlow.defaultBaseUrl,
            urlPathMode = UrlPathMode.AutoAppend
        )
        assertEquals(
            "https://api.siliconflow.cn/v1/models",
            ModelApiUrlBuilder.buildOperationUrl(settings, "/models")
        )
    }

    @Test
    fun candidatesIncludeFallbacksForBareHost() {
        val settings = ModelSettings(
            provider = ModelProvider.OpenAICompatible,
            baseUrl = "https://relay.example.com",
            urlPathMode = UrlPathMode.AutoAppend
        )
        val candidates = ModelApiUrlBuilder.buildModelsUrlCandidates(settings)
        assertTrue(candidates.contains("https://relay.example.com/v1/models"))
        assertTrue(candidates.any { it.endsWith("/api/tags") })
    }

    @Test
    fun parseOpenAiStyleDataArray() {
        val json = """{"object":"list","data":[{"id":"gpt-4o"},{"id":"deepseek-chat"}]}"""
        assertEquals(
            listOf("deepseek-chat", "gpt-4o"),
            ModelApiUrlBuilder.parseModelList(json)
        )
    }

    @Test
    fun parseAnthropicStyleNameField() {
        val json = """{"data":[{"name":"claude-3-5-sonnet-20241022","id":"ignored-if-name-first"}]}"""
        val models = ModelApiUrlBuilder.parseModelList(json)
        assertTrue(models.contains("claude-3-5-sonnet-20241022") || models.contains("ignored-if-name-first"))
    }

    @Test
    fun parseOllamaTags() {
        val json = """{"models":[{"name":"llama3:latest"},{"name":"qwen2.5:7b"}]}"""
        assertEquals(
            listOf("llama3:latest", "qwen2.5:7b"),
            ModelApiUrlBuilder.parseModelList(json)
        )
    }

    @Test
    fun parseTopLevelArray() {
        val json = """["a","b"]"""
        assertEquals(listOf("a", "b"), ModelApiUrlBuilder.parseModelList(json))
    }

    @Test(expected = IllegalStateException::class)
    fun parseHtmlFailsLoudly() {
        ModelApiUrlBuilder.parseModelList("<html>not api</html>")
    }

    @Test
    fun openRouterBareHostRewritesToApiV1() {
        val settings = ModelSettings(
            provider = ModelProvider.OpenRouter,
            baseUrl = "https://openrouter.ai",
            urlPathMode = UrlPathMode.AutoAppend
        )
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            ModelApiUrlBuilder.buildChatUrl(settings)
        )
        assertEquals(
            "https://openrouter.ai/api/v1/models",
            ModelApiUrlBuilder.buildOperationUrl(settings, "/models")
        )
    }

    @Test
    fun openRouterWrongV1PathRewrites() {
        val settings = ModelSettings(
            provider = ModelProvider.OpenRouter,
            baseUrl = "https://openrouter.ai/v1",
            urlPathMode = UrlPathMode.AutoAppend
        )
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            ModelApiUrlBuilder.buildChatUrl(settings)
        )
    }

    @Test
    fun anthropicChatUsesMessages() {
        val settings = ModelSettings(
            provider = ModelProvider.AnthropicCompatible,
            baseUrl = "https://claude-relay.test/v1",
            urlPathMode = UrlPathMode.AutoAppend
        )
        assertEquals(
            "https://claude-relay.test/v1/messages",
            ModelApiUrlBuilder.buildChatUrl(settings)
        )
        assertEquals(
            "https://claude-relay.test/v1/models",
            ModelApiUrlBuilder.buildOperationUrl(settings, "/models")
        )
    }
}
