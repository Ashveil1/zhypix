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
class NotificationLogTest {
    private fun log(max: Int = 100) = NotificationLog(JsonListStore(File(System.getProperty("java.io.tmpdir"), "nl-${System.nanoTime()}/n.json")), max)
    private val hour = 3600_000L

    @Test fun skipsOngoingAndEmpty() {
        val l = log()
        assertNull(l.add("Maps", "Navigating", "x", true, 1))
        assertNull(l.add("Chat", " ", null, false, 1))
        assertTrue(l.recent(2).isEmpty())
    }

    @Test fun masksVerificationCodesOnly() {
        val l = log()
        l.add("Bank", "Your code", "Your verification code is 482913", false, 1)
        l.add("Pizza", "Order", "Order 12345 is on the way", false, 2)
        val r = l.recent(3).associateBy { it.app }
        assertEquals("Your verification code is [hidden]", r.getValue("Bank").text)
        assertEquals("Order 12345 is on the way", r.getValue("Pizza").text)
    }

    @Test fun newestFirstDedupeFilterAndExpiry() {
        val l = log()
        l.add("Chat", "Mali", "hi", false, 1000)
        l.add("Chat", "Mali", "hi", false, 2000)
        l.add("Mail", "Boss", "report", false, 3000)
        assertEquals(listOf("Mail", "Chat"), l.recent(4000).map { it.app })
        assertEquals(1, l.recent(4000, app = "cha").size)
        assertTrue(l.recent(30 * hour).isEmpty())
    }

    @Test fun capsEntriesAndClears() {
        val l = log(max = 3)
        (1..6).forEach { l.add("A", "t$it", "b", false, it.toLong()) }
        assertEquals(3, l.recent(10, limit = 50).size)
        l.clear(); assertTrue(l.recent(10).isEmpty())
    }
}
