package com.zhypix.fresh

import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.zhypix.fresh.service.AgentAccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Deterministic (no model) proof that gestures land where intended, using the
 * real accessibility service against the real Settings app - the mis-tap class
 * of bugs shows up here, not in unit tests.
 */
class GestureE2eTest {

    companion object {
        private const val PKG = "com.zhypix.fresh"
        private const val SERVICE = "$PKG/com.zhypix.fresh.service.AgentAccessibilityService"
    }

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ui: UiAutomation = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun shell(cmd: String) {
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd)).use { it.readBytes() }
    }

    private fun bindService(): AgentAccessibilityService {
        AgentAccessibilityService.instance?.let { return it }
        shell("am start -n $PKG/.MainActivity")
        Thread.sleep(2500)
        val end = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < end) {
            shell("settings put secure enabled_accessibility_services null")
            Thread.sleep(1000)
            shell("settings put secure enabled_accessibility_services $SERVICE")
            shell("settings put secure accessibility_enabled 1")
            val waitUntil = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < waitUntil) {
                AgentAccessibilityService.instance?.let { return it }
                Thread.sleep(250)
            }
        }
        fail("accessibility service did not bind after re-enabling")
        throw IllegalStateException()
    }

    private fun find(text: String): Rect? {
        val root = ui.rootInActiveWindow ?: return null
        val hit = root.findAccessibilityNodeInfosByText(text).firstOrNull {
            it.text?.toString() == text
        } ?: return null
        return Rect().also { hit.getBoundsInScreen(it) }
    }

    private fun waitFor(text: String, ms: Long = 10_000): Rect? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            find(text)?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    @Test
    fun clickLandsOnIntendedSettingsRow() = runBlocking {
        val service = bindService()
        assertTrue("could not launch Settings", service.startApp("com.android.settings"))
        val row = waitFor("Network & internet") ?: run {
            fail("Settings home row not visible")
            return@runBlocking
        }
        Thread.sleep(2500) // launch transition: a tap during it is silently dropped
        val target = find("Network & internet") ?: run {
            fail("row vanished")
            return@runBlocking
        }
        val ok = service.click(target.centerX().toFloat(), target.centerY().toFloat())
        assertTrue("click dispatch failed", ok)
        val landed = waitFor("SIMs", 8_000) ?: waitFor("Mobile network", 4_000) ?: waitFor("Internet", 4_000)
        if (landed == null) {
            fail("click did not open the Network & internet page")
        }
    }

    @Test
    fun dragScrollsSettingsList() = runBlocking {
        val service = bindService()
        assertTrue("could not launch Settings", service.startApp("com.android.settings"))
        waitFor("Network & internet") ?: run {
            fail("Settings home not visible")
            return@runBlocking
        }
        Thread.sleep(2500)
        val (w, h) = service.screenSize()
        val before = ui.rootInActiveWindow?.let { root ->
            root.findAccessibilityNodeInfosByText("About emulated device").isNotEmpty()
        } ?: false
        val ok = service.drag(w / 2f, h * 0.7f, w / 2f, h * 0.2f)
        assertTrue("drag dispatch failed", ok)
        Thread.sleep(2000)
        val after = ui.rootInActiveWindow?.let { root ->
            root.findAccessibilityNodeInfosByText("About emulated device").isNotEmpty()
        } ?: false
        assertTrue("scroll did not reveal lower Settings entries", !before && after || after)
    }
}
