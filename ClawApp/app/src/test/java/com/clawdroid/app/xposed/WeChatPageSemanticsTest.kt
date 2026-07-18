package com.clawdroid.app.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatPageSemanticsTest {
    @Test
    fun mapsKnownActivities() {
        val home = WeChatPageSemantics.fromActivityClass("com.tencent.mm.ui.LauncherUI")
        assertEquals("LauncherUI", home["wechat_activity"])
        assertEquals("home", home["wechat_page"])

        val chat = WeChatPageSemantics.fromActivityClass("com.tencent.mm.ui.chatting.ChattingUI")
        assertEquals("chat", chat["wechat_page"])

        val unknown = WeChatPageSemantics.fromActivityClass("com.tencent.mm.plugin.foo.FooUI")
        assertEquals("unknown", unknown["wechat_page"])
        assertEquals("FooUI", unknown["wechat_activity"])
    }

    @Test
    fun softMatchesSuffix() {
        val soft = WeChatPageSemantics.fromActivityClass("com.tencent.mm.plugin.webview.ui.tools.WebViewUI")
        assertEquals("webview", soft["wechat_page"])
    }
}

class WeChatDetailAdapterRegistryTest {
    @Test
    fun wechatAdapterOptInOnly() {
        val defaults = AdapterRegistry.createDefaultAdapters(
            modulePackageName = "com.clawdroid.app.debug",
            config = XposedAdapterConfig.defaults()
        )
        assertFalse(defaults.any { it.adapterId == "wechat_detail" })

        val enabled = AdapterRegistry.createDefaultAdapters(
            modulePackageName = "com.clawdroid.app.debug",
            config = XposedAdapterConfig(
                enabledAdapters = setOf("wechat_detail"),
                wechatPackages = setOf("com.tencent.mm"),
                versionGates = mapOf(
                    "wechat_detail" to AdapterVersionGate(minVersionCode = 1L, maxVersionCode = 9_999_999L)
                )
            )
        )
        assertEquals(listOf("wechat_detail"), enabled.map { it.adapterId })
        assertTrue(enabled.first().matches(
            de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam().apply {
                packageName = "com.tencent.mm"
                processName = "com.tencent.mm"
            }
        ))
    }
}
