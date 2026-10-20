package com.example

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.ScreenAction
import com.example.service.ZhypixAccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deterministic (no model) check of the app's OWN gesture adapter: ScreenDevice dispatching through the real
 * accessibility service. UiAutomation is opened with DONT_SUPPRESS so it does not switch our service off, and the
 * service is re-bound from inside the test because `am instrument` force-stops the app process first.
 */
@RunWith(AndroidJUnit4::class)
class GestureAdapterE2eTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val ui: UiAutomation = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val component = "com.zhypix.app.jdufyx/com.example.service.ZhypixAccessibilityService"

    private fun shell(cmd: String) { android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd)).use { it.readBytes() } }

    private fun bindService(): ZhypixAccessibilityService {
        ZhypixAccessibilityService.instance?.let { return it }
        shell("settings put secure enabled_accessibility_services null")
        Thread.sleep(1000)
        shell("settings put secure enabled_accessibility_services $component")
        shell("settings put secure accessibility_enabled 1")
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) { ZhypixAccessibilityService.instance?.let { return it }; Thread.sleep(250) }
        fail("Accessibility service did not bind after re-enabling"); throw IllegalStateException()
    }

    private fun find(text: String): Rect? {
        val root = ui.rootInActiveWindow ?: return null
        val hit = root.findAccessibilityNodeInfosByText(text).firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text } ?: return null
        return Rect().also { hit.getBoundsInScreen(it) }
    }

    private fun waitFor(text: String, ms: Long = 8000): Rect? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { find(text)?.let { return it }; Thread.sleep(250) }
        return null
    }

    @Test fun serviceTapsSwipesAndGoesBackOnRealSettings() = runBlocking {
        val service = bindService()
        ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val row = waitFor("Network & internet") ?: fail("Settings home not visible").let { return@runBlocking }
        val log = StringBuilder()

        // 1. Tap through the app's own adapter.
        val tap = service.device.execute(ScreenAction.Click(row.centerX(), row.centerY()))
        log.append("tap: ${tap.success} ${tap.message}\n")
        assertTrue("tap rejected: ${tap.message}", tap.success)
        assertNotNull("Network page did not open after adapter tap", waitFor("Internet"))

        // 2. Swipe (drag) must complete without error. The page is short, so this checks dispatch, not content.
        val h = ctx.resources.displayMetrics.heightPixels; val w = ctx.resources.displayMetrics.widthPixels
        val swipe = service.device.execute(ScreenAction.Drag(w / 2, h * 3 / 4, w / 2, h / 4, 400))
        log.append("swipe: ${swipe.success} ${swipe.message}\n")
        assertTrue("swipe failed: ${swipe.message}", swipe.success)

        // 3. Back through the adapter returns to Settings home.
        val back = service.device.execute(ScreenAction.PressBack)
        log.append("back: ${back.success} ${back.message}\n")
        assertTrue("back rejected: ${back.message}", back.success)
        assertNotNull("Settings home not shown after adapter back", waitFor("Network & internet"))

        java.io.File(ctx.filesDir, "gesture-evidence.txt").writeText(log.toString())
    }
}
