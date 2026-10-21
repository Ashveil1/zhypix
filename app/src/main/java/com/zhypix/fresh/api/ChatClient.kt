package com.zhypix.fresh.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Minimal OpenAI-compatible chat-completions client with vision support.
 * The provider (base URL + key + model) is always user-configured; nothing
 * is hardcoded. The API key is never logged and is redacted from errors.
 */
class ChatClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) {
    class ApiException(val httpCode: Int, message: String) : Exception(message)

    sealed class Image {
        data class Jpeg(val base64: String) : Image()
    }

    data class Turn(val text: String, val imageBase64Jpeg: String? = null)

    private val moshi = Moshi.Builder().build()
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * One completion round. [history] entries are ("user"|"assistant"|"system")
     * to turns; images only make sense on user turns.
     */
    suspend fun complete(history: List<Pair<String, Turn>>): String = withContext(Dispatchers.IO) {
        val messages = history.map { (role, turn) ->
            if (turn.imageBase64Jpeg != null && role == "user") {
                mapOf(
                    "role" to role,
                    "content" to listOf(
                        mapOf("type" to "text", "text" to turn.text),
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf("url" to "data:image/jpeg;base64,${turn.imageBase64Jpeg}")
                        )
                    )
                )
            } else {
                mapOf("role" to role, "content" to turn.text)
            }
        }
        val body = mapAdapter.toJson(
            mapOf(
                "model" to model,
                "messages" to messages,
                "max_tokens" to 1024,
                "temperature" to 0.2
            )
        ).toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, redact("HTTP ${resp.code}: ${raw.take(400)}"))
            }
            val parsed = mapAdapter.fromJson(raw)
                ?: throw ApiException(resp.code, "Empty or non-JSON response from provider")
            val text = extractContent(parsed)
                ?: throw ApiException(resp.code, redact("Response has no message content: ${raw.take(300)}"))
            text
        }
    }

    private fun extractContent(root: Map<String, Any?>): String? {
        val choices = root["choices"] as? List<*> ?: return null
        val first = choices.firstOrNull() as? Map<*, *> ?: return null
        val message = first["message"] as? Map<*, *> ?: return null
        return when (val c = message["content"]) {
            is String -> c
            is List<*> -> c.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("\n")
            else -> null
        }
    }

    private fun redact(s: String): String =
        if (apiKey.isBlank()) s else s.replace(apiKey, "***")
}
