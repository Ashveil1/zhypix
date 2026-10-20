package com.example.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/** Fresh observation before every decision. No automatic repeat of an uncertain physical action. */
class ScreenLoop(
    private val capture: suspend () -> Observation,
    private val decide: suspend (Observation, List<String>) -> String,
    private val execute: suspend (ScreenAction) -> Execution,
    private val onStep: (Int, Int) -> Unit = { _, _ -> },
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    data class Observation(val image: String?, val phoneState: String, val metadata: String)
    data class Execution(val success: Boolean, val message: String, val safeToRetry: Boolean = false)
    private val mutex = Mutex()

    suspend fun run(maxSteps: Int = ScreenPolicy.MAX_STEPS, postActionMs: Long = ScreenPolicy.DEFAULT_POST_ACTION_MS): ScreenAction.Terminal {
        require(maxSteps in 1..ScreenPolicy.MAX_STEPS)
        require(postActionMs in 0..60000)
        if (!mutex.tryLock()) return ScreenAction.MessageUser("Another screen task is running. Stop it first.")
        try {
            val history = mutableListOf<String>()
            for (step in 1..maxSteps) {
                onStep(step, maxSteps)
                val observation = retryStage { capture() }
                var parsed: ActionParseResult = ActionParser.parse(retryStage { decide(observation, history.toList()) })
                if (parsed !is ActionParseResult.Ok) {
                    sleep(ScreenPolicy.STAGE_RETRY_MS)
                    history.add("Invalid action. Return exactly one supported name(JSON object) action.")
                    parsed = ActionParser.parse(retryStage { decide(observation, history.toList()) })
                }
                val action = (parsed as? ActionParseResult.Ok)?.action
                    ?: return ScreenAction.FailedToCompleteTask("The model returned an invalid action twice.")
                if (action is ScreenAction.Terminal) return action
                var result = execute(action)
                if (!result.success && result.safeToRetry) {
                    sleep(ScreenPolicy.STAGE_RETRY_MS)
                    result = execute(action)
                }
                history.add("$action: ${result.message}")
                if (!result.success) return ScreenAction.FailedToCompleteTask(result.message)
                if (postActionMs > 0) sleep(postActionMs)
            }
            return ScreenAction.FailedToCompleteTask("Maximum of $maxSteps steps reached.")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            return ScreenAction.FailedToCompleteTask(error.message ?: "Screen task failed.")
        } finally { mutex.unlock() }
    }

    private suspend fun <T> retryStage(block: suspend () -> T): T {
        try { return block() }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { sleep(ScreenPolicy.STAGE_RETRY_MS); return block() }
    }
}
