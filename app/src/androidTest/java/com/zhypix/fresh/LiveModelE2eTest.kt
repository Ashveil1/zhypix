package com.zhypix.fresh

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.zhypix.fresh.agent.AgentAction
import com.zhypix.fresh.agent.AgentLoop
import com.zhypix.fresh.api.ChatClient
import com.zhypix.fresh.data.ProviderStore
import com.zhypix.fresh.service.AgentAccessibilityService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end with the real free model: seed a provider, bind the accessibility
 * service, and let the agent drive the phone for a few steps. Asserts the
 * pipeline (perception -> model -> parse -> gesture) actually executes.
 *
 * UiAutomation is opened with DONT_SUPPRESS so acquiring it does not switch
 * our accessibility service off, and the service is re-bound from inside the
 * test because `am instrument` force-stops the app process first.
 */
class LiveModelE2eTest {

    companion object {
        private const val PKG = "com.zhypix.fresh"
        private const val SERVICE = "$PKG/com.zhypix.fresh.service.AgentAccessibilityService"
        private const val BASE_URL = "https://openrouter.ai/api/v1"
        private const val MODEL = "minimax/minimax-m3.1-flash:free"
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

    @Test
    fun agentExecutesAtLeastOneAction() = runBlocking {
        val key = requireNotNull(InstrumentationRegistry.getArguments().getString("e2eKey")) { "missing -e e2eKey" }

        val store = ProviderStore(inst.targetContext)
        store.saveProvider(ProviderStore.Provider("e2e", "e2e", BASE_URL, key, listOf(MODEL)))
        store.saveSelection(ProviderStore.Selection("e2e", MODEL))

        val service = bindService()
        assertNotNull("accessibility service never bound", service)

        val client = ChatClient(BASE_URL, key, MODEL)
        val loop = AgentLoop(service, client, null, maxSteps = 8, settleMs = 800, postActionDelayMs = 200)
        val steps = AtomicInteger(0)
        val actions = mutableListOf<String>()
        val end = try {
            withTimeout(300_000) {
                loop.run(
                    "Open the Android Settings app using start_app with package_name com.android.settings, then use finished.",
                    object : AgentLoop.Sink {
                        override fun onStep(step: Int, maxSteps: Int, action: AgentAction) {
                            steps.incrementAndGet()
                            actions.add(action.key)
                            println("E2E step $step/$maxSteps: ${action.key}")
                        }
                        override suspend fun awaitUserReply(prompt: String, needsConfirmation: Boolean): String = "continue"
                    }
                )
            }
        } catch (e: Exception) {
            org.junit.Assert.fail("loop threw: ${e.message}")
            return@runBlocking
        }

        println("E2E end=$end actions=$actions")
        assertTrue("no actions executed (end=$end)", steps.get() >= 1)
        assertTrue("unexpected end state: $end", end !is AgentLoop.End.Error)
    }
}
