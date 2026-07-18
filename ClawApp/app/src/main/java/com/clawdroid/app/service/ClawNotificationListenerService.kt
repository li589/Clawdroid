package com.clawdroid.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class ClawNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        ClawNotificationStore.listenerConnected = true
        runCatching {
            activeNotifications?.forEach { capture(it) }
        }
    }

    override fun onListenerDisconnected() {
        ClawNotificationStore.listenerConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) capture(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) {
            ClawNotificationStore.remove(sbn.key)
        }
    }

    private fun capture(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val sub = extras.getCharSequence("android.subText")?.toString().orEmpty()
        ClawNotificationStore.add(
            CapturedNotification(
                key = sbn.key,
                packageName = sbn.packageName.orEmpty(),
                title = title,
                text = text,
                subText = sub,
                postedAtMs = sbn.postTime,
                isOngoing = sbn.isOngoing
            )
        )
    }
}
