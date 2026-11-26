package com.clawdroid.app.data

import org.junit.Assert.assertThrows
import org.junit.Test

class AppSecretCipherFailureTest {
    @Test
    fun encryptionFailureThrowsInsteadOfReturningEmptySecret() {
        assertThrows(IllegalStateException::class.java) {
            encryptSecretOrThrow("secret") { error("keystore unavailable") }
        }
    }
}
