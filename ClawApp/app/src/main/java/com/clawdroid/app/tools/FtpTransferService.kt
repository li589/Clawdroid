package com.clawdroid.app.tools

import android.content.Context
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.Vector

/**
 * FTP / SFTP list|get|put restricted to app sandbox paths (filesDir / cacheDir / external app dirs).
 * SFTP uses password auth only (no key files in P0).
 */
class FtpTransferService(
    private val context: Context
) {
    fun execute(
        op: String,
        host: String,
        port: Int = 0,
        user: String = "anonymous",
        password: String = "",
        remotePath: String = "/",
        localPath: String = "",
        passive: Boolean = true,
        timeoutMs: Int = 15_000,
        protocol: String = "ftp"
    ): ClawToolCallResult {
        CacheDirPruner.pruneNamedCache(context.cacheDir, "ftp")
        val operation = op.trim().lowercase()
        if (operation !in setOf("list", "get", "put")) {
            return ClawToolCallResult(
                false,
                "失败: op 须为 list|get|put",
                error = "invalid_op"
            )
        }
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) {
            return ClawToolCallResult(false, "失败: host 不能为空", error = "invalid_host")
        }
        val proto = normalizeProtocol(protocol)
            ?: return ClawToolCallResult(
                false,
                "失败: protocol 须为 ftp|sftp",
                error = "invalid_protocol"
            )
        val remote = remotePath.trim().ifBlank { "/" }
        val timeout = timeoutMs.coerceIn(1_000, 120_000)
        val resolvedPort = when {
            port in 1..65535 -> port
            proto == "sftp" -> 22
            else -> 21
        }

        // Reject non-sandbox local paths before opening a network connection.
        if (operation == "get" || operation == "put") {
            val forWrite = operation == "get"
            resolveSandboxFile(localPath, forWrite = forWrite)
                ?: return sandboxPathError()
        }

        return when (proto) {
            "sftp" -> executeSftp(
                operation = operation,
                host = trimmedHost,
                port = resolvedPort,
                user = user.ifBlank { "anonymous" },
                password = password,
                remote = remote,
                localPath = localPath,
                timeoutMs = timeout
            )
            else -> executeFtp(
                operation = operation,
                host = trimmedHost,
                port = resolvedPort,
                user = user.ifBlank { "anonymous" },
                password = password,
                remote = remote,
                localPath = localPath,
                passive = passive,
                timeoutMs = timeout
            )
        }
    }

    private fun executeFtp(
        operation: String,
        host: String,
        port: Int,
        user: String,
        password: String,
        remote: String,
        localPath: String,
        passive: Boolean,
        timeoutMs: Int
    ): ClawToolCallResult {
        val client = FTPClient()
        return try {
            client.connectTimeout = timeoutMs
            client.defaultTimeout = timeoutMs
            @Suppress("DEPRECATION")
            client.setDataTimeout(timeoutMs)
            client.connect(host, port)
            val reply = client.replyCode
            if (!FTPReply.isPositiveCompletion(reply)) {
                return ClawToolCallResult(
                    false,
                    "失败: FTP 连接拒绝 reply=$reply",
                    error = "ftp_connect_rejected"
                )
            }
            if (!client.login(user, password)) {
                return ClawToolCallResult(false, "失败: FTP 登录失败", error = "ftp_login_failed")
            }
            client.setFileType(FTP.BINARY_FILE_TYPE)
            if (passive) {
                client.enterLocalPassiveMode()
            } else {
                client.enterLocalActiveMode()
            }
            client.soTimeout = timeoutMs

            when (operation) {
                "list" -> listFtp(client, remote)
                "get" -> getFtp(client, remote, localPath)
                else -> putFtp(client, remote, localPath)
            }
        } catch (error: Exception) {
            ClawToolCallResult(false, "失败: ${error.message}", error = error.message)
        } finally {
            runCatching {
                if (client.isConnected) {
                    client.logout()
                    client.disconnect()
                }
            }
        }
    }

    private fun executeSftp(
        operation: String,
        host: String,
        port: Int,
        user: String,
        password: String,
        remote: String,
        localPath: String,
        timeoutMs: Int
    ): ClawToolCallResult {
        var session: Session? = null
        var channel: ChannelSftp? = null
        return try {
            val jsch = JSch()
            session = jsch.getSession(user, host, port).also { s ->
                s.setPassword(password)
                val config = Properties()
                // P0: password auth against known hosts; accept any host key (document risk).
                config["StrictHostKeyChecking"] = "no"
                s.setConfig(config)
                s.timeout = timeoutMs
                s.connect(timeoutMs)
            }
            channel = (session.openChannel("sftp") as ChannelSftp).also { ch ->
                ch.connect(timeoutMs)
            }
            val sftp = channel!!
            when (operation) {
                "list" -> listSftp(sftp, remote)
                "get" -> getSftp(sftp, remote, localPath)
                else -> putSftp(sftp, remote, localPath)
            }
        } catch (error: Exception) {
            ClawToolCallResult(false, "失败: ${error.message}", error = error.message)
        } finally {
            runCatching { channel?.disconnect() }
            runCatching { session?.disconnect() }
        }
    }

    private fun listFtp(client: FTPClient, remote: String): ClawToolCallResult {
        val files = client.listFiles(remote) ?: emptyArray()
        val arr = JSONArray()
        files.forEach { f ->
            arr.put(
                JSONObject()
                    .put("name", f.name)
                    .put("size", f.size)
                    .put("type", when {
                        f.isDirectory -> "dir"
                        f.isSymbolicLink -> "link"
                        else -> "file"
                    })
                    .put("timestamp", f.timestamp?.time?.time ?: JSONObject.NULL)
            )
        }
        return listResult("ftp", remote, arr)
    }

    @Suppress("UNCHECKED_CAST")
    private fun listSftp(channel: ChannelSftp, remote: String): ClawToolCallResult {
        val entries = channel.ls(remote) as Vector<ChannelSftp.LsEntry>
        val arr = JSONArray()
        entries.forEach { entry ->
            val name = entry.filename
            if (name == "." || name == "..") return@forEach
            val attrs = entry.attrs
            arr.put(
                JSONObject()
                    .put("name", name)
                    .put("size", attrs.size)
                    .put(
                        "type",
                        when {
                            attrs.isDir -> "dir"
                            attrs.isLink -> "link"
                            else -> "file"
                        }
                    )
                    .put("mtime", attrs.mTime.toLong() * 1000L)
            )
        }
        return listResult("sftp", remote, arr)
    }

    private fun listResult(protocol: String, remote: String, arr: JSONArray): ClawToolCallResult {
        val json = JSONObject()
            .put("op", "list")
            .put("protocol", protocol)
            .put("remote_path", remote)
            .put("count", arr.length())
            .put("entries", arr)
        return ClawToolCallResult(success = true, output = json.toString(2))
    }

    private fun getFtp(client: FTPClient, remote: String, localPath: String): ClawToolCallResult {
        val dest = resolveSandboxFile(localPath, forWrite = true)
            ?: return sandboxPathError()
        dest.parentFile?.mkdirs()
        val ok = FileOutputStream(dest).use { out ->
            client.retrieveFile(remote, out)
        }
        if (!ok) {
            runCatching { dest.delete() }
            return ClawToolCallResult(
                false,
                "失败: 下载失败 reply=${client.replyString?.trim()}",
                error = "ftp_get_failed"
            )
        }
        return transferResult("ftp", "get", remote, dest)
    }

    private fun getSftp(channel: ChannelSftp, remote: String, localPath: String): ClawToolCallResult {
        val dest = resolveSandboxFile(localPath, forWrite = true)
            ?: return sandboxPathError()
        dest.parentFile?.mkdirs()
        try {
            channel.get(remote, dest.absolutePath)
        } catch (error: Exception) {
            runCatching { dest.delete() }
            return ClawToolCallResult(
                false,
                "失败: SFTP 下载失败: ${error.message}",
                error = "sftp_get_failed"
            )
        }
        return transferResult("sftp", "get", remote, dest)
    }

    private fun putFtp(client: FTPClient, remote: String, localPath: String): ClawToolCallResult {
        val src = resolveSandboxFile(localPath, forWrite = false)
            ?: return sandboxPathError()
        if (!src.isFile) {
            return ClawToolCallResult(false, "失败: 本地文件不存在", error = "local_missing")
        }
        val ok = FileInputStream(src).use { input ->
            client.storeFile(remote, input)
        }
        if (!ok) {
            return ClawToolCallResult(
                false,
                "失败: 上传失败 reply=${client.replyString?.trim()}",
                error = "ftp_put_failed"
            )
        }
        return transferResult("ftp", "put", remote, src)
    }

    private fun putSftp(channel: ChannelSftp, remote: String, localPath: String): ClawToolCallResult {
        val src = resolveSandboxFile(localPath, forWrite = false)
            ?: return sandboxPathError()
        if (!src.isFile) {
            return ClawToolCallResult(false, "失败: 本地文件不存在", error = "local_missing")
        }
        try {
            channel.put(src.absolutePath, remote)
        } catch (error: Exception) {
            return ClawToolCallResult(
                false,
                "失败: SFTP 上传失败: ${error.message}",
                error = "sftp_put_failed"
            )
        }
        return transferResult("sftp", "put", remote, src)
    }

    private fun transferResult(
        protocol: String,
        op: String,
        remote: String,
        local: File
    ): ClawToolCallResult {
        val json = JSONObject()
            .put("op", op)
            .put("protocol", protocol)
            .put("remote_path", remote)
            .put("local_path", local.absolutePath)
            .put("size_bytes", local.length())
        return ClawToolCallResult(success = true, output = json.toString(2))
    }

    private fun sandboxPathError(): ClawToolCallResult =
        ClawToolCallResult(
            false,
            "失败: local_path 必须位于应用沙箱 (files/cache)",
            error = "path_not_sandbox"
        )

    private fun normalizeProtocol(raw: String): String? {
        return when (raw.trim().lowercase()) {
            "", "ftp" -> "ftp"
            "sftp", "ssh" -> "sftp"
            else -> null
        }
    }

    /** Visible for unit tests. */
    fun resolveSandboxFile(raw: String, forWrite: Boolean): File? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            if (!forWrite) return null
            return File(context.cacheDir, "ftp/download_${System.currentTimeMillis()}.bin").also {
                it.parentFile?.mkdirs()
            }
        }
        val file = if (trimmed.startsWith("/")) {
            File(trimmed)
        } else {
            File(context.filesDir, trimmed)
        }
        return file.takeIf { isSandboxPath(it) }
    }

    fun isSandboxPath(file: File): Boolean {
        val roots = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.getExternalFilesDir(null),
            context.externalCacheDir
        )
        val target = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
        return roots.any { root ->
            val rootCanon = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
            target == rootCanon || target.path.startsWith(rootCanon.path + File.separator)
        }
    }
}
