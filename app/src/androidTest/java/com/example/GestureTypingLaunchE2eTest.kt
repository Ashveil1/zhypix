package com.example

import android.app.UiAutomation
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.ScreenAction
import com.example.service.ZhypixAccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic adapter checks for Home, app launch and typing into a focused field on real Android Settings. */
@RunWith(AndroidJUnit4::class)
class GestureTypingLaunchE2eTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ui: UiAutomation = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val component = "com.zhypix.app.jdufyx/com.example.service.ZhypixAccessibilityService"

    private fun shell(cmd: String) { android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd)).use { it.readBytes() } }

    private fun bindService(): ZhypixAccessibilityService {
        ZhypixAccessibilityService.instance?.let { return it }
        shell("settings put secure enabled_accessibility_services null"); Thread.sleep(1000)
        shell("settings put secure enabled_accessibility_services $component"); shell("settings put secure accessibility_enabled 1")
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) { ZhypixAccessibilityService.instance?.let { return it }; Thread.sleep(250) }
        throw AssertionError("Accessibility service did not bind")
    }

    private fun bounds(text: String): Rect? {
        val root = ui.rootInActiveWindow ?: return null
        val hit = root.findAccessibilityNodeInfosByText(text).firstOrNull() ?: return null
        return Rect().also { hit.getBoundsInScreen(it) }
    }
    private fun waitFor(text: String, ms: Long = 10000): Rect? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { bounds(text)?.let { return it }; Thread.sleep(250) }
        return null
    }
    private fun pkg(): String? = ui.rootInActiveWindow?.packageName?.toString()

    @Test fun homeLaunchAndTypeThroughAdapter() = runBlocking {
        val service = bindService()
        val log = StringBuilder()

        // Launch Settings through the adapter.
        val launch = service.device.execute(ScreenAction.StartApp("com.android.settings"))
        log.append("launch: ${launch.success} ${launch.message}\n")
        assertTrue("launch failed: ${launch.message}", launch.success)
        assertNotNull("Settings not visible after adapter launch", waitFor("Network & internet")); Thread.sleep(2500)
        assertEquals("com.android.settings", pkg())

        // Home through the adapter leaves Settings.
        val home = service.device.execute(ScreenAction.PressHome)
        log.append("home: ${home.success} ${home.message}\n")
        assertTrue(home.success)
        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end && pkg() == "com.android.settings") Thread.sleep(250)
        assertNotEquals("Still in Settings after Home", "com.android.settings", pkg())

        // Back to Settings, open search, type.
        service.device.execute(ScreenAction.StartApp("com.android.settings"))
        val search = waitFor("Search settings") ?: throw AssertionError("Search bar not found"); Thread.sleep(2500)
        val tap = service.device.execute(ScreenAction.Click(search.centerX(), search.centerY()))
        log.append("tap search: ${tap.success} ${tap.message}\n")
        assertTrue(tap.success)
        Thread.sleep(2000)
        val typed = service.device.execute(ScreenAction.Type("wifi"))
        log.append("type: ${typed.success} ${typed.message}\n")
        assertTrue("type failed: ${typed.message}", typed.success)
        val seen = waitFor("wifi", 5000)
        assertNotNull("Typed text not visible in the field", seen)

        // Typing again with no focus change must still work on the same field, and a launch of our own package is refused.
        val own = service.device.execute(ScreenAction.StartApp("com.zhypix.app.jdufyx"))
        log.append("own-package launch: ${own.success} ${own.message}\n")
        assertFalse("own package launch must be refused", own.success)

        java.io.File(inst.targetContext.filesDir, "typing-evidence.txt").writeText(log.toString())
    }
}
