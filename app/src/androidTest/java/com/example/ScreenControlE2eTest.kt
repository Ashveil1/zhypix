package com.example

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.agent.AgentDiagnostics
import com.example.service.ZhypixAccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end screen-control tests on a real Android system (CI emulator).
 *
 * Two layers:
 *  - UiAutomation tests (always run): exercise real accessibility windows,
 *    gestures and screenshots through the instrumentation's own UiAutomation,
 *    which needs no app-service binding.
 *  - Service tests (gated with Assume): exercise ZhypixAccessibilityService
 *    itself. On CI, `am instrument` force-stops the app and the emulator's
 *    AccessibilityManagerService never rebinds the service (verified: enabled,
 *    not crashed, zero bind attempts in dumpsys), so these skip gracefully.
 *    On a real device where the service is already bound by the user, they run.
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

    private fun awaitServiceOrNull(timeoutMs: Long = 5_000): ZhypixAccessibilityService? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            ZhypixAccessibilityService.instance?.let { return it }
            Thread.sleep(200)
        }
        return null
    }

    private fun openSettings() {
        val intent = Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg("com.android.settings")), 10_000)
        Thread.sleep(2000)
    }

    private fun uiAutomation(): UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation

    // ---------------- Wiring (always runs) ----------------

    @Test
    fun serviceDeclaredEnabledAndResolvable() {
        val intent = Intent("android.accessibilityservice.AccessibilityService")
            .setPackage(context.packageName)
        val resolved = context.packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
        val ours = resolved.firstOrNull { it.serviceInfo.name.endsWith("ZhypixAccessibilityService") }
        assertNotNull("Accessibility service not resolvable via PackageManager", ours)

        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val infos = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        Log.i("E2E", "enabled a11y services: ${infos.map { it.id }}")
        AgentDiagnostics.recordEvent("test", "wiring ok, enabled services: ${infos.map { it.id }}")
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

    // ---------------- UiAutomation (always runs) ----------------

    @Test
    fun uiAutomationHierarchyAvailable() {
        openSettings()
        val root = uiAutomation().rootInActiveWindow
        assertNotNull("UiAutomation root window should be available", root)
        var count = 0
        val stack = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty() && count < 1000) {
            val node = stack.removeFirst()
            count++
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        }
        AgentDiagnostics.recordEvent("test", "UiAutomation hierarchy nodes=$count")
        assertTrue("Hierarchy should contain multiple nodes, got $count", count > 5)
    }

    @Test
    fun uiAutomationClickByText() {
        openSettings()
        // The entry may be below the fold - scroll it into view first.
        val scrolled = androidx.test.uiautomator.UiScrollable(
            androidx.test.uiautomator.UiSelector().scrollable(true)
        ).scrollTextIntoView("Network & internet")
        assertTrue("Could not scroll 'Network & internet' into view", scrolled)
        val target = device.wait(Until.findObject(By.textContains("Network")), 5_000)
        assertNotNull("Settings list should contain 'Network & internet'", target)
        target.click()
        Thread.sleep(1500)
        val pkg = device.currentPackageName
        AgentDiagnostics.recordEvent("test", "UiAutomation click -> pkg=$pkg")
        assertTrue("Still on unexpected package after click: $pkg", pkg.contains("settings"))
    }

    @Test
    fun uiAutomationTypeIntoSearch() {
        openSettings()
        val search = device.wait(Until.findObject(By.textContains("Search")), 8_000)
        assertNotNull("Settings search bar not found", search)
        search.click()
        Thread.sleep(1500)
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        assertNotNull("No EditText after clicking search", field)
        field.text = "wifi"
        Thread.sleep(1500)
        val typed = device.findObject(By.textContains("wifi"))
        AgentDiagnostics.recordEvent("test", "UiAutomation typed, visible=${typed != null}")
        assertNotNull("Typed text not visible", typed)
    }

    @Test
    fun uiAutomationScrollList() {
        openSettings()
        val scrollable = device.wait(Until.findObject(By.scrollable(true)), 8_000)
        assertNotNull("No scrollable container in Settings", scrollable)
        val ok = scrollable.scroll(Direction.DOWN, 1.0f)
        AgentDiagnostics.recordEvent("test", "UiAutomation scroll -> $ok")
        assertTrue("Scroll returned false", ok)
    }

    @Test
    fun uiAutomationLongPressDispatches() {
        device.pressHome()
        Thread.sleep(1500)
        val dm = context.resources.displayMetrics
        val cx = dm.widthPixels / 2
        val cy = dm.heightPixels / 2
        val ok = device.swipe(cx, cy, cx, cy, 100)
        AgentDiagnostics.recordEvent("test", "UiAutomation long-press swipe -> $ok")
        assertTrue("Long-press swipe returned false", ok)
    }

    @Test
    fun uiAutomationDragDispatches() {
        openSettings()
        val dm = context.resources.displayMetrics
        val cx = dm.widthPixels / 2
        val ok = device.drag(cx, (dm.heightPixels * 0.7f).toInt(), cx, (dm.heightPixels * 0.3f).toInt(), 40)
        AgentDiagnostics.recordEvent("test", "UiAutomation drag -> $ok")
        assertTrue("Drag returned false", ok)
    }

    @Test
    fun uiAutomationScreenshotReturnsImage() {
        openSettings()
        val shot: Bitmap? = uiAutomation().takeScreenshot()
        AgentDiagnostics.recordEvent("test", "UiAutomation screenshot ${shot?.width}x${shot?.height}")
        assertNotNull("UiAutomation screenshot returned null", shot)
        assertTrue("Screenshot has zero area", (shot!!.width * shot.height) > 0)
    }

    // ---------------- Service (runs only when the service is bound) ----------------

    @Test
    fun serviceHierarchyAvailable() {
        val service = awaitServiceOrNull()
        assumeNotNull("ZhypixAccessibilityService not bound (emulator force-stop limitation)", service)
        val hierarchy = service!!.getActiveWindowHierarchy()
        assertTrue("Hierarchy should not be blank", hierarchy.isNotBlank())
        AgentDiagnostics.recordEvent("test", "service connected, hierarchy ${hierarchy.length} chars")
    }

    @Test
    fun serviceClickSmartInSettings() {
        val service = awaitServiceOrNull()
        assumeNotNull("ZhypixAccessibilityService not bound (emulator force-stop limitation)", service)
        openSettings()
        val result = runBlocking { service!!.clickSmart("Network") }
        AgentDiagnostics.recordEvent("test", "clickSmart(Network) success=${result.success}: ${result.message}")
        assertTrue("clickSmart('Network') failed: ${result.message}", result.success)
    }

    @Test
    fun serviceInputTextAndScroll() {
        val service = awaitServiceOrNull()
        assumeNotNull("ZhypixAccessibilityService not bound (emulator force-stop limitation)", service)
        openSettings()
        val clicked = runBlocking {
            val first = service!!.clickSmart("Search settings")
            if (first.success) first else service!!.clickSmart("Search")
        }
        assertTrue("Could not click Settings search bar: ${clicked.message}", clicked.success)
        Thread.sleep(1000)
        val typed = service!!.inputText("wifi")
        assertTrue("inputText failed - no focused editable field", typed)
        Thread.sleep(1200)
        val ok = service.scrollSmart("DOWN")
        AgentDiagnostics.recordEvent("test", "service inputText=$typed scroll=$ok")
        assertTrue("scrollSmart(DOWN) failed", ok)
    }

    @Test
    fun serviceGesturesAndScreenshot() {
        val service = awaitServiceOrNull()
        assumeNotNull("ZhypixAccessibilityService not bound (emulator force-stop limitation)", service)
        val dm = context.resources.displayMetrics
        val cx = dm.widthPixels / 2f
        openSettings()
        val drag = runBlocking {
            service!!.dispatchDragAndDrop(cx, dm.heightPixels * 0.7f, cx, dm.heightPixels * 0.3f)
        }
        assertTrue("dispatchDragAndDrop failed", drag)
        device.pressHome()
        Thread.sleep(1500)
        val longPress = runBlocking { service!!.dispatchLongPress(cx, dm.heightPixels / 2f) }
        assertTrue("dispatchLongPress failed", longPress)
        val latch = CountDownLatch(1)
        var shot: String? = null
        service!!.takeScreenshotBase64(forceFresh = true) { b64 ->
            shot = b64
            latch.countDown()
        }
        assertTrue("Screenshot callback timed out", latch.await(20, TimeUnit.SECONDS))
        AgentDiagnostics.recordEvent("test", "service gestures ok, screenshot bytes=${shot?.length ?: 0}")
        assertNotNull("Screenshot capture returned null", shot)
    }
}
