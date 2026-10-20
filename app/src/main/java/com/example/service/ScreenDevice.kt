package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import com.example.agent.ScreenAction
import com.example.agent.ScreenLoop
import com.example.agent.ScreenPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** New device adapter. Action coordinates are raw display pixels, never guessed or jittered. */
class ScreenDevice(private val service: ZhypixAccessibilityService) {
    suspend fun execute(action: ScreenAction): ScreenLoop.Execution = withContext(Dispatchers.Main.immediate) {
        when (action) {
            is ScreenAction.Click -> tap(action.x, action.y, false)
            is ScreenAction.DoubleClick -> tap(action.x, action.y, true)
            is ScreenAction.PressAndHold -> pointGesture(action.x, action.y,
                action.durationMs ?: ViewConfiguration.getLongPressTimeout().toLong() + ScreenPolicy.LONG_PRESS_EXTRA_MS)
            is ScreenAction.Drag -> swipe(action.startX, action.startY, action.endX, action.endY, action.durationMs ?: ScreenPolicy.SWIPE_MS, false)
            is ScreenAction.DragAndDrop -> swipe(action.fromX, action.fromY, action.toX, action.toY, ScreenPolicy.SWIPE_MS, true)
            is ScreenAction.Type -> type(action.text)
            is ScreenAction.Wait -> { delay(action.durationMs); ok("Waited ${action.durationMs} ms") }
            ScreenAction.PressBack -> global(AccessibilityService.GLOBAL_ACTION_BACK)
            ScreenAction.PressHome -> global(AccessibilityService.GLOBAL_ACTION_HOME)
            is ScreenAction.StartApp -> launch(action.packageName)
            is ScreenAction.Walkthrough -> ScreenLoop.Execution(false, "Guide requires user participation: ${action.steps}")
            is ScreenAction.Terminal -> ScreenLoop.Execution(false, "Terminal must be handled by the loop")
        }
    }

    private fun validPoint(x: Int, y: Int): Boolean {
        val dm = service.resources.displayMetrics
        return x in 0 until dm.widthPixels && y in 0 until dm.heightPixels
    }
    private fun blocked(x: Int, y: Int): Boolean =
        !validPoint(x, y) || FloatingAgentService.instance?.getOverlayBoundsOnScreen()?.contains(x, y) == true

    private fun path(x: Int, y: Int) = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
    private fun stroke(x: Int, y: Int, start: Long, duration: Long) =
        GestureDescription.StrokeDescription(path(x,y), start, duration)
    private fun ok(message: String) = ScreenLoop.Execution(true, message)
    private fun fail(message: String) = ScreenLoop.Execution(false, message)

    private suspend fun dispatch(builder: GestureDescription.Builder): ScreenLoop.Execution {
        val result = CompletableDeferred<ScreenLoop.Execution>()
        val accepted = service.dispatchGesture(builder.build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { result.complete(ok("Gesture completed")) }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                result.complete(fail("Gesture cancelled. It may have partially executed; inspect before another action."))
            }
        }, null)
        if (!accepted) return ScreenLoop.Execution(false, "Gesture was not dispatched", safeToRetry=true)
        return withTimeoutOrNull(ScreenPolicy.GESTURE_TIMEOUT_MS) { result.await() }
            ?: fail("Gesture timed out. Outcome unknown; do not repeat automatically.")
    }

    private suspend fun tap(x: Int, y: Int, twice: Boolean): ScreenLoop.Execution {
        if (blocked(x,y)) return fail("Point is outside the screen or within our overlay. Move the bubble first.")
        val am = service.getSystemService(AccessibilityService.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val talkback = am.isTouchExplorationEnabled
        if (twice && talkback) return fail("Double click is unavailable while a screen reader is active.")
        val builder = GestureDescription.Builder()
        if (talkback) ScreenPolicy.talkBackStartsMs.forEach { builder.addStroke(stroke(x,y,it,ScreenPolicy.TALKBACK_TAP_MS)) }
        else {
            builder.addStroke(stroke(x,y,0,ScreenPolicy.TAP_MS))
            if (twice) builder.addStroke(stroke(x,y,ScreenPolicy.TAP_MS+ScreenPolicy.DOUBLE_GAP_MS,ScreenPolicy.TAP_MS))
        }
        return dispatch(builder)
    }
    private suspend fun pointGesture(x: Int, y: Int, duration: Long): ScreenLoop.Execution {
        if (blocked(x,y)) return fail("Point is outside the screen or within our overlay")
        return dispatch(GestureDescription.Builder().addStroke(stroke(x,y,0,duration)))
    }
    private suspend fun swipe(x1: Int,y1: Int,x2: Int,y2: Int,duration: Long,hold: Boolean): ScreenLoop.Execution {
        if (blocked(x1,y1) || blocked(x2,y2)) return fail("Drag endpoint is outside the screen or within our overlay")
        val movement=path(x1,y1).apply { lineTo(x2.toFloat(),y2.toFloat()) }
        if (!hold) return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(movement,0,duration)))
        // Continued strokes belong to separate dispatches. Adding both to one gesture overlaps fingers.
        val press=GestureDescription.StrokeDescription(path(x1,y1),0,
            ViewConfiguration.getLongPressTimeout().toLong()+ScreenPolicy.LONG_PRESS_EXTRA_MS,true)
        val started=dispatch(GestureDescription.Builder().addStroke(press))
        if (!started.success) return started
        val continuation=press.createContinueStroke(movement,0,duration,false)
        val moved=dispatch(GestureDescription.Builder().addStroke(continuation))
        // Once the hold was sent, never auto-repeat the whole drag even if continuation was rejected.
        return moved.copy(safeToRetry=false)
    }
    private fun type(text: String): ScreenLoop.Execution {
        val focused=service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return fail("No input field has focus. Tap the intended field first.")
        try {
            if (!focused.isEditable || focused.isPassword) return fail("Input must be editable and must not be a password field. Ask the user to sign in.")
            val args=Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text) }
            return if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)) ok("Text set in focused field")
                else fail("Focused field rejected text. No clipboard fallback was attempted.")
        } finally { focused.recycle() }
    }
    private fun global(code: Int) = if (service.performGlobalAction(code)) ok("Navigation dispatched") else fail("Navigation rejected")
    private fun launch(pkg: String): ScreenLoop.Execution {
        if (pkg==service.packageName) return fail("Cannot launch our own package as a screen-control target")
        val intent=service.packageManager.getLaunchIntentForPackage(pkg) ?: return fail("No launch activity for $pkg")
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            service.startActivity(intent)
            ok("Launched $pkg")
        } catch (error:Exception) { fail("Failed to launch $pkg: ${error.message}") }
    }
}
