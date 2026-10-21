package com.zhypix.fresh

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
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
 */
class LiveModelE2eTest {

    companion object {
        private const val PKG = "com.zhypix.fresh"
        private const val SERVICE = "$PKG/com.zhypix.fresh.service.AgentAccessibilityService"
        private const val BASE_URL = "https://openrouter.ai/api/v1"
        private const val MODEL = "minimax/minimax-m3.1-flash:free"
    }

    @Test
    fun agentExecutesAtLeastOneAction() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val key = InstrumentationRegistry.getArguments().getString("e2eKey")
            ?: fail("missing -e e2eKey")

        val store = ProviderStore(ctx)
        store.saveProvider(ProviderStore.Provider("e2e", "e2e", BASE_URL, key, listOf(MODEL)))
        store.saveSelection(ProviderStore.Selection("e2e", MODEL))

        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("am start -n $PKG/.MainActivity")
        Thread.sleep(2500)
        // am instrument force-stops the app, which can leave the accessibility
        // service marked crashed; toggle it off/on to force a rebind.
        fun toggleService() {
            device.executeShellCommand("settings put secure accessibility_enabled 0")
            Thread.sleep(400)
            device.executeShellCommand("settings put secure enabled_accessibility_services $SERVICE")
            device.executeShellCommand("settings put secure accessibility_enabled 1")
        }
        toggleService()

        val bindDeadline = System.currentTimeMillis() + 90_000
        var service = AgentAccessibilityService.instance
        while (service == null && System.currentTimeMillis() < bindDeadline) {
            Thread.sleep(3000)
            toggleService()
            service = AgentAccessibilityService.instance
        }
        assertNotNull("accessibility service never bound", service)

        val client = ChatClient(BASE_URL, key, MODEL)
        val loop = AgentLoop(service!!, client, null, maxSteps = 8, settleMs = 800, postActionDelayMs = 200)
        val steps = AtomicInteger(0)
        val actions = mutableListOf<String>()
        val end = try {
            withTimeout(300_000) {
                loop.run(
                    "Open the Android Settings app using start_app with package_name com.android.settings, then use finished.",
                    object : AgentLoop.Sink {
                        override fun onStep(step: Int, maxSteps: Int, action: AgentAction) {
                            steps.incrementAndGet()
                            actions.add("${action.key}")
                            println("E2E step $step/$maxSteps: ${action.key}")
                        }
                        override suspend fun awaitUserReply(prompt: String, needsConfirmation: Boolean): String = "continue"
                    }
                )
            }
        } catch (e: Exception) {
            fail("loop threw: ${e.message}")
            return@runBlocking
        }

        println("E2E end=$end actions=$actions")
        assertTrue("no actions executed (end=$end)", steps.get() >= 1)
        assertTrue("unexpected end state: $end", end !is AgentLoop.End.Error)
    }
}
