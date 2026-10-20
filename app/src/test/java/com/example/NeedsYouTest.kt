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
class NeedsYouTest {
    private fun store() = NeedsYouStore(JsonListStore(File(System.getProperty("java.io.tmpdir"), "ny-${System.nanoTime()}/n.json")))

    @Test fun recordsOnlyUserWaitingStates() {
        val s = store()
        assertNull(s.record("t", ScreenAction.Finished("done"), 1))
        assertNull(s.record("t", ScreenAction.FailedToCompleteTask("x"), 1))
        assertNotNull(s.record("buy", ScreenAction.NeedsConfirmation("Confirm purchase?"), 1))
        assertNotNull(s.record("log in", ScreenAction.NeedLogin("Sign in"), 2))
        assertEquals(listOf("confirmation", "login"), s.pending().map { it.kind })
    }

    @Test fun duplicateIsRefreshedAndSurvivesNewInstance() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ny-${System.nanoTime()}")
        val a = NeedsYouStore(JsonListStore(File(dir, "n.json")))
        a.record("buy", ScreenAction.NeedsConfirmation("old"), 1)
        a.record("buy", ScreenAction.NeedsConfirmation("new"), 2)
        val b = NeedsYouStore(JsonListStore(File(dir, "n.json")))
        assertEquals(1, b.pending().size)
        assertEquals("new", b.pending().single().message)
    }

    @Test fun resolveRemovesFromPending() {
        val s = store()
        val item = s.record("buy", ScreenAction.NeedsConfirmation("c"), 1)!!
        assertTrue(s.resolve(item.id, 5))
        assertFalse(s.resolve(item.id, 6))
        assertTrue(s.pending().isEmpty())
    }
}
