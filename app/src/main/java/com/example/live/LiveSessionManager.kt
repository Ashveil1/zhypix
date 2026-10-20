package com.example.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemini Live (BidiGenerateContent) websocket session.
 *
 * Step 1 of the Live build: text-only session (connect, setup, send text,
 * receive text). Audio streaming, screen-frame streaming and the tool
 * bridge land in later steps (see docs/GEMINI_LIVE_DESIGN.md).
 *
 * The Live model id is always supplied by the caller from the provider's
 * fetched model list - nothing model-specific is hardcoded here.
 */
class LiveSessionManager(
    private val apiKey: String,
    private val modelId: String,
    private val systemPrompt: String,
    private val baseWsUrl: String = DEFAULT_WS_URL,
    private val toolsPayload: JSONArray? = null,
) {

    enum class State { IDLE, CONNECTING, LISTENING, SPEAKING, ACTING, ERROR, CLOSED }

    data class ToolCallRequest(val id: String, val name: String, val args: JSONObject)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // websocket: no read timeout
        .build()

    private var webSocket: WebSocket? = null
    private val setupComplete = AtomicBoolean(false)

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Called on the main-safe scope for every text chunk the model emits. */
    var onModelText: ((text: String, turnComplete: Boolean) -> Unit)? = null

    /** Called when the model requests tool execution. Reply via [sendToolResponse]. */
    var onToolCalls: ((calls: List<ToolCallRequest>) -> Unit)? = null

    /** Called once the session handshake finished and input can be sent. */
    var onReady: (() -> Unit)? = null

    fun connect() {
        if (_state.value == State.CONNECTING || _state.value == State.LISTENING) return
        require(apiKey.isNotBlank()) { "Live session needs an API key" }
        require(modelId.isNotBlank()) { "Live session needs a model id from the provider model list" }

        _state.value = State.CONNECTING
        _lastError.value = null
        setupComplete.set(false)

        val url = "$baseWsUrl?key=$apiKey"
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, socketListener)
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sendSetup(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleServerMessage(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _lastError.value = t.message ?: "connection failed"
            _state.value = State.ERROR
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (_state.value != State.ERROR) _state.value = State.CLOSED
        }
    }

    private fun sendSetup(ws: WebSocket) {
        val model = if (modelId.startsWith("models/")) modelId else "models/$modelId"
        val setup = JSONObject()
            .put("setup", JSONObject()
                .put("model", model)
                .put("generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("TEXT")))
                .put("systemInstruction", JSONObject()
                    .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))))
        if (toolsPayload != null) {
            setup.getJSONObject("setup").put("tools", toolsPayload)
        }
        ws.send(setup.toString())
    }

    /** Send a user text turn. Safe to call only after onReady. */
    fun sendText(text: String, turnComplete: Boolean = true) {
        val ws = webSocket ?: return
        if (!setupComplete.get()) return
        val msg = JSONObject()
            .put("clientContent", JSONObject()
                .put("turns", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))))
                .put("turnComplete", turnComplete))
        ws.send(msg.toString())
    }

    /** Stream one screen frame (base64 JPEG) as realtime video input. */
    fun sendVideoFrame(base64Jpeg: String) {
        val ws = webSocket ?: return
        if (!setupComplete.get() || base64Jpeg.isBlank()) return
        val msg = JSONObject()
            .put("realtimeInput", JSONObject()
                .put("video", JSONObject()
                    .put("data", base64Jpeg)
                    .put("mimeType", "image/jpeg")))
        ws.send(msg.toString())
    }

    /** Answer a tool call batch. Every call id from [onToolCalls] must get a response. */
    fun sendToolResponse(responses: List<Pair<ToolCallRequest, JSONObject>>) {
        val ws = webSocket ?: return
        val arr = JSONArray()
        for ((call, payload) in responses) {
            arr.put(JSONObject()
                .put("id", call.id)
                .put("name", call.name)
                .put("response", payload))
        }
        ws.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", arr)).toString())
    }

    internal fun handleServerMessage(raw: String) {
        val msg = try { JSONObject(raw) } catch (e: Exception) { return }

        if (msg.has("setupComplete")) {
            setupComplete.set(true)
            _state.value = State.LISTENING
            onReady?.invoke()
            return
        }

        msg.optJSONObject("serverContent")?.let { content ->
            val turnComplete = content.optBoolean("turnComplete", false)
            val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                _state.value = State.SPEAKING
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    sb.append(parts.optJSONObject(i)?.optString("text", ""))
                }
                if (sb.isNotEmpty()) onModelText?.invoke(sb.toString(), turnComplete)
            }
            if (turnComplete) _state.value = State.LISTENING
            if (content.optBoolean("interrupted", false)) _state.value = State.LISTENING
        }

        msg.optJSONObject("toolCall")?.let { toolCall ->
            val calls = mutableListOf<ToolCallRequest>()
            val arr = toolCall.optJSONArray("functionCalls") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val fc = arr.optJSONObject(i) ?: continue
                calls.add(
                    ToolCallRequest(
                        id = fc.optString("id", ""),
                        name = fc.optString("name", ""),
                        args = fc.optJSONObject("args") ?: JSONObject()
                    )
                )
            }
            if (calls.isNotEmpty()) {
                _state.value = State.ACTING
                onToolCalls?.invoke(calls)
            }
        }

        msg.optJSONObject("error")?.let { err ->
            _lastError.value = err.optString("message", "unknown Live error")
            _state.value = State.ERROR
        }
    }

    fun disconnect() {
        setupComplete.set(false)
        webSocket?.close(1000, "client done")
        webSocket = null
        if (_state.value != State.ERROR) _state.value = State.CLOSED
    }

    fun destroy() {
        disconnect()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        const val DEFAULT_WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}
