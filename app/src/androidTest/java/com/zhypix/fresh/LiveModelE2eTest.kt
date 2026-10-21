package com.zhypix.fresh

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.zhypix.fresh.agent.AgentAction
import com.zhypix.fresh.agent.AgentLoop
import com.zhypix.fresh.api.ChatClient
import com.zhypix.fresh.data.ProviderStore
import com.zhypix.fresh.service.AgentAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
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
 * The loop runs on Dispatchers.Main, the same dispatcher the app's ViewModel
 * uses. Gesture callbacks are delivered on the main looper, so any blocking
 * call on the main thread deadlocks the loop (the ANR seen on a physical
 * phone). The bounded await turns that deadlock into a fast, clear failure
 * instead of a hung run.
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
        val loop = AgentLoop(service, client, null, maxSteps = 10, settleMs = 800, postActionDelayMs = 200)
        val steps = AtomicInteger(0)
        val pauses = AtomicInteger(0)
        val actions = mutableListOf<String>()
        // Not a child of runBlocking: if the loop deadlocks the main thread the
        // await below still times out and fails cleanly instead of hanging CI.
        val result = CompletableDeferred<AgentLoop.End>()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                result.complete(
                    loop.run(
                        "Open the Android Settings app using start_app with package_name com.android.settings, then tap any row using click, then use finished.",
                        object : AgentLoop.Sink {
                            override fun onStep(step: Int, maxSteps: Int, action: AgentAction) {
                                steps.incrementAndGet()
                                actions.add(action.key)
                                println("E2E step $step/$maxSteps: ${action.key}")
                            }
                            override suspend fun awaitUserReply(prompt: String, needsConfirmation: Boolean): String {
                                pauses.incrementAndGet()
                                println("E2E pause confirm=$needsConfirmation: $prompt")
                                return "continue"
                            }
                        }
                    )
                )
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        val end = try {
            withTimeout(240_000) { result.await() }
        } catch (e: TimeoutCancellationException) {
            fail("agent loop froze on the main thread (no end within 240s); actions so far=$actions")
            return@runBlocking
        } catch (e: Exception) {
            fail("loop threw: ${e.message}")
            return@runBlocking
        }

        println("E2E end=$end actions=$actions pauses=${pauses.get()}")
        assertTrue("no actions executed (end=$end)", steps.get() >= 1)
        assertTrue("unexpected end state: $end", end !is AgentLoop.End.Error)
    }
}
