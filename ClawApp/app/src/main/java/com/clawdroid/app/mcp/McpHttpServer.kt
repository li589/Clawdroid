package com.clawdroid.app.mcp

import com.clawdroid.app.fault.FaultCodes
import com.clawdroid.app.fault.FaultIsolation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Localhost MCP HTTP+SSE transport (2024-11-05 style) for ADB port-forward.
 *
 * Endpoints:
 * - GET  / or /health     discovery / liveness
 * - GET  /sse             open SSE session, emits `endpoint` event
 * - POST /message?...     client → server JSON-RPC (SSE clients)
 * - POST /mcp             request/response JSON-RPC
 */
class McpHttpServer(
    private val handler: McpJsonRpcHandler,
    private val port: Int,
    private val authToken: String,
    private val bindLoopbackOnly: Boolean = true
) {
    private val running = AtomicBoolean(false)
    private var executor = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap<String, SseSession>()
    private var serverSocket: ServerSocket? = null

    val isRunning: Boolean get() = running.get()
    val listenPort: Int get() = port

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        if (executor.isShutdown) {
            executor = Executors.newCachedThreadPool()
        }
        val address = if (bindLoopbackOnly) {
            InetAddress.getByName("127.0.0.1")
        } else {
            null
        }
        val socket = ServerSocket(port, 50, address)
        serverSocket = socket
        executor.execute {
            try {
                while (running.get()) {
                    val client = try {
                        socket.accept()
                    } catch (_: SocketException) {
                        break
                    }
                    executor.execute { handleClient(client) }
                }
            } finally {
                running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        sessions.values.forEach { it.close() }
        sessions.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        executor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 60_000
        try {
            val request = readHttpRequest(socket.getInputStream())
            val method = request.method
            val pathOnly = request.path
            val queryMap = request.query
            val headers = request.headers

            if (!authorized(headers, queryMap)) {
                writeHttp(socket.getOutputStream(), 401, "text/plain; charset=utf-8", "unauthorized")
                return
            }

            when {
                method == "GET" && (pathOnly == "/" || pathOnly == "/health") -> {
                    val body =
                        """{"ok":true,"server":"clawdroid-mcp","protocol":"${McpJsonRpcHandler.PROTOCOL_VERSION}","capabilities":["tools","prompts","resources"],"sse":"/sse","mcp":"/mcp"}"""
                    writeHttp(socket.getOutputStream(), 200, "application/json; charset=utf-8", body)
                }
                method == "GET" && pathOnly == "/sse" -> {
                    handleSse(socket, headers)
                }
                method == "POST" && (pathOnly == "/message" || pathOnly == "/mcp") -> {
                    if (request.body.size > 2 * 1024 * 1024) {
                        writeHttp(socket.getOutputStream(), 413, "text/plain; charset=utf-8", "payload too large")
                        return
                    }
                    val body = String(request.body, StandardCharsets.UTF_8)
                    if (pathOnly == "/message") {
                        handleMessagePost(socket, queryMap, body)
                    } else {
                        handleMcpPost(socket, body)
                    }
                }
                method == "OPTIONS" -> {
                    writeHttp(
                        socket.getOutputStream(),
                        204,
                        "text/plain; charset=utf-8",
                        "",
                        extraHeaders = corsHeaders()
                    )
                }
                else -> writeHttp(socket.getOutputStream(), 404, "text/plain; charset=utf-8", "not found")
            }
        } catch (error: Throwable) {
            FaultIsolation.recordFault("mcp:http", error)
            runCatching { socket.close() }
        }
    }

    private fun safeHandleRpc(body: String): String? {
        return try {
            runBlocking(Dispatchers.IO) { handler.handle(body) }
        } catch (error: Throwable) {
            FaultIsolation.recordFault("mcp:rpc", error)
            val id = runCatching {
                val request = JSONObject(body)
                if (request.has("id") && !request.isNull("id")) request.get("id") else null
            }.getOrNull()
            JSONObject()
                .put("jsonrpc", McpJsonRpcHandler.JSONRPC)
                .put(
                    "error",
                    JSONObject()
                        .put("code", -32603)
                        .put(
                            "message",
                            "Internal error (${FaultCodes.MCP_INTERNAL}): " +
                                (error.message ?: error::class.java.simpleName)
                        )
                )
                .put("id", id ?: JSONObject.NULL)
                .toString()
        }
    }

    private fun handleSse(socket: Socket, headers: Map<String, String>) {
        val origin = headers["origin"]
        if (origin != null && !isAllowedOrigin(origin)) {
            writeHttp(socket.getOutputStream(), 403, "text/plain; charset=utf-8", "origin not allowed")
            return
        }
        val sessionId = newMcpSessionId()
        val out = socket.getOutputStream()
        val preamble = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: keep-alive\r\n")
            corsHeaders().forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        out.write(preamble.toByteArray(StandardCharsets.UTF_8))
        out.flush()

        // Absolute path relative to host; clients append to origin. Auth via header preferred.
        val messagePath = "/message?sessionId=$sessionId"
        writeSseEvent(out, "endpoint", messagePath)

        val session = SseSession(sessionId, socket, out)
        sessions[sessionId] = session
        try {
            while (running.get() && !socket.isClosed) {
                Thread.sleep(15_000)
                // SSE comment heartbeat (not a JSON-RPC notification)
                writeSseComment(out, "keepalive")
            }
        } catch (_: Exception) {
            // client gone
        } finally {
            sessions.remove(sessionId)
            session.close()
        }
    }

    private fun handleMessagePost(socket: Socket, query: Map<String, String>, body: String) {
        val sessionId = query["sessionId"].orEmpty()
        val session = sessions[sessionId]
        if (session == null) {
            writeHttp(socket.getOutputStream(), 404, "text/plain; charset=utf-8", "unknown session")
            return
        }
        val response = safeHandleRpc(body)
        writeHttp(
            socket.getOutputStream(),
            202,
            "text/plain; charset=utf-8",
            "accepted",
            extraHeaders = corsHeaders()
        )
        if (!response.isNullOrBlank()) {
            writeSseEvent(session.output, "message", response)
        }
    }

    private fun handleMcpPost(socket: Socket, body: String) {
        val response = safeHandleRpc(body)
        if (response.isNullOrBlank()) {
            writeHttp(
                socket.getOutputStream(),
                204,
                "text/plain; charset=utf-8",
                "",
                extraHeaders = corsHeaders()
            )
        } else {
            writeHttp(
                socket.getOutputStream(),
                200,
                "application/json; charset=utf-8",
                response,
                extraHeaders = corsHeaders()
            )
        }
    }

    private fun authorized(headers: Map<String, String>, query: Map<String, String>): Boolean {
        // Never run open; token is always generated by McpServerController.
        if (authToken.isBlank()) {
            return false
        }
        val bearer = extractBearerToken(headers["authorization"])
        val headerToken = headers["x-clawdroid-token"].orEmpty()
        val queryToken = query["token"].orEmpty()
        return constantTimeEquals(bearer, authToken) ||
            constantTimeEquals(headerToken, authToken) ||
            constantTimeEquals(queryToken, authToken)
    }

    private fun extractBearerToken(header: String?): String {
        if (header.isNullOrBlank()) return ""
        val trimmed = header.trim()
        return if (trimmed.length > 7 && trimmed.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) {
            trimmed.substring(7).trim()
        } else {
            ""
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    private fun isAllowedOrigin(origin: String): Boolean {
        return origin == "null" ||
            origin.startsWith("http://127.0.0.1") ||
            origin.startsWith("http://localhost") ||
            origin.startsWith("https://chatgpt.com") ||
            origin.startsWith("https://claude.ai")
    }

    private fun writeHttp(
        out: OutputStream,
        code: Int,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        if (bytes.isNotEmpty()) {
            out.write(bytes)
        }
        out.flush()
        runCatching { out.close() }
    }

    private fun writeSseEvent(out: OutputStream, event: String, data: String) {
        val payload = buildString {
            append("event: ").append(event).append("\n")
            data.lineSequence().forEach { line ->
                append("data: ").append(line).append("\n")
            }
            append("\n")
        }
        synchronized(out) {
            out.write(payload.toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
    }

    private fun writeSseComment(out: OutputStream, comment: String) {
        val payload = ": $comment\n\n"
        synchronized(out) {
            out.write(payload.toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
    }

    private fun corsHeaders(): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Headers" to "Content-Type, Authorization, X-Clawdroid-Token",
        "Access-Control-Allow-Methods" to "GET, POST, OPTIONS"
    )

    private data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    /**
     * Reads one HTTP request from a raw stream.
     * Avoids BufferedReader so Content-Length body bytes are not corrupted by char decoding.
     */
    private fun readHttpRequest(input: InputStream): HttpRequest {
        val headerBytes = readUntilHeaderEnd(input)
        val headerText = String(headerBytes, StandardCharsets.UTF_8)
        val lines = headerText.split("\r\n", "\n").filter { it.isNotEmpty() }
        require(lines.isNotEmpty()) { "empty request" }
        val requestLine = lines.first()
        val parts = requestLine.split(" ")
        require(parts.size >= 2) { "bad request line" }
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = linkedMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }
        }
        val pathOnly = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', missingDelimiterValue = ""))
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        require(length >= 0) { "negative content-length" }
        val body = if (length == 0) {
            ByteArray(0)
        } else {
            readExact(input, length)
        }
        return HttpRequest(method, pathOnly, query, headers, body)
    }

    private fun readUntilHeaderEnd(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream(512)
        var state = 0 // 0 none, 1 \r, 2 \r\n, 3 \r\n\r; 10 after lone \n
        while (true) {
            val b = input.read()
            if (b < 0) {
                throw EOFException("unexpected EOF while reading headers")
            }
            buffer.write(b)
            when (state) {
                0 -> state = when (b) {
                    '\r'.code -> 1
                    '\n'.code -> 10
                    else -> 0
                }
                1 -> state = when (b) {
                    '\n'.code -> 2
                    '\r'.code -> 1
                    else -> 0
                }
                2 -> state = when (b) {
                    '\r'.code -> 3
                    '\n'.code -> return buffer.toByteArray() // \r\n\n
                    else -> 0
                }
                3 -> {
                    if (b == '\n'.code) {
                        return buffer.toByteArray()
                    }
                    state = 0
                }
                10 -> {
                    if (b == '\n'.code) {
                        return buffer.toByteArray()
                    }
                    state = when (b) {
                        '\r'.code -> 1
                        '\n'.code -> 10
                        else -> 0
                    }
                }
            }
            if (buffer.size() > 64 * 1024) {
                throw IllegalArgumentException("headers too large")
            }
        }
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(buffer, offset, length - offset)
            if (n < 0) {
                throw EOFException("unexpected EOF while reading body")
            }
            offset += n
        }
        return buffer
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = pair.substring(0, idx)
            val value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
            key to value
        }.toMap()
    }

    private class SseSession(
        val id: String,
        private val socket: Socket,
        val output: OutputStream
    ) {
        fun close() {
            runCatching { socket.close() }
        }
    }
}
