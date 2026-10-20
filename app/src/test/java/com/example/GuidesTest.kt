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
class GuidesTest {
    private fun store() = GuideStore(JsonListStore(File(System.getProperty("java.io.tmpdir"), "g-${System.nanoTime()}/g.json")))

    @Test fun rejectsEmptyAndCleansSteps() {
        val s = store()
        assertNull(s.save(" ", listOf("a"), 1))
        assertNull(s.save("t", listOf(" ", ""), 1))
        assertEquals(listOf("Open Settings", "Tap Wi-Fi"), s.save("Wifi", listOf(" Open Settings ", "", "Tap Wi-Fi"), 1)!!.steps)
    }

    @Test fun sameTitleReplaces() {
        val s = store()
        val a = s.save("Send photo", listOf("one"), 1)!!
        val b = s.save("send PHOTO", listOf("two"), 2)!!
        assertEquals(a.id, b.id); assertEquals(1, s.all().size); assertEquals(listOf("two"), s.all().single().steps)
    }

    @Test fun findExactSubstringWords() {
        val s = store()
        s.save("Connect to office Wi-Fi", listOf("x"), 1); s.save("Change ringtone", listOf("y"), 2)
        assertEquals("Change ringtone", s.find("change ringtone")!!.title)
        assertEquals("Connect to office Wi-Fi", s.find("office")!!.title)
        assertEquals("Change ringtone", s.find("how to set ringtone please")!!.title)
        assertNull(s.find("banana")); assertNull(s.find(""))
    }

    @Test fun textAndDelete() {
        val s = store(); val g = s.save("T", listOf("a", "b"), 1)!!
        assertEquals("T\n1. a\n2. b", g.asText())
        assertTrue(s.delete(g.id)); assertFalse(s.delete(g.id))
    }
}
