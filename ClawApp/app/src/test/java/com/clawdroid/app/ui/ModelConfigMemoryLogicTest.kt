package com.clawdroid.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigMemoryLogicTest {

    @Test
    fun rememberValuePutsNewestFirstAndDedupes() {
        val first = ModelConfigMemoryLogic.rememberValue(emptyList(), "https://a/v1")
        val second = ModelConfigMemoryLogic.rememberValue(first, "https://b/v1")
        val again = ModelConfigMemoryLogic.rememberValue(second, "https://a/v1")
        assertEquals(listOf("https://a/v1", "https://b/v1"), again)
    }

    @Test
    fun providerSwitchRestoresSnapshot() {
        val openai = ModelSettings(
            provider = ModelProvider.OpenAI,
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-openai",
            modelName = "gpt-4o"
        )
        val relay = ModelSettings(
            provider = ModelProvider.OpenAICompatible,
            baseUrl = "https://relay.example.com/v1",
            apiKey = "sk-relay",
            modelName = "deepseek-chat"
        )
        var memory = ModelConfigMemory()
        memory = ModelConfigMemoryLogic.rememberSettings(memory, openai)
        memory = ModelConfigMemoryLogic.rememberSettings(memory, relay)

        val restored = ModelConfigMemoryLogic.resolveProviderSwitch(
            memory = memory,
            current = relay,
            newProvider = ModelProvider.OpenAI
        )
        assertEquals(ModelProvider.OpenAI, restored.provider)
        assertEquals("sk-openai", restored.apiKey)
        assertEquals("gpt-4o", restored.modelName)
        assertEquals("https://api.openai.com/v1", restored.baseUrl)
    }

    @Test
    fun fallbackStackPopsPreviousConfig() {
        val a = ModelSettings(
            provider = ModelProvider.OpenAICompatible,
            baseUrl = "https://a.example/v1",
            apiKey = "key-a",
            modelName = "model-a"
        )
        val b = ModelSettings(
            provider = ModelProvider.OpenAICompatible,
            baseUrl = "https://b.example/v1",
            apiKey = "key-b",
            modelName = "model-b"
        )
        var memory = ModelConfigMemoryLogic.rememberSettings(ModelConfigMemory(), a)
        memory = ModelConfigMemoryLogic.rememberWithFallback(memory, a, b)
        assertTrue(memory.canFallback)

        val (afterPop, snap) = ModelConfigMemoryLogic.popFallback(memory)
        requireNotNull(snap)
        assertEquals("https://a.example/v1", snap.baseUrl)
        assertEquals("key-a", snap.apiKey)
        assertFalse(afterPop.canFallback)
    }

    @Test
    fun typingModelNameDoesNotPushFallback() {
        val a = ModelSettings(
            provider = ModelProvider.OpenAICompatible,
            baseUrl = "https://relay/v1",
            apiKey = "k",
            modelName = "g"
        )
        val b = a.copy(modelName = "gpt")
        assertFalse(ModelConfigMemoryLogic.isCoarseChange(a, b))
        val memory = ModelConfigMemoryLogic.rememberWithFallback(ModelConfigMemory(), a, b)
        assertFalse(memory.canFallback)
    }

    @Test
    fun recentModelsAndKeysTracked() {
        val settings = ModelSettings(
            provider = ModelProvider.SiliconFlow,
            baseUrl = "https://api.siliconflow.cn/v1",
            apiKey = "sf-key",
            modelName = "Qwen/Qwen2.5-72B-Instruct"
        )
        val memory = ModelConfigMemoryLogic.rememberSettings(ModelConfigMemory(), settings)
        assertTrue(memory.recentUrls.contains("https://api.siliconflow.cn/v1"))
        assertTrue(memory.recentModels.contains("Qwen/Qwen2.5-72B-Instruct"))
        assertTrue(memory.recentApiKeys.contains("sf-key"))
        assertTrue(memory.providerSnapshots.containsKey(ModelProvider.SiliconFlow))
    }
}
