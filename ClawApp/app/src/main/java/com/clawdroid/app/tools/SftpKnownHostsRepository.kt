package com.clawdroid.app.tools

import android.util.Base64
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private data class StoredHostKey(
    val host: String,
    val type: Int,
    val keyBase64: String,
)

class SftpKnownHostsRepository(private val file: File) : HostKeyRepository {
    private val lock = ReentrantLock()
    private val keys = mutableListOf<StoredHostKey>()

    init {
        load()
    }

    @Synchronized
    override fun check(host: String, key: ByteArray): Int = lock.withLock {
        val candidate = HostKey(host, key)
        val type = typeFromName(candidate.getType())
        val keyBase64 = candidate.getKey()
        val sameType = keys.filter { it.host == host && it.type == type }
        if (sameType.isEmpty()) {
            add(candidate, null)
            return HostKeyRepository.OK
        }
        return if (sameType.any { it.keyBase64 == keyBase64 }) {
            HostKeyRepository.OK
        } else {
            HostKeyRepository.CHANGED
        }
    }

    @Synchronized
    override fun add(key: HostKey, userInfo: UserInfo?) = lock.withLock {
        val previous = keys.toList()
        val type = typeFromName(key.getType())
        keys.removeAll { it.host == key.host && it.type == type }
        keys.add(StoredHostKey(key.host, type, key.getKey()))
        try {
            persist()
        } catch (error: Throwable) {
            keys.clear()
            keys.addAll(previous)
            throw error
        }
    }

    @Synchronized
    override fun remove(host: String, type: String) = lock.withLock {
        keys.removeAll { it.host == host && it.type == typeFromName(type) }
        persist()
    }

    @Synchronized
    override fun remove(host: String, type: String, key: ByteArray) = lock.withLock {
        val keyBase64 = Base64.encodeToString(key, Base64.NO_WRAP)
        keys.removeAll { it.host == host && it.type == typeFromName(type) && it.keyBase64 == keyBase64 }
        persist()
    }

    override fun getKnownHostsRepositoryID(): String = "clawdroid-sftp-known-hosts"

    @Synchronized
    override fun getHostKey(): Array<HostKey> = lock.withLock {
        keys.map { HostKey(it.host, it.type, Base64.decode(it.keyBase64, Base64.NO_WRAP)) }.toTypedArray()
    }

    @Synchronized
    override fun getHostKey(host: String, type: String): Array<HostKey> = lock.withLock {
        keys.filter { it.host == host && it.type == typeFromName(type) }
            .map { HostKey(it.host, it.type, Base64.decode(it.keyBase64, Base64.NO_WRAP)) }
            .toTypedArray()
    }

    private fun load() {
        if (!file.isFile) return
        file.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachLine
            val parts = line.split(Regex("\\s+"))
            if (parts.size != 3) throw IllegalStateException("malformed known_hosts entry")
            keys.add(StoredHostKey(parts[0], parts[1].toInt(), parts[2]))
        }
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            temporary.bufferedWriter().use { writer ->
                keys.forEach { key ->
                    writer.append(key.host)
                    writer.append(' ')
                    writer.append(key.type.toString())
                    writer.append(' ')
                    writer.append(key.keyBase64)
                    writer.append('\n')
                }
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun typeFromName(name: String): Int = when (name) {
        "ssh-dss" -> HostKey.SSHDSS
        "ssh-rsa" -> HostKey.SSHRSA
        "ecdsa-sha2-nistp256" -> HostKey.ECDSA256
        "ecdsa-sha2-nistp384" -> HostKey.ECDSA384
        "ecdsa-sha2-nistp521" -> HostKey.ECDSA521
        "ssh-ed25519" -> HostKey.ED25519
        "ssh-ed448" -> HostKey.ED448
        else -> HostKey.UNKNOWN
    }
}
