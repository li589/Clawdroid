package com.clawdroid.app.xposed

/**
 * Maps well-known WeChat Activity class simple names to page labels.
 * Observation-only: no message body scraping, no private API hooks.
 */
internal object WeChatPageSemantics {
    private val PAGE_BY_SIMPLE_NAME = mapOf(
        "LauncherUI" to "home",
        "ChattingUI" to "chat",
        "SnsTimeLineUI" to "moments",
        "SnsUploadUI" to "moments_compose",
        "ContactInfoUI" to "contact",
        "AddressUI" to "contacts",
        "WalletIndexUI" to "wallet",
        "MallIndexUI" to "mall",
        "WebViewUI" to "webview",
        "FTSMainUI" to "search",
        "SearchUI" to "search",
        "SettingsUI" to "settings",
        "LoginHistoryUI" to "login",
        "LoginUI" to "login",
        "AppBrandUI" to "mini_program",
        "AppBrandPluginUI" to "mini_program",
        "LuckyMoneyNotHookReceiveUI" to "red_packet",
        "RemittanceAdapterUI" to "remittance"
    )

    fun fromActivityClass(activityClass: String): Map<String, String> {
        val simple = simpleName(activityClass)
        if (simple.isBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        out["wechat_activity"] = simple.take(64)
        val page = PAGE_BY_SIMPLE_NAME[simple]
        if (page != null) {
            out["wechat_page"] = page
        } else {
            // Soft label for unknown but still WeChat activities (suffix match).
            val soft = PAGE_BY_SIMPLE_NAME.entries.firstOrNull { (key, _) ->
                simple.endsWith(key) || simple.contains(key)
            }?.value
            if (soft != null) {
                out["wechat_page"] = soft
            } else {
                out["wechat_page"] = "unknown"
            }
        }
        return out
    }

    fun knownPages(): Set<String> = PAGE_BY_SIMPLE_NAME.values.toSet()

    fun simpleName(activityClass: String): String {
        val trimmed = activityClass.trim()
        if (trimmed.isBlank()) return ""
        val slash = trimmed.lastIndexOf('.')
        return if (slash >= 0 && slash + 1 < trimmed.length) {
            trimmed.substring(slash + 1)
        } else {
            trimmed
        }
    }
}
