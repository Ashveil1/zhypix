package com.example.service

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Automated Ad-Skipper for YouTube and other video apps.
 * Operates in real-time via Accessibility Service events and periodic polling.
 */
object AutoAdSkipper {

    private const val TAG = "AutoAdSkipper"

    var isEnabled: Boolean = true

    private var lastSkipAttemptTime: Long = 0L

    @Volatile
    private var scanInFlight: Boolean = false
    private const val MIN_SKIP_INTERVAL_MS = 1200L

    private val AD_RESOURCE_IDS = listOf(
        "skip_ad_button",
        "ad_skip_button_text",
        "ad_skip_button",
        "sub_ad_skip_button",
        "modern_skip_ad_button",
        "skip_ad_button_container",
        "ad_skip_button_icon",
        "skip_ad",
        "skip_button",
        "btn_skip"
    )

    private val AD_TEXT_KEYWORDS = listOf(
        "skip ad",
        "skip ads",
        "skip",
        "ข้ามโฆษณา",
        "ข้าม",
        "ข้ามโฆษณาใน",
        "skip in",
        "skip video"
    )

    /**
     * Checks the active window hierarchy for Skip Ad buttons and performs an instant click.
     * Returns true if an ad skip button was found and clicked.
     */
    fun checkAndSkipAd(service: ZhypixAccessibilityService?): Boolean {
        if (!isEnabled || service == null) return false

        val now = System.currentTimeMillis()
        if (now - lastSkipAttemptTime < MIN_SKIP_INTERVAL_MS) {
            return false
        }

        val candidateRoots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val windowList = service.windows
            if (!windowList.isNullOrEmpty()) {
                for (w in windowList) {
                    val r = try { w.root } catch (e: Exception) { null }
                    if (r != null) candidateRoots.add(r)
                }
            }
        } catch (e: Exception) {}

        val activeRoot = try { service.rootInActiveWindow } catch (e: Exception) { null }
        if (activeRoot != null && candidateRoots.none { it == activeRoot }) {
            candidateRoots.add(0, activeRoot)
        }

        if (candidateRoots.isEmpty()) return false
        if (scanInFlight) return false
        scanInFlight = true
        lastSkipAttemptTime = now

        // The tree scan is hundreds of binder IPC calls; never run it on the
        // accessibility callback thread (main) - it was a major jank source.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
        try {
            var skipTarget: Pair<AccessibilityNodeInfo, Rect>? = null
            for (root in candidateRoots) {
                skipTarget = findSkipAdNode(root)
                if (skipTarget != null) break
            }

            if (skipTarget != null) {
                Log.i(TAG, "Found YouTube Skip Ad button! Attempting instant auto-click...")

                var clicked = false
                val (node, bounds) = skipTarget

                // Try direct accessibility click
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
                            clicked = try { parent.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (e: Exception) { false }
                        }
                        parent = try { parent.parent } catch (e: Exception) { null }
                    }
                }

                // Fallback to gesture dispatch at center bounds
                if (!clicked && bounds.width() > 0 && bounds.height() > 0) {
                    val cx = bounds.centerX().toFloat()
                    val cy = bounds.centerY().toFloat()
                    Log.i(TAG, "Dispatching gesture click at ($cx, $cy) for Skip Ad button")
                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        service.visualizerView?.showClickFeedback(cx, cy)
                        service.dispatchClick(cx, cy)
                    }
                    clicked = true
                }

                try { node.recycle() } catch (e: Exception) {}

                if (clicked) {
                    Log.i(TAG, "Successfully auto-clicked Skip Ad button!")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking for ad skip button", e)
        } finally {
            candidateRoots.forEach { try { it.recycle() } catch (e: Exception) {} }
            scanInFlight = false
        }
        }

        return false
    }

    private fun findSkipAdNode(node: AccessibilityNodeInfo?): Pair<AccessibilityNodeInfo, Rect>? {
        if (node == null) return null

        val resId = node.viewIdResourceName?.lowercase() ?: ""
        val text = node.text?.toString()?.trim()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // 1. Check Resource ID match
        if (resId.isNotEmpty()) {
            val resName = resId.substringAfterLast(":id/")
            if (AD_RESOURCE_IDS.any { resName.contains(it) }) {
                return Pair(node, bounds)
            }
        }

        // 2. Check Text / Description match
        if (text.isNotEmpty()) {
            if (AD_TEXT_KEYWORDS.any { keyword -> text == keyword || text.startsWith(keyword) }) {
                return Pair(node, bounds)
            }
        }

        if (desc.isNotEmpty()) {
            if (AD_TEXT_KEYWORDS.any { keyword -> desc == keyword || desc.startsWith(keyword) }) {
                return Pair(node, bounds)
            }
        }

        // Search children
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            val found = findSkipAdNode(child)
            if (found != null) {
                return found
            }
        }

        return null
    }
}
