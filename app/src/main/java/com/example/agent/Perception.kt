package com.example.agent

import com.example.service.ZhypixAccessibilityService

/**
 * Perception stage of the agent loop (see docs/SCREEN_CONTROL_REBUILD.md).
 * Accessibility-tree-first: the tree is the primary observation, screenshots
 * are taken by the caller only when the active model has vision.
 */
object Perception {

    data class Snapshot(
        val hierarchy: String,
        val settleMs: Long,
        val captureMs: Long
    )

    /**
     * Waits for the accessibility event stream to go quiet, then captures the
     * window hierarchy exactly once. No fixed sleeps: typical taps settle in
     * ~150-300ms, app launches break early instead of always waiting 1200ms.
     */
    suspend fun settleAndSnapshot(
        service: ZhypixAccessibilityService,
        isAppLaunch: Boolean = false,
        quietMs: Long = 120L
    ): Snapshot {
        val capMs = if (isAppLaunch) 2500L else 900L
        val settleMs = service.awaitEventQuiet(quietMs = quietMs, maxWaitMs = capMs)
        val t0 = System.currentTimeMillis()
        val hierarchy = service.getActiveWindowHierarchy()
        return Snapshot(hierarchy, settleMs, System.currentTimeMillis() - t0)
    }
}
