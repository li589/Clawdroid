package com.clawdroid.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeErrorCodesTest {
    @Test
    fun includesRateLimitWriteAndTaskCodes() {
        assertTrue(RuntimeErrorCodes.allCodes.contains(RuntimeErrorCodes.ERR_RATE_LIMITED))
        assertTrue(RuntimeErrorCodes.allCodes.contains(RuntimeErrorCodes.ERR_FILE_WRITE_FAILED))
        assertTrue(RuntimeErrorCodes.allCodes.contains(RuntimeErrorCodes.ERR_TASK_QUEUE_FULL))
        assertEquals("rate limited", RuntimeErrorCodes.message(RuntimeErrorCodes.ERR_RATE_LIMITED))
        assertEquals("task queue is full", RuntimeErrorCodes.message(RuntimeErrorCodes.ERR_TASK_QUEUE_FULL))
    }
}
