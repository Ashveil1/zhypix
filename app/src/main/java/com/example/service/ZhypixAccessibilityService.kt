package com.example.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Stable Android service identity with new independently implemented device adapters. */
class ZhypixAccessibilityService : AccessibilityService() {
    companion object { var instance: ZhypixAccessibilityService? = null }
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
    val device by lazy { ScreenDevice(this) }
    val observation by lazy { ScreenObservation(this) }
    fun getActiveWindowHierarchy(): String = observation.phoneState()
}
