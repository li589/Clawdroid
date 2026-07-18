package com.clawdroid.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInputSanitizerTest {
    @Test
    fun stripsNewlinesFromApiKeyAndUrl() {
        val settings = ModelSettings(
            provider = ModelProvider.OpenAICompatible,
            baseUrl = "https://relay.example/v1\r\nX-Injected: 1",
            apiKey = "sk-abc\ndef",
            modelName = "gpt-4o"
        )
        val sanitized = ModelInputSanitizer.sanitize(settings).settings
        assertTrue(!sanitized.baseUrl.contains("\n") && !sanitized.baseUrl.contains("\r"))
        assertEquals("sk-abcdef", sanitized.apiKey)
    }

    @Test
    fun rejectsDangerousSchemes() {
        val settings = ModelSettings(
            provider = ModelProvider.OpenAICompatible,
            baseUrl = "javascript:alert(1)",
            apiKey = "k",
            modelName = "m"
        )
        val error = ModelInputSanitizer.validationError(settings)
        assertNotNull(error)
    }

    @Test
    fun rejectsPathTraversalInCustomPath() {
        val settings = ModelSettings(
            provider = ModelProvider.Custom,
            baseUrl = "https://ok.example/v1",
            apiKey = "k",
            modelName = "m",
            customApiPath = "/../../etc/passwd"
        )
        val sanitized = ModelInputSanitizer.sanitize(settings)
        assertTrue(!sanitized.settings.customApiPath.contains(".."))
    }

    @Test
    fun validOpenRouterPasses() {
        val settings = ModelSettings(
            provider = ModelProvider.OpenRouter,
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "sk-or-v1-xxx",
            modelName = "openai/gpt-4o"
        )
        assertNull(ModelInputSanitizer.validationError(settings))
    }

    @Test
    fun proxyHostRejectsFullUrl() {
        val settings = ModelSettings(
            provider = ModelProvider.OpenRouter,
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "sk-or-v1-xxx",
            modelName = "openai/gpt-4o",
            proxySettings = NetworkProxySettings(
                mode = NetworkProxyMode.Http,
                host = "http://127.0.0.1",
                port = 7890
            )
        )
        val sanitized = ModelInputSanitizer.sanitize(settings)
        assertEquals("127.0.0.1", sanitized.settings.proxySettings.host)
        assertTrue(sanitized.warnings.any { it.contains("代理主机") })
    }

    @Test
    fun customProxyRequiresHostAndPort() {
        val settings = ModelSettings(
            provider = ModelProvider.OpenRouter,
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "sk-or-v1-xxx",
            modelName = "openai/gpt-4o",
            proxySettings = NetworkProxySettings(
                mode = NetworkProxyMode.Socks,
                host = " ",
                port = 0
            )
        )
        // sanitize blanks host to 127.0.0.1 and coerces port; validation should then pass for host/port
        val sanitized = ModelInputSanitizer.sanitize(settings).settings
        assertEquals("127.0.0.1", sanitized.proxySettings.host)
        assertTrue(sanitized.proxySettings.port in 1..65535)
    }
}
