package com.example.agent

import org.json.JSONObject

/**
 * Screen-control action language, rebuilt from scratch.
 *
 * The model speaks one action per reply using the textual form
 *     name({"arg": value, ...})
 * e.g.  click({"x": 540, "y": 1230})
 *
 * Terminal actions end the task instead of manipulating the screen.
 * This parser is deliberately strict about the outer shape and lenient
 * about argument types (numbers may arrive as strings, booleans as "true").
 */
sealed class ScreenAction {

    /** Tap at raw screen coordinates. Both raw and image sizes are supplied to the model. */
    data class Click(val x: Int, val y: Int) : ScreenAction()

    /** Two taps at the same point, short gap. */
    data class DoubleClick(val x: Int, val y: Int) : ScreenAction()

    /** Finger down at the point for [durationMs] (default: platform long-press feel). */
    data class PressAndHold(val x: Int, val y: Int, val durationMs: Long? = null) : ScreenAction()

    /** Continuous drag from one point to another over [durationMs]. */
    data class Drag(
        val startX: Int, val startY: Int,
        val endX: Int, val endY: Int,
        val durationMs: Long? = null
    ) : ScreenAction()

    /** Pick up an item, move, release — for reorderable lists. */
    data class DragAndDrop(
        val fromX: Int, val fromY: Int,
        val toX: Int, val toY: Int
    ) : ScreenAction()

    /** Type text into the currently focused field. */
    data class Type(val text: String) : ScreenAction()

    /** Pause the loop for [durationMs] (loading screens, animations). */
    data class Wait(val durationMs: Long) : ScreenAction()

    object PressBack : ScreenAction()
    object PressHome : ScreenAction()

    /** Launch an installed app by package name. */
    data class StartApp(val packageName: String) : ScreenAction()

    /** Show an on-screen step-by-step guide overlay for the user to follow. */
    data class Walkthrough(val steps: String) : ScreenAction()

    // ----- terminal actions: end the task, no screen manipulation -----

    sealed class Terminal : ScreenAction() {
        /** Human-facing message to show/say as the task closes. */
        abstract val message: String
    }

    data class Finished(override val message: String) : Terminal()
    data class FailedToCompleteTask(override val message: String) : Terminal()
    data class MessageUser(override val message: String) : Terminal()

    /** Ask the user to confirm before a sensitive step (delete, pay, send). */
    data class NeedsConfirmation(override val message: String) : Terminal()

    /** A login wall blocks the task; ask the user to sign in themselves. */
    data class NeedLogin(override val message: String) : Terminal()

    /** Credentials are required and the user must supply them. */
    data class NeedLoginDetails(override val message: String) : Terminal()

    data class EncounteredCaptcha(override val message: String) : Terminal()
}

sealed class ActionParseResult {
    data class Ok(val action: ScreenAction) : ActionParseResult()

    /** The reply was plain prose with no action call in it. */
    object NotAnAction : ActionParseResult()

    /** Looked like an action but the name or arguments were unusable. */
    data class Invalid(val reason: String) : ActionParseResult()
}

object ActionParser {

    private val ACTION_PATTERN = Regex("""^(\w+)\(([\s\S]*)\)$""")

    /**
     * Parse exactly one action. Prose or extra calls are rejected, never silently skipped.
     */
    fun parse(reply: String): ActionParseResult {
        val trimmed = reply.trim()
        if (trimmed.isEmpty()) return ActionParseResult.NotAnAction

        ACTION_PATTERN.matchEntire(trimmed)?.let { return interpret(it) }

        return ActionParseResult.NotAnAction
    }

    private fun interpret(m: MatchResult): ActionParseResult {
        val name = m.groupValues[1].lowercase()
        val rawArgs = m.groupValues[2].trim()
        val args = if (rawArgs.isEmpty()) {
            JSONObject()
        } else {
            try {
                JSONObject(rawArgs)
            } catch (e: Exception) {
                return ActionParseResult.Invalid("bad JSON args for $name: ${e.message}")
            }
        }
        return try {
            when (name) {
                "click" -> ActionParseResult.Ok(ScreenAction.Click(reqInt(args, "x"), reqInt(args, "y")))
                "double_click" -> ActionParseResult.Ok(ScreenAction.DoubleClick(reqInt(args, "x"), reqInt(args, "y")))
                "press_and_hold" -> ActionParseResult.Ok(
                    ScreenAction.PressAndHold(reqInt(args, "x"), reqInt(args, "y"), optLong(args, "duration_ms"))
                )
                "drag" -> ActionParseResult.Ok(
                    ScreenAction.Drag(
                        reqInt(args, "start_x"), reqInt(args, "start_y"),
                        reqInt(args, "end_x"), reqInt(args, "end_y"),
                        optLong(args, "duration_ms")
                    )
                )
                "drag_and_drop" -> ActionParseResult.Ok(
                    ScreenAction.DragAndDrop(
                        reqInt(args, "start_x"), reqInt(args, "start_y"),
                        reqInt(args, "end_x"), reqInt(args, "end_y")
                    )
                )
                "type" -> ActionParseResult.Ok(ScreenAction.Type(reqString(args, "text")))
                "wait" -> ActionParseResult.Ok(ScreenAction.Wait((reqDouble(args, "seconds") * 1000).toLong().also { require(it in 0..60000) { "seconds outside 0..60" } }))
                "press_back" -> ActionParseResult.Ok(ScreenAction.PressBack)
                "press_home" -> ActionParseResult.Ok(ScreenAction.PressHome)
                "start_app" -> ActionParseResult.Ok(ScreenAction.StartApp(reqString(args, "package_name")))
                "walkthrough" -> ActionParseResult.Ok(ScreenAction.Walkthrough(reqString(args, "content")))
                "finished" -> ActionParseResult.Ok(ScreenAction.Finished(optMessage(args)))
                "failed_to_complete_task" -> ActionParseResult.Ok(ScreenAction.FailedToCompleteTask(optMessage(args)))
                "message_user" -> ActionParseResult.Ok(ScreenAction.MessageUser(optMessage(args)))
                "needs_confirmation" -> ActionParseResult.Ok(ScreenAction.NeedsConfirmation(reqString(args, "question")))
                "need_login" -> ActionParseResult.Ok(ScreenAction.NeedLogin(reqString(args, "content")))
                "need_login_details" -> ActionParseResult.Ok(ScreenAction.NeedLoginDetails(reqString(args, "content")))
                "encountered_captcha" -> ActionParseResult.Ok(ScreenAction.EncounteredCaptcha(optMessage(args)))
                else -> ActionParseResult.Invalid("unknown action: $name")
            }
        } catch (e: IllegalArgumentException) {
            ActionParseResult.Invalid("$name: ${e.message}")
        }
    }

    private fun optMessage(args: JSONObject): String =
        if (args.has("message")) args.optString("message") else args.optString("reason", "")

    private fun reqDouble(args: JSONObject, key: String): Double {
        require(args.has(key) && !args.isNull(key)) { "missing '$key'" }
        val value = args.get(key)
        require(value is Number || value is String) { "'$key' must be numeric" }
        return value.toString().toDoubleOrNull()?.also {
            require(it.isFinite()) { "'$key' must be finite" }
        } ?: throw IllegalArgumentException("'$key' must be numeric")
    }

    private fun reqInt(args: JSONObject, key: String): Int {
        val value = reqDouble(args, key)
        require(value >= 0 && value <= Int.MAX_VALUE) { "'$key' outside coordinate range" }
        return value.toInt()
    }

    private fun optLong(args: JSONObject, key: String): Long? {
        if (!args.has(key)) return null
        val value = reqDouble(args, key)
        require(value in 1.0..60000.0) { "'$key' outside duration range" }
        return value.toLong()
    }

    private fun reqString(args: JSONObject, key: String): String {
        if (!args.has(key)) throw IllegalArgumentException("missing '$key'")
        val v = args.get(key)
        if (v !is String || v.isEmpty()) throw IllegalArgumentException("'$key' must be a non-empty string")
        return v
    }
}
