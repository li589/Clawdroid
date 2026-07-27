package com.clawdroid.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelContextWindowCatalogTest {
    @Test
    fun resolvesKnownModels() {
        assertEquals(128_000, ModelContextWindowCatalog.resolve("gpt-4o"))
        assertEquals(200_000, ModelContextWindowCatalog.resolve("claude-3-5-sonnet-20241022"))
        assertTrue(ModelContextWindowCatalog.resolve("deepseek-chat") >= 32_000)
    }

    @Test
    fun blankFallsBackToDefault() {
        assertEquals(ModelContextWindowCatalog.DEFAULT_WINDOW, ModelContextWindowCatalog.resolve(""))
    }

    @Test
    fun contextSettingsAutoUsesCatalog() {
        val ctx = ContextSettings(contextWindowTokens = 0)
        assertEquals(128_000, ctx.effectiveContextWindow("gpt-4o-mini"))
        assertEquals(64_000, ContextSettings(contextWindowTokens = 64_000).effectiveContextWindow("gpt-4o"))
    }
}
