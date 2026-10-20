package com.example.agent

import org.json.JSONObject
import java.util.UUID

data class MemoryNote(val id: String, val text: String, val createdAt: Long) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("created", createdAt)
    companion object {
        fun fromJson(o: JSONObject): MemoryNote? = try { MemoryNote(o.getString("id"), o.getString("text"), o.getLong("created")) } catch (e: Exception) { null }
    }
}

/** Facts the user asked the agent to keep. Recall is plain keyword scoring, forget deletes for good. */
class AgentMemory(private val store: JsonListStore, private val maxNotes: Int = 500, private val maxLength: Int = 500) {
    fun all(): List<MemoryNote> = store.read().mapNotNull { MemoryNote.fromJson(it) }

    fun remember(text: String, now: Long): MemoryNote? {
        val clean = text.trim().take(maxLength)
        if (clean.isBlank()) return null
        val existing = all()
        existing.firstOrNull { it.text.equals(clean, ignoreCase = true) }?.let { return it }
        val note = MemoryNote(UUID.randomUUID().toString(), clean, now)
        store.write((existing + note).takeLast(maxNotes).map { it.toJson() })
        return note
    }

    /** Notes sharing the most words with the query, best first. Ties go to the newer note. Nothing matches an empty query. */
    fun recall(query: String, limit: Int = 5): List<MemoryNote> {
        val words = tokens(query)
        if (words.isEmpty()) return emptyList()
        return all().map { it to tokens(it.text).count { w -> w in words } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MemoryNote, Int>> { it.second }.thenByDescending { it.first.createdAt })
            .take(limit).map { it.first }
    }

    fun forget(id: String): Boolean {
        val items = all()
        if (items.none { it.id == id }) return false
        store.write(items.filter { it.id != id }.map { it.toJson() })
        return true
    }

    /** Forget every note matching the query. Returns how many were removed. */
    fun forgetMatching(query: String): Int {
        val ids = recall(query, Int.MAX_VALUE).map { it.id }.toSet()
        if (ids.isEmpty()) return 0
        store.write(all().filter { it.id !in ids }.map { it.toJson() })
        return ids.size
    }

    private fun tokens(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in STOP }.toSet()
    private companion object { val STOP = setOf("the", "and", "for", "is", "my", "me", "of", "to", "in", "on", "at", "it", "a", "an") }
}
