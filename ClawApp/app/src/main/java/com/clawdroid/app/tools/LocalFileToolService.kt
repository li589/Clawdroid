package com.clawdroid.app.tools

import android.content.Context
import com.clawdroid.app.runtime.ClawRuntimeClient
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64

/**
 * Hybrid file tools: app sandbox locally; allowlisted absolute paths via Runtime.
 */
class LocalFileToolService(
    private val context: Context,
    private val runtimeClient: ClawRuntimeClient
) {
    suspend fun read(
        path: String,
        mode: String = "bytes",
        offset: Long = 0,
        maxBytes: Int = 65536,
        lineStart: Int = 1,
        lineLimit: Int = 200,
        delimiter: String = ",",
        column: Int = 0
    ): ClawToolCallResult {
        val resolved = resolvePath(path)
        return if (isSandboxPath(resolved)) {
            readLocal(resolved, mode, offset, maxBytes, lineStart, lineLimit, delimiter, column)
        } else {
            readViaRuntime(resolved, mode, offset, maxBytes, lineStart, lineLimit, delimiter, column)
        }
    }

    suspend fun write(
        path: String,
        content: String,
        append: Boolean
    ): ClawToolCallResult {
        val resolved = resolvePath(path)
        return if (isSandboxPath(resolved)) {
            runCatching {
                resolved.parentFile?.mkdirs()
                if (append) resolved.appendText(content) else resolved.writeText(content)
                ClawToolCallResult(
                    success = true,
                    output = "成功: wrote ${content.toByteArray().size} bytes to ${resolved.absolutePath} append=$append"
                )
            }.getOrElse {
                ClawToolCallResult(false, "失败: ${it.message}", error = it.message)
            }
        } else {
            runtimeClient.writeFileLimited(
                path = resolved.absolutePath,
                content = content,
                append = append
            ).fold(
                onSuccess = {
                    ClawToolCallResult(true, "成功: runtime write path=${it.path} bytes=${it.writtenBytes} append=${it.append}")
                },
                onFailure = {
                    ClawToolCallResult(false, "失败: ${it.message}", error = it.message)
                }
            )
        }
    }

    suspend fun replace(
        path: String,
        find: String,
        replace: String,
        regex: Boolean,
        lineStart: Int?,
        lineEnd: Int?
    ): ClawToolCallResult {
        if (find.isEmpty()) {
            return ClawToolCallResult(false, "失败: find 不能为空", error = "empty_find")
        }
        if (lineStart != null && lineStart < 1) {
            return ClawToolCallResult(false, "失败: line_start 必须 >= 1", error = "invalid_line_range")
        }
        if (lineEnd != null && lineStart != null && lineEnd < lineStart) {
            return ClawToolCallResult(false, "失败: line_end 不能小于 line_start", error = "invalid_line_range")
        }
        val pattern = if (regex) {
            runCatching { Regex(find) }.getOrElse {
                return ClawToolCallResult(
                    false,
                    "失败: 非法正则: ${it.message}",
                    error = "invalid_regex"
                )
            }
        } else {
            null
        }

        val resolved = resolvePath(path)
        val original = if (isSandboxPath(resolved)) {
            if (!resolved.exists()) {
                return ClawToolCallResult(false, "失败: 文件不存在", error = "not_found")
            }
            resolved.readText()
        } else {
            val full = runtimeClient.readFileFullyLimited(path = resolved.absolutePath, maxTotalBytes = 2_000_000)
                .getOrElse {
                    return ClawToolCallResult(false, "失败: ${it.message}", error = it.message)
                }
            String(full, Charsets.UTF_8)
        }

        val lines = original.split('\n')
        if (lines.isEmpty()) {
            return ClawToolCallResult(false, "失败: 文件为空", error = "empty_file")
        }
        val startIdx = ((lineStart ?: 1) - 1).coerceIn(0, lines.size)
        val endExclusive = (lineEnd ?: lines.size).coerceIn(startIdx, lines.size)
        if (startIdx >= lines.size) {
            return ClawToolCallResult(false, "失败: line_start 超出文件行数", error = "invalid_line_range")
        }
        val head = lines.subList(0, startIdx)
        val mid = lines.subList(startIdx, endExclusive)
        val tail = lines.subList(endExclusive, lines.size)

        var replacements = 0
        val updatedMid = mid.map { line ->
            if (pattern != null) {
                val count = pattern.findAll(line).count()
                replacements += count
                pattern.replace(line, replace)
            } else {
                val count = line.split(find).size - 1
                if (count > 0) {
                    replacements += count
                    line.replace(find, replace)
                } else {
                    line
                }
            }
        }
        val newText = (head + updatedMid + tail).joinToString("\n")
        val writeResult = write(resolved.absolutePath, newText, append = false)
        return if (!writeResult.success) {
            writeResult
        } else {
            ClawToolCallResult(
                success = true,
                output = "成功: replacements=$replacements path=${resolved.absolutePath} lines=${startIdx + 1}..$endExclusive"
            )
        }
    }

    suspend fun stat(path: String, computeHash: Boolean): ClawToolCallResult {
        val resolved = resolvePath(path)
        if (isSandboxPath(resolved)) {
            if (!resolved.exists()) {
                return ClawToolCallResult(false, "失败: 文件不存在", error = "not_found")
            }
            val hash = if (computeHash) sha256File(resolved) else ""
            return ClawToolCallResult(
                success = true,
                output = buildString {
                    appendLine("path=${resolved.absolutePath}")
                    appendLine("size=${resolved.length()}")
                    appendLine("mtime_ms=${resolved.lastModified()}")
                    appendLine("is_file=${resolved.isFile}")
                    if (computeHash) append("sha256=$hash")
                }
            )
        }
        return runtimeClient.statFileLimited(path = resolved.absolutePath, computeHash = computeHash).fold(
            onSuccess = {
                ClawToolCallResult(
                    success = true,
                    output = "path=${it.path}\nsize=${it.size}\nmtime_ms=${it.mtimeMs}\nsha256=${it.sha256}"
                )
            },
            onFailure = {
                ClawToolCallResult(false, "失败: ${it.message}", error = it.message)
            }
        )
    }

    private fun readLocal(
        file: File,
        mode: String,
        offset: Long,
        maxBytes: Int,
        lineStart: Int,
        lineLimit: Int,
        delimiter: String,
        column: Int
    ): ClawToolCallResult {
        if (!file.exists()) {
            return ClawToolCallResult(false, "失败: 文件不存在", error = "not_found")
        }
        return when {
            mode.equals("lines", ignoreCase = true) || mode.equals("columns", ignoreCase = true) -> {
                formatLinesResult(
                    path = file.absolutePath,
                    lines = file.readLines(),
                    mode = mode,
                    lineStart = lineStart,
                    lineLimit = lineLimit,
                    delimiter = delimiter,
                    column = column
                )
            }
            else -> {
                val bytes = ByteArray(maxBytes.coerceIn(1, 1_048_576))
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset.coerceAtLeast(0))
                    val read = raf.read(bytes)
                    val chunk = if (read > 0) bytes.copyOf(read) else ByteArray(0)
                    ClawToolCallResult(
                        success = true,
                        output = buildString {
                            appendLine("path=${file.absolutePath}")
                            appendLine("mode=bytes offset=$offset read_bytes=${chunk.size} total_size=${file.length()}")
                            append("content_base64=${Base64.getEncoder().encodeToString(chunk)}")
                        }
                    )
                }
            }
        }
    }

    private suspend fun readViaRuntime(
        file: File,
        mode: String,
        offset: Long,
        maxBytes: Int,
        lineStart: Int,
        lineLimit: Int,
        delimiter: String,
        column: Int
    ): ClawToolCallResult {
        if (mode.equals("lines", ignoreCase = true) || mode.equals("columns", ignoreCase = true)) {
            val full = runtimeClient.readFileFullyLimited(path = file.absolutePath, maxTotalBytes = 2_000_000)
                .getOrElse {
                    return ClawToolCallResult(false, "失败: ${it.message}", error = it.message)
                }
            val text = String(full, Charsets.UTF_8)
            return formatLinesResult(
                path = file.absolutePath,
                lines = text.split('\n'),
                mode = mode,
                lineStart = lineStart,
                lineLimit = lineLimit,
                delimiter = delimiter,
                column = column
            )
        }
        return runtimeClient.readFileLimited(
            path = file.absolutePath,
            offset = offset,
            maxBytes = maxBytes
        ).fold(
            onSuccess = { result ->
                ClawToolCallResult(
                    success = true,
                    output = buildString {
                        appendLine("path=${result.path}")
                        appendLine("mode=bytes offset=${result.offset} read_bytes=${result.readBytes} total_size=${result.totalSize} eof=${result.eof}")
                        append("content_base64=${result.contentBase64}")
                    }
                )
            },
            onFailure = {
                ClawToolCallResult(false, "失败: ${it.message}", error = it.message)
            }
        )
    }

    private fun formatLinesResult(
        path: String,
        lines: List<String>,
        mode: String,
        lineStart: Int,
        lineLimit: Int,
        delimiter: String,
        column: Int
    ): ClawToolCallResult {
        if (mode.equals("columns", ignoreCase = true) && column < 0) {
            return ClawToolCallResult(false, "失败: column 必须 >= 0", error = "invalid_column")
        }
        val start = (lineStart - 1).coerceAtLeast(0)
        val slice = lines.drop(start).take(lineLimit.coerceAtLeast(1))
        val delim = delimiter.ifEmpty { "," }
        return ClawToolCallResult(
            success = true,
            output = buildString {
                appendLine("path=$path")
                if (mode.equals("columns", ignoreCase = true)) {
                    appendLine("mode=columns delimiter=$delim column=$column line_start=${start + 1} count=${slice.size} total_lines=${lines.size}")
                    slice.forEachIndexed { index, line ->
                        val cols = line.split(delim)
                        val cell = cols.getOrNull(column) ?: ""
                        appendLine("${start + index + 1}|$cell")
                    }
                } else {
                    appendLine("mode=lines line_start=${start + 1} count=${slice.size} total_lines=${lines.size}")
                    slice.forEachIndexed { index, line ->
                        appendLine("${start + index + 1}|$line")
                    }
                }
            }
        )
    }

    private fun resolvePath(raw: String): File {
        val trimmed = raw.trim()
        if (trimmed.startsWith("/")) return File(trimmed)
        return File(context.filesDir, trimmed)
    }

    private fun isSandboxPath(file: File): Boolean {
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

    companion object {
        fun sha256File(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { b -> "%02x".format(b) }
        }

        fun sha256Bytes(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { b -> "%02x".format(b) }
        }
    }
}
