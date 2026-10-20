package com.example.agent

import org.json.JSONObject

data class NotificationEntry(val app: String, val title: String, val text: String, val postedAt: Long) {
    fun toJson(): JSONObject = JSONObject().put("app", app).put("title", title).put("text", text).put("at", postedAt)
    companion object {
        fun fromJson(o: JSONObject): NotificationEntry? = try {
            NotificationEntry(o.getString("app"), o.getString("title"), o.getString("text"), o.getLong("at"))
        } catch (e: Exception) { null }
    }
}

/**
 * Recent notifications kept only on this device. Verification codes are masked before saving.
 * Nothing here is sent anywhere by itself; it is read only through the user's own agent tool.
 */
class NotificationLog(private val store: JsonListStore, private val maxEntries: Int = 100, private val maxAgeMs: Long = 24 * 3600_000L) {
    fun add(app: String, title: String?, text: String?, ongoing: Boolean, now: Long): NotificationEntry? {
        if (ongoing) return null
        val t = redact(title.orEmpty().trim()).take(120)
        val body = redact(text.orEmpty().trim()).take(300)
        if (t.isEmpty() && body.isEmpty()) return null
        val entry = NotificationEntry(app.take(80), t, body, now)
        val kept = fresh(now) .filterNot { it.app == entry.app && it.title == entry.title && it.text == entry.text }
        store.write((kept + entry).takeLast(maxEntries).map { it.toJson() })
        return entry
    }

    /** Newest first, optionally limited to one app label (case-insensitive substring). */
    fun recent(now: Long, app: String? = null, limit: Int = 10): List<NotificationEntry> =
        fresh(now).filter { app.isNullOrBlank() || it.app.contains(app, ignoreCase = true) }
            .sortedByDescending { it.postedAt }.take(limit.coerceIn(1, 50))

    fun clear() = store.write(emptyList())

    private fun fresh(now: Long) = store.read().mapNotNull { NotificationEntry.fromJson(it) }.filter { now - it.postedAt <= maxAgeMs }

    companion object {
        private val CODE_HINT = Regex("(?i)(code|otp|passcode|pin|verification|verify|รหัส|ยืนยัน)")
        private val DIGITS = Regex("\\b\\d{4,8}\\b")
        fun redact(s: String): String = if (CODE_HINT.containsMatchIn(s)) DIGITS.replace(s, "[hidden]") else s
    }
}
