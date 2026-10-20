package com.example.live

import com.example.service.ZhypixAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Streams screen frames into a Live session as realtime video input.
 *
 * Frames come from the existing accessibility screenshot pipeline
 * (which already handles scaling, JPEG quality and rate limits). A frame
 * is sent only when the screen hash changes, and streaming pauses while
 * the session is executing a tool action to avoid feeding the model
 * mid-gesture frames.
 */
class LiveFrameStreamer(
    private val session: LiveSessionManager,
    private val serviceProvider: () -> ZhypixAccessibilityService?,
    private val frameIntervalMs: Long = DEFAULT_FRAME_INTERVAL_MS,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Volatile
    var lastSentHash: Long = 0L
        private set

    @Volatile
    var framesSent: Int = 0
        private set

    val isRunning: Boolean get() = job?.isActive == true

    fun start() {
        if (isRunning) return
        job = scope.launch {
            while (isActive) {
                delay(frameIntervalMs)
                tick()
            }
        }
    }

    /** One capture/send cycle. Extracted for testability. */
    internal suspend fun tick() {
        val state = session.state.value
        if (state != LiveSessionManager.State.LISTENING && state != LiveSessionManager.State.SPEAKING) return
        val service = serviceProvider() ?: return
        val frame = service.observation.screenshot() ?: return
        // Content signature suppresses byte-identical frames. No screenshot is logged.
        val signature = frame.base64.hashCode().toLong()
        if (signature != lastSentHash) {
            lastSentHash = signature
            framesSent++
            session.sendVideoFrame(frame.base64)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        const val DEFAULT_FRAME_INTERVAL_MS = 1000L
    }
}
