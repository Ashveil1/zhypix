package com.example.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A named, ordered how-to the user taught or approved. Plain text steps, no recorded taps or coordinates. */
data class Guide(val id: String, val title: String, val steps: List<String>, val updatedAt: Long) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("steps", JSONArray(steps)).put("updated", updatedAt)
    fun asText(): String = "$title\n" + steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
    companion object {
        fun fromJson(o: JSONObject): Guide? = try {
            val a = o.getJSONArray("steps")
            Guide(o.getString("id"), o.getString("title"), (0 until a.length()).map { a.getString(it) }, o.getLong("updated"))
        } catch (e: Exception) { null }
    }
}

class GuideStore(private val store: JsonListStore, private val maxGuides: Int = 100, private val maxSteps: Int = 40, private val maxStepLength: Int = 300) {
    fun all(): List<Guide> = store.read().mapNotNull { Guide.fromJson(it) }

    /** Same title (case-insensitive) replaces the older guide. Blank steps are dropped; a guide needs at least one. */
    fun save(title: String, steps: List<String>, now: Long): Guide? {
        val t = title.trim().take(80)
        val clean = steps.map { it.trim().take(maxStepLength) }.filter { it.isNotEmpty() }.take(maxSteps)
        if (t.isEmpty() || clean.isEmpty()) return null
        val existing = all()
        val old = existing.firstOrNull { it.title.equals(t, ignoreCase = true) }
        val guide = Guide(old?.id ?: UUID.randomUUID().toString(), t, clean, now)
        store.write((existing.filter { it.id != guide.id } + guide).takeLast(maxGuides).map { it.toJson() })
        return guide
    }

    /** Best match by title: exact, then substring, then shared words. */
    fun find(query: String): Guide? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val guides = all()
        guides.firstOrNull { it.title.lowercase() == q }?.let { return it }
        guides.firstOrNull { it.title.lowercase().contains(q) }?.let { return it }
        val words = q.split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        return guides.map { g -> g to g.title.lowercase().split(Regex("\\s+")).count { it in words } }
            .filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    fun delete(id: String): Boolean {
        val items = all()
        if (items.none { it.id == id }) return false
        store.write(items.filter { it.id != id }.map { it.toJson() }); return true
    }
}
