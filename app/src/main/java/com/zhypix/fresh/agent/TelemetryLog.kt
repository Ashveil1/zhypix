package com.zhypix.fresh.agent

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File

/**
 * Append-only JSONL ring of per-step diagnostics in the app's private files
 * dir, capped around 1 MB. Lets a bug report say exactly which step was slow
 * or wrong. Never contains API keys.
 */
class TelemetryLog(private val file: File, private val maxBytes: Long = 1_000_000L) {

    private val adapter = Moshi.Builder().build()
        .adapter<Map<String, Any?>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    @Synchronized
    fun event(stage: String, fields: Map<String, Any?>) {
        try {
            val obj = LinkedHashMap<String, Any?>()
            obj["ts"] = System.currentTimeMillis()
            obj["stage"] = stage
            obj.putAll(fields)
            file.appendText(adapter.toJson(obj) + "\n")
            trimIfNeeded()
        } catch (_: Exception) {
            // Diagnostics must never break the agent.
        }
    }

    private fun trimIfNeeded() {
        if (file.length() <= maxBytes) return
        val keep = file.readText().lineSequence().toList().takeLast(500)
        file.writeText(keep.joinToString("\n") + "\n")
    }
}
