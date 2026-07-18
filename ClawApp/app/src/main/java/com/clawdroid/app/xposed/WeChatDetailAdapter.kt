package com.clawdroid.app.xposed

import android.app.Activity
import android.content.Intent

/**
 * WeChat (com.tencent.mm) adapter — opt-in via adapters.json.
 *
 * Scope: page identity + chrome control roles + shallow view dump (chat text redacted).
 * Out of scope: chat content, contacts scraping, payment automation hooks.
 */
internal class WeChatDetailAdapter(
    targetPackages: Set<String>
) : FocusSnapshotAdapter(
    id = "wechat_detail",
    targetPackages = targetPackages,
    targetLabel = "wechat"
) {
    override fun extraFields(activity: Activity, intent: Intent?): Map<String, String> {
        val component = activity.componentName
            ?: android.content.ComponentName(activity.packageName, activity.javaClass.name)
        val pageSemantics = WeChatPageSemantics.fromActivityClass(component.className)
        val page = pageSemantics["wechat_page"].orEmpty()
        val controls = WeChatControlRoles.fromActivity(activity, page)
        return super.extraFields(activity, intent) + pageSemantics + controls
    }

    override fun afterPersist(
        activity: Activity,
        processName: String,
        active: Boolean,
        focusPayload: String?
    ) {
        if (!active) return
        val page = ClawJsonLite.extrasString(focusPayload.orEmpty(), "wechat_page").orEmpty()
        val redactText = page == "chat" || page == "moments"
        val written = XposedViewSnapshotStore.writeFromActivity(
            activity = activity,
            adapterId = adapterId,
            redactText = redactText
        )
        if (written == null) {
            AdapterFuse.recordViewFailure(adapterId)
            de.robv.android.xposed.XposedBridge.log(
                "Clawdroid/$adapterId: failed to persist view dump for ${activity.javaClass.name}"
            )
            return
        }
        XposedViewPush.notify(activity.applicationContext, written)
    }
}
