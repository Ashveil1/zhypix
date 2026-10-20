package com.example.utils

import com.example.api.Content

/**
 * Generic history hygiene for the agent loop, independent of any specific
 * context-size target: estimate token usage, prune stale screen trees from
 * history (keeping only the latest), and a rolling action log so older steps
 * can be pruned without losing what happened. A fixed ~60k working budget
 * belongs to the future Gemini Live mode, not the main REST loop.
 */
object ContextBudget {

    /** Rough allowance for the system prompt + tool declarations that are
     *  sent with every request but not counted in conversationHistory. */
    const val SYSTEM_OVERHEAD_ALLOWANCE = 4_000

    /** Summarize when estimated usage crosses this fraction of the budget. */
    const val SUMMARIZE_FRACTION = 0.8

    /** Cheap local token estimate: ~4 chars per token, 258 tokens per image. */
    fun estimateHistoryTokens(history: List<Content>): Int {
        var tokens = SYSTEM_OVERHEAD_ALLOWANCE
        for (content in history) {
            for (part in content.parts) {
                part.text?.let { tokens += it.length / 4 }
                part.functionCall?.let {
                    tokens += it.name.length / 4
                    it.args?.let { args -> tokens += args.toString().length / 4 }
                }
                part.functionResponse?.let {
                    tokens += it.name.length / 4
                    tokens += it.response.toString().length / 4
                }
                part.inlineData?.let { tokens += 258 }
            }
        }
        return tokens
    }

    private val hierarchyMarkers = listOf(
        "Latest Screen Layout Hierarchy:\n",
        "Current screen layout hierarchy:\n",
        "Hierarchy retrieved:\n"
    )

    /**
     * Removes stale screen-state dumps from history, keeping only the newest
     * [keepLatest] occurrences. Old accessibility trees are the largest
     * recurring payload in the loop; the running action log preserves what
     * happened, so old trees carry no value. Handles both forms:
     * functionResponse maps with a "current_screen_hierarchy" entry and text
     * parts embedding a tree after a marker line.
     */
    fun pruneOldHierarchies(history: MutableList<Content>, keepLatest: Int = 1) {
        var seen = 0
        for (i in history.indices.reversed()) {
            val content = history[i]
            var touched = false
            val newParts = content.parts.map { part ->
                var p = part
                val fr = p.functionResponse
                if (fr != null) {
                    val treeKeys = fr.response.keys.filter { k ->
                        val v = fr.response[k]?.toString().orEmpty()
                        v.isNotEmpty() && (k == "current_screen_hierarchy" || hierarchyMarkers.any { v.contains(it) })
                    }
                    if (treeKeys.isNotEmpty()) {
                        seen++
                        if (seen > keepLatest) {
                            val newResponse = fr.response.toMutableMap()
                            for (k in treeKeys) {
                                newResponse[k] = "[pruned: older screen state]"
                            }
                            p = p.copy(functionResponse = fr.copy(response = newResponse))
                            touched = true
                        }
                    }
                }
                val t = p.text
                if (t != null) {
                    val marker = hierarchyMarkers.firstOrNull { t.contains(it) }
                    if (marker != null) {
                        seen++
                        if (seen > keepLatest) {
                            val head = t.substringBefore(marker)
                            p = p.copy(text = head + marker + "[pruned: older screen state]")
                            touched = true
                        }
                    }
                }
                p
            }
            if (touched) {
                history[i] = content.copy(parts = newParts)
            }
        }
    }
}

/**
 * Short rolling log of executed actions. Older loop steps collapse into this
 * log so the model keeps a memory of what it already did without paying the
 * token cost of every old screen tree and tool response.
 */
class RunningActionLog(private val cap: Int = 24) {
    private val entries = ArrayDeque<String>()

    val size: Int get() = entries.size

    @Synchronized
    fun add(action: String, target: String, success: Boolean, detail: String = "") {
        val status = if (success) "ok" else "failed"
        val line = buildString {
            append("- ").append(action)
            if (target.isNotBlank()) append(" '").append(target.take(40)).append("'")
            append(" -> ").append(status)
            if (detail.isNotBlank()) append(" (").append(detail.take(60)).append(")")
        }
        entries.addLast(line)
        while (entries.size > cap) entries.removeFirst()
    }

    @Synchronized
    fun render(): String =
        if (entries.isEmpty()) "" else entries.joinToString("\n")

    @Synchronized
    fun clear() = entries.clear()
}
