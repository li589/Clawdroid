package com.clawdroid.app.service

import android.accessibilityservice.AccessibilityService
import android.util.AndroidRuntimeException
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.clawdroid.app.automation.AutomationRuntimeStore

class ClawAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching {
            AutomationRuntimeStore.onAccessibilityServiceConnected()
        }.onFailure { e ->
            android.util.Log.e("ClawAccessibility", "onServiceConnected failed", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            return
        }
        var rootNode: AccessibilityNodeInfo? = null
        try {
            rootNode = rootInActiveWindow
            AutomationRuntimeStore.publishAccessibilityEvent(event, rootNode)
        } catch (e: SecurityException) {
            android.util.Log.w("ClawAccessibility", "Permission denied for accessibility event", e)
        } catch (e: AndroidRuntimeException) {
            android.util.Log.w("ClawAccessibility", "Runtime error during accessibility event", e)
        } catch (e: IllegalStateException) {
            android.util.Log.w("ClawAccessibility", "Service not connected", e)
        } catch (e: Exception) {
            android.util.Log.e("ClawAccessibility", "Unexpected error in onAccessibilityEvent", e)
        } finally {
            rootNode?.recycle()
        }
    }

    override fun onInterrupt() {
        runCatching {
            AutomationRuntimeStore.onAccessibilityServiceInterrupted()
        }.onFailure { e ->
            android.util.Log.e("ClawAccessibility", "onInterrupt failed", e)
        }
    }
}
