package com.zhypix.fresh.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhypix.fresh.agent.AgentLoop
import com.zhypix.fresh.agent.AgentAction
import com.zhypix.fresh.agent.TelemetryLog
import com.zhypix.fresh.api.ChatClient
import com.zhypix.fresh.data.ProviderStore
import com.zhypix.fresh.service.AgentAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class AgentViewModel(app: Application) : AndroidViewModel(app) {

    sealed class ChatItem {
        data class User(val text: String) : ChatItem()
        data class Agent(val text: String) : ChatItem()
        data class Status(val text: String) : ChatItem()
        data class Step(val n: Int, val max: Int, val action: String) : ChatItem()
    }

    sealed class RunState {
        object Idle : RunState()
        object Running : RunState()
        data class WaitingForUser(val prompt: String) : RunState()
    }

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items

    private val _runState = MutableStateFlow<RunState>(RunState.Idle)
    val runState: StateFlow<RunState> = _runState

    val store = ProviderStore(app)
    private val _providers = MutableStateFlow(store.providers())
    val providers: StateFlow<List<ProviderStore.Provider>> = _providers
    private val _selection = MutableStateFlow(store.selection())
    val selection: StateFlow<ProviderStore.Selection?> = _selection

    private val telemetry = TelemetryLog(File(app.filesDir, "agent_steps.jsonl"))
    private var job: Job? = null
    private var pendingReply: CompletableDeferred<String>? = null

    fun refreshProviders() {
        _providers.value = store.providers()
        _selection.value = store.selection()
    }

    private fun post(item: ChatItem) {
        _items.value = _items.value + item
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        post(ChatItem.User(trimmed))

        val waiting = _runState.value
        if (waiting is RunState.WaitingForUser) {
            pendingReply?.complete(trimmed)
            return
        }
        if (waiting is RunState.Running) return

        val service = AgentAccessibilityService.instance
        if (service == null) {
            post(ChatItem.Status("Accessibility service is off. Tap the gear icon and enable Zhypix."))
            return
        }
        val (provider, model) = store.selectedProvider() ?: run {
            post(ChatItem.Status("No provider set up yet. Add one in Providers (key icon)."))
            return
        }

        val client = ChatClient(provider.baseUrl, provider.apiKey, model)
        val loop = AgentLoop(service, client, telemetry)

        _runState.value = RunState.Running
        job = viewModelScope.launch {
            val end = try {
                loop.run(trimmed, object : AgentLoop.Sink {
                    override fun onStep(step: Int, maxSteps: Int, action: AgentAction) {
                        post(ChatItem.Step(step, maxSteps, action.key))
                    }
                    override suspend fun awaitUserReply(prompt: String, needsConfirmation: Boolean): String {
                        post(ChatItem.Agent(prompt))
                        _runState.value = RunState.WaitingForUser(prompt)
                        val reply = CompletableDeferred<String>()
                        pendingReply = reply
                        val answer = reply.await()
                        pendingReply = null
                        _runState.value = RunState.Running
                        return answer
                    }
                })
            } catch (e: kotlinx.coroutines.CancellationException) {
                AgentLoop.End.Cancelled
            } catch (e: Exception) {
                AgentLoop.End.Error(e.message ?: "unknown error")
            }

            _runState.value = RunState.Idle
            when (end) {
                is AgentLoop.End.Finished -> post(ChatItem.Agent(end.message))
                is AgentLoop.End.Failed -> post(ChatItem.Agent("I couldn't finish that: ${end.reason}"))
                is AgentLoop.End.MaxSteps -> post(ChatItem.Agent("Stopped after ${end.steps} steps without finishing. Tell me more detail or try a simpler task."))
                AgentLoop.End.Stuck -> post(ChatItem.Agent("I kept repeating the same action without the screen changing, so I stopped. Try rephrasing or take over manually."))
                AgentLoop.End.Cancelled -> post(ChatItem.Status("Stopped."))
                is AgentLoop.End.Error -> post(ChatItem.Agent("Error: ${end.message}"))
            }
        }
    }

    fun stop() {
        pendingReply?.complete("The user stopped the task.")
        pendingReply = null
        job?.cancel()
        job = null
        _runState.value = RunState.Idle
    }

    val serviceConnected: Boolean
        get() = AgentAccessibilityService.instance != null
}
