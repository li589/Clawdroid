package com.clawdroid.app.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.clawdroid.app.service.ClawNotificationStore
import org.json.JSONArray
import org.json.JSONObject

class NotificationToolService(
    private val context: Context
) {
    fun list(query: String, limit: Int): ClawToolCallResult {
        val enabled = isNotificationAccessEnabled()
        val items = ClawNotificationStore.list(query, limit)
        val arr = JSONArray()
        items.forEach { n ->
            arr.put(
                JSONObject()
                    .put("key", n.key)
                    .put("package", n.packageName)
                    .put("title", n.title)
                    .put("text", n.text)
                    .put("sub_text", n.subText)
                    .put("posted_at_ms", n.postedAtMs)
                    .put("ongoing", n.isOngoing)
            )
        }
        return ClawToolCallResult(
            success = enabled || items.isNotEmpty(),
            output = buildString {
                appendLine("listener_connected=${ClawNotificationStore.listenerConnected}")
                appendLine("access_enabled=$enabled")
                appendLine("count=${items.size}")
                if (!enabled) {
                    appendLine("hint=请在系统设置中开启 Clawdroid 通知使用权")
                }
                append(arr.toString(2))
            },
            error = if (enabled) null else "notification_access_disabled"
        )
    }

    fun openAccessSettings(): ClawToolCallResult {
        return try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ClawToolCallResult(true, "已打开通知使用权设置")
        } catch (error: Exception) {
            ClawToolCallResult(false, "失败: ${error.message}", error = error.message)
        }
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        val expected = "${context.packageName}/"
        return flat.split(':').any { it.contains(expected, ignoreCase = true) }
    }
}
