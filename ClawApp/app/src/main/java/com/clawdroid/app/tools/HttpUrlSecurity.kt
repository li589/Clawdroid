package com.clawdroid.app.tools

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

internal fun validatePublicHttpUrl(raw: String): URL = HttpUrlSecurity.validatePublicHttpUrl(raw)

object HttpUrlSecurity {
    private const val MAX_REDIRECTS = 5
    private val redirectCodes = setOf(301, 302, 303, 307, 308)

    fun validatePublicHttpUrl(raw: String): URL {
        val url = URL(raw.trim())
        if (url.protocol !in setOf("http", "https")) {
            throw IllegalArgumentException("only http/https URLs are allowed")
        }
        if (!url.userInfo.isNullOrBlank()) {
            throw IllegalArgumentException("URL credentials are not allowed")
        }
        val host = url.host?.trim('[', ']').orEmpty()
        if (host.isBlank()) {
            throw IllegalArgumentException("URL host is required")
        }
        val addresses = InetAddress.getAllByName(host)
        if (addresses.isEmpty() || addresses.any(::isUnsafeAddress)) {
            throw IllegalArgumentException("URL resolves to a non-public address")
        }
        return url
    }

    fun open(raw: String, connectTimeoutMs: Int, readTimeoutMs: Int, method: String = "GET"): HttpURLConnection {
        var current = validatePublicHttpUrl(raw)
        repeat(MAX_REDIRECTS + 1) { attempt ->
            current = validatePublicHttpUrl(current.toString())
            val connection = current.openConnection() as HttpURLConnection
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            val code = connection.responseCode
            if (code !in redirectCodes) {
                return connection
            }
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) {
                throw IllegalArgumentException("redirect is missing Location")
            }
            if (attempt == MAX_REDIRECTS) {
                throw IllegalArgumentException("too many redirects")
            }
            current = URL(current, location)
        }
        throw IllegalArgumentException("too many redirects")
    }

    private fun isUnsafeAddress(address: InetAddress): Boolean =
        address.isLoopbackAddress ||
            address.isAnyLocalAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
}
