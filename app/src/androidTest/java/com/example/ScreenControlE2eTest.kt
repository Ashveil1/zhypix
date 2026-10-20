package com.example

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.agent.AgentDiagnostics
import com.example.service.ZhypixAccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end screen-control tests driven directly against the accessibility
 * service (no model involved): the service is enabled by the CI workflow via
 * `adb shell settings put secure enabled_accessibility_services ...` before
 * this test runs. Each test exercises one capability the agent loop depends
 * on, against real system apps (Settings / home screen).
 */
@RunWith(AndroidJUnit4::class)
class ScreenControlE2eTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        AgentDiagnostics.init(context)
    }

    private fun awaitService(timeoutMs: Long = 20_000): ZhypixAccessibilityService {
        // `am instrument` force-stops the app before running, which unbinds
        // the accessibility service and blocks re-binding while the package is
        // stopped. Launching the main activity from the shell counts as an
        // external start and clears the stopped state; then re-enable the
        // service (UiAutomation shell commands carry WRITE_SECURE_SETTINGS).
        try {
            device.executeShellCommand("am start -n com.zhypix.app.jdufyx/com.example.MainActivity")
            Thread.sleep(2000)
            enableAccessibilityService()
        } catch (e: Exception) {
            // fall through to the polling failure below
        }
        val start = System.currentTimeMillis()
        var lastReenable = 0L
        while (System.currentTimeMillis() - start < timeoutMs) {
            ZhypixAccessibilityService.instance?.let { return it }
            // The force-stop before instrumentation marks the bound service
            // crashed; writing the same settings value is a no-op, so toggle
            // accessibility off/on every 6s to force a re-bind attempt.
            if (System.currentTimeMillis() - lastReenable > 6000L) {
                lastReenable = System.currentTimeMillis()
                try { enableAccessibilityService() } catch (e: Exception) {}
            }
            Thread.sleep(250)
        }
        fail("ZhypixAccessibilityService not connected - enable it with: adb shell settings put secure enabled_accessibility_services com.zhypix.app.jdufyx/com.example.service.ZhypixAccessibilityService")
        throw IllegalStateException("unreachable")
    }

    private fun enableAccessibilityService() {
        device.executeShellCommand("settings put secure accessibility_enabled 0")
        Thread.sleep(400)
        device.executeShellCommand("settings put secure enabled_accessibility_services com.zhypix.app.jdufyx/com.example.service.ZhypixAccessibilityService")
        device.executeShellCommand("settings put secure accessibility_enabled 1")
    }

    private fun openSettings() {
        val intent = Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg("com.android.settings")), 10_000)
        Thread.sleep(2000)
    }

    @Test
    fun serviceConnectsAndHierarchyAvailable() {
        val service = awaitService()
        val hierarchy = service.getActiveWindowHierarchy()
        assertTrue("Hierarchy should not be blank", hierarchy.isNotBlank())
        AgentDiagnostics.recordEvent("test", "service connected, hierarchy ${hierarchy.length} chars")
    }

    @Test
    fun clickByTextInSettings() {
        val service = awaitService()
        openSettings()
        val result = runBlocking { service.clickSmart("Network") }
        AgentDiagnostics.recordEvent("test", "clickSmart(Network) success=${result.success}: ${result.message}")
        assertTrue("clickSmart('Network') failed: ${result.message}", result.success)
    }

    @Test
    fun typeIntoSettingsSearchField() {
        val service = awaitService()
        openSettings()
        val clicked = runBlocking {
            val first = service.clickSmart("Search settings")
            if (first.success) first else service.clickSmart("Search")
        }
        assertTrue("Could not click Settings search bar: ${clicked.message}", clicked.success)
        Thread.sleep(1000)
        val typed = service.inputText("wifi")
        AgentDiagnostics.recordEvent("test", "inputText(wifi) -> $typed")
        assertTrue("inputText failed - no focused editable field", typed)
        Thread.sleep(1500)
        val hierarchy = service.getActiveWindowHierarchy()
        assertTrue("Typed text not visible in hierarchy", hierarchy.contains("wifi", ignoreCase = true))
    }

    @Test
    fun scrollSettingsList() {
        val service = awaitService()
        openSettings()
        val ok = service.scrollSmart("DOWN")
        AgentDiagnostics.recordEvent("test", "scrollSmart(DOWN) -> $ok")
        assertTrue("scrollSmart(DOWN) failed", ok)
    }

    @Test
    fun longPressOnHomeScreen() {
        val service = awaitService()
        device.pressHome()
        Thread.sleep(1500)
        val dm = context.resources.displayMetrics
        val ok = runBlocking { service.dispatchLongPress(dm.widthPixels / 2f, dm.heightPixels / 2f) }
        AgentDiagnostics.recordEvent("test", "dispatchLongPress(center) -> $ok")
        assertTrue("dispatchLongPress failed", ok)
    }

    @Test
    fun dragAndDropGestureDispatches() {
        val service = awaitService()
        openSettings()
        val dm = context.resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val ok = runBlocking {
            service.dispatchDragAndDrop(cx, dm.heightPixels * 0.7f, cx, dm.heightPixels * 0.3f)
        }
        AgentDiagnostics.recordEvent("test", "dispatchDragAndDrop -> $ok")
        assertTrue("dispatchDragAndDrop failed", ok)
    }

    @Test
    fun screenshotCaptureReturnsImage() {
        val service = awaitService()
        openSettings()
        val latch = CountDownLatch(1)
        var result: String? = null
        service.takeScreenshotBase64(forceFresh = true) { b64 ->
            result = b64
            latch.countDown()
        }
        assertTrue("Screenshot callback timed out", latch.await(20, TimeUnit.SECONDS))
        AgentDiagnostics.recordEvent("test", "screenshot bytes=${result?.length ?: 0}")
        assertNotNull("Screenshot capture returned null", result)
    }

    @Test
    fun diagnosticsLogIsWritten() {
        AgentDiagnostics.recordActionStep(
            step = 1,
            action = "CLICK",
            target = "e2e-self-check",
            success = true,
            message = "diagnostics self-check",
            executeMs = 5,
            settleMs = 10,
            captureMs = 15,
            treeSize = 100
        )
        val file = AgentDiagnostics.exportFile()
        assertNotNull("Diagnostics file should exist after recording", file)
        val content = file!!.readText()
        assertTrue("Diagnostics file should contain the recorded step", content.contains("e2e-self-check"))
    }
}
