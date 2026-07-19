package com.clawdroid.app.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

enum class DownloadState {
    Queued,
    Running,
    Paused,
    Completed,
    Failed,
    Cancelled
}

data class DownloadTask(
    val id: String,
    val url: String,
    val destPath: String,
    val expectedSha256: String,
    val resume: Boolean,
    val threads: Int,
    @Volatile var state: DownloadState = DownloadState.Queued,
    @Volatile var downloadedBytes: Long = 0L,
    @Volatile var totalBytes: Long = -1L,
    @Volatile var error: String = "",
    @Volatile var sha256: String = "",
    @Volatile var usedThreads: Int = 1,
    val createdAtMs: Long = System.currentTimeMillis(),
    @Volatile var updatedAtMs: Long = System.currentTimeMillis()
)

class ToolDownloadService(
    private val context: Context
) {
    companion object {
        private const val MAX_FINISHED_TASKS = 40
    }

    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    private val cancelFlags = ConcurrentHashMap<String, Boolean>()

    fun start(
        url: String,
        destPath: String?,
        expectedSha256: String,
        resume: Boolean,
        threads: Int = 1
    ): ClawToolCallResult {
        pruneFinishedTasks()
        CacheDirPruner.pruneNamedCache(context.cacheDir, "downloads")
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            return ClawToolCallResult(false, "失败: 仅支持 http/https", error = "invalid_url")
        }
        val dest = resolveDest(destPath, trimmedUrl)
        dest.parentFile?.mkdirs()
        val id = "dl-${UUID.randomUUID().toString().take(8)}"
        val task = DownloadTask(
            id = id,
            url = trimmedUrl,
            destPath = dest.absolutePath,
            expectedSha256 = expectedSha256.trim().lowercase(),
            resume = resume,
            threads = threads.coerceIn(1, 8)
        )
        tasks[id] = task
        cancelFlags[id] = false
        thread(name = "claw-download-$id", isDaemon = true) {
            runDownload(task)
        }
        return ClawToolCallResult(
            success = true,
            output = "started download_id=$id dest=${dest.absolutePath} resume=$resume threads=${task.threads}"
        )
    }

    fun status(downloadId: String): ClawToolCallResult {
        if (downloadId.isBlank()) {
            val arr = JSONArray()
            tasks.values.sortedByDescending { it.createdAtMs }.forEach { arr.put(taskJson(it)) }
            return ClawToolCallResult(true, arr.toString(2))
        }
        val task = tasks[downloadId]
            ?: return ClawToolCallResult(false, "未知 download_id", error = "unknown_download")
        return ClawToolCallResult(true, taskJson(task).toString(2))
    }

    fun cancel(downloadId: String): ClawToolCallResult {
        val task = tasks[downloadId]
            ?: return ClawToolCallResult(false, "未知 download_id", error = "unknown_download")
        cancelFlags[downloadId] = true
        task.state = DownloadState.Cancelled
        task.updatedAtMs = System.currentTimeMillis()
        return ClawToolCallResult(true, "cancelled $downloadId")
    }

    fun verify(path: String, expectedSha256: String): ClawToolCallResult {
        val file = resolveExisting(path)
            ?: return ClawToolCallResult(false, "失败: 文件不存在", error = "not_found")
        val hash = LocalFileToolService.sha256File(file)
        val expected = expectedSha256.trim().lowercase()
        val ok = expected.isBlank() || expected == hash
        return ClawToolCallResult(
            success = ok,
            output = "path=${file.absolutePath}\nsha256=$hash\nexpected=${expected.ifBlank { "(none)" }}\nmatch=$ok",
            error = if (ok) null else "hash_mismatch"
        )
    }

    private fun runDownload(task: DownloadTask) {
        task.state = DownloadState.Running
        task.updatedAtMs = System.currentTimeMillis()
        try {
            val file = File(task.destPath)
            if (!task.resume && file.exists()) {
                file.delete()
            }
            val meta = probeRemote(task.url)
            val total = meta.contentLength
            val acceptRanges = meta.acceptRanges
            task.totalBytes = total

            val useMulti = task.threads > 1 && acceptRanges && total > 0 &&
                total >= 256 * 1024L && !task.resume
            if (useMulti) {
                task.usedThreads = task.threads
                downloadMulti(task, file, total, task.threads)
            } else {
                task.usedThreads = 1
                downloadSingle(task, file)
            }

            if (task.state == DownloadState.Cancelled) return
            if (task.state == DownloadState.Failed) return

            val hash = LocalFileToolService.sha256File(file)
            task.sha256 = hash
            task.downloadedBytes = file.length()
            if (task.expectedSha256.isNotBlank() && task.expectedSha256 != hash) {
                task.state = DownloadState.Failed
                task.error = "hash_mismatch expected=${task.expectedSha256} actual=$hash"
            } else {
                task.state = DownloadState.Completed
            }
        } catch (error: Exception) {
            if (task.state != DownloadState.Cancelled) {
                task.state = DownloadState.Failed
                task.error = error.message ?: error::class.java.simpleName
            }
        } finally {
            task.updatedAtMs = System.currentTimeMillis()
            pruneFinishedTasks()
        }
    }

    private fun pruneFinishedTasks(keep: Int = MAX_FINISHED_TASKS) {
        val finished = tasks.values.filter {
            it.state == DownloadState.Completed ||
                it.state == DownloadState.Failed ||
                it.state == DownloadState.Cancelled
        }.sortedByDescending { it.updatedAtMs }
        if (finished.size <= keep) return
        finished.drop(keep).forEach { stale ->
            tasks.remove(stale.id)
            cancelFlags.remove(stale.id)
        }
    }

    private data class RemoteMeta(val contentLength: Long, val acceptRanges: Boolean)

    private fun probeRemote(url: String): RemoteMeta {
        val head = open(url).apply {
            requestMethod = "HEAD"
            instanceFollowRedirects = true
        }
        return try {
            val code = head.responseCode
            if (code in 200..399) {
                val len = head.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                val ranges = head.getHeaderField("Accept-Ranges")
                    ?.contains("bytes", ignoreCase = true) == true ||
                    head.getHeaderField("Accept-Ranges").isNullOrBlank()
                RemoteMeta(len, ranges && len > 0)
            } else {
                RemoteMeta(-1L, false)
            }
        } catch (_: Exception) {
            RemoteMeta(-1L, false)
        } finally {
            head.disconnect()
        }
    }

    private fun downloadSingle(task: DownloadTask, file: File) {
        var existing = if (task.resume && file.exists()) file.length() else 0L
        if (!task.resume && file.exists()) {
            file.delete()
            existing = 0L
        }
        val connection = open(task.url).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            if (existing > 0) {
                setRequestProperty("Range", "bytes=$existing-")
            }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299 && code != 206) {
                throw IllegalStateException("HTTP $code")
            }
            val totalHeader = connection.getHeaderField("Content-Length")?.toLongOrNull()
            task.totalBytes = when {
                code == 206 && totalHeader != null -> existing + totalHeader
                totalHeader != null && code == 200 -> totalHeader
                else -> task.totalBytes
            }
            RandomAccessFile(file, "rw").use { raf ->
                if (code == 206) raf.seek(existing) else {
                    raf.setLength(0)
                    existing = 0L
                }
                connection.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelFlags[task.id] == true) {
                            task.state = DownloadState.Cancelled
                            return
                        }
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        task.downloadedBytes = raf.filePointer
                        task.updatedAtMs = System.currentTimeMillis()
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadMulti(task: DownloadTask, file: File, total: Long, threads: Int) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(total) }
        val partSize = (total + threads - 1) / threads
        val progress = AtomicLong(0L)
        val errors = ConcurrentHashMap.newKeySet<String>()
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        try {
            for (i in 0 until threads) {
                val start = i * partSize
                if (start >= total) {
                    latch.countDown()
                    continue
                }
                val end = minOf(total - 1, start + partSize - 1)
                pool.execute {
                    try {
                        downloadRange(task, file, start, end, progress, errors)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await(6, TimeUnit.HOURS)
        } finally {
            pool.shutdownNow()
        }
        if (cancelFlags[task.id] == true) {
            task.state = DownloadState.Cancelled
            return
        }
        if (errors.isNotEmpty()) {
            task.state = DownloadState.Failed
            task.error = errors.first()
        }
        task.downloadedBytes = progress.get().coerceAtMost(total)
    }

    private fun downloadRange(
        task: DownloadTask,
        file: File,
        start: Long,
        end: Long,
        progress: AtomicLong,
        errors: MutableSet<String>
    ) {
        if (cancelFlags[task.id] == true) return
        val connection = open(task.url).apply {
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=$start-$end")
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            if (code != 206 && code != 200) {
                errors += "HTTP $code for range $start-$end"
                return
            }
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(start)
                connection.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var remaining = end - start + 1
                    while (remaining > 0) {
                        if (cancelFlags[task.id] == true) return
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        remaining -= n
                        val done = progress.addAndGet(n.toLong())
                        task.downloadedBytes = done
                        task.updatedAtMs = System.currentTimeMillis()
                    }
                }
            }
        } catch (error: Exception) {
            errors += error.message ?: error::class.java.simpleName
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
        }
    }

    private fun resolveDest(destPath: String?, url: String): File {
        if (!destPath.isNullOrBlank()) {
            val raw = destPath.trim()
            val candidate = File(raw)
            return if (candidate.isAbsolute) candidate else File(context.cacheDir, raw)
        }
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "download.bin" }
        val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
        return File(dir, name)
    }

    private fun resolveExisting(path: String): File? {
        val candidate = File(path.trim())
        val file = if (candidate.isAbsolute) candidate else File(context.cacheDir, path.trim())
        return file.takeIf { it.exists() && it.isFile }
    }

    private fun taskJson(task: DownloadTask): JSONObject {
        val pct = if (task.totalBytes > 0) {
            (task.downloadedBytes * 100.0 / task.totalBytes)
        } else {
            -1.0
        }
        return JSONObject()
            .put("download_id", task.id)
            .put("url", task.url)
            .put("dest_path", task.destPath)
            .put("state", task.state.name)
            .put("downloaded_bytes", task.downloadedBytes)
            .put("total_bytes", task.totalBytes)
            .put("percent", pct)
            .put("threads", task.threads)
            .put("used_threads", task.usedThreads)
            .put("sha256", task.sha256)
            .put("expected_sha256", task.expectedSha256)
            .put("error", task.error)
            .put("updated_at_ms", task.updatedAtMs)
    }
}
