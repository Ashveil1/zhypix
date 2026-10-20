package com.example.agent

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * On-device diagnostics for the agent loop. Appends one JSON line per event
 * (action step, retry, terminal state, parse/network issue) to
 * filesDir/agent_diagnostics.jsonl so a failing run can be inspected after
 * the fact. The log is capped at ~1 MB; when the cap is hit the older half
 * is dropped. Read it with: adb run-as <package> cat files/agent_diagnostics.jsonl
 * (debug builds) or via exportFile() from a share sheet.
 */
object AgentDiagnostics {

    private const val FILE_NAME = "agent_diagnostics.jsonl"
    private const val MAX_BYTES = 1_000_000L

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        try {
            logFile = File(context.filesDir, FILE_NAME)
        } catch (e: Exception) {
            Log.w("Zhypix", "AgentDiagnostics init failed", e)
        }
    }

    private fun append(obj: JSONObject) {
        val f = logFile ?: return
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                val lines = f.readLines()
                f.writeText(lines.drop(lines.size / 2).joinToString("\n") + "\n")
            }
            f.appendText(obj.toString() + "\n")
        } catch (e: Exception) {
            Log.w("Zhypix", "AgentDiagnostics append failed", e)
        }
    }

    /** One executed screen action with its stage timings and outcome. */
    fun recordActionStep(
        step: Int,
        action: String,
        target: String,
        success: Boolean,
        message: String,
        executeMs: Long,
        settleMs: Long,
        captureMs: Long,
        treeSize: Int,
        shotMs: Long = 0
    ) {
        val o = JSONObject()
        o.put("ts", System.currentTimeMillis())
        o.put("type", "action_step")
        o.put("step", step)
        o.put("action", action)
        o.put("target", target.take(200))
        o.put("success", success)
        o.put("message", message.take(300))
        o.put("executeMs", executeMs)
        o.put("settleMs", settleMs)
        o.put("captureMs", captureMs)
        o.put("treeSize", treeSize)
        o.put("shotMs", shotMs)
        append(o)
        Log.d("Zhypix", "diag step=$step $action success=$success exec=${executeMs}ms settle=${settleMs}ms tree=$treeSize")
    }

    /** A non-action event: stage is backend | action | parse | network | terminal. */
    fun recordEvent(stage: String, message: String) {
        val o = JSONObject()
        o.put("ts", System.currentTimeMillis())
        o.put("type", "event")
        o.put("stage", stage)
        o.put("message", message.take(300))
        append(o)
    }

    fun exportFile(): File? = logFile?.takeIf { it.exists() }

    fun recentSummary(maxLines: Int = 20): String {
        val f = logFile ?: return ""
        return try {
            if (!f.exists()) "" else f.readLines().takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            ""
        }
    }
}
