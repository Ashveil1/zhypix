package com.example.service

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Multi-layer resilient selector specification for finding screen elements.
 */
data class ElementSelector(
    val resourceId: String? = null,
    val text: String? = null,
    val contentDesc: String? = null,
    val className: String? = null,
    val x: Float? = null,
    val y: Float? = null
) {
    companion object {
        fun parse(query: String): ElementSelector {
            val q = query.trim()
            if (q.isEmpty()) return ElementSelector()

            // Check coordinate format e.g. "(100, 200)", "100,200", "[100, 200]", "0.5, 0.5"
            val cleanCoords = q.replace("(", "").replace(")", "")
                .replace("[", "").replace("]", "")
                .replace("\"", "").replace("'", "")
                .replace(" ", "")
                .split(",")
            if (cleanCoords.size == 2) {
                val cx = cleanCoords[0].toFloatOrNull()
                val cy = cleanCoords[1].toFloatOrNull()
                if (cx != null && cy != null) {
                    return ElementSelector(x = cx, y = cy)
                }
            }

            // Check resource-id format
            if (q.contains(":id/") || q.startsWith("id/")) {
                return ElementSelector(resourceId = q)
            }

            // General text / content description query
            return ElementSelector(text = q, contentDesc = q)
        }
    }
}

data class ResolvedElement(
    val node: AccessibilityNodeInfo,
    val bounds: Rect,
    val matchScore: Int,
    val matchType: String,
    val totalMatchesCount: Int
) {
    val isUnique: Boolean get() = totalMatchesCount == 1
    val centerX: Float get() = bounds.centerX().toFloat()
    val centerY: Float get() = bounds.centerY().toFloat()
}

object ElementResolver {

    private const val TAG = "ElementResolver"

    /**
     * Resolves an ElementSelector against active windows with multi-layer fallback chain
     * and real-time bounds re-querying.
     */
    fun resolve(
        service: ZhypixAccessibilityService?,
        selector: ElementSelector
    ): ResolvedElement? {
        if (service == null) return null

        // Convert normalized coordinates (<= 1.0) to screen pixel coordinates if needed
        val effectiveSelector = if (selector.x != null && selector.y != null && selector.x <= 1.0f && selector.y <= 1.0f && (selector.x > 0f || selector.y > 0f)) {
            val dm = service.resources.displayMetrics
            selector.copy(
                x = selector.x * dm.widthPixels,
                y = selector.y * dm.heightPixels
            )
        } else {
            selector
        }

        val rootNodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            val windowList = service.windows
            if (!windowList.isNullOrEmpty()) {
                for (w in windowList) {
                    val root = try { w.root } catch (e: Exception) { null }
                    if (root != null) rootNodes.add(root)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching window roots", e)
        }

        if (rootNodes.isEmpty()) {
            val activeRoot = try { service.rootInActiveWindow } catch (e: Exception) { null }
            if (activeRoot != null) rootNodes.add(activeRoot)
        }

        if (rootNodes.isEmpty()) {
            Log.e(TAG, "No active window roots available for element resolution")
            return null
        }

        val candidates = mutableListOf<MatchCandidate>()

        for (root in rootNodes) {
            searchNodes(root, effectiveSelector, candidates)
        }

        if (candidates.isEmpty()) {
            // Cleanup roots
            rootNodes.forEach { try { it.recycle() } catch (e: Exception) {} }
            return null
        }

        // Sort candidates by matchScore descending, then by clickability, then by bounds area
        candidates.sortWith(
            compareByDescending<MatchCandidate> { it.score }
                .thenByDescending { if (it.node.isClickable) 1 else 0 }
                .thenByDescending { it.bounds.width() * it.bounds.height() }
        )

        val best = candidates.first()
        val totalTopMatches = candidates.count { it.score == best.score }

        Log.i(TAG, "Resolved element using layer=${best.matchType} score=${best.score} bounds=${best.bounds} unique=${totalTopMatches == 1}")

        // Recycle all candidates except the best
        for (c in candidates) {
            if (c !== best) {
                try { c.node.recycle() } catch (e: Exception) {}
            }
        }
        rootNodes.forEach { try { it.recycle() } catch (e: Exception) {} }

        return ResolvedElement(
            node = best.node,
            bounds = best.bounds,
            matchScore = best.score,
            matchType = best.matchType,
            totalMatchesCount = totalTopMatches
        )
    }

    private data class MatchCandidate(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val score: Int,
        val matchType: String
    )

    private fun searchNodes(
        node: AccessibilityNodeInfo?,
        selector: ElementSelector,
        outCandidates: MutableList<MatchCandidate>
    ) {
        if (node == null) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Validate bounds are on-screen
        if (bounds.width() > 0 && bounds.height() > 0) {
            val scoreInfo = evaluateNodeMatch(node, bounds, selector)
            if (scoreInfo != null) {
                outCandidates.add(
                    MatchCandidate(
                        node = node,
                        bounds = bounds,
                        score = scoreInfo.first,
                        matchType = scoreInfo.second
                    )
                )
            }
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                searchNodes(child, selector, outCandidates)
                if (outCandidates.none { it.node === child }) {
                    try { child.recycle() } catch (e: Exception) {}
                }
            }
        }
    }

    private fun evaluateNodeMatch(
        node: AccessibilityNodeInfo,
        bounds: Rect,
        selector: ElementSelector
    ): Pair<Int, String>? {
        // Layer 1: Resource ID match
        if (!selector.resourceId.isNullOrEmpty()) {
            val resId = node.viewIdResourceName
            if (!resId.isNullOrEmpty()) {
                if (resId.equals(selector.resourceId, ignoreCase = true)) {
                    return Pair(100, "RESOURCE_ID_EXACT")
                }
                if (resId.endsWith(selector.resourceId.removePrefix("id/"), ignoreCase = true)) {
                    return Pair(95, "RESOURCE_ID_PARTIAL")
                }
            }
        }

        // Layer 2: Text / Content Description
        val nodeText = node.text?.toString()?.trim()
        val nodeDesc = node.contentDescription?.toString()?.trim()

        val queryText = selector.text ?: selector.contentDesc
        if (!queryText.isNullOrEmpty()) {
            val q = queryText.lowercase()

            if (nodeText != null) {
                val t = nodeText.lowercase()
                if (t == q) return Pair(90, "TEXT_EXACT")
                if (t.startsWith(q)) return Pair(75, "TEXT_STARTS_WITH")
                if (t.contains(q)) return Pair(60, "TEXT_CONTAINS")
            }

            if (nodeDesc != null) {
                val d = nodeDesc.lowercase()
                if (d == q) return Pair(88, "DESC_EXACT")
                if (d.startsWith(q)) return Pair(72, "DESC_STARTS_WITH")
                if (d.contains(q)) return Pair(58, "DESC_CONTAINS")
            }
        }

        // Layer 3: Coordinate Re-Query (bounds hit)
        if (selector.x != null && selector.y != null) {
            val targetX = selector.x.toInt()
            val targetY = selector.y.toInt()
            if (bounds.contains(targetX, targetY)) {
                // Prefer clickable nodes or leaf nodes
                val score = if (node.isClickable) 50 else 30
                return Pair(score, "COORDINATE_REBOUND")
            }
        }

        return null
    }

    /**
     * Generates a compact, token-efficient UI hierarchy tree representation for LLM context.
     * Strips non-interactive structural containers while retaining text, ids, and clickable bounds.
     */
    fun buildCompactTree(service: ZhypixAccessibilityService?): String {
        if (service == null) return "Accessibility Service inactive"

        val sb = StringBuilder()
        sb.appendLine("=== COMPACT ACCESSIBILITY TREE ===")

        val rootNodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            val windowList = service.windows
            if (!windowList.isNullOrEmpty()) {
                for (w in windowList) {
                    val root = try { w.root } catch (e: Exception) { null }
                    if (root != null) rootNodes.add(root)
                }
            }
        } catch (e: Exception) {}

        if (rootNodes.isEmpty()) {
            val activeRoot = try { service.rootInActiveWindow } catch (e: Exception) { null }
            if (activeRoot != null) rootNodes.add(activeRoot)
        }

        if (rootNodes.isEmpty()) {
            sb.appendLine("No active root window")
            return sb.toString()
        }

        for (root in rootNodes) {
            traverseCompact(root, sb, depth = 0)
        }
        rootNodes.forEach { try { it.recycle() } catch (e: Exception) {} }

        return sb.toString().trimEnd()
    }

    private fun traverseCompact(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val resId = node.viewIdResourceName?.substringAfterLast(":id/")
        val isClickable = node.isClickable
        val isEditable = node.isEditable

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val isMeaningful = !text.isNullOrEmpty() || !desc.isNullOrEmpty() || !resId.isNullOrEmpty() || isClickable || isEditable

        if (isMeaningful && bounds.width() > 0 && bounds.height() > 0) {
            val indent = "  ".repeat(depth.coerceAtMost(6))
            val tags = mutableListOf<String>()

            if (!resId.isNullOrEmpty()) tags.add("id=$resId")
            if (!text.isNullOrEmpty()) tags.add("text=\"${text.take(40)}\"")
            if (!desc.isNullOrEmpty()) tags.add("desc=\"${desc.take(40)}\"")
            if (isClickable) tags.add("clickable")
            if (isEditable) tags.add("editable")
            tags.add("bounds=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")

            val clsName = node.className?.toString()?.substringAfterLast(".") ?: "View"
            sb.appendLine("$indent<$clsName ${tags.joinToString(" ")} />")
        }

        val childDepth = if (isMeaningful) depth + 1 else depth
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                traverseCompact(child, sb, childDepth)
                try { child.recycle() } catch (e: Exception) {}
            }
        }
    }
}
