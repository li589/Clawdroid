package com.clawdroid.app.ipc

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthDigestTest {
    @Test
    fun computeAuthDigest_matchesRuntimeAuthGo() {
        // Vector generated from ClawRuntime auth.go authDigest().
        val digest = ClawRuntimeIpcClient.computeAuthDigest(
            secret = "test-shared-secret-0123456789abcdef",
            nonce = "nonce-xyz",
            packageName = "com.clawdroid.app.debug",
            signatureDigest = "sha256:AaBbCcDd",
            clientTimestamp = 1_700_000_000L
        )
        assertEquals(
            "cd8e1b7de9070c14572d68d5d438cbc9acd1ddd4de2b302b2f019fcc1dd7ad5c",
            digest
        )
    }

    @Test
    fun computeAuthDigest_normalizesSignatureCase() {
        val upper = ClawRuntimeIpcClient.computeAuthDigest(
            secret = "test-shared-secret-0123456789abcdef",
            nonce = "nonce-xyz",
            packageName = "com.clawdroid.app.debug",
            signatureDigest = "sha256:AABBCCDD",
            clientTimestamp = 1_700_000_000L
        )
        val lower = ClawRuntimeIpcClient.computeAuthDigest(
            secret = "test-shared-secret-0123456789abcdef",
            nonce = "nonce-xyz",
            packageName = "com.clawdroid.app.debug",
            signatureDigest = "sha256:aabbccdd",
            clientTimestamp = 1_700_000_000L
        )
        assertEquals(lower, upper)
    }
}
