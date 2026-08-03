package com.clawdroid.app.data

import com.clawdroid.app.agent.MemoryBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFacadeTest {
    @Test
    fun dedupePreserveOrder() {
        val out = MemoryFacade.dedupePreserveOrder(
            listOf("A", "a", " B ", "c", "b")
        )
        assertEquals(listOf("A", "B", "c"), out)
    }

    @Test
    fun trimToBudgetKeepsEarlierSections() {
        val trimmed = MemoryFacade.trimToBudget(
            MemoryBundle(
                workingSummary = "w".repeat(50),
                episodicSnippets = listOf("e1", "e2-long-" + "x".repeat(200)),
                semanticFacts = listOf("s1"),
                filePaths = listOf("/a", "/b")
            ),
            maxTotalChars = 120
        )
        assertTrue(trimmed.workingSummary.isNotEmpty())
        assertTrue(trimmed.asRetrievedContext().length <= 200)
    }
}
