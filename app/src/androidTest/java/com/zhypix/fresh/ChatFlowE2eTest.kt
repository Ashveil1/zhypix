package com.zhypix.fresh

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.zhypix.fresh.data.ProviderStore
import com.zhypix.fresh.service.AgentAccessibilityService
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Drives the REAL app UI the way a user does: type a message into the chat,
 * tap Send, and let the agent work - twice in a row (multi-turn).
 *
 * Guards the two failures seen on a physical phone:
 *  - the app freezing (ANR) after Send: any blocking call on the main thread
 *    deadlocks against gesture callbacks that are delivered on the main looper
 *  - the agent never completing the task
 *
 * Progress is read from the app's own telemetry file (same UID), so polling
 * never disturbs the screen the agent is operating on.
 */
class ChatFlowE2eTest {

    companion object {
        private const val PKG = "com.zhypix.fresh"
        private const val SERVICE = "$PKG/com.zhypix.fresh.service.AgentAccessibilityService"
        private const val BASE_URL = "https://openrouter.ai/api/v1"
        private const val MODEL = "minimax/minimax-m3.1-flash:free"
    }

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ui: UiAutomation = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val device: UiDevice = UiDevice.getInstance(inst)
    private val telemetryFile: File get() = File(inst.targetContext.filesDir, "agent_steps.jsonl")

    private fun shell(cmd: String) {
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd)).use { it.readBytes() }
    }

    /**
     * `am instrument` force-stops the app, which unbinds the accessibility
     * service; a single settings write often does not rebind it. Toggle it
     * until the service reports itself connected, same as the other e2e tests.
     */
    private fun bindService() {
        AgentAccessibilityService.instance?.let { return }
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
                AgentAccessibilityService.instance?.let { return }
                Thread.sleep(250)
            }
        }
        fail("accessibility service did not bind after re-enabling")
    }

    private fun telemetry(): String = try {
        telemetryFile.takeIf { it.exists() }?.readText() ?: ""
    } catch (e: Exception) { "" }

    private fun countEvents(stage: String) = telemetry().lines().count { it.contains("\"$stage\"") }

    private fun awaitEvent(stage: String, atLeast: Int, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (countEvents(stage) >= atLeast) return true
            Thread.sleep(2000)
        }
        return false
    }

    private fun launchApp() {
        shell("am start -n $PKG/.MainActivity")
        device.wait(Until.hasObject(By.pkg(PKG).depth(0)), 5_000)
    }

    private fun sendChatMessage(text: String) {
        launchApp()
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 15_000)
            ?: run { fail("chat input field not found"); return }
        field.text = text
        repeat(3) {
            val send = device.wait(Until.findObject(By.text("Send")), 15_000) ?: return@repeat
            send.click()
            // Run started: the button switches to Stop while the agent works.
            if (device.wait(Until.hasObject(By.text("Stop")), 10_000)) return
            device.pressBack() // a keyboard may have covered the button; dismiss and retry
            Thread.sleep(1000)
        }
        val visible = device.findObjects(By.pkg(PKG)).mapNotNull { it.text }.distinct().take(12)
        fail("could not start an agent run from the chat UI; visible texts=$visible")
    }

    @Test
    fun twoTurnsWithoutFreeze() {
        val key = requireNotNull(InstrumentationRegistry.getArguments().getString("e2eKey")) { "missing -e e2eKey" }
        val store = ProviderStore(inst.targetContext)
        store.saveProvider(ProviderStore.Provider("e2e", "e2e", BASE_URL, key, listOf(MODEL)))
        store.saveSelection(ProviderStore.Selection("e2e", MODEL))
        bindService()
        telemetryFile.delete()

        // Turn 1: send a task through the real chat UI.
        sendChatMessage("Open the Android Settings app with start_app (package com.android.settings), tap one row with click, then reply finished.")
        // Freeze check: the first executed step must be logged soon after Send.
        assertTrue(
            "no agent step executed within 180s of Send - the app froze (ANR) or the agent never acted",
            awaitEvent("agent.step", 1, 180_000)
        )
        assertTrue(
            "turn 1 never finished (no terminal event within 300s)",
            awaitEvent("agent.terminal", 1, 300_000)
        )

        // Turn 2: a second message in the same session must also work.
        val stepsBefore = countEvents("agent.step")
        sendChatMessage("Open Settings again with start_app and reply finished.")
        assertTrue(
            "turn 2 produced no executed steps",
            awaitEvent("agent.step", stepsBefore + 1, 180_000)
        )
        assertTrue(
            "turn 2 never finished",
            awaitEvent("agent.terminal", 2, 300_000)
        )
    }
}
