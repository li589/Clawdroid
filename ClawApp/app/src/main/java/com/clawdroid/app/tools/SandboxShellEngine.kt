package com.clawdroid.app.tools

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * App-uid sandbox shell engine: cwd under filesDir/sandbox, allowlisted argv only.
 * Runs inside the [:sandbox] process via [com.clawdroid.app.service.SandboxShellService].
 */
internal class SandboxShellEngine(
    private val sandboxRoot: File
) {
    constructor(context: android.content.Context) : this(
        File(context.filesDir, "sandbox").also { it.mkdirs() }
    )

    fun exec(command: String, timeoutMs: Long = 8_000): ClawToolCallResult {
        val trimmed = command.trim().replace(Regex("""\s+"""), " ")
        if (trimmed.isBlank()) {
            return ClawToolCallResult(false, "失败: 空命令", error = "empty_command")
        }
        val argv = parseAllowlisted(trimmed)
            ?: return ClawToolCallResult(
                false,
                "失败: 命令不在沙箱白名单或不安全: $trimmed",
                error = "sandbox_command_not_allowlisted"
            )
        return try {
            val process = ProcessBuilder(argv)
                .directory(sandboxRoot)
                .redirectErrorStream(false)
                .start()
            val stdout = java.io.ByteArrayOutputStream()
            val stderr = java.io.ByteArrayOutputStream()
            val stdoutReader = kotlin.concurrent.thread(isDaemon = true) {
                drainLimited(process.inputStream, stdout, 8_000)
            }
            val stderrReader = kotlin.concurrent.thread(isDaemon = true) {
                drainLimited(process.errorStream, stderr, 2_000)
            }
            val finished = process.waitFor(timeoutMs.coerceIn(1_000, 30_000), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                process.waitFor(2, TimeUnit.SECONDS)
                return ClawToolCallResult(false, "失败: 超时", error = "sandbox_timeout")
            }
            stdoutReader.join(1_000)
            stderrReader.join(1_000)
            if (stdoutReader.isAlive || stderrReader.isAlive) {
                return ClawToolCallResult(false, "失败: 输出管道未在超时内关闭", error = "sandbox_pipe_timeout")
            }
            val code = process.exitValue()
            val output = buildString {
                appendLine("cwd=${sandboxRoot.absolutePath}")
                appendLine("argv=${argv.joinToString(" ")}")
                appendLine("exit=$code")
                appendLine("stdout=${stdout.toString(Charsets.UTF_8.name())}")
                append("stderr=${stderr.toString(Charsets.UTF_8.name())}")
            }
            ClawToolCallResult(
                success = code == 0,
                output = output,
                error = if (code == 0) null else "exit_$code",
                shellOutput = output
            )
        } catch (error: Exception) {
            ClawToolCallResult(false, "失败: ${error.message}", error = error.message)
        }
    }

    internal fun drainLimited(input: java.io.InputStream, output: java.io.ByteArrayOutputStream, limit: Int) {
        val buffer = ByteArray(8 * 1024)
        var stored = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            if (stored < limit) {
                val take = minOf(read, limit - stored)
                output.write(buffer, 0, take)
                stored += take
            }
        }
    }

    fun isCommandAllowed(command: String): Boolean =
        parseAllowlisted(command.trim().replace(Regex("\\s+"), " ")) != null

    internal fun parseAllowlisted(command: String): List<String>? {
        if (command.isBlank() || METACHAR.containsMatchIn(command)) {
            return null
        }
        when (command) {
            "pwd" -> return listOf("pwd")
            "id" -> return listOf("id")
            "uname -a" -> return listOf("uname", "-a")
            "ls" -> return listOf("ls", "-la")
        }
        REL_LS.matcher(command).let { m ->
            if (m.matches()) {
                val rel = m.group(1) ?: return null
                if (!isSafeRel(rel)) return null
                return listOf("ls", "-la", "--", rel)
            }
        }
        REL_CAT.matcher(command).let { m ->
            if (m.matches()) {
                val rel = m.group(1) ?: return null
                if (!isSafeRel(rel)) return null
                return listOf("cat", "--", rel)
            }
        }
        REL_HEAD.matcher(command).let { m ->
            if (m.matches()) {
                val n = m.group(1)?.toIntOrNull() ?: return null
                val rel = m.group(2) ?: return null
                if (n !in 1..200 || !isSafeRel(rel)) return null
                return listOf("head", "-n", n.toString(), "--", rel)
            }
        }
        REL_TAIL.matcher(command).let { m ->
            if (m.matches()) {
                val n = m.group(1)?.toIntOrNull() ?: return null
                val rel = m.group(2) ?: return null
                if (n !in 1..200 || !isSafeRel(rel)) return null
                return listOf("tail", "-n", n.toString(), "--", rel)
            }
        }
        REL_WC.matcher(command).let { m ->
            if (m.matches()) {
                val rel = m.group(1) ?: return null
                if (!isSafeRel(rel)) return null
                return listOf("wc", "-l", "--", rel)
            }
        }
        REL_MKDIR.matcher(command).let { m ->
            if (m.matches()) {
                val rel = m.group(1) ?: return null
                if (!isSafeRel(rel)) return null
                return listOf("mkdir", "-p", "--", rel)
            }
        }
        ECHO.matcher(command).let { m ->
            if (m.matches()) {
                val text = m.group(1) ?: return null
                if (text.length > 256 || METACHAR.containsMatchIn(text)) return null
                return listOf("echo", text)
            }
        }
        return null
    }

    private fun isSafeRel(rel: String): Boolean {
        if (rel.isBlank() || rel.startsWith("/") || rel.contains('\u0000')) return false
        if (rel.contains("..")) return false
        val target = File(sandboxRoot, rel).canonicalFile
        val root = sandboxRoot.canonicalFile
        return target.path == root.path || target.path.startsWith(root.path + File.separator)
    }

    companion object {
        private val METACHAR = Regex("""[;|&`$<>\n\r]""")
        private val REL_LS = Pattern.compile("""^ls ([A-Za-z0-9._/\-]+)$""")
        private val REL_CAT = Pattern.compile("""^cat ([A-Za-z0-9._/\-]+)$""")
        private val REL_HEAD = Pattern.compile("""^head -n (\d+) ([A-Za-z0-9._/\-]+)$""")
        private val REL_TAIL = Pattern.compile("""^tail -n (\d+) ([A-Za-z0-9._/\-]+)$""")
        private val REL_WC = Pattern.compile("""^wc -l ([A-Za-z0-9._/\-]+)$""")
        private val REL_MKDIR = Pattern.compile("""^mkdir -p ([A-Za-z0-9._/\-]+)$""")
        private val ECHO = Pattern.compile("""^echo ([A-Za-z0-9 ._/\-:]+)$""")

        fun encodeResult(result: ClawToolCallResult): String =
            org.json.JSONObject()
                .put("success", result.success)
                .put("output", result.output)
                .put("error", result.error ?: org.json.JSONObject.NULL)
                .put("shellOutput", result.shellOutput ?: org.json.JSONObject.NULL)
                .toString()

        fun decodeResult(json: String): ClawToolCallResult {
            val obj = org.json.JSONObject(json)
            return ClawToolCallResult(
                success = obj.optBoolean("success"),
                output = obj.optString("output"),
                error = obj.optString("error").takeIf { it.isNotBlank() && it != "null" },
                shellOutput = obj.optString("shellOutput").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }
}
