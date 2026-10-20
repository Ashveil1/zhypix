package com.example.service
 
data class ActionResult(val success: Boolean, val message: String)

object GestureMapper {
    /**
     * Translates coordinates and actions from the AI model into AccessibilityService gesture dispatchers.
     */
    suspend fun executeAction(service: ZhypixAccessibilityService?, actionType: String, target: String, context: android.content.Context? = null): ActionResult {
        val isDesktopActive = com.example.utils.LinuxTerminalSimulator.isDesktopScreenActive.value
        if (isDesktopActive) {
            return executeLinuxAction(actionType, target)
        }
        return when (actionType.uppercase()) {
            "OPEN_APP" -> {
                val ctx = service ?: context
                if (ctx == null) return ActionResult(false, "Failed to get Context to open apps.")
                val pm = ctx.packageManager
                val packages = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                var foundIntent: android.content.Intent? = null
                var foundName = ""
                
                var bestApp: android.content.pm.ApplicationInfo? = null
                
                // Prioritize exact match
                for (app in packages) {
                    val label = pm.getApplicationLabel(app).toString()
                    if (label.equals(target, ignoreCase = true) || app.packageName.equals(target, ignoreCase = true)) {
                        if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                            bestApp = app
                            break
                        }
                    }
                }
                
                // Fallback to startsWith
                if (bestApp == null) {
                    for (app in packages) {
                        val label = pm.getApplicationLabel(app).toString()
                        if (label.startsWith(target, ignoreCase = true)) {
                            if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                                bestApp = app
                                break
                            }
                        }
                    }
                }
                
                // Fallback to contains
                if (bestApp == null) {
                    for (app in packages) {
                        val label = pm.getApplicationLabel(app).toString()
                        if (label.contains(target, ignoreCase = true) || app.packageName.contains(target, ignoreCase = true)) {
                            if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                                bestApp = app
                                break
                            }
                        }
                    }
                }
                
                if (bestApp != null) {
                    foundIntent = pm.getLaunchIntentForPackage(bestApp.packageName)
                    foundName = pm.getApplicationLabel(bestApp).toString()
                }
                
                if (foundIntent != null) {
                    foundIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    ctx.startActivity(foundIntent)
                    service?.invalidateScreenshotCache()
                    ActionResult(true, "Successfully opened $foundName.")
                } else {
                    ActionResult(false, "Failed to find app matching '$target'.")
                }
            }
            else -> {
                if (service == null) {
                    val fallbackCtx = context
                    val diagLog = if (fallbackCtx != null) {
                        "\n--- Diagnostics Log ---\n" + com.example.utils.AccessibilityDiagnosis.runDiagnostics(fallbackCtx, service)
                    } else {
                        " (No context available for diagnostics)"
                    }
                    return ActionResult(false, "Accessibility Service is not enabled. Ask the user to enable/restart it in Settings.$diagLog")
                }
                when (actionType.uppercase()) {
                    "CLICK", "TAP" -> {
                        return service.clickSmart(target)
                    }
                    "DOUBLE_CLICK", "DOUBLE_TAP" -> {
                        return service.doubleClickSmart(target)
                    }
                    "LONG_PRESS", "LONG_CLICK", "PRESS_AND_HOLD" -> {
                        return service.longPressSmart(target)
                    }
                    "SWIPE", "SCROLL" -> {
                        val cleanTarget = target.uppercase().trim()
                        val displayMetrics = service.resources.displayMetrics
                        val screenW = displayMetrics.widthPixels.toFloat()
                        val screenH = displayMetrics.heightPixels.toFloat()
                        val cx = screenW / 2f
                        val cy = screenH / 2f

                        if (cleanTarget in listOf("UP", "SCROLL_UP", "PAGE_UP", "DOWN", "SCROLL_DOWN", "PAGE_DOWN", "LEFT", "RIGHT")) {
                            // Native accessibility scroll is faster and more reliable than a swipe
                            val nativeDir = when {
                                cleanTarget.contains("UP") -> "UP"
                                cleanTarget.contains("DOWN") -> "DOWN"
                                cleanTarget.contains("LEFT") -> "LEFT"
                                else -> "RIGHT"
                            }
                            if (service.scrollSmart(nativeDir)) {
                                return ActionResult(true, "Scrolled $nativeDir using native accessibility scroll.")
                            }
                            val (sx, sy, ex, ey) = when {
                                cleanTarget.contains("UP") -> listOf(cx, cy * 1.4f, cx, cy * 0.4f)
                                cleanTarget.contains("DOWN") -> listOf(cx, cy * 0.4f, cx, cy * 1.4f)
                                cleanTarget.contains("LEFT") -> listOf(screenW * 0.85f, cy, screenW * 0.15f, cy)
                                cleanTarget.contains("RIGHT") -> listOf(screenW * 0.15f, cy, screenW * 0.85f, cy)
                                else -> listOf(cx, cy * 1.4f, cx, cy * 0.4f)
                            }
                            val ok = service.dispatchSwipe(sx, sy, ex, ey, 250L)
                            return if (ok) {
                                ActionResult(true, "Successfully swiped $cleanTarget across screen.")
                            } else {
                                ActionResult(false, "Failed to dispatch swipe $cleanTarget gesture.")
                            }
                        }

                        // Robust float coordinate extraction
                        val numMatches = Regex("""[-+]?[0-9]*\.?[0-9]+""").findAll(target).mapNotNull { it.value.toFloatOrNull() }.toList()

                        if (numMatches.size >= 4) {
                            var startX = numMatches[0]
                            var startY = numMatches[1]
                            var endX = numMatches[2]
                            var endY = numMatches[3]

                            // Auto-scale normalized coordinates (0.0 to 1.0)
                            if (startX <= 1.0f && startY <= 1.0f && endX <= 1.0f && endY <= 1.0f &&
                                (startX > 0f || startY > 0f || endX > 0f || endY > 0f)) {
                                startX *= screenW
                                startY *= screenH
                                endX *= screenW
                                endY *= screenH
                            }

                            val ok = service.dispatchSwipe(startX, startY, endX, endY)
                            return if (ok) {
                                ActionResult(true, "Executed swipe from (${startX.toInt()}, ${startY.toInt()}) to (${endX.toInt()}, ${endY.toInt()}) successfully.")
                            } else {
                                ActionResult(false, "Failed to dispatch swipe gesture.")
                            }
                        }
                        return ActionResult(false, "Invalid format for swipe target: '$target'. Expected 'startX,startY,endX,endY' or direction ('UP', 'DOWN', 'LEFT', 'RIGHT').")
                    }
                    "GLOBAL_ACTION", "SYSTEM", "PRESS_KEY", "KEY" -> {
                        val ok = service.performGlobalSystemAction(target)
                        return if (ok) {
                            ActionResult(true, "Successfully executed global system action '$target'.")
                        } else {
                            ActionResult(false, "Failed to execute global system action '$target'. Supported: BACK, HOME, RECENTS, NOTIFICATIONS.")
                        }
                    }
                    "BACK" -> {
                        val ok = service.performGlobalSystemAction("BACK")
                        return if (ok) ActionResult(true, "Pressed BACK button.") else ActionResult(false, "Failed to press BACK button.")
                    }
                    "HOME" -> {
                        val ok = service.performGlobalSystemAction("HOME")
                        return if (ok) ActionResult(true, "Pressed HOME button.") else ActionResult(false, "Failed to press HOME button.")
                    }
                    "RECENTS" -> {
                        val ok = service.performGlobalSystemAction("RECENTS")
                        return if (ok) ActionResult(true, "Opened Recents / App Switcher.") else ActionResult(false, "Failed to open Recents.")
                    }
                    "OBSERVE" -> {
                        val hierarchy = service.getActiveWindowHierarchy()
                        return ActionResult(true, "Hierarchy retrieved:\n$hierarchy")
                    }
                    "WAIT", "SLEEP" -> {
                        val durationSec = target.filter { it.isDigit() }.toLongOrNull() ?: 1L
                        val maxWaitMs = (durationSec * 1000L).coerceIn(300L, 5000L)
                        var elapsed = 0L
                        val step = 100L
                        while (elapsed < maxWaitMs) {
                            kotlinx.coroutines.delay(step)
                            elapsed += step
                            val h = service.getActiveWindowHierarchy()
                            if (!service.isLoadingState(h) && elapsed >= 200L) {
                                break
                            }
                        }
                        return ActionResult(true, "Waited ${(elapsed / 1000f)}s for UI to settle.")
                    }
                    "TYPE", "INPUT", "SET_TEXT" -> {
                        val cleanText = target.trim().removeSurrounding("\"", "").removeSurrounding("'", "")
                        val success = service.inputText(cleanText)
                        return if (success) {
                            ActionResult(true, "Successfully typed text '$cleanText' into the focused field.")
                        } else {
                            ActionResult(false, "Failed to type. Ensure an input field is focused (use CLICK on the field or text target first).")
                        }
                    }
                    else -> ActionResult(false, "Unsupported action type: $actionType")
                }
            }
        }
    }

    private suspend fun executeLinuxAction(actionType: String, target: String): ActionResult {
        val act = actionType.uppercase().trim()
        val tgt = target.trim()
        
        return when (act) {
            "CLICK", "TAP" -> {
                val coordParts = tgt.replace("(", "").replace(")", "")
                    .replace("[", "").replace("]", "")
                    .replace("\"", "").replace("'", "")
                    .replace(" ", "")
                    .split(",")
                if (coordParts.size == 2) {
                    val x = coordParts[0].toFloatOrNull()?.toInt()
                    val y = coordParts[1].toFloatOrNull()?.toInt()
                    if (x != null && y != null) {
                        val cmd = "xdotool mousemove $x $y click 1"
                        com.example.utils.LinuxTerminalSimulator.executeCommand(cmd)
                        ActionResult(true, "Successfully executed Linux click at ($x, $y).")
                    } else {
                        ActionResult(false, "Failed to parse coordinates for Linux click action: '$tgt'")
                    }
                } else {
                    ActionResult(false, "Invalid coordinate format for Linux click action: '$tgt'")
                }
            }
            "TYPE" -> {
                val escapedText = tgt.replace("'", "'\\''")
                val cmd = "xdotool type '$escapedText'"
                com.example.utils.LinuxTerminalSimulator.executeCommand(cmd)
                ActionResult(true, "Successfully typed text on Linux desktop.")
            }
            "PRESS_KEY", "KEY" -> {
                val key = when (tgt.uppercase()) {
                    "ENTER", "RETURN" -> "Return"
                    "BACKSPACE" -> "BackSpace"
                    "TAB" -> "Tab"
                    "ESCAPE", "ESC" -> "Escape"
                    "UP" -> "Up"
                    "DOWN" -> "Down"
                    "LEFT" -> "Left"
                    "RIGHT" -> "Right"
                    "PAGE_UP", "PAGEUP" -> "Page_Up"
                    "PAGE_DOWN", "PAGEDOWN" -> "Page_Down"
                    else -> tgt
                }
                val cmd = "xdotool key '$key'"
                com.example.utils.LinuxTerminalSimulator.executeCommand(cmd)
                ActionResult(true, "Successfully pressed key '$key' on Linux desktop.")
            }
            "BACK" -> {
                com.example.utils.LinuxTerminalSimulator.executeCommand("xdotool key Alt+Left")
                ActionResult(true, "Pressed Linux browser back shortcut (Alt+Left).")
            }
            "SWIPE" -> {
                val coordParts = tgt.replace("(", "").replace(")", "")
                    .replace("[", "").replace("]", "")
                    .replace("\"", "").replace("'", "")
                    .replace(" ", "")
                    .split(",")
                if (coordParts.size == 4) {
                    val sx = coordParts[0].toFloatOrNull()?.toInt()
                    val sy = coordParts[1].toFloatOrNull()?.toInt()
                    val ex = coordParts[2].toFloatOrNull()?.toInt()
                    val ey = coordParts[3].toFloatOrNull()?.toInt()
                    if (sx != null && sy != null && ex != null && ey != null) {
                        val cmd = "xdotool mousemove $sx $sy mousedown 1 mousemove $ex $ey mouseup 1"
                        com.example.utils.LinuxTerminalSimulator.executeCommand(cmd)
                        ActionResult(true, "Successfully executed Linux drag/swipe from ($sx, $sy) to ($ex, $ey).")
                    } else {
                        ActionResult(false, "Failed to parse drag coordinates: '$tgt'")
                    }
                } else if (tgt.uppercase() == "UP" || tgt.uppercase() == "DOWN" || tgt.uppercase() == "LEFT" || tgt.uppercase() == "RIGHT") {
                    val key = when (tgt.uppercase()) {
                        "UP" -> "Page_Up"
                        "DOWN" -> "Page_Down"
                        "LEFT" -> "Left"
                        "RIGHT" -> "Right"
                        else -> "Page_Down"
                    }
                    com.example.utils.LinuxTerminalSimulator.executeCommand("xdotool key $key")
                    ActionResult(true, "Scrolled Linux display using key '$key'.")
                } else {
                    ActionResult(false, "Invalid swipe/scroll target for Linux desktop: '$tgt'")
                }
            }
            "WAIT", "SLEEP" -> {
                val durationSec = tgt.filter { it.isDigit() }.toLongOrNull() ?: 1L
                val maxWaitMs = (durationSec * 1000L).coerceIn(300L, 5000L)
                kotlinx.coroutines.delay(maxWaitMs)
                ActionResult(true, "Waited ${durationSec}s on Linux desktop.")
            }
            "OPEN_APP" -> {
                // Try launching app in background
                val appCmd = when (tgt.lowercase()) {
                    "chrome", "chromium", "google-chrome" -> "google-chrome --no-sandbox > /dev/null 2>&1 &"
                    "firefox" -> "firefox > /dev/null 2>&1 &"
                    "terminal", "bash", "xterm" -> "xterm > /dev/null 2>&1 &"
                    else -> "$tgt > /dev/null 2>&1 &"
                }
                com.example.utils.LinuxTerminalSimulator.executeCommand(appCmd)
                ActionResult(true, "Launched app '$tgt' on Linux desktop.")
            }
            "OBSERVE" -> {
                ActionResult(true, "Linux desktop layout active. Viewport: 1280x720. Desktop layout hierarchy will be processed in the next step.")
            }
            else -> {
                ActionResult(false, "Unsupported action on Linux desktop: $act")
            }
        }
    }
}
