package com.zhypix.fresh

import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.zhypix.fresh.data.ProviderStore
import com.zhypix.fresh.service.AgentAccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Drives the REAL app UI the way a user does: put a message into the chat
 * field, tap Send, and let the agent work - twice in a row (multi-turn).
 *
 * Guards the two failures seen on a physical phone:
 *  - the app freezing (ANR) after Send: any blocking call on the main thread
 *    deadlocks against gesture callbacks that are delivered on the main looper
 *  - the agent never completing the task
 *
 * Progress is read from the app's own telemetry file (same UID), so polling
 * never disturbs the screen the agent is operating on.
 *
 * Everything goes through ONE UiAutomation opened with DONT_SUPPRESS and the
 * app's own accessibility service. Do NOT use UiDevice here: its default
 * UiAutomation suppresses accessibility services, which keeps our service
 * from ever binding.
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

    // ---------- UI driving through the DONT_SUPPRESS UiAutomation ----------

    private fun root(): AccessibilityNodeInfo? = try { ui.rootInActiveWindow } catch (e: Exception) { null }

    private fun findByText(text: String): AccessibilityNodeInfo? {
        val r = root() ?: return null
        return r.findAccessibilityNodeInfosByText(text).firstOrNull { it.text?.toString() == text }
    }

    private fun findEditText(): AccessibilityNodeInfo? {
        fun walk(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.className?.toString() == "android.widget.EditText") return n
            for (i in 0 until n.childCount) {
                walk(try { n.getChild(i) } catch (e: Exception) { null })?.let { return it }
            }
            return null
        }
        return walk(root())
    }

    private fun waitForNode(text: String, ms: Long): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            findByText(text)?.let { return it }
            Thread.sleep(400)
        }
        return null
    }

    private fun setFieldText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return try { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) } catch (e: Exception) { false }
    }

    private fun tapNode(node: AccessibilityNodeInfo): Boolean {
        // Deterministic first: fire the semantics onClick of the node or its
        // nearest clickable ancestor (Compose buttons expose this).
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) {
                if (try { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (e: Exception) { false }) return true
            }
            n = try { n.parent } catch (e: Exception) { null }
        }
        // Fallback: a real gesture at the node's center via our service.
        val service = AgentAccessibilityService.instance ?: return false
        val b = Rect()
        node.getBoundsInScreen(b)
        if (b.isEmpty) return false
        return runBlocking { service.click(b.centerX().toFloat(), b.centerY().toFloat()) }
    }

    private fun launchApp() {
        shell("am start -n $PKG/.MainActivity")
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            if (root()?.packageName?.toString() == PKG) return
            Thread.sleep(400)
        }
    }

    private fun dumpTree(): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 10 || sb.length > 3000) return
            val b = Rect()
            n.getBoundsInScreen(b)
            sb.append("  ".repeat(depth))
                .append(n.className?.toString()?.substringAfterLast('.') ?: "?")
                .append(" [").append(b.toShortString()).append("]")
            if (n.isClickable) sb.append(" click")
            if (n.isEditable) sb.append(" edit")
            n.text?.toString()?.take(40)?.let { sb.append(" \"").append(it).append('"') }
            sb.append('\n')
            for (i in 0 until n.childCount) {
                walk(try { n.getChild(i) } catch (e: Exception) { null }, depth + 1)
            }
        }
        walk(root(), 0)
        return sb.toString().ifBlank { "(no tree)" }
    }

    private fun sendChatMessage(text: String) {
        launchApp()
        val eventsBefore = telemetry().lines().size
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end) {
            val field = findEditText()
            if (field != null && setFieldText(field, text)) {
                val send = waitForNode("Send", 5_000)
                if (send != null && tapNode(send)) {
                    // Run started: the button switches to Stop, or telemetry grows.
                    val waitEnd = System.currentTimeMillis() + 15_000
                    while (System.currentTimeMillis() < waitEnd) {
                        if (findByText("Stop") != null) return
                        if (telemetry().lines().size > eventsBefore) return
                        Thread.sleep(400)
                    }
                }
            }
            Thread.sleep(1000)
        }
        fail("could not start an agent run from the chat UI; tree=\n${dumpTree()}")
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
