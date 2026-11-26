package com.clawdroid.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParallelAgentIdsTest {
    @Test
    fun parsesAndDeduplicatesWithinLimit() {
        assertEquals(listOf("a", "b"), parseParallelAgentIds("a,b,a", max = 2))
    }

    @Test
    fun rejectsEmptyAndOverLimitLists() {
        assertNull(parseParallelAgentIds(" , ", max = 2))
        assertNull(parseParallelAgentIds("a,b,c", max = 2))
    }
}
