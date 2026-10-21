package com.zhypix.fresh.agent

/** One instruction the model can emit, in canonical form name({...}). */
sealed class AgentAction {
    abstract val key: String

    data class Click(val x: Int, val y: Int) : AgentAction() { override val key = "click" }
    data class DoubleClick(val x: Int, val y: Int) : AgentAction() { override val key = "double_click" }
    data class PressAndHold(val x: Int, val y: Int) : AgentAction() { override val key = "press_and_hold" }
    data class Drag(val startX: Int, val startY: Int, val endX: Int, val endY: Int) : AgentAction() { override val key = "drag" }
    data class DragAndDrop(val startX: Int, val startY: Int, val endX: Int, val endY: Int) : AgentAction() { override val key = "drag_and_drop" }
    data class TypeText(val text: String) : AgentAction() { override val key = "type" }
    data class Wait(val seconds: Double) : AgentAction() { override val key = "wait" }
    object PressBack : AgentAction() { override val key = "press_back" }
    object PressHome : AgentAction() { override val key = "press_home" }
    data class StartApp(val packageName: String) : AgentAction() { override val key = "start_app" }

    /** Ends the loop: the task finished. */
    data class Finished(val message: String) : AgentAction() { override val key = "finished" }
    /** Ends the loop: the task cannot be completed. */
    data class Failed(val reason: String) : AgentAction() { override val key = "failed_to_complete_task" }
    /** Pauses the loop until the user replies. */
    data class MessageUser(val message: String) : AgentAction() { override val key = "message_user" }
    /** Pauses the loop until the user confirms or declines. */
    data class NeedsConfirmation(val question: String) : AgentAction() { override val key = "needs_confirmation" }
    /** Pauses the loop: the user must log in to something first. */
    data class NeedLogin(val content: String) : AgentAction() { override val key = "need_login" }
    /** Pauses the loop: the user must solve a captcha. */
    object EncounteredCaptcha : AgentAction() { override val key = "encountered_captcha" }

    val isTerminal: Boolean
        get() = this is Finished || this is Failed
    val pausesForUser: Boolean
        get() = this is MessageUser || this is NeedsConfirmation || this is NeedLogin || this is EncounteredCaptcha
    val userFacingText: String?
        get() = when (this) {
            is Finished -> message
            is Failed -> reason
            is MessageUser -> message
            is NeedsConfirmation -> question
            is NeedLogin -> content
            is EncounteredCaptcha -> "I hit a captcha. Please solve it, then tell me to continue."
            else -> null
        }
}
