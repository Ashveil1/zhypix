package com.example.agent

import com.example.service.ActionResult
import com.example.service.GestureMapper
import com.example.service.ZhypixAccessibilityService

/**
 * Action stage of the agent loop (see docs/SCREEN_CONTROL_REBUILD.md).
 * Single entry point that runs one action and reports how long it took, so
 * the loop can log per-step latency and tune settle/verify behavior on real
 * numbers instead of guesses.
 *
 * Routing itself lives in GestureMapper (click -> ElementResolver ->
 * ACTION_CLICK -> bounded-jitter gesture; scroll -> native ACTION_SCROLL_*;
 * type -> focused-node-first input).
 */
object ActionExecutor {

    data class Outcome(
        val result: ActionResult,
        val executeMs: Long
    )

    suspend fun execute(
        service: ZhypixAccessibilityService?,
        actionType: String,
        target: String,
        context: android.content.Context? = null
    ): Outcome {
        val start = System.currentTimeMillis()
        val result = GestureMapper.executeAction(service, actionType, target, context)
        return Outcome(result, System.currentTimeMillis() - start)
    }
}
