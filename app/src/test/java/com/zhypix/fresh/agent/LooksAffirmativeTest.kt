package com.zhypix.fresh.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LooksAffirmativeTest {

    @Test
    fun commonAffirmativesAreAccepted() {
        listOf(
            "yes", "Yes", "OK", "ok", "confirm", "go ahead", "proceed", "continue",
            "ตกลง", "ใช่", "โอเค", "ยืนยัน", "ทำเลย", "ได้", "เอา", "เอาเลย", "ต่อเลย", "จัดไป", "ครับ", "ค่ะ"
        ).forEach {
            assertTrue("expected affirmative: '$it'", AgentLoop.looksAffirmative(it))
        }
    }

    @Test
    fun refusalsAndAmbiguityAreNotAccepted() {
        listOf("", "no", "stop", "wait", "หยุด", "ไม่", "เดี๋ยว", "why would you do that").forEach {
            assertFalse("expected non-affirmative: '$it'", AgentLoop.looksAffirmative(it))
        }
    }
}
