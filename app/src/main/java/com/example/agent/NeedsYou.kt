package com.example.agent

import org.json.JSONObject
import java.util.UUID

/** A task that stopped because only the user can continue it (confirmation, login, captcha). Survives app restarts. */
data class NeedsYouItem(
    val id: String,
    val task: String,
    val kind: String,
    val message: String,
    val createdAt: Long,
    val resolvedAt: Long? = null
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("task", task).put("kind", kind)
        .put("message", message).put("created", createdAt).apply { resolvedAt?.let { put("resolved", it) } }

    companion object {
        fun fromJson(o: JSONObject): NeedsYouItem? = try {
            NeedsYouItem(o.getString("id"), o.getString("task"), o.getString("kind"), o.getString("message"),
                o.getLong("created"), if (o.has("resolved")) o.getLong("resolved") else null)
        } catch (e: Exception) { null }

        /** Only terminal states that genuinely wait for the user are recorded. */
        fun kindOf(terminal: ScreenAction.Terminal): String? = when (terminal) {
            is ScreenAction.NeedsConfirmation -> "confirmation"
            is ScreenAction.NeedLogin -> "login"
            is ScreenAction.NeedLoginDetails -> "login_details"
            is ScreenAction.EncounteredCaptcha -> "captcha"
            else -> null
        }
    }
}

class NeedsYouStore(private val store: JsonListStore, private val maxKept: Int = 50) {
    private fun all(): List<NeedsYouItem> = store.read().mapNotNull { NeedsYouItem.fromJson(it) }
    fun pending(): List<NeedsYouItem> = all().filter { it.resolvedAt == null }.sortedBy { it.createdAt }

    /** Same task + kind already waiting is refreshed, not duplicated. */
    fun record(task: String, terminal: ScreenAction.Terminal, now: Long): NeedsYouItem? {
        val kind = NeedsYouItem.kindOf(terminal) ?: return null
        val existing = all()
        val dup = existing.firstOrNull { it.resolvedAt == null && it.task == task && it.kind == kind }
        val item = (dup ?: NeedsYouItem(UUID.randomUUID().toString(), task, kind, terminal.message, now)).copy(message = terminal.message)
        val merged = existing.filter { it.id != item.id } + item
        val resolved = merged.filter { it.resolvedAt != null }.sortedByDescending { it.resolvedAt }.take(maxKept)
        store.write((merged.filter { it.resolvedAt == null } + resolved).map { it.toJson() })
        return item
    }

    fun resolve(id: String, now: Long): Boolean {
        val items = all()
        if (items.none { it.id == id && it.resolvedAt == null }) return false
        store.write(items.map { if (it.id == id) it.copy(resolvedAt = now) else it }.map { it.toJson() })
        return true
    }
}
