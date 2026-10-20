package com.example

import com.example.agent.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentMemoryTest {
    private fun mem() = AgentMemory(JsonListStore(File(System.getProperty("java.io.tmpdir"), "mem-${System.nanoTime()}/m.json")))

    @Test fun rememberDedupesAndRejectsBlank() {
        val m = mem()
        assertNull(m.remember("   ", 1))
        val a = m.remember("My sister Mali lives in Chiang Mai", 1)!!
        assertEquals(a.id, m.remember("my sister mali lives in chiang mai", 2)!!.id)
        assertEquals(1, m.all().size)
    }

    @Test fun recallRanksByOverlapThenRecency() {
        val m = mem()
        m.remember("Favorite coffee is flat white", 1)
        m.remember("Mali lives in Chiang Mai", 2)
        m.remember("Mali birthday is in March", 3)
        assertEquals("Mali lives in Chiang Mai", m.recall("where does Mali live in Chiang Mai").first().text)
        assertEquals("Mali birthday is in March", m.recall("Mali").first().text)
        assertTrue(m.recall("").isEmpty())
        assertTrue(m.recall("the of").isEmpty())
        assertTrue(m.recall("quantum").isEmpty())
    }

    @Test fun forgetRemovesForGood() {
        val dir = File(System.getProperty("java.io.tmpdir"), "mem-${System.nanoTime()}")
        val m = AgentMemory(JsonListStore(File(dir, "m.json")))
        val a = m.remember("Mali lives in Chiang Mai", 1)!!
        m.remember("Mali likes tea", 2)
        m.remember("Wifi password hint is blue", 3)
        assertTrue(m.forget(a.id)); assertFalse(m.forget(a.id))
        assertEquals(1, m.forgetMatching("Mali"))
        assertEquals(listOf("Wifi password hint is blue"), AgentMemory(JsonListStore(File(dir, "m.json"))).all().map { it.text })
    }

    @Test fun capsLengthAndCount() {
        val m = AgentMemory(JsonListStore(File(System.getProperty("java.io.tmpdir"), "mem-${System.nanoTime()}/m.json")), maxNotes = 3, maxLength = 10)
        (1..5).forEach { m.remember("${it}x" + "y".repeat(20), it.toLong()) }
        assertEquals(3, m.all().size)
        assertTrue(m.all().all { it.text.length <= 10 })
    }
}
