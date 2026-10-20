package com.example.agent

import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/** A saved task the agent runs on a schedule. Either every N minutes or daily at a local time. */
data class Routine(
    val id: String,
    val name: String,
    val task: String,
    val intervalMinutes: Int?,
    val dailyMinuteOfDay: Int?,
    val enabled: Boolean = true,
    val lastRunAt: Long? = null
) {
    init {
        require(name.isNotBlank() && task.isNotBlank()) { "Routine needs a name and a task" }
        require((intervalMinutes == null) != (dailyMinuteOfDay == null)) { "Choose either an interval or a daily time" }
        intervalMinutes?.let { require(it in MIN_INTERVAL..MAX_INTERVAL) { "Interval must be $MIN_INTERVAL..$MAX_INTERVAL minutes" } }
        dailyMinuteOfDay?.let { require(it in 0..1439) { "Daily time must be 00:00..23:59" } }
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("task", task)
        .put("enabled", enabled).apply {
            intervalMinutes?.let { put("interval", it) }
            dailyMinuteOfDay?.let { put("daily", it) }
            lastRunAt?.let { put("last", it) }
        }

    companion object {
        const val MIN_INTERVAL = 15 // Android will not reliably wake more often than this.
        const val MAX_INTERVAL = 7 * 24 * 60
        fun create(name: String, task: String, intervalMinutes: Int? = null, dailyMinuteOfDay: Int? = null) =
            Routine(UUID.randomUUID().toString(), name.trim(), task.trim(), intervalMinutes, dailyMinuteOfDay)

        fun fromJson(o: JSONObject): Routine? = try {
            Routine(o.getString("id"), o.getString("name"), o.getString("task"),
                if (o.has("interval")) o.getInt("interval") else null,
                if (o.has("daily")) o.getInt("daily") else null,
                o.optBoolean("enabled", true), if (o.has("last")) o.getLong("last") else null)
        } catch (e: Exception) { null }
    }
}

object RoutineSchedule {
    /** Next time this routine is due strictly after [now]; null when disabled. A missed run is never replayed in bulk. */
    fun nextDue(routine: Routine, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        if (!routine.enabled) return null
        routine.intervalMinutes?.let { minutes ->
            val step = minutes * 60_000L
            val base = routine.lastRunAt ?: return now // never ran: due now
            return maxOf(base + step, now)
        }
        val minute = routine.dailyMinuteOfDay ?: return null
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minute / 60); set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** Due means: interval elapsed, or a daily slot passed since the last run (at most one catch-up run). */
    fun isDue(routine: Routine, now: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
        if (!routine.enabled) return false
        routine.intervalMinutes?.let { return routine.lastRunAt == null || now - routine.lastRunAt >= it * 60_000L }
        val minute = routine.dailyMinuteOfDay ?: return false
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minute / 60); set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis > now) cal.add(Calendar.DAY_OF_YEAR, -1)
        return (routine.lastRunAt ?: 0L) < cal.timeInMillis
    }
}

class RoutineStore(private val store: JsonListStore) {
    fun all(): List<Routine> = store.read().mapNotNull { Routine.fromJson(it) }
    fun save(routine: Routine) { store.write((all().filter { it.id != routine.id } + routine).map { it.toJson() }) }
    fun delete(id: String) { store.write(all().filter { it.id != id }.map { it.toJson() }) }
    fun markRan(id: String, at: Long) { all().firstOrNull { it.id == id }?.let { save(it.copy(lastRunAt = at)) } }
    fun due(now: Long): List<Routine> = all().filter { RoutineSchedule.isDue(it, now) }
}
