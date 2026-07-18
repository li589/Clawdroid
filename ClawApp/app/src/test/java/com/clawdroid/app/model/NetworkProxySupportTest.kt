package com.clawdroid.app.model

import com.clawdroid.app.ui.NetworkProxyMode
import com.clawdroid.app.ui.NetworkProxySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

class NetworkProxySupportTest {
    @Test
    fun systemModeReturnsNullProxy() {
        assertNull(NetworkProxySupport.buildProxy(NetworkProxySettings()))
    }

    @Test
    fun httpProxyBuildsHttpType() {
        val proxy = NetworkProxySupport.buildProxy(
            NetworkProxySettings(
                mode = NetworkProxyMode.Http,
                host = "127.0.0.1",
                port = 7890
            )
        )
        assertNotNull(proxy)
        assertEquals(Proxy.Type.HTTP, proxy!!.type())
    }

    @Test
    fun socksProxyBuildsSocksType() {
        val proxy = NetworkProxySupport.buildProxy(
            NetworkProxySettings(
                mode = NetworkProxyMode.Socks,
                host = "127.0.0.1",
                port = 1080
            )
        )
        assertNotNull(proxy)
        assertEquals(Proxy.Type.SOCKS, proxy!!.type())
    }

    @Test
    fun describeMentionsSystemOrHost() {
        assertTrue(NetworkProxySupport.describe(NetworkProxySettings()).contains("系统"))
        assertEquals(
            "HTTP 127.0.0.1:7890",
            NetworkProxySupport.describe(
                NetworkProxySettings(mode = NetworkProxyMode.Http, host = "127.0.0.1", port = 7890)
            )
        )
    }
}
