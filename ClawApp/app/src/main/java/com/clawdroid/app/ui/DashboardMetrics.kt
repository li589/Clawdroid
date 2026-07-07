package com.clawdroid.app.ui

import android.os.Debug
import android.os.Process
import com.clawdroid.app.env.AppPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.math.max

internal data class ProcessDashboardMetric(
    val label: String,
    val pid: Int = 0,
    val command: String = "missing",
    val state: String = "?",
    val cpuPercent: Float = 0f,
    val rssBytes: Long = 0L,
    val present: Boolean = false
)

internal data class DashboardRuntimeMetrics(
    val sampledAtEpochMs: Long = 0L,
    val appCpuPercent: Float = 0f,
    val appMemoryBytes: Long = 0L,
    val appJavaHeapBytes: Long = 0L,
    val appNativeHeapBytes: Long = 0L,
    val systemMemoryUsedBytes: Long = 0L,
    val systemMemoryTotalBytes: Long = 0L,
    val loadAverage1: Float = 0f,
    val loadAverage5: Float = 0f,
    val runtimeProcess: ProcessDashboardMetric = ProcessDashboardMetric(label = "ClawRuntime"),
    val magiskProcess: ProcessDashboardMetric = ProcessDashboardMetric(label = "Magisk"),
    val rootBacked: Boolean = false,
    val procFsReadable: Boolean = true,
    val note: String = "等待首轮采样"
)

internal object DashboardMetricsCollector {
    private data class RawProcessSample(
        val pid: Int,
        val command: String,
        val state: String,
        val cpuTicks: Long,
        val rssPages: Long
    )

    private data class ProcFsSnapshot(
        val totalCpuTicks: Long,
        val loadAverage1: Float,
        val loadAverage5: Float,
        val memTotalBytes: Long,
        val memAvailableBytes: Long,
        val appCpuTicks: Long
    )

    private data class CachedRootSamples(
        val sampledAtEpochMs: Long,
        val samples: Map<String, RawProcessSample>
    )

    private val previousProcessTicks = mutableMapOf<String, Long>()
    private var previousTotalCpuTicks: Long? = null
    private var cachedRootSamples = CachedRootSamples(
        sampledAtEpochMs = 0L,
        samples = emptyMap()
    )
    private val cpuCoreCount = max(Runtime.getRuntime().availableProcessors(), 1)
    private const val defaultPageSizeBytes = 4096L
    private const val rootSampleIntervalMs = 3_000L

    suspend fun sample(rootAvailable: Boolean): DashboardRuntimeMetrics = withContext(Dispatchers.IO) {
        val directProcFs = runCatching { readProcFsSnapshot() }.getOrNull()
        val procFs = directProcFs ?: if (rootAvailable) {
            readRootProcFsSnapshot(Process.myPid())
        } else {
            null
        }
        val normalizedProcFs = if (
            rootAvailable &&
            procFs != null &&
            procFs.totalCpuTicks <= 0L &&
            procFs.memTotalBytes <= 0L &&
            procFs.loadAverage1 <= 0f &&
            procFs.loadAverage5 <= 0f
        ) {
            readRootProcFsSnapshot(Process.myPid()) ?: procFs
        } else {
            procFs
        }
        val appMemoryInfo = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val currentTotalTicks = normalizedProcFs?.totalCpuTicks ?: 0L
        val previousTotalTicks = previousTotalCpuTicks

        val appCpuPercent = computeCpuPercent(
            key = "app:${Process.myPid()}",
            totalCpuTicks = currentTotalTicks,
            previousTotalCpuTicks = previousTotalTicks,
            processCpuTicks = normalizedProcFs?.appCpuTicks ?: 0L
        )

        val rootSamples = if (rootAvailable) {
            readCachedRootProcessSamples()
        } else {
            emptyMap()
        }

        val runtimeProcess = buildProcessMetric(
            label = "ClawRuntime",
            sample = rootSamples["runtime"],
            currentTotalCpuTicks = currentTotalTicks,
            previousTotalCpuTicks = previousTotalTicks
        )
        val magiskProcess = buildProcessMetric(
            label = "Magisk",
            sample = rootSamples["magisk"],
            currentTotalCpuTicks = currentTotalTicks,
            previousTotalCpuTicks = previousTotalTicks
        )

        previousTotalCpuTicks = currentTotalTicks

        DashboardRuntimeMetrics(
            sampledAtEpochMs = System.currentTimeMillis(),
            appCpuPercent = appCpuPercent,
            appMemoryBytes = appMemoryInfo.totalPss.toLong() * 1024L,
            appJavaHeapBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            appNativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            systemMemoryUsedBytes = ((normalizedProcFs?.memTotalBytes ?: 0L) - (normalizedProcFs?.memAvailableBytes ?: 0L))
                .coerceAtLeast(0L),
            systemMemoryTotalBytes = normalizedProcFs?.memTotalBytes ?: 0L,
            loadAverage1 = normalizedProcFs?.loadAverage1 ?: 0f,
            loadAverage5 = normalizedProcFs?.loadAverage5 ?: 0f,
            runtimeProcess = runtimeProcess,
            magiskProcess = magiskProcess,
            rootBacked = rootAvailable,
            procFsReadable = normalizedProcFs != null,
            note = buildSamplingNote(
                rootAvailable = rootAvailable,
                procFsReadable = normalizedProcFs != null,
                runtimeProcess = runtimeProcess,
                magiskProcess = magiskProcess
            )
        )
    }

    private fun buildSamplingNote(
        rootAvailable: Boolean,
        procFsReadable: Boolean,
        runtimeProcess: ProcessDashboardMetric,
        magiskProcess: ProcessDashboardMetric
    ): String {
        return when {
            !procFsReadable -> "当前设备限制访问 /proc 指标；已降级为仅展示可获取的内存与 Root 进程状态"
            !rootAvailable -> "当前仅采集 App 与系统指标；Root 可用后会补充 Runtime 与 Magisk 进程数据"
            !runtimeProcess.present && !magiskProcess.present -> "Root 已可用，但尚未发现 ClawRuntime 与 Magisk 进程"
            !runtimeProcess.present -> "已采集 Magisk 守护进程；ClawRuntime 进程暂未发现"
            !magiskProcess.present -> "已采集 ClawRuntime 进程；Magisk 守护进程暂未发现"
            else -> "App 与系统指标每 1 秒刷新一次，Root 进程指标最多每 3 秒刷新一次"
        }
    }

    private suspend fun readCachedRootProcessSamples(): Map<String, RawProcessSample> {
        val now = System.currentTimeMillis()
        if (now - cachedRootSamples.sampledAtEpochMs < rootSampleIntervalMs) {
            return cachedRootSamples.samples
        }
        val latest = readRootProcessSamples()
        cachedRootSamples = CachedRootSamples(
            sampledAtEpochMs = now,
            samples = latest
        )
        return latest
    }

    private fun buildProcessMetric(
        label: String,
        sample: RawProcessSample?,
        currentTotalCpuTicks: Long,
        previousTotalCpuTicks: Long?
    ): ProcessDashboardMetric {
        if (sample == null || sample.pid <= 0) {
            return ProcessDashboardMetric(label = label)
        }
        return ProcessDashboardMetric(
            label = label,
            pid = sample.pid,
            command = sample.command,
            state = sample.state,
            cpuPercent = computeCpuPercent(
                key = "${label.lowercase()}:${sample.pid}",
                totalCpuTicks = currentTotalCpuTicks,
                previousTotalCpuTicks = previousTotalCpuTicks,
                processCpuTicks = sample.cpuTicks
            ),
            rssBytes = sample.rssPages * defaultPageSizeBytes,
            present = true
        )
    }

    private fun computeCpuPercent(
        key: String,
        totalCpuTicks: Long,
        previousTotalCpuTicks: Long?,
        processCpuTicks: Long
    ): Float {
        val previousProcess = previousProcessTicks.put(key, processCpuTicks)
        if (previousTotalCpuTicks == null || previousProcess == null) {
            return 0f
        }
        val totalDelta = totalCpuTicks - previousTotalCpuTicks
        val processDelta = processCpuTicks - previousProcess
        if (totalDelta <= 0L || processDelta <= 0L) {
            return 0f
        }
        return ((processDelta.toDouble() / totalDelta.toDouble()) * 100.0 * cpuCoreCount)
            .toFloat()
            .coerceAtLeast(0f)
    }

    private fun readProcFsSnapshot(): ProcFsSnapshot {
        val totalCpuTicks = File("/proc/stat").useLines { lines ->
            lines.firstOrNull()
                ?.split(Regex("\\s+"))
                ?.drop(1)
                ?.mapNotNull { it.toLongOrNull() }
                ?.sum()
        } ?: 0L

        val loadParts = File("/proc/loadavg").readText().trim().split(Regex("\\s+"))
        val meminfo = File("/proc/meminfo").useLines { lines ->
            lines.associate { line ->
                val parts = line.split(':', limit = 2)
                parts[0] to (parts.getOrNull(1)?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull() ?: 0L)
            }
        }
        val appStat = parseProcStat(File("/proc/self/stat").readText())

        return ProcFsSnapshot(
            totalCpuTicks = totalCpuTicks,
            loadAverage1 = loadParts.getOrNull(0)?.toFloatOrNull() ?: 0f,
            loadAverage5 = loadParts.getOrNull(1)?.toFloatOrNull() ?: 0f,
            memTotalBytes = (meminfo["MemTotal"] ?: 0L) * 1024L,
            memAvailableBytes = (meminfo["MemAvailable"] ?: 0L) * 1024L,
            appCpuTicks = appStat?.cpuTicks ?: 0L
        )
    }

    private suspend fun readRootProcFsSnapshot(appPid: Int): ProcFsSnapshot? {
        val command = """
            total_ticks=${'$'}(cat /proc/stat 2>/dev/null | head -n 1 | awk '{sum=0; for (i=2; i<=NF; i++) sum += ${'$'}i; print sum}')
            load_line=${'$'}(cat /proc/loadavg 2>/dev/null)
            mem_total=${'$'}(cat /proc/meminfo 2>/dev/null | awk '/MemTotal:/ {print ${'$'}2; exit}')
            mem_available=${'$'}(cat /proc/meminfo 2>/dev/null | awk '/MemAvailable:/ {print ${'$'}2; exit}')
            app_stat=${'$'}(cat /proc/$appPid/stat 2>/dev/null)
            echo "TOTAL=${'$'}total_ticks"
            echo "LOAD=${'$'}load_line"
            echo "MEMTOTAL=${'$'}mem_total"
            echo "MEMAVAILABLE=${'$'}mem_available"
            echo "APPSTAT=${'$'}app_stat"
        """.trimIndent()
        val result = AppPermissionManager.runRawRootCommand(command)
        if (!result.success) {
            return null
        }
        val lines = result.output.lines().associate { line ->
            val parts = line.split('=', limit = 2)
            parts.firstOrNull().orEmpty() to parts.getOrElse(1) { "" }
        }
        val appStat = lines["APPSTAT"]?.takeIf { it.isNotBlank() }?.let(::parseProcStat)
        val loadParts = lines["LOAD"].orEmpty().trim().split(Regex("\\s+"))
        return ProcFsSnapshot(
            totalCpuTicks = lines["TOTAL"]?.toLongOrNull() ?: 0L,
            loadAverage1 = loadParts.getOrNull(0)?.toFloatOrNull() ?: 0f,
            loadAverage5 = loadParts.getOrNull(1)?.toFloatOrNull() ?: 0f,
            memTotalBytes = (lines["MEMTOTAL"]?.toLongOrNull() ?: 0L) * 1024L,
            memAvailableBytes = (lines["MEMAVAILABLE"]?.toLongOrNull() ?: 0L) * 1024L,
            appCpuTicks = appStat?.cpuTicks ?: 0L
        )
    }

    private suspend fun readRootProcessSamples(): Map<String, RawProcessSample> {
        val command = """
            report_proc() {
              label="${'$'}1"
              name="${'$'}2"
              pid=${'$'}(pidof "${'$'}name" 2>/dev/null | awk '{print ${'$'}1}')
              if [ -n "${'$'}pid" ] && [ -r "/proc/${'$'}pid/stat" ]; then
                stat=${'$'}(cat "/proc/${'$'}pid/stat")
                set -- ${'$'}stat
                comm=${'$'}2
                comm=${'$'}{comm#(}
                comm=${'$'}{comm%)}
                state=${'$'}3
                proc_ticks=${'$'}(( ${'$'}14 + ${'$'}15 ))
                rss_pages=${'$'}24
                echo "PROC|${'$'}label|${'$'}pid|${'$'}comm|${'$'}state|${'$'}proc_ticks|${'$'}rss_pages"
              else
                echo "PROC|${'$'}label|0|missing|?|0|0"
              fi
            }
            report_proc runtime clawdroid-runtime
            report_proc magisk magiskd
        """.trimIndent()

        val process = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (_: Exception) {
            return emptyMap()
        }

        return try {
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
                emptyMap()
            } else {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.lineSequence()
                        .mapNotNull(::parseRootProcessLine)
                        .associateBy { it.first }
                        .mapValues { it.value.second }
                }
            }
        } catch (_: Exception) {
            emptyMap()
        } finally {
            process.destroy()
        }
    }

    private fun parseRootProcessLine(line: String): Pair<String, RawProcessSample>? {
        val parts = line.trim().split('|')
        if (parts.size < 7 || parts[0] != "PROC") {
            return null
        }
        val label = parts[1]
        return label to RawProcessSample(
            pid = parts[2].toIntOrNull() ?: 0,
            command = parts[3],
            state = parts[4],
            cpuTicks = parts[5].toLongOrNull() ?: 0L,
            rssPages = parts[6].toLongOrNull() ?: 0L
        )
    }

    private fun parseProcStat(raw: String): RawProcessSample? {
        val openIndex = raw.indexOf('(')
        val closeIndex = raw.lastIndexOf(')')
        if (openIndex <= 0 || closeIndex <= openIndex) {
            return null
        }
        val pid = raw.substring(0, openIndex).trim().toIntOrNull() ?: return null
        val command = raw.substring(openIndex + 1, closeIndex)
        val tail = raw.substring(closeIndex + 1).trim().split(Regex("\\s+"))
        if (tail.size < 22) {
            return null
        }
        val state = tail[0]
        val utime = tail[11].toLongOrNull() ?: 0L
        val stime = tail[12].toLongOrNull() ?: 0L
        val rssPages = tail[21].toLongOrNull() ?: 0L
        return RawProcessSample(
            pid = pid,
            command = command,
            state = state,
            cpuTicks = utime + stime,
            rssPages = rssPages
        )
    }
}
