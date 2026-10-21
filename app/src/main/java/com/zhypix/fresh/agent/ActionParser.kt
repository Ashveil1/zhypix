package com.zhypix.fresh.agent

/**
 * Parses the model's reply into exactly one action.
 *
 * The contract given to the model is the canonical form `name({"x":1})`, but
 * models also emit bare JSON objects or fenced code blocks. A reply may carry
 * prose around the action; it must contain EXACTLY ONE action - zero or several
 * are errors, and the error text is fed back to the model so it can correct.
 */
object ActionParser {

    sealed class Result {
        data class One(val action: AgentAction, val prose: String) : Result()
        object None : Result()
        data class Several(val count: Int) : Result()
        data class Invalid(val reason: String) : Result()
    }

    private data class Candidate(val start: Int, val end: Int, val name: String, val args: Map<String, Any?>)

    private val KNOWN = setOf(
        "click", "double_click", "press_and_hold", "drag", "drag_and_drop",
        "type", "wait", "press_back", "press_home", "start_app",
        "finished", "failed_to_complete_task", "message_user",
        "needs_confirmation", "need_login", "encountered_captcha"
    )

    fun parse(reply: String, json: (String) -> Map<String, Any?>?): Result {
        val candidates = mutableListOf<Candidate>()

        // Form 1: canonical name({...}) possibly with prose around it.
        var i = 0
        while (i < reply.length) {
            val c = reply[i]
            if (c.isLetter()) {
                var j = i + 1
                while (j < reply.length && (reply[j].isLetterOrDigit() || reply[j] == '_')) j++
                val word = reply.substring(i, j)
                if (word.lowercase() in KNOWN && j < reply.length && reply[j] == '(') {
                    val close = matchCallParen(reply, j) ?: return Result.Invalid("unbalanced parentheses after $word(")
                    val inner = reply.substring(j + 1, close - 1).trim()
                    val args = if (inner.isEmpty()) emptyMap() else json(inner)
                        ?: return Result.Invalid("arguments of $word(...) are not a JSON object")
                    candidates.add(Candidate(i, close, word.lowercase(), args))
                    i = close
                } else {
                    i = j
                }
            } else i++
        }

        // Form 2: a bare JSON object with an "action"/"name" key (anywhere, often fenced).
        if (candidates.isEmpty() && (reply.contains("\"action\"") || reply.contains("\"name\""))) {
            var k = 0
            while (k < reply.length) {
                if (reply[k] == '{') {
                    val end = matchBrace(reply, k)
                    if (end != null) {
                        val obj = json(reply.substring(k, end))
                        if (obj != null) {
                            val name = (obj["action"] ?: obj["name"]) as? String
                            if (name != null && name.lowercase() in KNOWN) {
                                @Suppress("UNCHECKED_CAST")
                                val args = (obj["args"] ?: obj["arguments"] ?: obj["parameters"] ?: obj) as? Map<String, Any?> ?: emptyMap()
                                candidates.add(Candidate(k, end, name.lowercase(), args))
                            }
                        }
                        k = end
                    } else k++
                } else k++
            }
        }

        return when {
            candidates.isEmpty() -> Result.None
            candidates.size > 1 -> Result.Several(candidates.size)
            else -> {
                val c = candidates[0]
                when (val a = toAction(c.name, c.args)) {
                    null -> Result.Invalid("unknown or malformed action ${c.name}")
                    else -> Result.One(a, (reply.substring(0, c.start) + reply.substring(c.end)).trim())
                }
            }
        }
    }

    /** Index just past the ')' matching the '(' at [open], honoring strings/braces. */
    private fun matchCallParen(s: String, open: Int): Int? {
        var depthParen = 0
        var depthBrace = 0
        var depthBracket = 0
        var inString = false
        var quote = ' '
        var idx = open
        while (idx < s.length) {
            val c = s[idx]
            if (inString) {
                if (c == '\\') idx++
                else if (c == quote) inString = false
            } else when (c) {
                '"', '\'' -> { inString = true; quote = c }
                '(' -> depthParen++
                ')' -> {
                    depthParen--
                    if (depthParen == 0 && depthBrace == 0) return idx + 1
                    if (depthParen < 0) return null
                }
                '{' -> depthBrace++
                '}' -> depthBrace--
                '[' -> depthBracket++
                ']' -> depthBracket--
            }
            idx++
        }
        return null
    }

    /** Index just past the '}' matching the '{' at [open], honoring strings. */
    private fun matchBrace(s: String, open: Int): Int? {
        var depth = 0
        var inString = false
        var idx = open
        while (idx < s.length) {
            val c = s[idx]
            if (inString) {
                if (c == '\\') idx++
                else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return idx + 1
                }
            }
            idx++
        }
        return null
    }

    private fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    private fun str(v: Any?): String? = (v as? String)?.takeIf { it.isNotBlank() }

    private fun toAction(name: String, a: Map<String, Any?>): AgentAction? {
        fun intOf(vararg keys: String): Int? = keys.firstNotNullOfOrNull { num(a[it]) }?.toInt()
        return when (name) {
            "click" -> { val x = intOf("x"); val y = intOf("y"); if (x != null && y != null) AgentAction.Click(x, y) else null }
            "double_click" -> { val x = intOf("x"); val y = intOf("y"); if (x != null && y != null) AgentAction.DoubleClick(x, y) else null }
            "press_and_hold" -> { val x = intOf("x"); val y = intOf("y"); if (x != null && y != null) AgentAction.PressAndHold(x, y) else null }
            "drag" -> {
                val x1 = intOf("start_x", "x1"); val y1 = intOf("start_y", "y1")
                val x2 = intOf("end_x", "x2"); val y2 = intOf("end_y", "y2")
                if (x1 != null && y1 != null && x2 != null && y2 != null) AgentAction.Drag(x1, y1, x2, y2) else null
            }
            "drag_and_drop" -> {
                val x1 = intOf("start_x", "x1"); val y1 = intOf("start_y", "y1")
                val x2 = intOf("end_x", "x2"); val y2 = intOf("end_y", "y2")
                if (x1 != null && y1 != null && x2 != null && y2 != null) AgentAction.DragAndDrop(x1, y1, x2, y2) else null
            }
            "type" -> str(a["text"])?.let { AgentAction.TypeText(it) }
            "wait" -> num(a["seconds"])?.let { AgentAction.Wait(it.coerceIn(0.1, 30.0)) }
            "press_back" -> AgentAction.PressBack
            "press_home" -> AgentAction.PressHome
            "start_app" -> str(a["package_name"])?.let { AgentAction.StartApp(it) }
                ?: str(a["package"])?.let { AgentAction.StartApp(it) }
            "finished" -> AgentAction.Finished(str(a["message"]) ?: "Done.")
            "failed_to_complete_task" -> AgentAction.Failed(str(a["reason"]) ?: "The model gave up.")
            "message_user" -> str(a["message"])?.let { AgentAction.MessageUser(it) }
            "needs_confirmation" -> str(a["question"])?.let { AgentAction.NeedsConfirmation(it) }
            "need_login" -> AgentAction.NeedLogin(str(a["content"]) ?: "Please log in, then tell me to continue.")
            "encountered_captcha" -> AgentAction.EncounteredCaptcha
            else -> null
        }
    }
}
