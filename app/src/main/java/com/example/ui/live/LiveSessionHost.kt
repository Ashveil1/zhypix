package com.example.ui.live

import android.content.Context
import com.example.live.LiveController
import com.example.live.LiveSessionManager
import com.example.viewmodel.AgentViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * UI-side owner of the single active [LiveController].
 *
 * The controller itself lives in com.example.live (engine layer). This host
 * only: creates it from the CURRENT provider settings (Gemini provider +
 * Live-capable model id from the provider's fetched model list - nothing is
 * hardcoded), exposes it to Compose, and bridges the sensitive-action
 * confirmation hook to a UI dialog state.
 */
object LiveSessionHost {

    data class ConfirmRequest(
        val actionType: String,
        val target: String,
        val answer: (Boolean) -> Unit,
    )

    private val _controller = MutableStateFlow<LiveController?>(null)
    val controller: StateFlow<LiveController?> = _controller.asStateFlow()

    private val _pendingConfirm = MutableStateFlow<ConfirmRequest?>(null)
    val pendingConfirm: StateFlow<ConfirmRequest?> = _pendingConfirm.asStateFlow()

    /** Set by the overlay entry point to ask the app UI to open the Live screen. */
    private val _openRequested = MutableStateFlow(false)
    val openRequested: StateFlow<Boolean> = _openRequested.asStateFlow()

    fun requestOpen() {
        _openRequested.value = true
    }

    fun consumeOpenRequest() {
        _openRequested.value = false
    }

    /** True when the configured provider is Gemini and its fetched model list has a Live model. */
    fun liveAvailability(viewModel: AgentViewModel): Pair<Boolean, String?> {
        val isGemini = viewModel.provider.value.equals("Gemini", ignoreCase = true)
        if (!isGemini) return false to null
        val liveModel = viewModel.availableModels.value
            .mapNotNull { it.modelId ?: it.id }
            .firstOrNull {
                it.contains("live", ignoreCase = true) ||
                    it.contains("native-audio", ignoreCase = true)
            }
        return (liveModel != null) to liveModel
    }

    /** Create (or replace) the controller. Returns null when Live is unavailable/misconfigured. */
    fun ensureController(context: Context, viewModel: AgentViewModel): LiveController? {
        _controller.value?.let { return it }
        val (available, modelId) = liveAvailability(viewModel)
        val apiKey = viewModel.apiKey.value.trim()
        if (!available || modelId == null || apiKey.isBlank()) return null
        val created = LiveController(
            context = context.applicationContext,
            apiKey = apiKey,
            modelId = modelId,
        )
        created.setConfirmAction { actionType, target ->
            suspendCancellableCoroutine { cont ->
                _pendingConfirm.value = ConfirmRequest(actionType, target) { allowed ->
                    _pendingConfirm.value = null
                    if (cont.isActive) cont.resume(allowed)
                }
                cont.invokeOnCancellation { _pendingConfirm.value = null }
            }
        }
        _controller.value = created
        return created
    }

    fun startSession(context: Context, viewModel: AgentViewModel, voice: Boolean): Boolean {
        val c = ensureController(context, viewModel) ?: return false
        c.start(voice)
        return true
    }

    fun toggleVoice(context: Context, viewModel: AgentViewModel, voice: Boolean): Boolean {
        val c = _controller.value ?: return startSession(context, viewModel, voice)
        // Voice mode is fixed at connect time; restart the session to change it.
        val active = c.state.value.let {
            it != LiveSessionManager.State.IDLE &&
                it != LiveSessionManager.State.CLOSED &&
                it != LiveSessionManager.State.ERROR
        }
        if (active) {
            c.stop()
        }
        c.start(voice)
        return true
    }

    fun stopSession() {
        _controller.value?.stop()
    }

    fun endSession() {
        _controller.value?.destroy()
        _controller.value = null
        _pendingConfirm.value = null
    }
}
