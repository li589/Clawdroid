package com.clawdroid.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelListCatalogTest {
    @Test
    fun filterByQueryIsCaseInsensitive() {
        val models = listOf("GPT-4o", "deepseek-chat", "claude-3-5-sonnet")
        val result = ModelListCatalog.filter(models, "gpt")
        assertEquals(listOf("GPT-4o"), result.filtered)
        assertEquals(3, result.total)
        assertTrue(result.summary().contains("显示 1 / 共 3"))
    }

    @Test
    fun filterByTokenAndQuery() {
        val models = listOf(
            "deepseek-ai/DeepSeek-V3",
            "deepseek-ai/DeepSeek-R1",
            "Qwen/Qwen2.5-72B-Instruct",
            "openai/gpt-4o"
        )
        val result = ModelListCatalog.filter(models, query = "V3", activeToken = "deepseek")
        assertEquals(listOf("deepseek-ai/DeepSeek-V3"), result.filtered)
    }

    @Test
    fun suggestTokensFromVendorsAndKeywords() {
        val models = listOf(
            "deepseek-ai/DeepSeek-V3",
            "deepseek-ai/DeepSeek-R1",
            "Qwen/Qwen2.5-72B-Instruct",
            "Qwen/Qwen2.5-32B-Instruct",
            "openai/gpt-4o",
            "openai/gpt-4.1"
        )
        val tokens = ModelListCatalog.suggestTokens(models)
        assertTrue(tokens.contains("deepseek-ai") || tokens.contains("deepseek"))
        assertTrue(tokens.contains("qwen") || tokens.contains("openai") || tokens.contains("gpt"))
    }

    @Test
    fun filterRespectsLimit() {
        val models = (1..500).map { "model-$it" }
        val result = ModelListCatalog.filter(models, query = "model", limit = 50)
        assertEquals(50, result.filtered.size)
        assertEquals(500, result.total)
    }
}
