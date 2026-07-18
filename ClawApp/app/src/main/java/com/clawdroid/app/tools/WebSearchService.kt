package com.clawdroid.app.tools

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * Lightweight web search without paid APIs.
 * Providers: Wikipedia OpenSearch (stable JSON) and DuckDuckGo HTML lite (best-effort scrape).
 */
class WebSearchService {
    fun search(
        query: String,
        maxResults: Int = 5,
        provider: String = "auto"
    ): ClawToolCallResult {
        val q = query.trim()
        if (q.isBlank()) {
            return ClawToolCallResult(false, "失败: query 不能为空", error = "invalid_query")
        }
        if (q.length > 256) {
            return ClawToolCallResult(false, "失败: query 过长", error = "query_too_long")
        }
        val limit = maxResults.coerceIn(1, 10)
        val mode = provider.trim().lowercase().ifBlank { "auto" }
        return try {
            val results = when (mode) {
                "wikipedia", "wiki" -> searchWikipedia(q, limit)
                "ddg", "duckduckgo" -> searchDuckDuckGo(q, limit)
                "auto" -> {
                    val wiki = searchWikipedia(q, limit)
                    if (wiki.isNotEmpty()) wiki else searchDuckDuckGo(q, limit)
                }
                else -> return ClawToolCallResult(
                    false,
                    "失败: 未知 provider=$provider（支持 auto|wikipedia|ddg）",
                    error = "invalid_provider"
                )
            }
            val used = when {
                mode == "auto" && results.any { it.provider == "wikipedia" } -> "wikipedia"
                mode == "auto" -> "ddg"
                mode in setOf("wikipedia", "wiki") -> "wikipedia"
                else -> "ddg"
            }
            val arr = JSONArray()
            results.forEach { hit ->
                arr.put(
                    JSONObject()
                        .put("title", hit.title)
                        .put("url", hit.url)
                        .put("snippet", hit.snippet)
                        .put("provider", hit.provider)
                )
            }
            val json = JSONObject()
                .put("query", q)
                .put("provider", used)
                .put("count", results.size)
                .put("results", arr)
            ClawToolCallResult(
                success = results.isNotEmpty(),
                output = json.toString(2),
                error = if (results.isEmpty()) "no_results" else null
            )
        } catch (error: Exception) {
            ClawToolCallResult(false, "失败: ${error.message}", error = error.message)
        }
    }

    internal fun searchWikipedia(query: String, limit: Int): List<SearchHit> {
        val lang = if (query.any { it.code in 0x4E00..0x9FFF }) "zh" else "en"
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url =
            "https://$lang.wikipedia.org/w/api.php?action=opensearch&search=$encoded&limit=$limit&namespace=0&format=json"
        val body = httpGet(url, accept = "application/json")
        val array = JSONArray(body)
        if (array.length() < 4) return emptyList()
        val titles = array.optJSONArray(1) ?: return emptyList()
        val snippets = array.optJSONArray(2) ?: JSONArray()
        val urls = array.optJSONArray(3) ?: JSONArray()
        val hits = ArrayList<SearchHit>()
        for (i in 0 until minOf(titles.length(), limit)) {
            val title = titles.optString(i).trim()
            val link = urls.optString(i).trim()
            if (title.isBlank() || link.isBlank()) continue
            hits += SearchHit(
                title = title,
                url = link,
                snippet = snippets.optString(i).trim(),
                provider = "wikipedia"
            )
        }
        return hits
    }

    internal fun parseDuckDuckGoHtml(html: String, limit: Int): List<SearchHit> {
        val hits = ArrayList<SearchHit>()
        // result__a links; href may be redirect uddg=
        val linkRegex = Regex(
            """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val snippetRegex = Regex(
            """class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</(?:a|td|span|div)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val snippets = snippetRegex.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
        var index = 0
        for (match in linkRegex.findAll(html)) {
            if (hits.size >= limit) break
            val href = decodeDdgUrl(match.groupValues[1].trim())
            val title = stripTags(match.groupValues[2]).trim()
            if (title.isBlank() || href.isBlank()) continue
            val snippet = snippets.getOrNull(index).orEmpty()
            index++
            hits += SearchHit(title = title, url = href, snippet = snippet, provider = "ddg")
        }
        return hits
    }

    private fun searchDuckDuckGo(query: String, limit: Int): List<SearchHit> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val html = httpGet(
            "https://html.duckduckgo.com/html/?q=$encoded",
            accept = "text/html"
        )
        return parseDuckDuckGoHtml(html, limit)
    }

    private fun decodeDdgUrl(href: String): String {
        val decoded = href.replace("&amp;", "&")
        val marker = "uddg="
        val idx = decoded.indexOf(marker)
        if (idx >= 0) {
            val raw = decoded.substring(idx + marker.length).substringBefore('&')
            return runCatching {
                java.net.URLDecoder.decode(raw, Charsets.UTF_8.name())
            }.getOrDefault(decoded)
        }
        if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
            return decoded
        }
        return decoded
    }

    private fun stripTags(html: String): String {
        return html.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun httpGet(url: String, accept: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ClawdroidWebSearch/0.3")
            setRequestProperty("Accept", accept)
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            error("http_$code")
        }
        val charset = charsetFrom(connection.contentType.orEmpty())
        return connection.inputStream.use { input ->
            val buf = ByteArray(512_000)
            var total = 0
            while (total < buf.size) {
                val n = input.read(buf, total, buf.size - total)
                if (n <= 0) break
                total += n
            }
            String(buf, 0, total, charset)
        }
    }

    private fun charsetFrom(contentType: String): Charset {
        val match = Regex("charset=([\\w\\-]+)", RegexOption.IGNORE_CASE).find(contentType)
        val name = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    data class SearchHit(
        val title: String,
        val url: String,
        val snippet: String,
        val provider: String
    )
}
