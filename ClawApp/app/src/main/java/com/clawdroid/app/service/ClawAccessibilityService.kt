package com.clawdroid.app.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.clawdroid.app.automation.AutomationRuntimeStore

class ClawAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AutomationRuntimeStore.onAccessibilityServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            return
        }
        val rootNode = rootInActiveWindow
        AutomationRuntimeStore.publishAccessibilityEvent(event, rootNode)
    }

    override fun onInterrupt() {
        AutomationRuntimeStore.onAccessibilityServiceInterrupted()
    }
}
