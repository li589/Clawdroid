package com.clawdroid.app.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatControlRolesTest {
    @Test
    fun matchesChatChromeRolesWithoutUsingMessageText() {
        val candidates = listOf(
            WeChatControlRoles.Candidate(
                className = "TextView",
                contentDescription = "",
                bounds = "0,0,100,40"
            ),
            WeChatControlRoles.Candidate(
                className = "EditText",
                id = "chat_footer_input",
                bounds = "10,800,700,880"
            ),
            WeChatControlRoles.Candidate(
                className = "ImageButton",
                contentDescription = "发送",
                bounds = "720,810,780,870"
            ),
            WeChatControlRoles.Candidate(
                className = "ImageView",
                id = "actionbar_up_btn",
                contentDescription = "返回",
                bounds = "0,40,80,120"
            )
        )
        val roles = WeChatControlRoles.fromCandidates("chat", candidates)
        assertEquals("chat_footer_input|10,800,700,880", roles["wechat_ctrl_input"])
        assertTrue(roles["wechat_ctrl_send"]!!.contains("720,810"))
        assertTrue(roles["wechat_ctrl_back"]!!.contains("actionbar_up_btn"))
    }

    @Test
    fun skipsPaymentPages() {
        val candidates = listOf(
            WeChatControlRoles.Candidate(
                className = "Button",
                contentDescription = "发送",
                bounds = "1,1,2,2"
            )
        )
        assertTrue(WeChatControlRoles.fromCandidates("wallet", candidates).isEmpty())
        assertTrue(WeChatControlRoles.fromCandidates("red_packet", candidates).isEmpty())
        assertTrue(WeChatControlRoles.fromCandidates("remittance", candidates).isEmpty())
    }

    @Test
    fun doesNotTreatListCellTextAsRole() {
        val role = WeChatControlRoles.matchRole(
            "chat",
            WeChatControlRoles.Candidate(className = "TextView", contentDescription = "", bounds = "0,0,1,1")
        )
        assertNull(role)
    }
}

class ClawJsonLiteTest {
    @Test
    fun readsNestedExtrasAndEscapedUrl() {
        val raw = """
            {
              "package_name":"com.android.chrome",
              "activity_class":"com.google.android.apps.chrome.Main",
              "extras":{
                "browser_url":"https://example.com/a?q=%22x%22",
                "nested_note":"has {brace} inside",
                "wechat_page":"chat",
                "wechat_ctrl_send":"send|1,2,3,4"
              }
            }
        """.trimIndent()
        assertEquals("com.android.chrome", ClawJsonLite.string(raw, "package_name"))
        assertEquals("https://example.com/a?q=%22x%22", ClawJsonLite.extrasString(raw, "browser_url"))
        assertEquals("has {brace} inside", ClawJsonLite.extrasString(raw, "nested_note"))
        assertEquals("chat", ClawJsonLite.extrasString(raw, "wechat_page"))
        assertEquals(1, ClawJsonLite.extrasKeysPrefixed(raw, "wechat_ctrl_"))
        assertTrue(ClawJsonLite.hasRequiredIdentity(raw))
    }

    @Test
    fun rejectsInvalidJson() {
        assertFalse(ClawJsonLite.hasRequiredIdentity("not-json"))
        assertFalse(ClawJsonLite.hasRequiredIdentity("""{"package_name":"only"}"""))
        assertNull(ClawJsonLite.extrasString("""{"extras":"bad"}""", "x"))
    }
}

class FocusSummaryExtrasTest {
    @Test
    fun formatSummaryIncludesWechatAndBrowser() {
        val payload = XposedFocusSnapshotStore.buildPayload(
            packageName = "com.tencent.mm",
            processName = "com.tencent.mm",
            activityClass = "com.tencent.mm.ui.chatting.ChattingUI",
            adapterId = "wechat_detail",
            loadedAtEpochMs = 1L,
            activityTitle = "",
            intentAction = "",
            intentData = "",
            extras = mapOf(
                "wechat_page" to "chat",
                "wechat_ctrl_send" to "send|1,2,3,4",
                "wechat_ctrl_input" to "input|5,6,7,8",
                "browser_url" to "https://example.com/path"
            ),
            active = true
        )
        val summary = XposedFocusSnapshotStore.formatSummary(payload)
        assertTrue(summary.contains("wechat_page=chat"))
        assertTrue(summary.contains("ctrl=2"))
        assertTrue(summary.contains("browser_url=https://example.com/path"))
    }
}
