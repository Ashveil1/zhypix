package com.zhypix.fresh.agent

/**
 * Stops the loop when the model is stuck repeating the same action against an
 * unchanged screen - the failure mode users experience as "it loops forever".
 */
class LoopGuard(
    private val maxIdenticalRepeats: Int = 3,
    private val historySize: Int = 12
) {
    private val recent = ArrayDeque<Pair<String, String>>()

    /** Returns true when this action+screen combination has repeated too often. */
    fun recordAndIsStuck(actionKey: String, screenSignature: String): Boolean {
        val repeats = recent.count { it.first == actionKey && it.second == screenSignature } + 1
        recent.addLast(actionKey to screenSignature)
        while (recent.size > historySize) recent.removeFirst()
        return repeats >= maxIdenticalRepeats
    }

    fun reset() = recent.clear()
}
