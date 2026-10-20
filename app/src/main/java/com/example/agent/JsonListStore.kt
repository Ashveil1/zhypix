package com.example.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Small atomic JSON-array file store used by routines, needs-you items and memories.
 * Kept separate from Room on purpose: the Room database uses destructive migration, so a schema bump would wipe user data.
 */
class JsonListStore(private val file: File) {
    @Synchronized fun read(): List<JSONObject> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        } catch (e: Exception) {
            // Keep the unreadable file for inspection instead of silently overwriting it.
            file.renameTo(File(file.parentFile, file.name + ".corrupt"))
            emptyList()
        }
    }

    @Synchronized fun write(items: List<JSONObject>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray(items).toString())
        if (!tmp.renameTo(file)) { file.delete(); require(tmp.renameTo(file)) { "Could not save ${file.name}" } }
    }
}
