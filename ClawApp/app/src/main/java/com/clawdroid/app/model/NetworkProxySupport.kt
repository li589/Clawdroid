package com.clawdroid.app.model

import com.clawdroid.app.ui.NetworkProxyMode
import com.clawdroid.app.ui.NetworkProxySettings
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 将 [NetworkProxySettings] 应用到 [HttpURLConnection]。
 * System 模式不强制代理，由系统/VPN 路由决定出口。
 */
internal object NetworkProxySupport {
    fun openConnection(url: String, proxySettings: NetworkProxySettings): HttpURLConnection {
        val urlObj = URL(url)
        val proxy = buildProxy(proxySettings)
        val connection = if (proxy == null || proxy.type() == Proxy.Type.DIRECT) {
            urlObj.openConnection()
        } else {
            urlObj.openConnection(proxy)
        }
        return connection as HttpURLConnection
    }

    fun buildProxy(settings: NetworkProxySettings): Proxy? {
        return when (settings.mode) {
            NetworkProxyMode.System -> null
            NetworkProxyMode.Http -> {
                val host = settings.host.trim()
                require(host.isNotBlank()) { "HTTP 代理主机不能为空" }
                require(settings.port in 1..65535) { "HTTP 代理端口无效: ${settings.port}" }
                Proxy(Proxy.Type.HTTP, InetSocketAddress(host, settings.port))
            }
            NetworkProxyMode.Socks -> {
                val host = settings.host.trim()
                require(host.isNotBlank()) { "SOCKS 代理主机不能为空" }
                require(settings.port in 1..65535) { "SOCKS 代理端口无效: ${settings.port}" }
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, settings.port))
            }
        }
    }

    fun applyProxyAuthorization(connection: HttpURLConnection, settings: NetworkProxySettings) {
        if (settings.mode != NetworkProxyMode.Http) return
        val user = settings.username.trim()
        if (user.isEmpty()) return
        val token = Base64.getEncoder().encodeToString(
            "$user:${settings.password}".toByteArray(StandardCharsets.UTF_8)
        )
        connection.setRequestProperty("Proxy-Authorization", "Basic $token")
    }

    fun describe(settings: NetworkProxySettings): String = settings.summary()
}
