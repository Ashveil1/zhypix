package com.example.service

import com.example.api.*

object ChatSummarizationService {
    suspend fun summarizeHistory(
        history: List<Content>,
        provider: String,
        apiKey: String,
        baseUrl: String,
        modelName: String
    ): String? {
        val textToSummarize = history.mapNotNull { content ->
            val role = content.role
            val text = content.parts.mapNotNull { it.text }.joinToString(" ")
            if (role != null && text.isNotBlank()) "$role: $text" else null
        }.joinToString("\n")
        
        val summaryPrompt = "Please carefully summarize the following conversational history. Keep all vital facts, user preferences, and the current state or goal of the ongoing task. Keep it concise to save tokens.\n\n$textToSummarize"

        var lastErr: Exception? = null
        for (attempt in 1..3) {
            try {
                if (provider == "Gemini") {
                    val request = GenerateContentRequest(
                        contents = listOf(Content(parts = listOf(Part(text = summaryPrompt)), role = "user"))
                    )
                    val fullPath = "${baseUrl.removeSuffix("/")}/v1beta/models/$modelName:generateContent"
                    val response = RetrofitClient.getService(baseUrl).generateContent(
                        url = fullPath,
                        apiKey = apiKey,
                        request = request
                    )
                    return response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                } else {
                    val openAiMessages = listOf(
                        mapOf<String, Any>("role" to "user", "content" to summaryPrompt)
                    )
                    val request = mapOf<String, Any>(
                        "model" to modelName,
                        "messages" to openAiMessages
                    )
                    val trimmedPath = baseUrl.removeSuffix("/")
                    val fullPath = if (trimmedPath.endsWith("/v1/chat/completions")) trimmedPath
                        else if (trimmedPath.endsWith("/v1")) "$trimmedPath/chat/completions"
                        else "$trimmedPath/v1/chat/completions"
                    
                    val authHeader = if (apiKey.isNotBlank()) "Bearer $apiKey" else null
                    val response = RetrofitClient.getService(baseUrl).generateOpenAiContent(
                        url = fullPath,
                        authHeader = authHeader,
                        request = request
                    )
                    return response.choices?.firstOrNull()?.message?.content?.toString()
                }
            } catch (e: Exception) {
                lastErr = e
                if (attempt < 3) {
                    kotlinx.coroutines.delay(attempt * 1000L)
                }
            }
        }
        lastErr?.printStackTrace()
        return null
    }
}
