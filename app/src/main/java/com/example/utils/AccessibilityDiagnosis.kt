package com.example.utils

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.util.Log

object AccessibilityDiagnosis {
    fun runDiagnostics(context: Context, serviceInstance: Any?): String {
        val sb = StringBuilder()
        sb.appendLine("--- Accessibility Service Diagnostics ---")
        
        try {
            val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            sb.appendLine("1. AccessibilityManager.isEnabled: ${accessibilityManager.isEnabled}")
            sb.appendLine("2. AccessibilityManager.isTouchExplorationEnabled: ${accessibilityManager.isTouchExplorationEnabled}")
        } catch (e: Exception) {
            sb.appendLine("1 & 2. Failed to query AccessibilityManager: ${e.message}")
        }

        try {
            val expectedComponentName = android.content.ComponentName(context, "com.example.service.ZhypixAccessibilityService").flattenToString()
            
            var accessibilityEnabled = 0
            try {
                accessibilityEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
            } catch (e: Settings.SettingNotFoundException) {
                // Ignore
            }
            sb.appendLine("3. Settings.Secure.ACCESSIBILITY_ENABLED: $accessibilityEnabled")

            val enabledServicesSetting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            sb.appendLine("4. SECURE.ENABLED_ACCESSIBILITY_SERVICES list (raw): $enabledServicesSetting")
            
            val isServiceInSettings = enabledServicesSetting?.contains(expectedComponentName) == true
            sb.appendLine("5. Is our service explicitly in the enabled list?: $isServiceInSettings")
            sb.appendLine("   (Expected matched name: $expectedComponentName)")
            
        } catch (e: Exception) {
            sb.appendLine("3, 4, 5. Failed to query Secure Settings: ${e.message}")
        }

        sb.appendLine("6. Static instance ref (ZhypixAccessibilityService.instance): ${if (serviceInstance != null) "AVAILABLE" else "NULL (Not bound or process died)"}")
        
        sb.appendLine("-------------------------------------------")
        val report = sb.toString()
        Log.e("ZhypixDiagnostics", report)
        return report
    }
}
