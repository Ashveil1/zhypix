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
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val confirmBefore: suspend (ScreenAction) -> String? = { null }
) {
    data class Observation(val image: String?, val phoneState: String, val metadata: String)
    data class Execution(val success: Boolean, val message: String, val safeToRetry: Boolean = false)
    private val mutex get() = ScreenSessionGate.mutex

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
                var replyExcerpt = ""
                if (parsed !is ActionParseResult.Ok) {
                    val why = (parsed as? ActionParseResult.Invalid)?.reason ?: "no single action call found"
                    sleep(ScreenPolicy.STAGE_RETRY_MS)
                    history.add("Your last reply was rejected ($why). Reply with exactly one action call and nothing else, for example: click({\"x\":100,\"y\":200}) or finished({\"message\":\"...\"})")
                    replyExcerpt = retryStage { decide(observation, history.toList()) }.also { reply ->
                        parsed = ActionParser.parse(reply)
                    }.take(120).replace("\n", " ")
                }
                val action = (parsed as? ActionParseResult.Ok)?.action
                    ?: return ScreenAction.FailedToCompleteTask(
                        "The model did not return a valid action after two attempts" +
                            (if (replyExcerpt.isBlank()) "." else " (last reply started with: \"$replyExcerpt\").") +
                            " Rephrase the task and try again."
                    )
                if (action is ScreenAction.Terminal) return action
                if (action is ScreenAction.Walkthrough) return ScreenAction.MessageUser(action.steps)
                confirmBefore(action)?.let { return ScreenAction.NeedsConfirmation(it) }
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

/** Shared by REST tasks and Live actions to prevent overlapping dispatch. */
object ScreenSessionGate { val mutex = Mutex() }
