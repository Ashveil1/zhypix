package com.zhypix.fresh

import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.zhypix.fresh.data.ProviderStore
import com.zhypix.fresh.service.AgentAccessibilityService
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Drives the REAL app UI the way a user does: type a message into the chat
 * field, tap Send, and let the agent work - twice in a row (multi-turn).
 *
 * Guards the two failures seen on a physical phone:
 *  - the app freezing (ANR) after Send: a blocked main thread makes the
 *    Compose rule's idle synchronization time out and fail the test
 *  - the agent never completing the task
 *
 * The chat UI is driven with the in-process Compose test rule (the emulator's
 * accessibility ACTION_CLICK/gesture path does not reliably trigger Compose
 * buttons); progress is then read from the app's own telemetry file (same
 * UID), so polling never disturbs the screen the agent is operating on.
 *
 * Do NOT use UiDevice here: its default UiAutomation suppresses accessibility
 * services, which keeps our service from ever binding.
 */
class ChatFlowE2eTest {

    companion object {
        private const val PKG = "com.zhypix.fresh"
        private const val SERVICE = "$PKG/com.zhypix.fresh.service.AgentAccessibilityService"
        private const val BASE_URL = "https://openrouter.ai/api/v1"
        private const val MODEL = "minimax/minimax-m3.1-flash:free"
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ui: UiAutomation = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
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

    // ---------- telemetry (written by the app's own AgentViewModel) ----------

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

    private fun dumpTree(): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 10 || sb.length > 2500) return
            val b = Rect()
            n.getBoundsInScreen(b)
            sb.append("  ".repeat(depth))
                .append(n.className?.toString()?.substringAfterLast('.') ?: "?")
            n.text?.toString()?.take(40)?.let { sb.append(" \"").append(it).append('"') }
            sb.append('\n')
            for (i in 0 until n.childCount) {
                walk(try { n.getChild(i) } catch (e: Exception) { null }, depth + 1)
            }
        }
        walk(try { ui.rootInActiveWindow } catch (e: Exception) { null }, 0)
        return sb.toString().ifBlank { "(no tree)" }
    }

    private fun sendChatMessage(text: String) {
        composeRule.onNode(hasSetTextAction()).performTextInput(text)
        composeRule.onNodeWithText("Send").performClick()
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
            "no agent step executed within 180s of Send - the app froze (ANR) or the agent never acted; tree=\n${dumpTree()}",
            awaitEvent("agent.step", 1, 180_000)
        )
        assertTrue(
            "turn 1 never finished (no terminal event within 300s)",
            awaitEvent("agent.terminal", 1, 300_000)
        )

        // Turn 2: a second message in the same session must also work. The
        // agent left our app in the background; bring its existing task (and
        // activity instance, so the session continues) back to the front.
        val stepsBefore = countEvents("agent.step")
        shell("am start -f 0x10020000 -n $PKG/.MainActivity") // NEW_TASK | REORDER_TO_FRONT
        composeRule.waitUntil(15_000) {
            var resumed = false
            composeRule.runOnIdle {
                resumed = composeRule.activity.lifecycle.currentState == Lifecycle.State.RESUMED
            }
            resumed
        }
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
