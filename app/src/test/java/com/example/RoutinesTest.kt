package com.example

import com.example.agent.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoutinesTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(h: Int, m: Int) = (h * 60L + m) * 60_000L + 20 * 24 * 3600_000L // day 20 since epoch, UTC

    @Test fun rejectsBadRoutines() {
        assertThrows(IllegalArgumentException::class.java) { Routine.create("a", "t") }
        assertThrows(IllegalArgumentException::class.java) { Routine.create("a", "t", intervalMinutes = 5) }
        assertThrows(IllegalArgumentException::class.java) { Routine.create("a", "t", 30, 60) }
        assertThrows(IllegalArgumentException::class.java) { Routine.create("a", "t", dailyMinuteOfDay = 1440) }
        assertThrows(IllegalArgumentException::class.java) { Routine.create(" ", "t", 30) }
    }

    @Test fun intervalDueLogic() {
        val r = Routine.create("n", "t", intervalMinutes = 60).copy(lastRunAt = at(8, 0))
        assertFalse(RoutineSchedule.isDue(r, at(8, 59)))
        assertTrue(RoutineSchedule.isDue(r, at(9, 0)))
        assertEquals(at(9, 0), RoutineSchedule.nextDue(r, at(8, 30)))
        assertTrue(RoutineSchedule.isDue(r.copy(lastRunAt = null), at(1, 0)))
        assertNull(RoutineSchedule.nextDue(r.copy(enabled = false), at(8, 30)))
    }

    @Test fun dailyRunsOnceAndCatchesUpOnce() {
        val r = Routine.create("n", "t", dailyMinuteOfDay = 9 * 60)
        assertTrue(RoutineSchedule.isDue(r.copy(lastRunAt = at(9, 0) - 86_400_000L), at(9, 5), utc))
        assertFalse(RoutineSchedule.isDue(r.copy(lastRunAt = at(9, 1)), at(23, 0), utc))
        // missed several days: still just one due run, not a backlog
        assertTrue(RoutineSchedule.isDue(r.copy(lastRunAt = at(9, 0) - 5 * 86_400_000L), at(10, 0), utc))
        assertEquals(at(9, 0) + 86_400_000L, RoutineSchedule.nextDue(r, at(9, 0), utc))
        assertEquals(at(9, 0), RoutineSchedule.nextDue(r, at(8, 0), utc))
    }

    @Test fun storeRoundTripAndCorruptFile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "routines-${System.nanoTime()}").apply { mkdirs() }
        val file = File(dir, "routines.json")
        val store = RoutineStore(JsonListStore(file))
        val r = Routine.create("Morning", "Check weather", dailyMinuteOfDay = 480)
        store.save(r); store.markRan(r.id, 123L)
        assertEquals(123L, store.all().single().lastRunAt)
        store.delete(r.id); assertTrue(store.all().isEmpty())
        file.writeText("{not json"); assertTrue(store.all().isEmpty())
        assertTrue(File(dir, "routines.json.corrupt").exists())
    }
}
