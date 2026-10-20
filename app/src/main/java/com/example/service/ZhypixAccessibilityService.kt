package com.example.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Stable service identity only. Legacy screen control was removed for a clean rebuild. */
class ZhypixAccessibilityService : AccessibilityService() {
    companion object { var instance: ZhypixAccessibilityService? = null }
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
    // Temporary inactive interfaces for chat and voice while replacement lands.
    fun getActiveWindowHierarchy(): String = "Screen control is being rebuilt."
    fun takeScreenshotWithHash(forceFresh: Boolean = false, callback: (String?, Long) -> Unit) {
        callback(null, 0L)
    }
}
