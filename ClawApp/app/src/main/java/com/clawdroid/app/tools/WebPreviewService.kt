package com.clawdroid.app.tools

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.LinkedHashSet

/**
 * Lightweight web preview: fetch HTML, extract title/text/image URLs (no WebView).
 */
class WebPreviewService {
    fun preview(
        url: String,
        maxBytes: Int = 512_000,
        includeImages: Boolean = true
    ): ClawToolCallResult {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return ClawToolCallResult(false, "失败: 仅支持 http/https", error = "invalid_url")
        }
        return try {
            val connection = (URL(trimmed).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ClawdroidWebPreview/0.3")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                return ClawToolCallResult(false, "失败: HTTP $code", error = "http_$code")
            }
            val contentType = connection.contentType.orEmpty()
            val charset = charsetFrom(contentType)
            val raw = connection.inputStream.use { input ->
                val buf = ByteArray(maxBytes.coerceIn(8_192, 2_000_000))
                var total = 0
                while (total < buf.size) {
                    val n = input.read(buf, total, buf.size - total)
                    if (n <= 0) break
                    total += n
                }
                String(buf, 0, total, charset)
            }
            val title = Regex(
                """<title[^>]*>(.*?)</title>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                .replace(Regex("\\s+"), " ")
            val text = stripHtml(raw).take(4_000)
            val images = if (includeImages) extractImages(raw, trimmed) else emptyList()
            val json = JSONObject()
                .put("url", trimmed)
                .put("final_url", connection.url?.toString() ?: trimmed)
                .put("http_status", code)
                .put("content_type", contentType)
                .put("title", title)
                .put("text", text)
                .put("text_chars", text.length)
                .put("images", JSONArray(images))
                .put("image_count", images.size)
            ClawToolCallResult(success = true, output = json.toString(2))
        } catch (error: Exception) {
            ClawToolCallResult(false, "失败: ${error.message}", error = error.message)
        }
    }

    private fun charsetFrom(contentType: String): Charset {
        val match = Regex("charset=([\\w\\-]+)", RegexOption.IGNORE_CASE).find(contentType)
        val name = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    private fun stripHtml(html: String): String {
        var s = html
        s = Regex("(?is)<script[^>]*>.*?</script>").replace(s, " ")
        s = Regex("(?is)<style[^>]*>.*?</style>").replace(s, " ")
        s = Regex("(?is)<!--.*?-->").replace(s, " ")
        s = Regex("(?i)<br\\s*/?>").replace(s, "\n")
        s = Regex("(?i)</p>").replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, " ")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
        return s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun extractImages(html: String, baseUrl: String): List<String> {
        val found = LinkedHashSet<String>()
        val pattern = Regex("""(?i)<img[^>]+src\s*=\s*['"]([^'"]+)['"]""")
        pattern.findAll(html).forEach { m ->
            val src = m.groupValues[1].trim()
            if (src.isNotBlank() && !src.startsWith("data:")) {
                found += resolveUrl(baseUrl, src)
            }
            if (found.size >= 20) return found.toList()
        }
        return found.toList()
    }

    private fun resolveUrl(base: String, ref: String): String {
        return runCatching { URL(URL(base), ref).toString() }.getOrDefault(ref)
    }
}
