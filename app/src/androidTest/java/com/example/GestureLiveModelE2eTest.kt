package com.example

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.*
import com.example.service.ZhypixAccessibilityService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Free vision model -> ScreenModel -> ScreenLoop -> the app's own ScreenObservation + ScreenDevice on the real
 * accessibility service. A narrow harness only lets taps land on the two named Settings rows and Back, so the run cannot change anything.
 */
@RunWith(AndroidJUnit4::class)
class GestureLiveModelE2eTest {
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
        throw AssertionError("Accessibility service did not bind")
    }

    private fun bounds(text: String): Rect? {
        val root = ui.rootInActiveWindow ?: return null
        val hit = root.findAccessibilityNodeInfosByText(text).firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text } ?: return null
        return Rect().also { hit.getBoundsInScreen(it) }
    }

    private fun waitFor(text: String, ms: Long = 8000): Rect? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { bounds(text)?.let { return it }; Thread.sleep(250) }
        return null
    }

    @Test fun modelDrivesRealAdapterTwoLevelsWithoutChangingAnything() = runBlocking {
        val file = java.io.File(ctx.filesDir, "live-model-test.json")
        require(file.exists()) { "Manual live-model workflow must supply private test config" }
        val config = JSONObject(file.readText()); file.delete()
        val service = bindService()
        val model = ScreenModel(config.getString("key"), config.getString("base"), config.getString("model"), false)
        val evidence = java.io.File(ctx.filesDir, "live-model-evidence.txt")
        val task = "In Android Settings, first open Network & internet, then open the Internet row inside it. Do not toggle or change anything. Once the Internet page is visibly open, return finished."
        evidence.writeText("Live model: ${config.getString("model")}\nPath: real ScreenObservation + ScreenDevice (accessibility service)\nTask: $task\n")
        ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertNotNull(waitFor("Network & internet")); Thread.sleep(2500)
        val allowed = listOf("Network & internet", "Internet")
        val loop = ScreenLoop(
            capture = { service.observation.capture() },
            decide = { observation, history ->
                val reply = model.decide(task, observation, history)
                evidence.appendText("Reply: ${reply.take(300).replace("\n", " ")}\n"); reply
            },
            execute = { action ->
                evidence.appendText("Action: $action\n")
                val ok = when (action) {
                    is ScreenAction.Click -> allowed.any { label -> bounds(label)?.contains(action.x, action.y) == true }
                    ScreenAction.PressBack -> true
                    is ScreenAction.Wait -> true
                    else -> false
                }
                if (!ok) ScreenLoop.Execution(false, "Action outside the safe test subset")
                else service.device.execute(action).also { evidence.appendText("Adapter: ${it.success} ${it.message}\n") }
            }
        )
        val result = loop.run(maxSteps = 8)
        evidence.appendText("Terminal: $result\n")
        assertTrue("Model did not report completion: $result", result is ScreenAction.Finished)
        val internetPage = waitFor("Internet")
        assertNotNull("Internet page not visible", internetPage)
        assertTrue("Still on Network & internet list", bounds("Network & internet") == null || bounds("Mobile network") == null)
        evidence.appendText("PASS: verified independently. No settings changed.\n")
    }
}
