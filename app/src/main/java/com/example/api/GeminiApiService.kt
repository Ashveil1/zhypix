package com.example.api

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class GenerateContentRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null,
    val tools: List<Map<String, Any>>? = null,
    val systemInstruction: Content? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    val parts: List<Part>,
    val role: String? = null
)

@JsonClass(generateAdapter = true)
data class Part(
    val text: String? = null,
    val functionCall: FunctionCall? = null,
    val functionResponse: FunctionResponse? = null,
    val inlineData: InlineData? = null
)

@JsonClass(generateAdapter = true)
data class InlineData(
    val mimeType: String,
    val data: String // Base64 encoded string
)

@JsonClass(generateAdapter = true)
data class FunctionCall(
    val name: String,
    val args: Map<String, Any>? = null
)

@JsonClass(generateAdapter = true)
data class FunctionResponse(
    val name: String,
    val response: Map<String, Any>
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val thinkingConfig: ThinkingConfig? = null,
    val maxOutputTokens: Int? = null
)

@JsonClass(generateAdapter = true)
data class ThinkingConfig(
    val thinkingLevel: String? = "HIGH"
)

@JsonClass(generateAdapter = true)
data class SafetyRating(
    val category: String,
    val probability: String,
    val blocked: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class PromptFeedback(
    val blockReason: String? = null,
    val safetyRatings: List<SafetyRating>? = null
)

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    val candidates: List<Candidate>? = null,
    val promptFeedback: PromptFeedback? = null
)

@JsonClass(generateAdapter = true)
data class Candidate(
    val content: Content? = null,
    val finishReason: String? = null,
    val safetyRatings: List<SafetyRating>? = null,
    val groundingMetadata: GroundingMetadata? = null
)

@JsonClass(generateAdapter = true)
data class GroundingMetadata(
    val webSearchQueries: List<String>? = null,
    val groundingChunks: List<GroundingChunk>? = null,
    val searchEntryPoint: SearchEntryPoint? = null
)

@JsonClass(generateAdapter = true)
data class GroundingChunk(
    val web: WebSource? = null
)

@JsonClass(generateAdapter = true)
data class WebSource(
    val uri: String? = null,
    val title: String? = null
)

@JsonClass(generateAdapter = true)
data class SearchEntryPoint(
    val renderedContent: String? = null
)

@JsonClass(generateAdapter = true)
data class CountTokensRequest(
    val contents: List<Content>,
    val systemInstruction: Content? = null
)

@JsonClass(generateAdapter = true)
data class CountTokensResponse(
    val totalTokens: Int? = null
)

@JsonClass(generateAdapter = true)
data class GeminiModelInfo(
    val name: String,
    val displayName: String? = null,
    val inputTokenLimit: Int? = null,
    val outputTokenLimit: Int? = null
)

@JsonClass(generateAdapter = true)
data class GeminiModelsResponse(
    val models: List<GeminiModelInfo>? = null
)

@JsonClass(generateAdapter = true)
data class ModelInfoResponse(
    val name: String? = null,
    val inputTokenLimit: Int? = null,
    val outputTokenLimit: Int? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiModelInfo(
    val id: String? = null,
    val name: String? = null,
    val context_length: Int? = null,
    val max_position_embeddings: Int? = null
) {
    val modelId: String
        get() = id ?: name ?: ""
}

@JsonClass(generateAdapter = true)
data class OpenAiModelsResponse(
    val data: List<OpenAiModelInfo>? = null
)

// OpenAI Compatible classes
@JsonClass(generateAdapter = true)
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val tools: List<OpenAiTool>? = null,
    val tool_choice: String? = null,
    val reasoning_effort: String? = null,
    val max_tokens: Int? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiMessage(
    val role: String,
    val content: Any? = null, // Can be String or List<Map<String, Any>>
    val tool_calls: List<OpenAiToolCall>? = null,
    val tool_call_id: String? = null,
    val name: String? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiTool(
    val type: String = "function",
    val function: OpenAiFunction
)

@JsonClass(generateAdapter = true)
data class OpenAiFunction(
    val name: String,
    val description: String?,
    val parameters: Map<String, Any>?
)

@JsonClass(generateAdapter = true)
data class OpenAiToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiFunctionCall
)

@JsonClass(generateAdapter = true)
data class OpenAiFunctionCall(
    val name: String,
    val arguments: String // JSON string
)

@JsonClass(generateAdapter = true)
data class OpenAiChatResponse(
    val choices: List<OpenAiChoice>? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiChoice(
    val message: OpenAiMessage? = null,
    val finish_reason: String? = null
)

interface GeminiApiService {
    @retrofit2.http.GET
    suspend fun getGeminiModels(
        @retrofit2.http.Url url: String,
        @Query("key") apiKey: String
    ): GeminiModelsResponse

    @retrofit2.http.GET
    suspend fun getModelInfo(
        @retrofit2.http.Url url: String,
        @Query("key") apiKey: String
    ): ModelInfoResponse

    @POST
    suspend fun countTokens(
        @retrofit2.http.Url url: String,
        @Query("key") apiKey: String,
        @Body request: CountTokensRequest
    ): CountTokensResponse

    @POST
    suspend fun generateContent(
        @retrofit2.http.Url url: String,
        @Query("key") apiKey: String, // query parameter for Gemini
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
    
    @retrofit2.http.GET
    suspend fun getOpenAiModelInfo(
        @retrofit2.http.Url url: String,
        @retrofit2.http.Header("Authorization") authHeader: String
    ): OpenAiModelInfo

    @retrofit2.http.GET
    suspend fun getOpenAiModels(
        @retrofit2.http.Url url: String,
        @retrofit2.http.Header("Authorization") authHeader: String?
    ): OpenAiModelsResponse

    @retrofit2.http.GET
    suspend fun getClaudeModels(
        @retrofit2.http.Url url: String,
        @retrofit2.http.Header("x-api-key") apiKey: String,
        @retrofit2.http.Header("anthropic-version") version: String
    ): OpenAiModelsResponse

    @POST
    @retrofit2.http.Headers("Content-Type: application/json")
    suspend fun generateOpenAiContent(
        @retrofit2.http.Url url: String,
        @retrofit2.http.Header("Authorization") authHeader: String?,
        @Body request: Map<String, @JvmSuppressWildcards Any>
    ): OpenAiChatResponse
}

class AnyAdapter {
    @com.squareup.moshi.FromJson
    fun fromJson(reader: com.squareup.moshi.JsonReader): Any? {
        return reader.readJsonValue()
    }

    @com.squareup.moshi.ToJson
    fun toJson(writer: com.squareup.moshi.JsonWriter, value: Any?) {
        when (value) {
            null -> writer.nullValue()
            is Boolean -> writer.value(value)
            is Number -> writer.value(value)
            is String -> writer.value(value)
            is List<*> -> {
                writer.beginArray()
                for (item in value) {
                    toJson(writer, item)
                }
                writer.endArray()
            }
            is Map<*, *> -> {
                writer.beginObject()
                for ((k, v) in value) {
                    writer.name(k.toString())
                    toJson(writer, v)
                }
                writer.endObject()
            }
            else -> writer.value(value.toString())
        }
    }
}

object RetrofitClient {
    private val moshi = Moshi.Builder()
        .add(AnyAdapter())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val services = mutableMapOf<String, GeminiApiService>()

    fun getService(baseUrl: String): GeminiApiService {
        val fixedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return services.getOrPut(fixedUrl) {
            Retrofit.Builder()
                .baseUrl(fixedUrl)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(GeminiApiService::class.java)
        }
    }
}

