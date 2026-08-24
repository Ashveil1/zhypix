package com.example.service

import android.util.Log

enum class VerifyStatus {
    PASSED,
    FAILED_NO_STATE_CHANGE,
    FAILED_EXPECTED_ELEMENT_MISSING,
    ESCALATE_TO_PLANNER
}

data class VerifyResult(
    val status: VerifyStatus,
    val message: String,
    val consecutiveFailures: Int = 0
)

data class PostConditionPredicate(
    val type: PredicateType,
    val target: String? = null
)

enum class PredicateType {
    ANY_STATE_CHANGE,
    ELEMENT_EXISTS,
    ELEMENT_DISAPPEARED,
    TEXT_INPUT_SUCCESS
}

object LocalPostActionVerifier {

    private const val TAG = "LocalPostVerifier"
    private const val MAX_CONSECUTIVE_FAILURES = 2

    private var consecutiveFailureCount = 0

    /**
     * Evaluates whether an action successfully brought about the expected outcome locally
     * without needing an expensive LLM round-trip.
     */
    fun verify(
        service: ZhypixAccessibilityService?,
        actionType: String,
        target: String,
        preHierarchyHash: Int,
        postHierarchyHash: Int,
        predicate: PostConditionPredicate = PostConditionPredicate(PredicateType.ANY_STATE_CHANGE)
    ): VerifyResult {
        if (service == null) {
            return VerifyResult(VerifyStatus.ESCALATE_TO_PLANNER, "Accessibility service disconnected.")
        }

        val act = actionType.uppercase().trim()

        // Global / passive actions always pass
        if (act == "OBSERVE" || act == "WAIT" || act == "SLEEP") {
            consecutiveFailureCount = 0
            return VerifyResult(VerifyStatus.PASSED, "Passive action verified.")
        }

        when (predicate.type) {
            PredicateType.ANY_STATE_CHANGE -> {
                // Check if hierarchy changed
                if (preHierarchyHash != postHierarchyHash) {
                    consecutiveFailureCount = 0
                    return VerifyResult(VerifyStatus.PASSED, "UI hierarchy updated successfully.")
                }

                // If hierarchy didn't change, re-check element existence
                consecutiveFailureCount++
                return if (consecutiveFailureCount >= MAX_CONSECUTIVE_FAILURES) {
                    VerifyResult(
                        VerifyStatus.ESCALATE_TO_PLANNER,
                        "Action $act '$target' resulted in no UI state change twice consecutively.",
                        consecutiveFailureCount
                    )
                } else {
                    VerifyResult(
                        VerifyStatus.FAILED_NO_STATE_CHANGE,
                        "Action $act '$target' resulted in no detectable UI state change.",
                        consecutiveFailureCount
                    )
                }
            }

            PredicateType.ELEMENT_EXISTS -> {
                val query = predicate.target ?: target
                val selector = ElementSelector.parse(query)
                val resolved = ElementResolver.resolve(service, selector)

                if (resolved != null) {
                    consecutiveFailureCount = 0
                    return VerifyResult(VerifyStatus.PASSED, "Expected element '$query' exists on active screen.")
                }

                consecutiveFailureCount++
                return if (consecutiveFailureCount >= MAX_CONSECUTIVE_FAILURES) {
                    VerifyResult(
                        VerifyStatus.ESCALATE_TO_PLANNER,
                        "Expected element '$query' missing after action.",
                        consecutiveFailureCount
                    )
                } else {
                    VerifyResult(
                        VerifyStatus.FAILED_EXPECTED_ELEMENT_MISSING,
                        "Expected element '$query' not yet visible on screen.",
                        consecutiveFailureCount
                    )
                }
            }

            PredicateType.ELEMENT_DISAPPEARED -> {
                val query = predicate.target ?: target
                val selector = ElementSelector.parse(query)
                val resolved = ElementResolver.resolve(service, selector)

                if (resolved == null) {
                    consecutiveFailureCount = 0
                    return VerifyResult(VerifyStatus.PASSED, "Target element '$query' successfully disappeared.")
                }

                consecutiveFailureCount++
                return VerifyResult(
                    VerifyStatus.FAILED_NO_STATE_CHANGE,
                    "Target element '$query' still visible on screen.",
                    consecutiveFailureCount
                )
            }

            PredicateType.TEXT_INPUT_SUCCESS -> {
                consecutiveFailureCount = 0
                return VerifyResult(VerifyStatus.PASSED, "Text input submitted.")
            }
        }
    }

    fun resetFailureCount() {
        consecutiveFailureCount = 0
    }
}
