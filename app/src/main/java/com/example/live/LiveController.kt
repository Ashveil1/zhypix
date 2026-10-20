package com.example.live

import android.content.Context
import com.example.service.ZhypixAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates a Gemini Live session: websocket session, screen-control
 * tool bridge, screen-frame streaming, and (optionally) voice in/out.
 *
 * The UI layer only talks to this class: [start], [stop], [state], and
 * [transcript]. All model ids come from the caller (the provider's
 * fetched model list) - nothing is hardcoded.
 */
class LiveController(
    private val context: Context,
    private val apiKey: String,
    private val modelId: String,
    systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {

    data class TranscriptEntry(val fromUser: Boolean, val text: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val toolBridge = LiveToolBridge(
        serviceProvider = { ZhypixAccessibilityService.instance },
        contextProvider = { context }
    )

    private val session = LiveSessionManager(
        apiKey = apiKey,
        modelId = modelId,
        systemPrompt = systemPrompt,
        toolsPayload = toolBridge.toolsPayload(),
        responseModalities = listOf("AUDIO", "TEXT")
    )

    private val frameStreamer = LiveFrameStreamer(
        session = session,
        serviceProvider = { ZhypixAccessibilityService.instance }
    )

    private val audio = LiveAudioPipeline(context, session)

    private val _state = MutableStateFlow(LiveSessionManager.State.IDLE)
    val state: StateFlow<LiveSessionManager.State> = _state.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcript: StateFlow<List<TranscriptEntry>> = _transcript.asStateFlow()

    private var voiceEnabled = false

    init {
        session.onModelText = { text, _ ->
            _transcript.value = _transcript.value + TranscriptEntry(fromUser = false, text = text)
        }
        session.onModelAudio = { pcm, _ -> audio.enqueuePlayback(pcm) }
        session.onInterrupted = { audio.interruptPlayback() }
        session.onToolCalls = { calls ->
            scope.launch {
                val responses = calls.map { call -> call to toolBridge.execute(call) }
                session.sendToolResponse(responses)
            }
        }
        session.onReady = {
            frameStreamer.start()
            if (voiceEnabled) audio.start()
        }
        scope.launch {
            session.state.collect { _state.value = it }
        }
    }

    /** Optional sensitive-action confirmation hook, wired by the UI layer. */
    fun setConfirmAction(hook: (suspend (actionType: String, target: String) -> Boolean)?) {
        toolBridge.confirmAction = hook
    }

    /** Start the Live session. Voice requires RECORD_AUDIO permission. */
    fun start(voice: Boolean) {
        voiceEnabled = voice && audio.hasRecordPermission()
        session.connect()
    }

    /** Send a user text message into the Live conversation. */
    fun sendText(text: String) {
        if (text.isBlank()) return
        _transcript.value = _transcript.value + TranscriptEntry(fromUser = true, text = text)
        session.sendText(text)
    }

    fun stop() {
        frameStreamer.stop()
        audio.stop()
        session.disconnect()
    }

    fun destroy() {
        stop()
        session.destroy()
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT = ""
    }
}
