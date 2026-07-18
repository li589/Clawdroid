package com.clawdroid.app.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusIntentExtrasTest {
    @Test
    fun settingsFragmentExtrasFromRaw() {
        val map = SettingsFragmentExtras.fromRaw(
            showFragment = "com.android.settings.wifi.WifiSettings",
            fragmentArgsKey = "wifi",
            argumentsSummary = "key=value",
            deepLink = "intent:#Intent;end"
        )
        assertEquals("com.android.settings.wifi.WifiSettings", map["settings_fragment"])
        assertEquals("wifi", map["settings_fragment_args_key"])
        assertEquals("key=value", map["settings_arguments"])
        assertTrue(map["settings_deep_link"]!!.startsWith("intent:"))
    }
}
