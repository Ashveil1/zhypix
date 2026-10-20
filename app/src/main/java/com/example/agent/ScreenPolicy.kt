package com.example.agent

/** Numeric design values stated in the supplied architecture study. */
object ScreenPolicy {
    const val MAX_STEPS = 150
    const val STAGE_RETRY_MS = 1000L
    const val BILLING_RETRY_MS = 1500L
    const val TAP_MS = 125L
    const val DOUBLE_GAP_MS = 175L
    const val LONG_PRESS_EXTRA_MS = 100L
    const val SWIPE_MS = 860L
    const val TALKBACK_TAP_MS = 75L
    val talkBackStartsMs: List<Long> get() = listOf(0L, 475L, 675L)
    const val AGENT_IMAGE_LONG_SIDE = 780
    const val BUBBLE_IMAGE_LONG_SIDE = 540
    const val JPEG_QUALITY = 75
    const val CAPTURE_QUEUE_SLOTS = 3
    // Not specified by the study: explicit local choices, not claimed parity.
    const val DEFAULT_POST_ACTION_MS = 0L
    const val RETRIES_PER_STAGE = 1
    const val GESTURE_TIMEOUT_MS = 5000L
    const val CAPTURE_TIMEOUT_MS = 15000L
    const val CAPTURE_MIN_INTERVAL_MS = 350L
    const val TREE_NODE_LIMIT = 400
    const val TREE_DEPTH_LIMIT = 20
}
