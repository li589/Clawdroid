package com.clawdroid.app.tools

import com.jcraft.jsch.JSch
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class SftpKnownHostsRepositoryTest {
    @Test
    fun pinsFirstKeyAndRejectsChangedKey() {
        val file = File.createTempFile("known-hosts", ".tmp")
        file.deleteOnExit()
        val repository = SftpKnownHostsRepository(file)
        val jsch = JSch()
        val firstPair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 1024)
        val changedPair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 1024)
        try {
            val first = firstPair.publicKeyBlob
            val changed = changedPair.publicKeyBlob
            assertEquals(HostKeyRepository.OK, repository.check("example.com", first))
            assertEquals(HostKeyRepository.OK, repository.check("example.com", first))
            assertNotEquals(HostKeyRepository.OK, repository.check("example.com", changed))
        } finally {
            firstPair.dispose()
            changedPair.dispose()
        }
    }

    @Test
    fun rejectsMalformedKnownHostsFile() {
        val file = File.createTempFile("known-hosts", ".tmp")
        file.deleteOnExit()
        file.writeText("broken-entry\n")
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            SftpKnownHostsRepository(file)
        }
    }

    @Test
    fun failsWhenKnownHostsCannotBePersisted() {
        val blocker = File.createTempFile("known-hosts-blocker", ".tmp")
        blocker.deleteOnExit()
        val repository = SftpKnownHostsRepository(File(blocker, "known_hosts"))
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 1024)
        try {
            org.junit.Assert.assertThrows(Exception::class.java) {
                repository.check("example.com", pair.publicKeyBlob)
            }
        } finally {
            pair.dispose()
        }
    }

    @Test
    fun reloadsPersistedKey() {
        val file = File.createTempFile("known-hosts", ".tmp")
        file.deleteOnExit()
        val firstRepository = SftpKnownHostsRepository(file)
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 1024)
        try {
            val key = pair.publicKeyBlob
            firstRepository.check("example.com", key)

            val reloaded = SftpKnownHostsRepository(file)
            assertEquals(HostKeyRepository.OK, reloaded.check("example.com", key))
        } finally {
            pair.dispose()
        }
    }
}
