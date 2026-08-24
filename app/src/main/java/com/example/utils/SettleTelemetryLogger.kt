package com.example.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object SettleTelemetryLogger {

    private const val LOG_TAG = "SettleTelemetry"
    private const val FILE_NAME = "settle_telemetry.log"
    private const val MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024 // 2MB roll over

    // Atomic telemetry counters
    val totalScreenshotCalls = AtomicInteger(0)
    val successScreenshotCalls = AtomicInteger(0)
    val failedScreenshotCalls = AtomicInteger(0)
    val shortIntervalErrorCount = AtomicInteger(0)
    val totalScreenshotDurationMs = AtomicLong(0L)

    // Memory ring buffers for live UI display
    private val recentDurations = ConcurrentLinkedQueue<Long>()
    private val recentPHashLogs = ConcurrentLinkedQueue<String>()
    private val recentErrorLogs = ConcurrentLinkedQueue<String>()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Calculates 64-bit Difference Hash (dHash) from a Bitmap.
     * Grayscales and resizes to 9x8, comparing adjacent pixels to produce a 64-bit spatial fingerprint.
     */
    fun calculateDHash(bitmap: Bitmap): Long {
        return try {
            val resized = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
            var hash = 0L
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val pixelLeft = resized.getPixel(x, y)
                    val pixelRight = resized.getPixel(x + 1, y)

                    val grayLeft = (Color.red(pixelLeft) * 299 + Color.green(pixelLeft) * 587 + Color.blue(pixelLeft) * 114) / 1000
                    val grayRight = (Color.red(pixelRight) * 299 + Color.green(pixelRight) * 587 + Color.blue(pixelRight) * 114) / 1000

                    if (grayLeft > grayRight) {
                        hash = hash or (1L shl (y * 8 + x))
                    }
                }
            }
            resized.recycle()
            hash
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error calculating dHash", e)
            0L
        }
    }

    /**
     * Calculates Hamming Distance (number of differing bits) between two 64-bit dHashes.
     */
    fun hammingDistance(hash1: Long, hash2: Long): Int {
        return java.lang.Long.bitCount(hash1 xor hash2)
    }

    /**
     * Logs the duration of a takeScreenshot() call (in milliseconds).
     */
    fun logScreenshotDuration(context: Context?, durationMs: Long, success: Boolean, errorCode: Int? = null) {
        totalScreenshotCalls.incrementAndGet()
        if (success) {
            successScreenshotCalls.incrementAndGet()
            totalScreenshotDurationMs.addAndGet(durationMs)
            pushRingBuffer(recentDurations, durationMs, max = 30)
        } else {
            failedScreenshotCalls.incrementAndGet()
        }

        val timestamp = dateFormat.format(Date())
        val logLine = if (success) {
            "[$timestamp] [SCREENSHOT_TIMING] duration=${durationMs}ms success=true"
        } else {
            "[$timestamp] [SCREENSHOT_TIMING] duration=${durationMs}ms success=false errorCode=$errorCode"
        }

        Log.i(LOG_TAG, logLine)
        appendToFile(context, logLine)
    }

    /**
     * Logs ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT or other screenshot rate limiting errors.
     */
    fun logShortIntervalError(context: Context?, intervalMs: Long, errorCode: Int) {
        shortIntervalErrorCount.incrementAndGet()

        val timestamp = dateFormat.format(Date())
        val errDesc = if (errorCode == 2) "ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT" else "ERROR_CODE_$errorCode"
        val logLine = "[$timestamp] [SHORT_INTERVAL_ERR] code=$errorCode ($errDesc) intervalSinceLastAttempt=${intervalMs}ms totalShortIntervalErrs=${shortIntervalErrorCount.get()}"

        Log.w(LOG_TAG, logLine)
        pushRingBuffer(recentErrorLogs, logLine, max = 30)
        appendToFile(context, logLine)
    }

    /**
     * Logs raw pHash / dHash diff between double captures to calibrate UI settlement thresholds.
     */
    fun logPHashDiff(context: Context?, stateTag: String, hash1: Long, hash2: Long, diffBits: Int, intervalMs: Long) {
        val timestamp = dateFormat.format(Date())
        val hash1Hex = String.format("0x%016X", hash1)
        val hash2Hex = String.format("0x%016X", hash2)
        
        val category = when {
            diffBits <= 2 -> "STATIC_NOISE_FREE"
            diffBits in 3..8 -> "SLIGHT_UI_FLICKER_OR_CURSOR"
            diffBits in 9..20 -> "MODERATE_FADE_TRANSITION"
            else -> "HEAVY_ANIMATION_OR_SCROLL"
        }

        val logLine = "[$timestamp] [PHASH_DIFF] tag=$stateTag category=$category interval=${intervalMs}ms diff=$diffBits bits hash1=$hash1Hex hash2=$hash2Hex"

        Log.i(LOG_TAG, logLine)
        pushRingBuffer(recentPHashLogs, logLine, max = 50)
        appendToFile(context, logLine)
    }

    /**
     * Appends a log line to persistent file storage.
     */
    private fun appendToFile(context: Context?, line: String) {
        if (context == null) return
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_FILE_SIZE_BYTES) {
                // Rotate file
                val backup = File(context.filesDir, "$FILE_NAME.old")
                if (backup.exists()) backup.delete()
                file.renameTo(backup)
            }
            FileWriter(file, true).use { writer ->
                writer.appendLine(line)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to write settle telemetry to file", e)
        }
    }

    private fun <T> pushRingBuffer(queue: ConcurrentLinkedQueue<T>, item: T, max: Int) {
        queue.add(item)
        while (queue.size > max) {
            queue.poll()
        }
    }

    fun getAverageDurationMs(): Long {
        val count = successScreenshotCalls.get()
        return if (count > 0) totalScreenshotDurationMs.get() / count else 0L
    }

    /**
     * Formats summary telemetry metrics for display in UI.
     */
    fun getTelemetrySummary(): String {
        val total = totalScreenshotCalls.get()
        val success = successScreenshotCalls.get()
        val failed = failedScreenshotCalls.get()
        val shortErrs = shortIntervalErrorCount.get()
        val avgDur = getAverageDurationMs()

        return StringBuilder().apply {
            appendLine("=== Settle Fusion Telemetry Summary ===")
            appendLine("Total Screenshot Calls: $total")
            appendLine("Successful: $success | Failed: $failed")
            appendLine("Avg Screenshot Duration: ${avgDur}ms")
            appendLine("Interval Short Error Count: $shortErrs")
            appendLine("\n--- Recent pHash Diff Samples ---")
            if (recentPHashLogs.isEmpty()) {
                appendLine("No pHash diff samples recorded yet.")
            } else {
                recentPHashLogs.toList().takeLast(10).forEach { appendLine(it) }
            }
            appendLine("\n--- Recent Error Logs ---")
            if (recentErrorLogs.isEmpty()) {
                appendLine("No short-interval errors recorded.")
            } else {
                recentErrorLogs.toList().takeLast(10).forEach { appendLine(it) }
            }
        }.toString()
    }

    /**
     * Reads the entire telemetry file contents.
     */
    fun getLogFileContent(context: Context): String {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) {
                file.readText()
            } else {
                "Telemetry log file is empty or does not exist."
            }
        } catch (e: Exception) {
            "Error reading log file: ${e.message}"
        }
    }

    /**
     * Clears all counters and deletes the telemetry log file.
     */
    fun clearLogs(context: Context) {
        totalScreenshotCalls.set(0)
        successScreenshotCalls.set(0)
        failedScreenshotCalls.set(0)
        shortIntervalErrorCount.set(0)
        totalScreenshotDurationMs.set(0L)
        recentDurations.clear()
        recentPHashLogs.clear()
        recentErrorLogs.clear()

        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.delete()
            val oldFile = File(context.filesDir, "$FILE_NAME.old")
            if (oldFile.exists()) oldFile.delete()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error clearing log files", e)
        }
    }
}
