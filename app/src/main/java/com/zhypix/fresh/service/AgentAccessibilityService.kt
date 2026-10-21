package com.zhypix.fresh.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Eyes and hands of the agent. Perception: screenshot + tree summary + phone
 * state. Actuation: gestures with fixed, predictable timing and TalkBack
 * adjustments. Written fresh; timings follow the design contract.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
            private set

        private const val TAP_MS = 125L
        private const val DOUBLE_TAP_GAP_MS = 175L
        private const val SWIPE_MS = 860L
        private const val TALKBACK_TAP_MS = 75L

        /** Rects (raw screen px) the agent must never touch - e.g. our own overlay. */
        @Volatile private var exclusionRects: List<Rect> = emptyList()
        fun setExclusionRects(rects: List<Rect>) { exclusionRects = rects }
        fun isExcluded(x: Float, y: Float): Boolean = exclusionRects.any { it.contains(x.toInt(), y.toInt()) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- perception ----------

    suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(shot: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                    shot.hardwareBuffer.close()
                    cont.resume(hw?.copy(Bitmap.Config.ARGB_8888, false))
                } catch (e: Exception) {
                    cont.resume(null)
                }
            }
            override fun onFailure(errorCode: Int) { cont.resume(null) }
        })
    }

    fun screenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /** Compact text summary of the interactive screen, capped for prompt size. */
    fun treeSummary(maxNodes: Int = 200, maxChars: Int = 4000): String {
        val root = rootInActiveWindow ?: return "(no active window)"
        val sb = StringBuilder()
        var count = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || count >= maxNodes || sb.length >= maxChars || depth > 18) return
            val text = node.text?.toString()?.take(60)
            val desc = node.contentDescription?.toString()?.take(60)
            val interesting = node.isClickable || node.isEditable || node.isScrollable ||
                !text.isNullOrBlank() || !desc.isNullOrBlank()
            if (interesting) {
                val b = Rect()
                node.getBoundsInScreen(b)
                val flags = buildString {
                    if (node.isClickable) append(" click")
                    if (node.isEditable) append(" edit")
                    if (node.isScrollable) append(" scroll")
                    if (node.isFocused) append(" focus")
                }
                val label = listOfNotNull(text, desc).joinToString(" / ")
                sb.append("  ".repeat(minOf(depth, 8)))
                    .append(node.className?.toString()?.substringAfterLast('.') ?: "?")
                    .append(" [").append(b.centerX()).append(',').append(b.centerY()).append(']')
                    .append(flags)
                if (label.isNotBlank()) sb.append(" \"").append(label).append('"')
                sb.append('\n')
                count++
            }
            for (i in 0 until node.childCount) {
                walk(try { node.getChild(i) } catch (e: Exception) { null }, depth + 1)
            }
        }
        walk(root, 0)
        return sb.toString().ifBlank { "(empty tree)" }
    }

    fun phoneState(): String {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        val label = pkg?.let { appLabel(it) } ?: "Unknown"
        val imm = getSystemService(InputMethodManager::class.java)
        val keyboard = imm?.isAcceptingText == true
        val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val focusedDesc = focused?.let {
            listOfNotNull(it.text?.toString(), it.contentDescription?.toString(), it.className?.toString()?.substringAfterLast('.'))
                .joinToString(" - ").take(120)
        } ?: "none"
        return "Current app: $label (${pkg ?: "unknown"}) Keyboard visible: $keyboard\nFocused element: $focusedDesc"
    }

    fun installedPackages(): List<String> {
        val pm = packageManager
        @Suppress("DEPRECATION")
        return pm.getInstalledApplications(0)
            .mapNotNull { app ->
                if (pm.getLaunchIntentForPackage(app.packageName) != null) app.packageName else null
            }
            .sorted()
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    // ---------- actuation ----------

    private suspend fun dispatch(path: Path, startMs: Long, durationMs: Long, continueFrom: StrokeContinuation? = null): Boolean =
        suspendCancellableCoroutine { cont ->
            val stroke = if (continueFrom == null) {
                GestureDescription.StrokeDescription(path, startMs, durationMs)
            } else {
                continueFrom.stroke.continueStroke(path, startMs, durationMs, false)
            }
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { cont.resume(false) }
            }, null)
            if (!ok) cont.resume(false)
        }

    private data class StrokeContinuation(val stroke: GestureDescription.StrokeDescription)

    private suspend fun dispatchStrokes(strokes: List<Triple<Path, Long, Long>>): Boolean {
        // Sequential one-stroke gestures: simple and reliable.
        for ((path, _, dur) in strokes) {
            val p = Path(path)
            val ok = dispatch(p, 0L, dur)
            if (!ok) return false
        }
        return true
    }

    private fun touchExplorationActive(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java) ?: return false
        return am.isEnabled && am.isTouchExplorationEnabled
    }

    suspend fun click(x: Float, y: Float): Boolean {
        if (isExcluded(x, y)) return false
        return if (touchExplorationActive()) {
            // Screen reader: first tap only focuses; focus-then-double-tap activates.
            tapOnce(x, y, TALKBACK_TAP_MS)
            SystemClock.sleep(400)
            tapOnce(x, y, TALKBACK_TAP_MS)
            SystemClock.sleep(200)
            tapOnce(x, y, TALKBACK_TAP_MS)
        } else {
            tapOnce(x, y, TAP_MS)
        }
    }

    private suspend fun tapOnce(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 0L, durationMs)
    }

    suspend fun doubleClick(x: Float, y: Float): Pair<Boolean, String?> {
        if (isExcluded(x, y)) return false to null
        if (touchExplorationActive()) {
            return false to "Double click is unavailable while a screen reader is active; use click instead."
        }
        tapOnce(x, y, TAP_MS)
        SystemClock.sleep(DOUBLE_TAP_GAP_MS)
        return tapOnce(x, y, TAP_MS) to null
    }

    suspend fun pressAndHold(x: Float, y: Float): Boolean {
        if (isExcluded(x, y)) return false
        val holdMs = ViewConfiguration.getLongPressTimeout() + 100L
        return tapOnce(x, y, holdMs)
    }

    suspend fun drag(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        if (isExcluded(x1, y1)) return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatch(path, 0L, SWIPE_MS)
    }

    suspend fun dragAndDrop(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        if (isExcluded(x1, y1)) return false
        val holdMs = ViewConfiguration.getLongPressTimeout() + 100L
        // Hold in place, then continue the same stroke to the drop point.
        return suspendCancellableCoroutine { cont ->
            val holdPath = Path().apply { moveTo(x1, y1) }
            val first = GestureDescription.StrokeDescription(holdPath, 0L, holdMs, true)
            val movePath = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val second = first.continueStroke(movePath, 0L, SWIPE_MS, false)
            val gesture = GestureDescription.Builder().addStroke(first).addStroke(second).build()
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { cont.resume(false) }
            }, null)
            if (!ok) cont.resume(false)
        }
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = try { target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) } catch (e: Exception) { false }
        if (ok) return true
        // Fallback: clipboard paste into the focused node.
        return try {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("zhypix", text))
            target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) { false }
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val found = findEditable(try { node.getChild(i) } catch (e: Exception) { null })
            if (found != null) return found
        }
        return null
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun startApp(packageName: String): Boolean {
        if (packageName == this.packageName) return false // never relaunch ourselves mid-task
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return try { startActivity(intent); true } catch (e: Exception) { false }
    }
}
