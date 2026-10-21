package com.zhypix.fresh.agent

import org.junit.Assert.*
import org.junit.Test

class LoopGuardTest {

    @Test
    fun `third identical action on identical screen is stuck`() {
        val g = LoopGuard(maxIdenticalRepeats = 3)
        assertFalse(g.recordAndIsStuck("click:100,200", "sigA"))
        assertFalse(g.recordAndIsStuck("click:100,200", "sigA"))
        assertTrue(g.recordAndIsStuck("click:100,200", "sigA"))
    }

    @Test
    fun `changed screen resets the stuck pattern`() {
        val g = LoopGuard(maxIdenticalRepeats = 3)
        assertFalse(g.recordAndIsStuck("click:100,200", "sigA"))
        assertFalse(g.recordAndIsStuck("click:100,200", "sigA"))
        assertFalse(g.recordAndIsStuck("click:100,200", "sigB"))
        assertFalse(g.recordAndIsStuck("click:100,200", "sigC"))
    }

    @Test
    fun `different actions on same screen are fine`() {
        val g = LoopGuard(maxIdenticalRepeats = 3)
        assertFalse(g.recordAndIsStuck("click:100,200", "sigA"))
        assertFalse(g.recordAndIsStuck("drag:1,2,3,4", "sigA"))
        assertFalse(g.recordAndIsStuck("click:50,60", "sigA"))
    }

    @Test
    fun `reset clears history`() {
        val g = LoopGuard(maxIdenticalRepeats = 3)
        g.recordAndIsStuck("click:1,2", "sigA")
        g.recordAndIsStuck("click:1,2", "sigA")
        g.reset()
        assertFalse(g.recordAndIsStuck("click:1,2", "sigA"))
    }
}
