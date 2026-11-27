package com.clawdroid.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiContentContractTest {
    private val promptRoot = File("src/main/assets/claw/prompts")

    @Test
    fun chatWelcomeIsAtMostTwoSentences() {
        val text = File(promptRoot, "chat-welcome.md").readText().trim()
        val sentenceCount = Regex("[。！？!?]").findAll(text).count()
        assertTrue("welcome copy has $sentenceCount sentences: $text", sentenceCount <= 2)
        assertTrue("welcome copy is too verbose: ${text.length}", text.length <= 120)
    }

    @Test
    fun chatSuggestionsAreLimitedToFourActions() {
        val suggestions = File(promptRoot, "chat-suggestions.txt").readLines()
            .map(String::trim)
            .filter(String::isNotEmpty)
        assertTrue("suggestion count=${suggestions.size}", suggestions.size <= 4)
        assertTrue("suggestions must be unique", suggestions.distinct().size == suggestions.size)
    }

    @Test
    fun environmentSummaryAvoidsDuplicateRootTerminology() {
        val summary = buildLocalEnvironmentSummary(
            overviewEnvironmentStatus(rootGranted = true)
        )
        assertFalse("duplicate Root terminology: $summary", summary.contains("Root环境"))
        assertTrue("summary is too verbose: ${summary.length}", summary.length <= 220)
    }
}
