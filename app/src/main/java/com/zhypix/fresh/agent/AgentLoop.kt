package com.zhypix.fresh.agent

import android.graphics.Bitmap
import android.util.Base64
import com.zhypix.fresh.api.ChatClient
import com.zhypix.fresh.service.AgentAccessibilityService
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The observe -> one action -> settle -> verify loop.
 *
 * Per stage retries (1s apart, once): backend, parse, action, network.
 * Never retries user cancellation. Stops on terminal actions, on the step
 * cap, or when the LoopGuard sees the same action against an unchanged
 * screen too many times.
 */
class AgentLoop(
    private val service: AgentAccessibilityService,
    private val client: ChatClient,
    private val telemetry: TelemetryLog?,
    private val maxSteps: Int = 150,
    private val settleMs: Long = 1000,
    private val postActionDelayMs: Long = 300
) {
    sealed class End {
        data class Finished(val message: String) : End()
        data class Failed(val reason: String) : End()
        data class MaxSteps(val steps: Int) : End()
        object Stuck : End()
        object Cancelled : End()
        data class Error(val message: String) : End()
    }

    interface Sink {
        fun onStep(step: Int, maxSteps: Int, action: AgentAction) {}
        fun onNote(text: String) {}
        /** Called when the model pauses for the user. Must return the user's reply. */
        suspend fun awaitUserReply(prompt: String, needsConfirmation: Boolean): String
    }

    private val moshi = Moshi.Builder().build()
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    private val jsonParse: (String) -> Map<String, Any?>? = { s ->
        try { mapAdapter.fromJson(s) } catch (e: Exception) { null }
    }

    private val guard = LoopGuard()
    private val history = mutableListOf<Pair<String, ChatClient.Turn>>()
    private var followUp: String? = null

    fun queueFollowUp(text: String) { followUp = text }

    suspend fun run(goal: String, sink: Sink): End {
        guard.reset()
        history.clear()

        val (w, h) = service.screenSize()
        history.add("system" to ChatClient.Turn(ScreenPrompt.build(w, h)))

        var taskPrompt = goal
        val installed = try { service.installedPackages().joinToString(", ") } catch (e: Exception) { "" }

        var step = 0
        var lastSignature = ""
        var lastNote = "Task started."
        var parseFailures = 0
        var backendFailures = 0
        var actionFailures = 0

        while (step < maxSteps) {
            if (followUp != null) {
                taskPrompt += "\n\nThe user replied: " + followUp
                followUp = null
            }
            step++
            val t0 = System.currentTimeMillis()

            val shot = service.screenshot()
            val scaled = shot?.let { scaleLongSide(it, 780) }
            val b64 = scaled?.let { jpegBase64(it, 75) }
            val signature = scaled?.let { averageHash(it) } ?: "nosig"
            val phoneState = service.phoneState()
            val tree = service.treeSummary()

            val obsText = buildString {
                append("Step ").append(step).append('/').append(maxSteps).append('\n')
                append("Screen is ").append(w).append('x').append(h).append(" raw pixels. The attached screenshot is scaled.\n")
                append(phoneState).append('\n')
                if (step == 1 && installed.isNotBlank()) {
                    append("Installed apps: ").append(installed).append('\n')
                }
                append("Screen elements (center coordinates are raw pixels):\n").append(tree).append('\n')
                append("Last step result: ").append(lastNote).append('\n')
                append("Task: ").append(taskPrompt)
            }

            history.add("user" to ChatClient.Turn(obsText, b64))
            pruneImages(keepLast = 3)
            pruneOldTurns(keepTurns = 16)

            // ---- backend stage (one retry after 1s) ----
            val reply: String = try {
                client.complete(history).also { backendFailures = 0 }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                backendFailures++
                telemetry?.event("agent.backend", mapOf("step" to step, "failure_reason" to e.message))
                if (backendFailures >= 2) return End.Error("Provider unreachable: ${e.message}")
                delay(1000)
                try {
                    client.complete(history)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e2: Exception) {
                    return End.Error("Provider unreachable: ${e2.message}")
                }
            }

            // ---- parse stage (one corrective retry) ----
            val parsed = ActionParser.parse(reply, jsonParse)
            val action: AgentAction = when (parsed) {
                is ActionParser.Result.One -> parsed.action
                is ActionParser.Result.None -> {
                    // A reply with no action and prose is the model talking to the user.
                    if (parsed === ActionParser.Result.None && reply.isNotBlank()) {
                        val userReply = sink.awaitUserReply(reply, needsConfirmation = false)
                        followUp = userReply
                        continue
                    }
                    return End.Error("Model returned nothing.")
                }
                is ActionParser.Result.Several -> {
                    parseFailures++
                    telemetry?.event("agent.parse", mapOf("step" to step, "failure_reason" to "multiple actions"))
                    if (parseFailures >= 2) {
                        return End.Error("The model kept returning several actions at once. Its reply: ${reply.take(300)}")
                    }
                    history.add("assistant" to ChatClient.Turn(reply))
                    history.add("user" to ChatClient.Turn(
                        "Invalid: your reply contained ${parsed.count} actions. Reply with EXACTLY ONE action in the form name({...})."))
                    delay(1000)
                    continue
                }
                is ActionParser.Result.Invalid -> {
                    parseFailures++
                    telemetry?.event("agent.parse", mapOf("step" to step, "failure_reason" to parsed.reason))
                    if (parseFailures >= 2) {
                        return End.Error("The model returned an invalid action twice. Reply and I'll continue. Its reply: ${reply.take(300)}")
                    }
                    history.add("assistant" to ChatClient.Turn(reply))
                    history.add("user" to ChatClient.Turn(
                        "Invalid action (${parsed.reason}). Reply with EXACTLY ONE valid action, e.g. click({\"x\":540,\"y\":1200})."))
                    delay(1000)
                    continue
                }
            }
            parseFailures = 0

            sink.onStep(step, maxSteps, action)
            history.add("assistant" to ChatClient.Turn(reply))

            // ---- terminal / pause ----
            if (action.isTerminal) {
                telemetry?.event("agent.terminal", mapOf("step" to step, "action_type" to action.key))
                return when (action) {
                    is AgentAction.Finished -> End.Finished(action.message)
                    is AgentAction.Failed -> End.Failed(action.reason)
                    else -> End.Error("unreachable")
                }
            }
            if (action.pausesForUser) {
                telemetry?.event("agent.pause", mapOf("step" to step, "action_type" to action.key))
                val needsConfirm = action is AgentAction.NeedsConfirmation
                val replyText = sink.awaitUserReply(action.userFacingText ?: "I need your input.", needsConfirm)
                if (needsConfirm && !looksAffirmative(replyText)) {
                    return End.Finished("Stopped: you did not confirm. Nothing was sent or changed.")
                }
                followUp = replyText
                continue
            }

            // ---- stuck guard ----
            val actionKey = action.key + ":" + action.toString()
            if (guard.recordAndIsStuck(actionKey, signature)) {
                telemetry?.event("agent.stuck", mapOf("step" to step, "action_type" to action.key))
                return End.Stuck
            }

            // ---- action stage (one retry after 1s) ----
            val execResult = execute(action, w, h)
            val note = if (execResult == null) {
                actionFailures++
                telemetry?.event("agent.action", mapOf("step" to step, "action_type" to action.key, "failure_reason" to "dispatch failed"))
                if (actionFailures >= 2) {
                    "Action ${action.key} failed twice. The gesture may have been rejected by the system."
                } else {
                    delay(1000)
                    when (execute(action, w, h)) {
                        null -> "Action ${action.key} failed twice. The gesture may have been rejected by the system."
                        else -> "Action ${action.key} executed."
                    }
                }
            } else {
                actionFailures = 0
                execResult
            }

            if (postActionDelayMs > 0) delay(postActionDelayMs)
            delay(settleMs)

            // ---- verify: did the screen change? ----
            val after = service.screenshot()?.let { scaleLongSide(it, 780) }
            val afterSig = after?.let { averageHash(it) } ?: signature
            val changed = afterSig != signature
            lastNote = note + if (changed) " Screen changed." else " Screen did NOT change - do not repeat this exact action."
            lastSignature = afterSig

            telemetry?.event("agent.step", mapOf(
                "step" to step, "action_type" to action.key,
                "latency_ms" to (System.currentTimeMillis() - t0),
                "screen_changed" to changed
            ))
        }
        return End.MaxSteps(maxSteps)
    }

    private fun execute(action: AgentAction, w: Int, h: Int): String? {
        fun inBounds(x: Int, y: Int) = x in 0..w && y in 0..h
        return when (action) {
            is AgentAction.Click -> {
                if (!inBounds(action.x, action.y)) "Rejected: (${action.x},${action.y}) is outside the ${w}x$h screen."
                else if (!runBlockingSafe { service.click(action.x.toFloat(), action.y.toFloat()) }) null
                else "Tapped (${action.x},${action.y})."
            }
            is AgentAction.DoubleClick -> {
                if (!inBounds(action.x, action.y)) "Rejected: (${action.x},${action.y}) is outside the ${w}x$h screen."
                else {
                    val (ok, msg) = runBlockingSafe { service.doubleClick(action.x.toFloat(), action.y.toFloat()) }
                    if (!ok) msg ?: "double_click failed."
                    else "Double-tapped (${action.x},${action.y})."
                }
            }
            is AgentAction.PressAndHold -> {
                if (!inBounds(action.x, action.y)) "Rejected: (${action.x},${action.y}) is outside the ${w}x$h screen."
                else if (!runBlockingSafe { service.pressAndHold(action.x.toFloat(), action.y.toFloat()) }) null
                else "Long-pressed (${action.x},${action.y})."
            }
            is AgentAction.Drag -> {
                if (!inBounds(action.startX, action.startY) || !inBounds(action.endX, action.endY)) "Rejected: drag endpoints outside the ${w}x$h screen."
                else if (!runBlockingSafe { service.drag(action.startX.toFloat(), action.startY.toFloat(), action.endX.toFloat(), action.endY.toFloat()) }) null
                else "Dragged."
            }
            is AgentAction.DragAndDrop -> {
                if (!inBounds(action.startX, action.startY) || !inBounds(action.endX, action.endY)) "Rejected: drag endpoints outside the ${w}x$h screen."
                else if (!runBlockingSafe { service.dragAndDrop(action.startX.toFloat(), action.startY.toFloat(), action.endX.toFloat(), action.endY.toFloat()) }) null
                else "Dragged and dropped."
            }
            is AgentAction.TypeText ->
                if (service.typeText(action.text)) "Typed ${action.text.length} characters." else null
            is AgentAction.Wait -> { kotlinx.coroutines.runBlocking { delay((action.seconds * 1000).toLong()) }; "Waited ${action.seconds}s." }
            is AgentAction.PressBack -> if (service.pressBack()) "Pressed back." else null
            is AgentAction.PressHome -> if (service.pressHome()) "Pressed home." else null
            is AgentAction.StartApp ->
                if (service.startApp(action.packageName)) "Launched ${action.packageName}." else "Could not launch ${action.packageName} (not installed?)."
            else -> "Unhandled action."
        }
    }

    private fun <T> runBlockingSafe(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }

    private fun looksAffirmative(text: String): Boolean {
        val t = text.trim().lowercase()
        val yes = listOf("yes", "ok", "yep", "yeah", "confirm", "go ahead", "sure", "do it", "ตกลง", "ใช่", "โอเค", "ยืนยัน", "ทำเลย", "ได้", "เอาเลย")
        return yes.any { t == it || t.startsWith("$it ") || t.startsWith("$it!") || t.startsWith("$it.") }
    }

    /** Keeps the request within model context: old observations collapse to a stub. */
    private fun pruneOldTurns(keepTurns: Int) {
        if (history.size <= keepTurns + 1) return
        for (i in 1 until history.size - keepTurns) {
            val (role, turn) = history[i]
            if (!turn.text.startsWith("[step]") && turn.imageBase64Jpeg == null) {
                history[i] = role to turn.copy(text = "[step] earlier observation or reply omitted")
            } else if (turn.imageBase64Jpeg != null) {
                history[i] = role to turn.copy(text = "[step] earlier observation omitted", imageBase64Jpeg = null)
            }
        }
    }

    private fun pruneImages(keepLast: Int) {
        var images = 0
        for (i in history.indices.reversed()) {
            val (role, turn) = history[i]
            if (role == "user" && turn.imageBase64Jpeg != null) {
                images++
                if (images > keepLast) {
                    history[i] = role to turn.copy(
                        text = turn.text + "\n[older screenshot removed]",
                        imageBase64Jpeg = null
                    )
                }
            }
        }
    }

    companion object {
        fun scaleLongSide(src: Bitmap, maxLongSide: Int): Bitmap {
            val longSide = maxOf(src.width, src.height)
            if (longSide <= maxLongSide) return src
            val scale = maxLongSide.toFloat() / longSide
            return Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }

        fun jpegBase64(bitmap: Bitmap, quality: Int): String {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }

        /** 8x8 average-hash signature used for screen-change detection. */
        fun averageHash(bitmap: Bitmap): String {
            val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
            val pixels = IntArray(64)
            small.getPixels(pixels, 0, 8, 0, 0, 8, 8)
            val lums = DoubleArray(64) { i ->
                val p = pixels[i]
                0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
            }
            val avg = lums.average()
            val sb = StringBuilder()
            for (i in 0 until 16) {
                var nibble = 0
                for (bit in 0 until 4) {
                    if (lums[i * 4 + bit] >= avg) nibble = nibble or (1 shl bit)
                }
                sb.append(Integer.toHexString(nibble))
            }
            return sb.toString()
        }
    }
}
