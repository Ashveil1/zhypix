package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReadyGate {
    TREE_ONLY,       // Evaluates purely on accessibility hierarchy tree
    FRAME_REQUIRED,  // Needs screenshot/frame verification
    BOTH             // Changes screen state (needs tree + frame settle)
}

class ZhypixAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ZhypixAccessibilityService? = null
            private set
    }

    private var lastScreenshotBase64: String? = null
    private var lastScreenshotTime: Long = 0L
    private var lastScreenshotAttemptTime: Long = 0L
    private var lastDHash: Long = 0L
    private val screenshotDebounceMs = 500L // Reduced debounce to allow faster successive screenshots

    internal var visualizerView: GestureVisualizerView? = null
    private var windowManager: WindowManager? = null

    fun invalidateScreenshotCache() {
        lastScreenshotBase64 = null
        lastScreenshotTime = 0L
        lastDHash = 0L
        Log.d("Zhypix", "Screenshot cache invalidated due to user action")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("Zhypix", "Accessibility Service Connected")

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            visualizerView = GestureVisualizerView(this)
            windowManager?.addView(visualizerView, params)
            Log.d("Zhypix", "Accessibility gesture visualizer overlay added successfully")
        } catch (e: Exception) {
            Log.e("Zhypix", "Failed to add accessibility overlay", e)
        }

        // Start background ad-skipper ticker
        GlobalScope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    AutoAdSkipper.checkAndSkipAd(this@ZhypixAccessibilityService)
                } catch (e: Exception) {
                    // Ignore transient errors
                }
                delay(1200L)
            }
        }
    }

    private var lastEventTime: Long = 0L
    private var wasInLoadingState: Boolean = false

    fun isLoadingState(hierarchy: String): Boolean {
        if (hierarchy.isEmpty()) return true
        val lower = hierarchy.lowercase()
        val loadingKeywords = listOf(
            "progressbar", "progressindicator", "loading", "buffering",
            "กำลังโหลด", "รอสักครู่", "shimmer", "skeleton", "spinner",
            "กำลังเปิด", "กำลังเชื่อมต่อ", "connecting"
        )
        if (loadingKeywords.any { lower.contains(it) }) {
            return true
        }
        val textLines = hierarchy.lines().filter { it.contains("text=") || it.contains("desc=") }
        if (textLines.isEmpty() && hierarchy.length < 350) {
            return true
        }
        return false
    }

    private var lastKnownHierarchyHash: Int = 0

    private fun checkAndNotifyLoadingTransition() {
        val vm = com.example.viewmodel.AgentViewModel.instance
        val isWaiting = vm?.wasWaitingForScreenLoad == true
        if (!isWaiting) {
            wasInLoadingState = false
            lastKnownHierarchyHash = 0
            return
        }

        val currentH = getActiveWindowHierarchy()
        val currentlyLoading = isLoadingState(currentH)
        val currentHash = currentH.hashCode()

        if (wasInLoadingState && !currentlyLoading) {
            Log.i("Zhypix", "Screen transition detected: Loading state finished! Triggering auto-resume...")
            vm?.notifyScreenLoadingFinished(currentH)
        } else if (!currentlyLoading && lastKnownHierarchyHash != 0 && currentHash != lastKnownHierarchyHash) {
            Log.i("Zhypix", "Screen transition detected: UI layout changed while waiting! Triggering auto-resume...")
            vm?.notifyScreenLoadingFinished(currentH)
        }
        wasInLoadingState = currentlyLoading
        if (currentH.isNotBlank()) {
            lastKnownHierarchyHash = currentHash
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()?.lowercase() ?: ""
        // Filter noise events from IME / virtual keyboards so they don't reset lastEventTime
        if (pkg.contains("inputmethod") || pkg.contains("latin") || pkg.contains("gboard") || pkg.contains("keyboard") || pkg.contains("ime")) {
            return
        }

        val myPkg = packageName.lowercase()
        val isOurApp = (pkg == myPkg || pkg == "com.example" || pkg.startsWith("com.aistudio"))

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                lastEventTime = System.currentTimeMillis()
                invalidateScreenshotCache()

                // Trigger instant ad skip check on window/content update (only for external apps)
                if (!isOurApp) {
                    AutoAdSkipper.checkAndSkipAd(this)
                }

                // Trigger auto-resume monitor when screen transitions from loading to loaded (only if actively waiting)
                val vm = com.example.viewmodel.AgentViewModel.instance
                if (vm?.wasWaitingForScreenLoad == true) {
                    checkAndNotifyLoadingTransition()
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d("Zhypix", "Accessibility Service Interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        visualizerView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e("Zhypix", "Failed to remove visualizer overlay", e)
            }
        }
        visualizerView = null
        instance = null
        return super.onUnbind(intent)
    }

    suspend fun dispatchClick(x: Float, y: Float, bounds: Rect? = null): Boolean {
        invalidateScreenshotCache()

        // Human-like Gaussian jitter and fast responsive touch dwell time.
        // When element bounds are known the jitter is constrained inside the
        // element so small targets are never missed because of jitter.
        val (targetX, targetY) = BezierGestureSynthesizer.applyHumanClickJitter(x, y, bounds)

        val path = Path()
        path.moveTo(targetX, targetY)
        path.lineTo(targetX + 1.0f, targetY + 1.0f)

        val gestureBuilder = GestureDescription.Builder()
        val dwellTime = (20..35).random().toLong()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, dwellTime))

        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val ok = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d("Zhypix", "Fast gesture click completed at ($targetX, $targetY)")
                        visualizerView?.showClickFeedback(targetX, targetY)
                        invalidateScreenshotCache()
                        deferred.complete(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w("Zhypix", "Gesture cancelled on Android at ($targetX, $targetY)")
                        invalidateScreenshotCache()
                        deferred.complete(false)
                    }
                }, null)

                if (!ok) {
                    Log.e("Zhypix", "dispatchGesture returned false for click")
                    invalidateScreenshotCache()
                    deferred.complete(false)
                }
            } catch (e: Exception) {
                Log.e("Zhypix", "Exception during gesture dispatch", e)
                invalidateScreenshotCache()
                deferred.complete(false)
            }
        }

        val res = kotlinx.coroutines.withTimeoutOrNull(1500L) { deferred.await() } ?: false
        if (res) {
            invalidateScreenshotCache()
        }
        return res
    }

    suspend fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 180): Boolean {
        invalidateScreenshotCache()

        val distance = Math.hypot((endX - startX).toDouble(), (endY - startY).toDouble()).toFloat()
        val actualDuration = if (durationMs == 180L || durationMs == 300L) {
            BezierGestureSynthesizer.calculateHumanSwipeDuration(distance).coerceAtMost(220L)
        } else {
            durationMs
        }

        val path = BezierGestureSynthesizer.createHumanSwipePath(startX, startY, endX, endY)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, actualDuration))

        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val ok = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d("Zhypix", "Swipe completed (duration=${actualDuration}ms)")
                        visualizerView?.showSwipeFeedback(startX, startY, endX, endY, actualDuration)
                        deferred.complete(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w("Zhypix", "Swipe cancelled")
                        deferred.complete(false)
                    }
                }, null)

                if (!ok) {
                    Log.e("Zhypix", "dispatchGesture returned false for swipe")
                    deferred.complete(false)
                }
            } catch (e: Exception) {
                Log.e("Zhypix", "Exception during swipe dispatch", e)
                deferred.complete(false)
            }
        }

        return kotlinx.coroutines.withTimeoutOrNull(2000L) { deferred.await() } ?: false
    }

    fun getActiveWindowHierarchy(): String {
        val builder = StringBuilder()
        
        // Capture all interactive windows to avoid being "blinded" by our overlay window
        val windowList = try {
            windows
        } catch (e: Exception) {
            Log.e("Zhypix", "Failed to retrieve windows list", e)
            null
        }

        var traversedInteractive = false

        if (!windowList.isNullOrEmpty()) {
            for (window in windowList) {
                val rootNode = try {
                    window.root
                } catch (e: Exception) {
                    null
                }
                if (rootNode != null) {
                    val packageName = rootNode.packageName?.toString() ?: ""
                    if (packageName == "com.example" || packageName.startsWith("com.aistudio.") || packageName.contains("com.example.service")) {
                        try {
                            rootNode.recycle()
                        } catch (e: Exception) {}
                        continue
                    }
                    
                    val windowTypeStr = when (window.type) {
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "Application"
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "Input Method (Keyboard)"
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "System UI"
                        else -> "System Layer"
                    }
                    
                    builder.append("--- Window: $windowTypeStr Window (Package: $packageName) ---\n")
                    
                    traverseNode(rootNode, builder, 1)
                    try {
                        rootNode.recycle()
                    } catch (e: Exception) {}
                    traversedInteractive = true
                }
            }
        }

        // Fallback to active root node if no windows could be traversed
        if (!traversedInteractive) {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                builder.append("=== Active Window Root Fallback (Package: ${activeRoot.packageName}) ===\n")
                traverseNode(activeRoot, builder, 0)
                try {
                    activeRoot.recycle()
                } catch (e: Exception) {}
            } else {
                builder.append("No active viewport or system windows retrieved. Verify Accessibility Service is active.")
            }
        }

        return builder.toString()
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, builder: StringBuilder, depth: Int) {
        if (node == null) return
        
        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
        
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        
        val isClickable = node.isClickable
        val isScrollable = node.isScrollable
        val isFocused = node.isFocused

        if (text.isNotEmpty() || contentDesc.isNotEmpty() || isClickable || isScrollable || isFocused) {
            if (bounds.width() > 0 && bounds.height() > 0) {
                val centerX = bounds.centerX()
                val centerY = bounds.centerY()
                builder.append(indent)
                    .append("[$className] ")
                
                if (text.isNotEmpty()) builder.append("Text=\"$text\" ")
                if (contentDesc.isNotEmpty()) builder.append("Desc=\"$contentDesc\" ")
                builder.append("Bounds=(${bounds.left},${bounds.top})->(${bounds.right},${bounds.bottom}) ")
                builder.append("Center=($centerX,$centerY) ")
                if (isClickable) builder.append("Clickable ")
                if (isScrollable) builder.append("Scrollable ")
                if (isFocused) builder.append("Focused ")
                
                builder.append("\n")
            }
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                traverseNode(child, builder, depth + 1)
                try {
                    child.recycle()
                } catch (e: Exception) {}
            }
        }
    }

    fun inputText(text: String): Boolean {
        invalidateScreenshotCache()

        val candidateRoots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val windowList = windows
            if (!windowList.isNullOrEmpty()) {
                for (w in windowList) {
                    val root = try { w.root } catch (e: Exception) { null }
                    if (root != null) candidateRoots.add(root)
                }
            }
        } catch (e: Exception) {
            Log.w("Zhypix", "Error getting windows list", e)
        }

        val activeRoot = try { rootInActiveWindow } catch (e: Exception) { null }
        if (activeRoot != null && candidateRoots.none { it == activeRoot }) {
            candidateRoots.add(0, activeRoot)
        }

        if (candidateRoots.isEmpty()) {
            Log.e("Zhypix", "No active root nodes available for typing text")
            return false
        }

        var success = false
        var inputNode: AccessibilityNodeInfo? = null

        try {
            // First priority: Check focused input
            for (root in candidateRoots) {
                inputNode = try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (e: Exception) { null }
                if (inputNode != null) break
            }

            // Second priority: Look for focused node with editable capability
            if (inputNode == null) {
                for (root in candidateRoots) {
                    inputNode = findFocusedOrEditableNode(root)
                    if (inputNode != null) break
                }
            }

            // Third priority: Find any editable node in active hierarchy
            if (inputNode == null) {
                for (root in candidateRoots) {
                    inputNode = findEditableNode(root)
                    if (inputNode != null) break
                }
            }

            if (inputNode != null) {
                if (!inputNode.isFocused) {
                    try { inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } catch (e: Exception) {}
                }

                // Strategy A: ACTION_SET_TEXT
                val arguments = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                success = try {
                    inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                } catch (e: Exception) {
                    false
                }

                // Strategy B: Fallback to Clipboard Paste if ACTION_SET_TEXT failed or not supported
                if (!success) {
                    try {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        if (clipboard != null) {
                            val clip = android.content.ClipData.newPlainText("zhypix_input", text)
                            clipboard.setPrimaryClip(clip)
                            success = inputNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        }
                    } catch (e: Exception) {
                        Log.w("Zhypix", "Clipboard paste fallback failed", e)
                    }
                }
            } else {
                // Strategy C: If no specific input node found, copy to clipboard anyway and try pasting into focused window
                try {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    if (clipboard != null) {
                        val clip = android.content.ClipData.newPlainText("zhypix_input", text)
                        clipboard.setPrimaryClip(clip)
                        for (root in candidateRoots) {
                            val pasteSuccess = try { root.performAction(AccessibilityNodeInfo.ACTION_PASTE) } catch (e: Exception) { false }
                            if (pasteSuccess) {
                                success = true
                                break
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e("Zhypix", "Failed to input text", e)
        } finally {
            try { inputNode?.recycle() } catch (e: Exception) {}
            candidateRoots.forEach { try { it.recycle() } catch (e: Exception) {} }
        }
        return success
    }

    private fun findFocusedOrEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val cls = node.className?.toString() ?: ""
        if (node.isFocused && (node.isEditable || cls.contains("EditText", ignoreCase = true) || cls.contains("TextField", ignoreCase = true))) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            val match = findFocusedOrEditableNode(child)
            if (match != null) return match
        }
        return null
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val cls = node.className?.toString() ?: ""
        if (node.isEditable || cls.contains("EditText", ignoreCase = true) || cls.contains("AutoComplete", ignoreCase = true) || cls.contains("SearchAutoComplete", ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            val match = findEditableNode(child)
            if (match != null) return match
        }
        return null
    }

    suspend fun clickSmart(target: String): ActionResult {
        invalidateScreenshotCache()
        val selector = ElementSelector.parse(target)
        val resolved = ElementResolver.resolve(this, selector)

        if (resolved != null) {
            val node = resolved.node
            val cx = resolved.centerX
            val cy = resolved.centerY

            var clicked = false
            try {
                if (node.isClickable) {
                    clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            } catch (e: Exception) {
                clicked = false
            }

            if (!clicked) {
                var parent = try { node.parent } catch (e: Exception) { null }
                while (parent != null && !clicked) {
                    if (parent.isClickable) {
                        try {
                            clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        } catch (e: Exception) {}
                    }
                    if (!clicked) {
                        parent = try { parent.parent } catch (e: Exception) { null }
                    }
                }
            }

            if (!clicked) {
                clicked = dispatchClick(cx, cy, resolved.bounds)
            } else {
                visualizerView?.showClickFeedback(cx, cy)
            }

            try { node.recycle() } catch (e: Exception) {}

            val uniquenessInfo = if (resolved.isUnique) "unique match" else "selected best of ${resolved.totalMatchesCount} matches"
            if (clicked) {
                return ActionResult(true, "Successfully clicked element layer=${resolved.matchType} ($uniquenessInfo) at ($cx, $cy)")
            } else {
                return ActionResult(false, "Found element layer=${resolved.matchType} at ($cx, $cy) but click action failed")
            }
        }

        // Fallback for raw coordinate tap if no node was hit
        if (selector.x != null && selector.y != null) {
            val fx = selector.x.toFloat()
            val fy = selector.y.toFloat()
            val ok = dispatchClick(fx, fy)
            return if (ok) {
                ActionResult(true, "Dispatched raw click gesture at ($fx, $fy)")
            } else {
                ActionResult(false, "Failed to dispatch click gesture at ($fx, $fy)")
            }
        }

        return ActionResult(false, "Could not find element matching '$target' on active screen")
    }

    /**
     * Gets a compact, token-efficient UI tree representation for LLM context.
     */
    fun getCompactTree(): String {
        return ElementResolver.buildCompactTree(this)
    }

    private fun findNodeAt(x: Int, y: Int): AccessibilityNodeInfo? {
        val windowList = try { windows } catch (e: Exception) { null }
        if (!windowList.isNullOrEmpty()) {
            for (w in windowList) {
                val root = try { w.root } catch (e: Exception) { null }
                if (root != null) {
                    val target = searchNodeAt(root, x, y)
                    if (target != null) return target
                    try { root.recycle() } catch (e: Exception) {}
                }
            }
        }
        val activeRoot = rootInActiveWindow ?: return null
        return searchNodeAt(activeRoot, x, y)
    }

    private fun searchNodeAt(node: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        if (node == null) return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            val match = searchNodeAt(child, x, y)
            if (match != null) return match
        }

        if (node.isClickable || node.text != null || node.contentDescription != null) {
            return node
        }
        return null
    }

    private fun findAndClickNodeAt(x: Int, y: Int): Boolean {
        val node = findNodeAt(x, y) ?: return false
        var ok = false
        try {
            if (node.isClickable) {
                ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                var p = try { node.parent } catch (e: Exception) { null }
                while (p != null && !ok) {
                    if (p.isClickable) {
                        ok = p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    if (!ok) {
                        p = try { p.parent } catch (e: Exception) { null }
                    }
                }
            }
        } catch (e: Exception) {
            ok = false
        }
        try { node.recycle() } catch (e: Exception) {}
        return ok
    }

    private fun findNodeByTextOrDesc(query: String): Pair<AccessibilityNodeInfo, Rect>? {
        val q = query.lowercase()
        val windowList = try { windows } catch (e: Exception) { null }
        if (!windowList.isNullOrEmpty()) {
            for (w in windowList) {
                val root = try { w.root } catch (e: Exception) { null }
                if (root != null) {
                    val match = searchNodeText(root, q)
                    if (match != null) return match
                    try { root.recycle() } catch (e: Exception) {}
                }
            }
        }
        val activeRoot = rootInActiveWindow ?: return null
        return searchNodeText(activeRoot, q)
    }

    private fun searchNodeText(node: AccessibilityNodeInfo?, query: String): Pair<AccessibilityNodeInfo, Rect>? {
        if (node == null) return null
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if ((text.contains(query) || desc.contains(query)) && bounds.width() > 0 && bounds.height() > 0) {
            return Pair(node, bounds)
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            val match = searchNodeText(child, query)
            if (match != null) return match
        }
        return null
    }

    fun performGlobalSystemAction(action: String): Boolean {
        invalidateScreenshotCache()
        val globalActionCode = when (action.uppercase().trim()) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        return performGlobalAction(globalActionCode)
    }

    /**
     * Native scroll: finds a scrollable node and performs ACTION_SCROLL_* directly.
     * More reliable and faster than synthesizing a swipe gesture. Returns false when
     * no scrollable node exists so callers can fall back to a swipe.
     */
    fun scrollSmart(direction: String): Boolean {
        val action = when (direction.uppercase().trim()) {
            "UP", "SCROLL_UP" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "DOWN", "SCROLL_DOWN" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "LEFT", "SCROLL_LEFT" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "RIGHT", "SCROLL_RIGHT" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val windowList = try { windows } catch (e: Exception) { null }
        if (!windowList.isNullOrEmpty()) {
            for (w in windowList) {
                val root = try { w.root } catch (e: Exception) { null }
                if (root != null) roots.add(root)
            }
        }
        if (roots.isEmpty()) {
            val activeRoot = try { rootInActiveWindow } catch (e: Exception) { null }
            if (activeRoot != null) roots.add(activeRoot)
        }
        var performed = false
        for (root in roots) {
            val node = findScrollableNode(root)
            if (node != null) {
                performed = try { node.performAction(action) } catch (e: Exception) { false }
                try { node.recycle() } catch (e: Exception) {}
                if (performed) break
            }
        }
        roots.forEach { try { it.recycle() } catch (e: Exception) {} }
        return performed
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            val match = findScrollableNode(child)
            if (match != null) return match
        }
        return null
    }

    /**
     * Event Sensor: waits until the AccessibilityEvent stream has been quiet for
     * [quietMs] or [maxWaitMs] elapse, whichever comes first. Pure event timing,
     * no hierarchy IPC per poll, so it is much cheaper than awaitTreeSettle for
     * post-action settling. Returns the actual wait time in ms.
     */
    suspend fun awaitEventQuiet(quietMs: Long = 120L, maxWaitMs: Long = 900L): Long {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxWaitMs) {
            val quietFor = System.currentTimeMillis() - lastEventTime
            if (quietFor >= quietMs) break
            kotlinx.coroutines.delay(25L)
        }
        val waited = System.currentTimeMillis() - start
        Log.d("Zhypix", "[Perception] Event stream quiet after ${waited}ms")
        return waited
    }

    /**
     * Tree Sensor: Waits for AccessibilityEvent stream to quiet down and UI hierarchy to stabilize.
     * Automatically extends wait time if screen is in a loading state, breaking instantly when loading finishes.
     */
    suspend fun awaitTreeSettle(maxWaitMs: Long = 500L, quietWindowMs: Long = 40L): String {
        invalidateScreenshotCache()
        var previousHierarchy = ""
        var currentHierarchy = getActiveWindowHierarchy()
        var elapsed = 0L
        val pollInterval = 40L

        val isInitiallyLoading = isLoadingState(currentHierarchy)
        val effectiveMaxWait = if (isInitiallyLoading) maxWaitMs.coerceAtLeast(6000L) else maxWaitMs

        while (elapsed < effectiveMaxWait) {
            kotlinx.coroutines.delay(pollInterval)
            elapsed += pollInterval
            previousHierarchy = currentHierarchy
            currentHierarchy = getActiveWindowHierarchy()

            val quietTime = System.currentTimeMillis() - lastEventTime
            val currentlyLoading = isLoadingState(currentHierarchy)

            // Fast Settle condition: not currently loading AND hierarchy stable and non-empty
            if (!currentlyLoading && currentHierarchy.isNotEmpty() && currentHierarchy == previousHierarchy && quietTime >= quietWindowMs) {
                Log.d("Zhypix", "[Perception] Fast tree settled in ${elapsed}ms")
                break
            }
        }
        return currentHierarchy
    }

    /**
     * Frame Sensor: Fast single screenshot capture with fallback.
     */
    suspend fun awaitFrameSettle(maxWaitMs: Long = 3500L, stateTag: String = "SETTLE_CHECK", forceFresh: Boolean = true): String? {
        return kotlinx.coroutines.withTimeoutOrNull(maxWaitMs) {
            val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
            takeScreenshotWithHash(forceFresh = forceFresh) { img, _ -> deferred.complete(img) }
            deferred.await()
        }
    }

    /**
     * Dual-Signal Settle Detector: Fast routing depending on ReadyGate.
     */
    suspend fun awaitSettle(gate: ReadyGate, maxWaitMs: Long = 600L): String {
        Log.d("Zhypix", "[Perception] Fast awaitSettle with ReadyGate: $gate (maxWait: ${maxWaitMs}ms)")
        return awaitTreeSettle(maxWaitMs = maxWaitMs.coerceAtMost(500L), quietWindowMs = 40L)
    }

    /**
     * Precondition Guard: Checks if target element / coordinates / app exists in the active window hierarchy.
     */
    fun verifyPrecondition(actionType: String, target: String): Boolean {
        val cleanAct = actionType.uppercase().trim()
        val cleanTgt = target.replace("(", "").replace(")", "")
            .replace("[", "").replace("]", "")
            .replace("\"", "").replace("'", "").trim()

        if (cleanAct == "GLOBAL_ACTION" || cleanAct == "BACK" || cleanAct == "HOME" || cleanAct == "OBSERVE") {
            return true
        }

        if (cleanAct == "OPEN_APP") {
            val pm = packageManager ?: return true
            val packages = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            return packages.any { app ->
                val label = pm.getApplicationLabel(app).toString()
                label.contains(cleanTgt, ignoreCase = true) || app.packageName.contains(cleanTgt, ignoreCase = true)
            }
        }

        if (cleanAct == "TYPE") {
            val rootNode = rootInActiveWindow ?: return false
            var editableFound = false
            try {
                val inputNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableNode(rootNode)
                editableFound = (inputNode != null)
                try { inputNode?.recycle() } catch (e: Exception) {}
            } catch (e: Exception) {
                editableFound = false
            } finally {
                try { rootNode.recycle() } catch (e: Exception) {}
            }
            return editableFound
        }

        if (cleanAct == "CLICK" || cleanAct == "TAP") {
            val parts = cleanTgt.split(",")
            if (parts.size == 2 && parts[0].trim().toFloatOrNull() != null && parts[1].trim().toFloatOrNull() != null) {
                // Coordinate click - verify we have an active window root
                val root = rootInActiveWindow
                val hasRoot = root != null
                try { root?.recycle() } catch (e: Exception) {}
                return hasRoot
            }

            // Text/label click - verify matching text or contentDesc exists in current hierarchy
            val foundInfo = findNodeByTextOrDesc(cleanTgt)
            if (foundInfo != null) {
                try { foundInfo.first.recycle() } catch (e: Exception) {}
                return true
            }
            return false
        }

        if (cleanAct == "SWIPE") {
            return true
        }

        return true
    }

    suspend fun waitForScreenSettled(maxWaitMs: Long = 1500L, pollIntervalMs: Long = 60L): String {
        return awaitTreeSettle(maxWaitMs = maxWaitMs)
    }

    fun takeScreenshotBase64(forceFresh: Boolean = false, callback: (String?) -> Unit) {
        takeScreenshotWithHash(forceFresh = forceFresh) { base64, _ -> callback(base64) }
    }

    fun takeScreenshotWithHash(forceFresh: Boolean = false, retryCount: Int = 0, callback: (String?, Long) -> Unit) {
        if (forceFresh && retryCount == 0) {
            invalidateScreenshotCache()
        }
        val now = System.currentTimeMillis()
        val cached = lastScreenshotBase64
        val cachedHash = lastDHash
        if (!forceFresh && cached != null && (now - lastScreenshotTime) < screenshotDebounceMs) {
            Log.d("Zhypix", "Returning debounced, cached screenshot (elapsed: ${now - lastScreenshotTime}ms)")
            callback(cached, cachedHash)
            return
        }

        val floatingInstance = com.example.service.FloatingAgentService.instance
        if (floatingInstance != null) {
            floatingInstance.hideFloatingView()
        }

        val callStartNanos = System.nanoTime()
        val intervalMs = if (lastScreenshotAttemptTime > 0) now - lastScreenshotAttemptTime else 0L
        lastScreenshotAttemptTime = now

        // Minimum interval between takeScreenshot calls to respect Android Accessibility rate limit (~330-350ms)
        val minInterval = 340L
        val requiredDelay = if (intervalMs > 0 && intervalMs < minInterval) {
            (minInterval - intervalMs).coerceAtLeast(0L)
        } else {
            0L
        }

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                try {
                    takeScreenshot(
                        android.view.Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                                if (floatingInstance != null) {
                                    floatingInstance.showFloatingView()
                                }

                                val durationMs = (System.nanoTime() - callStartNanos) / 1_000_000
                                com.example.utils.SettleTelemetryLogger.logScreenshotDuration(
                                    this@ZhypixAccessibilityService,
                                    durationMs,
                                    true
                                )

                                CoroutineScope(Dispatchers.Default).launch {
                                    try {
                                        val hardwareBuffer = screenshot.hardwareBuffer
                                        val colorSpace = screenshot.colorSpace
                                        val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                        if (bitmap != null) {
                                            val softwareBitmap = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                            bitmap.recycle()
                                            hardwareBuffer.close()
                                            
                                            if (softwareBitmap != null) {
                                                val dHash = com.example.utils.SettleTelemetryLogger.calculateDHash(softwareBitmap)
                                                val outputStream = java.io.ByteArrayOutputStream()
                                                
                                                val scaleFactor = com.example.viewmodel.AgentViewModel.instance?.screenshotScale?.value ?: 0.5f
                                                val quality = com.example.viewmodel.AgentViewModel.instance?.screenshotQuality?.value ?: 60
                                                
                                                val targetWidth = (softwareBitmap.width * scaleFactor).toInt().coerceAtLeast(100)
                                                val targetHeight = (softwareBitmap.height * scaleFactor).toInt().coerceAtLeast(100)
                                                
                                                val scaled = android.graphics.Bitmap.createScaledBitmap(softwareBitmap, targetWidth, targetHeight, true)
                                                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, outputStream)
                                                val base64 = android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
                                                
                                                scaled.recycle()
                                                softwareBitmap.recycle()
                                                
                                                // Cache the new screenshot & hash
                                                lastScreenshotBase64 = base64
                                                lastScreenshotTime = System.currentTimeMillis()
                                                lastDHash = dHash
                                                
                                                // Trigger green light LED indicator on screen overlay
                                                withContext(Dispatchers.Main) {
                                                    visualizerView?.showScreenshotFlash()
                                                }

                                                callback(base64, dHash)
                                                return@launch
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("Zhypix", "Failed to process screenshot bitmap in background", e)
                                    }
                                    callback(null, 0L)
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                val durationMs = (System.nanoTime() - callStartNanos) / 1_000_000
                                Log.e("Zhypix", "Screenshot failed with error code $errorCode in ${durationMs}ms (retryCount=$retryCount)")
                                com.example.utils.SettleTelemetryLogger.logScreenshotDuration(
                                    this@ZhypixAccessibilityService,
                                    durationMs,
                                    false,
                                    errorCode
                                )
                                com.example.utils.SettleTelemetryLogger.logShortIntervalError(
                                    this@ZhypixAccessibilityService,
                                    intervalMs,
                                    errorCode
                                )

                                // Handle error code 3 (ERROR_TAKE_SCREENSHOT_INTERVAL_TIME) or temporary failures with retry
                                if ((errorCode == 3 || errorCode == 1) && retryCount < 3) {
                                    Log.w("Zhypix", "Retrying screenshot in 350ms due to rate limit (errorCode=$errorCode, retry=${retryCount + 1})")
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        takeScreenshotWithHash(forceFresh = true, retryCount = retryCount + 1, callback = callback)
                                    }, 350L)
                                    return
                                }

                                if (floatingInstance != null) {
                                    floatingInstance.showFloatingView()
                                }
                                callback(null, 0L)
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.e("Zhypix", "SecurityException or other error taking screenshot", e)
                    if (floatingInstance != null) {
                        floatingInstance.showFloatingView()
                    }
                    callback(null, 0L)
                }
            } else {
                if (floatingInstance != null) {
                    floatingInstance.showFloatingView()
                }
                callback(null, 0L)
            }
        }, requiredDelay)
    }
}
