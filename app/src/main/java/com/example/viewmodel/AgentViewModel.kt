package com.example.viewmodel

import retrofit2.HttpException
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhypix.BuildConfig
import com.example.api.Candidate
import com.example.api.Content
import com.example.api.FunctionCall
import com.example.api.FunctionResponse
import com.example.api.GenerateContentRequest
import com.example.api.GenerationConfig
import com.example.api.Part
import com.example.api.RetrofitClient
import com.example.api.ThinkingConfig
import com.example.db.AppDatabase
import com.example.db.UserPreference
import com.example.db.ChatSession
import com.example.db.ChatMessageEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers

import com.example.model.*

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        var instance: AgentViewModel? = null
            private set

        /** Hard cap of executed actions per user turn - runaway-loop guard. */
        private const val MAX_ACTIONS_PER_TURN = 30
        private const val MAX_STEPS_PER_TASK = 150
        /** Consecutive no-state-change repeats of the same action before a hard stop. */
        private const val STUCK_ACTION_ABORT_THRESHOLD = 4
    }

    init {
        instance = this
        // The floating controller bubble dims and goes click-through whenever
        // the agent is mid-task so it neither covers targets nor eats taps.
        viewModelScope.launch {
            _isProcessing.collect { active ->
                try { com.example.service.FloatingAgentService.instance?.setAgentActive(active) } catch (e: Exception) {}
            }
        }
    }

    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private val db = AppDatabase.getDatabase(application)
    private val prefDao = db.userPreferenceDao()
    private val chatDao = db.chatDao()
    private val cryptoManager = com.example.security.CryptoManager()

    val chatSessions: StateFlow<List<ChatSession>> = chatDao.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private var currentSessionId: Long? = null
    val currentSessionIdFlow = MutableStateFlow<Long?>(null)

    val userPreferences: StateFlow<List<UserPreference>> = prefDao.getAllPreferences()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val ttsManager = com.example.utils.EdgeTtsManager(application)
    val sttManager = com.example.utils.SpeechToTextManager(application)

    private val _thaiVoice = MutableStateFlow(com.example.utils.EdgeTtsManager.DEFAULT_THAI_VOICE)
    val thaiVoice = _thaiVoice.asStateFlow()

    private val _requestedSettingsSubScreen = MutableStateFlow<SettingsScreenType?>(null)
    val requestedSettingsSubScreen: StateFlow<SettingsScreenType?> = _requestedSettingsSubScreen.asStateFlow()

    fun openSettings(subScreen: SettingsScreenType = SettingsScreenType.PROFILE) {
        _requestedSettingsSubScreen.value = subScreen
    }

    fun clearRequestedSettingsSubScreen() {
        _requestedSettingsSubScreen.value = null
    }

    fun isProviderAndModelConfigured(): Boolean {
        val p = _provider.value.trim()
        val m = _modelName.value.trim()
        val key = _apiKey.value.trim()

        if (p.isBlank() || m.isBlank() || m.equals("none", ignoreCase = true)) return false

        if (p == "Gemini") {
            val effectiveKey = if (key.isNotBlank()) key else BuildConfig.GEMINI_API_KEY
            if (effectiveKey.isBlank() || effectiveKey == "MY_GEMINI_API_KEY") return false
        } else if (p == "Ollama") {
            if (_baseUrl.value.trim().isBlank()) return false
        } else {
            if (key.isBlank()) return false
        }

        return true
    }

    private val _englishVoice = MutableStateFlow(com.example.utils.EdgeTtsManager.DEFAULT_ENGLISH_VOICE)
    val englishVoice = _englishVoice.asStateFlow()

    private val _japaneseVoice = MutableStateFlow(com.example.utils.EdgeTtsManager.DEFAULT_JAPANESE_VOICE)
    val japaneseVoice = _japaneseVoice.asStateFlow()

    private val _chineseVoice = MutableStateFlow(com.example.utils.EdgeTtsManager.DEFAULT_CHINESE_VOICE)
    val chineseVoice = _chineseVoice.asStateFlow()

    private val _ttsAutoSpeak = MutableStateFlow(false)
    val ttsAutoSpeak = _ttsAutoSpeak.asStateFlow()

    private val _inAppTtsEnabled = MutableStateFlow(false)
    val inAppTtsEnabled = _inAppTtsEnabled.asStateFlow()

    private val _isAppActive = MutableStateFlow(false)
    val isAppActive = _isAppActive.asStateFlow()

    private val _continuousVoiceMode = MutableStateFlow(false)
    val continuousVoiceMode = _continuousVoiceMode.asStateFlow()

    private val _ultraConciseMode = MutableStateFlow(false)
    val ultraConciseMode = _ultraConciseMode.asStateFlow()

    private val _ttsEngineOption = MutableStateFlow("edge") // "edge" or "google"
    val ttsEngineOption = _ttsEngineOption.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _lastScreenshot = MutableStateFlow<String?>(null)
    val lastScreenshot = _lastScreenshot.asStateFlow()

    private val _lastHierarchy = MutableStateFlow<String?>(null)
    val lastHierarchy = _lastHierarchy.asStateFlow()

    private val _activeAction = MutableStateFlow<AgentAction?>(null)
    val activeAction = _activeAction.asStateFlow()

    private val _actionHistory = MutableStateFlow<List<AgentAction>>(emptyList())
    val actionHistory = _actionHistory.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()

    private val _reasoningSteps = MutableStateFlow<List<ReasoningStep>>(emptyList())
    val reasoningSteps = _reasoningSteps.asStateFlow()

    private val _agentState = MutableStateFlow(AgentState.IDLE)
    val agentState = _agentState.asStateFlow()

    private val _providerProfiles = MutableStateFlow<Map<String, ProviderProfile>>(emptyMap())
    val providerProfiles = _providerProfiles.asStateFlow()

    private val _provider = MutableStateFlow("")
    val provider = _provider.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey = _apiKey.asStateFlow()

    private val _baseUrl = MutableStateFlow("")
    val baseUrl = _baseUrl.asStateFlow()

    private val _modelName = MutableStateFlow("")
    val modelName = _modelName.asStateFlow()

    private val _thinkingLevel = MutableStateFlow("NONE")
    val thinkingLevel = _thinkingLevel.asStateFlow()

    private val _autoSaveSettings = MutableStateFlow(true)
    val autoSaveSettings = _autoSaveSettings.asStateFlow()

    private val _autoSkipAdsEnabled = MutableStateFlow(true)
    val autoSkipAdsEnabled = _autoSkipAdsEnabled.asStateFlow()

    private val _webSearchEnabled = MutableStateFlow(true)
    val webSearchEnabled = _webSearchEnabled.asStateFlow()

    private val _visionMode = MutableStateFlow("AUTO")
    val visionMode = _visionMode.asStateFlow()



    private val _screenshotQuality = MutableStateFlow(75)
    val screenshotQuality = _screenshotQuality.asStateFlow()

    private val _screenshotScale = MutableStateFlow(0.5f)
    val screenshotScale = _screenshotScale.asStateFlow()

    // Optional cap on the screenshot's long side in pixels (0 = off). Keeps
    // images near the model's sweet spot regardless of device resolution.
    private val _screenshotMaxLongSide = MutableStateFlow(780)
    val screenshotMaxLongSide = _screenshotMaxLongSide.asStateFlow()

    private var installedAppsCache: String? = null

    private val _selectedPersona = MutableStateFlow("DEFAULT") // "DEFAULT", "FRIENDLY", "CONCISE", "TUTOR", "CUSTOM"
    val selectedPersona = _selectedPersona.asStateFlow()

    private val _customPersonaPrompt = MutableStateFlow("")
    val customPersonaPrompt = _customPersonaPrompt.asStateFlow()

    private val _duckMediaOnSpeech = MutableStateFlow(true)
    val duckMediaOnSpeech = _duckMediaOnSpeech.asStateFlow()

    private var isFlashlightOn = false

    private var lastHierarchyHash = 0
    private var lastExecutedActionSig = ""
    private var sameStateActionCount = 0
    private var autoContinueCount = 0
    private var fallbackRetryCount = 0
    private var actionsExecutedInCurrentTurn = 0
    private var turnAbortRequested = false

    // Task-level step counter (exposed so the UI can show "Step N/150") and
    // terminal status reported by the agent via report_task_status.
    private val _currentStep = MutableStateFlow(0)
    val currentStep = _currentStep.asStateFlow()
    private val _maxStepsPerTask = MutableStateFlow(MAX_STEPS_PER_TASK)
    val maxStepsPerTask = _maxStepsPerTask.asStateFlow()
    var pendingTerminalStatus: String? = null
    var pendingTerminalMessage: String = ""
    private var parseRetryCount = 0

    private var lastActionTime: Long = System.currentTimeMillis()
    private var watchdogJob: kotlinx.coroutines.Job? = null
    var isExecutingWaitAction: Boolean = false

    fun recordActionExecuted() {
        lastActionTime = System.currentTimeMillis()
        actionsExecutedInCurrentTurn++
        _currentStep.value++
        if (_currentStep.value >= MAX_STEPS_PER_TASK) {
            pendingTerminalStatus = "max_steps"
            pendingTerminalMessage = "Stopped after $MAX_STEPS_PER_TASK steps in one task - the agent may be looping. Tell me what to adjust and I can continue."
            turnAbortRequested = true
        }
    }

    fun startInactivityWatchdog() {}

    fun stopInactivityWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    var wasWaitingForScreenLoad: Boolean = false

    /** Set when the user touches the screen while the agent is running: pause after the current step. */
    @Volatile
    var touchPauseRequested: Boolean = false

    fun requestPauseFromTouch() {
        if (!_isProcessing.value) return
        touchPauseRequested = true
        Log.i("Zhypix", "Pause requested: user touched the screen")
    }

    /** Sensitive action (send/pay/confirm-style button) awaiting explicit user confirmation. */
    @Volatile
    var pendingSensitiveActionDesc: String? = null
    @Volatile
    private var sensitiveActionApproved: Boolean = false

    // Generic capability heuristic on the configured model id (AUTO vision mode).
    // Works for any provider's naming; not a model selection list.
    private fun modelSupportsVision(modelId: String): Boolean {
        val lowerModel = modelId.lowercase()
        return lowerModel.contains("gpt-4o") ||
                lowerModel.contains("claude-3-5") ||
                lowerModel.contains("claude-3.5") ||
                lowerModel.contains("claude-3-opus") ||
                lowerModel.contains("gemini") ||
                lowerModel.contains("pixtral") ||
                lowerModel.contains("vision")
    }

    fun isVisionActive(): Boolean {
        val callModel = _modelName.value.trim() // no hardcoded default: blank means "not configured"
        val visionModeVal = _visionMode.value
        return when (visionModeVal) {
            "YES" -> true
            "NO" -> false
            else -> modelSupportsVision(callModel)
        }
    }

    private val moshi = com.squareup.moshi.Moshi.Builder().build()
    private val mcpListType = com.squareup.moshi.Types.newParameterizedType(List::class.java, com.example.model.McpServer::class.java)
    private val mcpAdapter = moshi.adapter<List<com.example.model.McpServer>>(mcpListType)

    private val _mcpServers = MutableStateFlow<List<com.example.model.McpServer>>(emptyList())
    val mcpServers = _mcpServers.asStateFlow()

    private val _reasoningChain = MutableStateFlow<List<ReasoningStep>>(emptyList())
    val reasoningChain = _reasoningChain.asStateFlow()

    private val reasoningTimeFormat = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    }
    private var lastReasoningEmitAt = 0L
    private var pendingReasoningStep: ReasoningStep? = null
    private var reasoningFlushJob: kotlinx.coroutines.Job? = null

    fun addReasoningStep(title: String, description: String, status: String = "SUCCESS", type: String = "INFO") {
        val step = ReasoningStep(
            title = title,
            description = description,
            timestamp = reasoningTimeFormat.get()!!.format(java.util.Date()),
            status = status,
            type = type
        )
        // Coalesce bursts: a single action emits ~10 steps, and every emission
        // used to trigger a full overlay/chat recomposition. Failures and
        // warnings always emit immediately; routine steps flush at ~6/sec.
        val now = System.currentTimeMillis()
        val always = status == "FAILED" || status == "WARNING"
        if (always || now - lastReasoningEmitAt >= 150L) {
            lastReasoningEmitAt = now
            val pending = pendingReasoningStep
            pendingReasoningStep = null
            val base = if (pending != null) _reasoningChain.value + pending else _reasoningChain.value
            _reasoningChain.value = (base + step).takeLast(200)
        } else {
            pendingReasoningStep = step
            scheduleReasoningFlush()
        }
    }

    private fun scheduleReasoningFlush() {
        if (reasoningFlushJob?.isActive == true) return
        reasoningFlushJob = appScope.launch {
            kotlinx.coroutines.delay(160L)
            val p = pendingReasoningStep ?: return@launch
            pendingReasoningStep = null
            lastReasoningEmitAt = System.currentTimeMillis()
            _reasoningChain.value = (_reasoningChain.value + p).takeLast(200)
        }
    }

    fun clearReasoningChain() {
        _reasoningChain.value = emptyList()
    }

    init {
        com.example.agent.AgentDiagnostics.init(getApplication())
        // Load initial settings
        viewModelScope.launch {
            userPreferences.collect { prefs ->
                val savedProvider = prefs.find { it.key == "cfg_provider" }?.value
                val activeProvider = if (!savedProvider.isNullOrEmpty()) savedProvider else _provider.value
                
                val savedProfileKeys = prefs.filter { it.key.startsWith("cfg_provider_key_") }
                    .map { it.key.removePrefix("cfg_provider_key_") }
                    .filter { it.isNotBlank() }
                val legacyModel = prefs.find { it.key == "cfg_modelName" }?.value
                val legacyKey = prefs.find { it.key == "cfg_apiKey" }?.value

                val allIds = mutableSetOf<String>().apply {
                    addAll(savedProfileKeys)
                    if (!savedProvider.isNullOrEmpty()) add(savedProvider)
                    if (!legacyModel.isNullOrEmpty() || !legacyKey.isNullOrEmpty()) {
                        if (!savedProvider.isNullOrEmpty()) add(savedProvider) else add("Gemini")
                    }
                }.toList()
                
                val currentProfiles = _providerProfiles.value
                val loadedProfiles = mutableMapOf<String, ProviderProfile>()
                allIds.forEach { id ->
                    val savedEncKey = prefs.find { it.key == "cfg_provider_key_$id" }?.value ?: ""
                    var savedKey = if (savedEncKey.isNotEmpty()) cryptoManager.decrypt(savedEncKey) else ""
                    
                    var savedUrl = prefs.find { it.key == "cfg_provider_url_$id" }?.value ?: ""
                    var savedModel = prefs.find { it.key == "cfg_provider_model_$id" }?.value ?: ""
                    
                    val existingProf = currentProfiles[id]
                    if (savedUrl.isEmpty()) {
                        savedUrl = if (existingProf?.baseUrl?.isNotEmpty() == true) existingProf.baseUrl else when (id) {
                            "Gemini" -> "https://generativelanguage.googleapis.com/"
                            "Claude" -> "https://api.anthropic.com/v1"
                            "OpenAI" -> "https://api.openai.com/v1"
                            "Groq" -> "https://api.groq.com/openai/v1"
                            else -> ""
                        }
                    }
                    if (savedModel.isEmpty()) {
                        savedModel = if (existingProf?.modelName?.isNotEmpty() == true) existingProf.modelName else ""
                    }
                    if (savedKey.isEmpty() && existingProf?.apiKey?.isNotEmpty() == true) {
                        savedKey = existingProf.apiKey
                    }
                    
                    if (savedKey.isEmpty() && id == activeProvider) {
                        val legacyKey = prefs.find { it.key == "cfg_apiKey" }?.value
                        if (!legacyKey.isNullOrEmpty()) {
                            val decKey = cryptoManager.decrypt(legacyKey)
                            if (decKey.isNotEmpty()) savedKey = decKey
                        }
                    }
                    
                    loadedProfiles[id] = ProviderProfile(id, id, savedKey, savedUrl, savedModel)
                }
                
                _providerProfiles.value = loadedProfiles

                if (!savedProvider.isNullOrEmpty()) {
                    _provider.value = savedProvider
                }

                val activeProf = loadedProfiles[_provider.value] ?: ProviderProfile(_provider.value, _provider.value)
                val savedGlobalUrl = prefs.find { it.key == "cfg_baseUrl" }?.value
                val savedGlobalModel = prefs.find { it.key == "cfg_modelName" }?.value
                val savedGlobalKey = prefs.find { it.key == "cfg_apiKey" }?.value?.let { cryptoManager.decrypt(it) }

                _apiKey.value = if (!savedGlobalKey.isNullOrEmpty()) savedGlobalKey else if (activeProf.apiKey.isNotEmpty()) activeProf.apiKey else _apiKey.value
                _baseUrl.value = if (!savedGlobalUrl.isNullOrEmpty()) savedGlobalUrl else if (activeProf.baseUrl.isNotEmpty()) activeProf.baseUrl else _baseUrl.value
                _modelName.value = if (!savedGlobalModel.isNullOrEmpty()) savedGlobalModel else if (activeProf.modelName.isNotEmpty()) activeProf.modelName else _modelName.value

                val thinkingPref = prefs.find { it.key == "cfg_thinkingLevel" }?.value
                if (_thinkingLevel.value == "NONE" && !thinkingPref.isNullOrEmpty()) _thinkingLevel.value = thinkingPref

                val autoSavePref = prefs.find { it.key == "cfg_autoSave" }?.value
                if (autoSavePref != null) {
                    _autoSaveSettings.value = autoSavePref.toBoolean()
                }

                val webSearchPref = prefs.find { it.key == "cfg_webSearch" }?.value
                if (webSearchPref != null) {
                    _webSearchEnabled.value = true
                }

                val visionPref = prefs.find { it.key == "cfg_visionMode" }?.value
                if (!visionPref.isNullOrEmpty()) {
                    _visionMode.value = visionPref
                }



                val maxLongSidePref = prefs.find { it.key == "cfg_screenshotMaxLongSide" }?.value
                if (maxLongSidePref != null) {
                    _screenshotMaxLongSide.value = maxLongSidePref.toIntOrNull() ?: 780
                }

                val qualityPref = prefs.find { it.key == "cfg_screenshotQuality" }?.value
                if (qualityPref != null) {
                    _screenshotQuality.value = qualityPref.toIntOrNull() ?: 60
                }

                val scalePref = prefs.find { it.key == "cfg_screenshotScale" }?.value
                if (scalePref != null) {
                    _screenshotScale.value = scalePref.toFloatOrNull() ?: 0.5f
                }

                val mcpPref = prefs.find { it.key == "cfg_mcp_servers" }?.value
                if (!mcpPref.isNullOrEmpty()) {
                    try {
                        val servers = mcpAdapter.fromJson(mcpPref)
                        if (servers != null) {
                            _mcpServers.value = servers
                        }
                    } catch (e: Exception) {
                        Log.e("AgentViewModel", "Failed to parse mcp servers", e)
                    }
                }

                // Load Voice Preferences
                prefs.find { it.key == "cfg_thai_voice" }?.value?.let { _thaiVoice.value = it }
                prefs.find { it.key == "cfg_english_voice" }?.value?.let { _englishVoice.value = it }
                prefs.find { it.key == "cfg_japanese_voice" }?.value?.let { _japaneseVoice.value = it }
                prefs.find { it.key == "cfg_chinese_voice" }?.value?.let { _chineseVoice.value = it }
                prefs.find { it.key == "cfg_tts_auto_speak" }?.value?.let { _ttsAutoSpeak.value = it.toBoolean() }
                prefs.find { it.key == "cfg_in_app_tts" }?.value?.let { _inAppTtsEnabled.value = it.toBoolean() }
                prefs.find { it.key == "cfg_continuous_voice_mode" }?.value?.let { _continuousVoiceMode.value = it.toBoolean() }
                prefs.find { it.key == "cfg_ultra_concise_mode" }?.value?.let { _ultraConciseMode.value = it.toBoolean() }
                prefs.find { it.key == "cfg_tts_engine" }?.value?.let { _ttsEngineOption.value = it }
                prefs.find { it.key == "cfg_selected_persona" }?.value?.let { _selectedPersona.value = it }
                prefs.find { it.key == "cfg_custom_persona_prompt" }?.value?.let { _customPersonaPrompt.value = it }
                prefs.find { it.key == "cfg_duck_media_on_speech" }?.value?.let { _duckMediaOnSpeech.value = it.toBoolean() }
            }
        }

        @OptIn(kotlinx.coroutines.FlowPreview::class)
        viewModelScope.launch(Dispatchers.IO) {
            _messages
                .debounce(300L)
                .collect { msgs ->
                    val sessionId = currentSessionId
                    if (sessionId != null && msgs.isNotEmpty() && _autoSaveSettings.value) {
                        val entities = msgs.mapNotNull { msg ->
                            when (msg) {
                                is ChatMessage.User -> ChatMessageEntity(
                                    sessionId = sessionId,
                                    role = "user",
                                    content = msg.text
                                )
                                is ChatMessage.Agent -> ChatMessageEntity(
                                    sessionId = sessionId,
                                    role = "agent",
                                    content = msg.text
                                )
                                is ChatMessage.System -> ChatMessageEntity(
                                    sessionId = sessionId,
                                    role = "system",
                                    content = msg.text
                                )
                                is ChatMessage.TaskExecution -> ChatMessageEntity(
                                    sessionId = sessionId,
                                    role = "task_execution",
                                    content = msg.action.target,
                                    actionType = msg.action.actionType,
                                    actionTarget = msg.action.target,
                                    status = msg.status,
                                    resultSnippet = msg.resultSnippet
                                )
                                else -> null
                            }
                        }
                        try {
                            chatDao.replaceSessionMessages(sessionId, entities)
                        } catch (e: Exception) {
                            Log.e("AgentViewModel", "Error saving session messages", e)
                        }
                    }
                }
        }
    }

    fun updateScreenshot(base64: String?) {
        _lastScreenshot.value = base64
        val service = com.example.service.ZhypixAccessibilityService.instance
        if (service != null) {
            _lastHierarchy.value = service.getActiveWindowHierarchy()
        }
    }

    fun selectProvider(newProviderId: String) {
        val currentId = _provider.value
        val map = _providerProfiles.value.toMutableMap()

        // 1. Save state of current provider before switching
        map[currentId] = ProviderProfile(
            id = currentId,
            name = map[currentId]?.name ?: currentId,
            apiKey = _apiKey.value,
            baseUrl = _baseUrl.value,
            modelName = _modelName.value
        )

        // 2. Load state of new target provider
        val target = map[newProviderId] ?: ProviderProfile(
            id = newProviderId,
            name = newProviderId,
            apiKey = "",
            baseUrl = when(newProviderId) {
                "Gemini" -> "https://generativelanguage.googleapis.com/"
                "Claude" -> "https://api.anthropic.com/v1"
                "OpenAI" -> "https://api.openai.com/v1"
                "Groq" -> "https://api.groq.com/openai/v1"
                else -> ""
            },
            modelName = ""
        )
        map[newProviderId] = target

        _providerProfiles.value = map
        _provider.value = newProviderId
        _apiKey.value = target.apiKey
        _baseUrl.value = target.baseUrl
        _modelName.value = target.modelName

        saveSettings()
    }

    fun saveProviderProfile(
        id: String,
        apiKey: String,
        baseUrl: String,
        modelName: String,
        makeActive: Boolean = true
    ) {
        val map = _providerProfiles.value.toMutableMap()
        map[id] = ProviderProfile(id, id, apiKey, baseUrl, modelName)
        _providerProfiles.value = map

        if (makeActive) {
            _provider.value = id
            _apiKey.value = apiKey
            _baseUrl.value = baseUrl
            _modelName.value = modelName
        }
        saveSettings()
    }

    fun addCustomProvider(name: String, apiKey: String = "", baseUrl: String = "", modelName: String = "") {
        val id = name.trim()
        if (id.isEmpty()) return
        val map = _providerProfiles.value.toMutableMap()
        map[id] = ProviderProfile(id, id, apiKey, baseUrl, modelName)
        _providerProfiles.value = map
        selectProvider(id)
    }

    fun deleteCustomProvider(providerId: String) {
        if (providerId in listOf("Gemini", "Claude", "OpenAI", "Groq", "Custom")) return
        val map = _providerProfiles.value.toMutableMap()
        map.remove(providerId)
        _providerProfiles.value = map
        viewModelScope.launch {
            prefDao.insertPreference(UserPreference("cfg_provider_key_$providerId", ""))
            prefDao.insertPreference(UserPreference("cfg_provider_url_$providerId", ""))
            prefDao.insertPreference(UserPreference("cfg_provider_model_$providerId", ""))
        }
        if (_provider.value == providerId) {
            selectProvider("Gemini")
        } else {
            saveSettings()
        }
    }

    fun updateSetting(key: String, value: String) {
        when (key) {
            "provider" -> selectProvider(value)
            "apiKey" -> {
                _apiKey.value = value
                val activeId = _provider.value
                val map = _providerProfiles.value.toMutableMap()
                val existing = map[activeId] ?: ProviderProfile(activeId, activeId)
                map[activeId] = existing.copy(apiKey = value)
                _providerProfiles.value = map
            }
            "baseUrl" -> {
                _baseUrl.value = value
                val activeId = _provider.value
                val map = _providerProfiles.value.toMutableMap()
                val existing = map[activeId] ?: ProviderProfile(activeId, activeId)
                map[activeId] = existing.copy(baseUrl = value)
                _providerProfiles.value = map
            }
            "modelName" -> {
                _modelName.value = value
                val activeId = _provider.value
                val map = _providerProfiles.value.toMutableMap()
                val existing = map[activeId] ?: ProviderProfile(activeId, activeId)
                map[activeId] = existing.copy(modelName = value)
                _providerProfiles.value = map
            }
            "thinkingLevel" -> _thinkingLevel.value = value
            "autoSave" -> _autoSaveSettings.value = value.toBoolean()
            "autoSkipAds" -> {
                _autoSkipAdsEnabled.value = value.toBoolean()
                // Legacy automatic screen actions removed during rebuild.
            }
            "webSearch" -> _webSearchEnabled.value = true
            "visionMode" -> _visionMode.value = value
            "screenshotQuality" -> _screenshotQuality.value = value.toIntOrNull() ?: 75
            "screenshotMaxLongSide" -> _screenshotMaxLongSide.value = value.toIntOrNull() ?: 780
            "screenshotScale" -> _screenshotScale.value = value.toFloatOrNull() ?: 0.5f
        }
        if (_autoSaveSettings.value) {
            saveSettings()
        }
    }

    fun saveSettings() {
        viewModelScope.launch {
            val activeId = _provider.value
            val map = _providerProfiles.value.toMutableMap()
            map[activeId] = ProviderProfile(
                id = activeId,
                name = map[activeId]?.name ?: activeId,
                apiKey = _apiKey.value,
                baseUrl = _baseUrl.value,
                modelName = _modelName.value
            )
            _providerProfiles.value = map

            val prefList = mutableListOf<UserPreference>()
            prefList.add(UserPreference("cfg_provider", activeId))
            prefList.add(UserPreference("cfg_apiKey", cryptoManager.encrypt(_apiKey.value)))
            prefList.add(UserPreference("cfg_baseUrl", _baseUrl.value))
            prefList.add(UserPreference("cfg_modelName", _modelName.value))
            prefList.add(UserPreference("cfg_thinkingLevel", _thinkingLevel.value))
            prefList.add(UserPreference("cfg_autoSave", _autoSaveSettings.value.toString()))
            prefList.add(UserPreference("cfg_webSearch", _webSearchEnabled.value.toString()))
            prefList.add(UserPreference("cfg_visionMode", _visionMode.value))
            prefList.add(UserPreference("cfg_screenshotQuality", _screenshotQuality.value.toString()))
            prefList.add(UserPreference("cfg_screenshotScale", _screenshotScale.value.toString()))
            prefList.add(UserPreference("cfg_thai_voice", _thaiVoice.value))
            prefList.add(UserPreference("cfg_english_voice", _englishVoice.value))
            prefList.add(UserPreference("cfg_japanese_voice", _japaneseVoice.value))
            prefList.add(UserPreference("cfg_chinese_voice", _chineseVoice.value))
            prefList.add(UserPreference("cfg_tts_auto_speak", _ttsAutoSpeak.value.toString()))
            prefList.add(UserPreference("cfg_in_app_tts", _inAppTtsEnabled.value.toString()))
            prefList.add(UserPreference("cfg_continuous_voice_mode", _continuousVoiceMode.value.toString()))
            prefList.add(UserPreference("cfg_ultra_concise_mode", _ultraConciseMode.value.toString()))
            prefList.add(UserPreference("cfg_tts_engine", _ttsEngineOption.value))
            prefList.add(UserPreference("cfg_selected_persona", _selectedPersona.value))
            prefList.add(UserPreference("cfg_custom_persona_prompt", _customPersonaPrompt.value))
            prefList.add(UserPreference("cfg_duck_media_on_speech", _duckMediaOnSpeech.value.toString()))

            map.forEach { (id, prof) ->
                prefList.add(UserPreference("cfg_provider_key_$id", cryptoManager.encrypt(prof.apiKey)))
                prefList.add(UserPreference("cfg_provider_url_$id", prof.baseUrl))
                prefList.add(UserPreference("cfg_provider_model_$id", prof.modelName))
            }

            try {
                val mcpJson = mcpAdapter.toJson(_mcpServers.value)
                prefList.add(UserPreference("cfg_mcp_servers", mcpJson))
            } catch (e: Exception) {
                Log.e("AgentViewModel", "Failed to serialize mcp servers", e)
            }

            prefDao.insertPreferences(prefList)
        }
    }

    fun addMcpServer(server: com.example.model.McpServer) {
        _mcpServers.value = _mcpServers.value + server
        saveSettings()
    }

    fun updateMcpServer(server: com.example.model.McpServer) {
        _mcpServers.value = _mcpServers.value.map { if (it.id == server.id) server else it }
        saveSettings()
    }

    fun deleteMcpServer(serverId: String) {
        _mcpServers.value = _mcpServers.value.filter { it.id != serverId }
        saveSettings()
    }

    fun getVoicePreferences(): com.example.utils.VoicePreferences {
        val prefs = com.example.utils.VoicePreferences(
            thaiVoice = _thaiVoice.value,
            englishVoice = _englishVoice.value,
            japaneseVoice = _japaneseVoice.value,
            chineseVoice = _chineseVoice.value,
            preferredEngine = _ttsEngineOption.value
        )
        Log.d("AgentViewModel", "getVoicePreferences() returning: $prefs")
        return prefs
    }

    fun setThaiVoice(voice: String) {
        Log.d("AgentViewModel", "setThaiVoice called with: $voice")
        _thaiVoice.value = voice
        saveSettings()
    }

    fun setEnglishVoice(voice: String) {
        _englishVoice.value = voice
        saveSettings()
    }

    fun setJapaneseVoice(voice: String) {
        _japaneseVoice.value = voice
        saveSettings()
    }

    fun setChineseVoice(voice: String) {
        _chineseVoice.value = voice
        saveSettings()
    }

    fun setTtsAutoSpeak(enabled: Boolean) {
        _ttsAutoSpeak.value = enabled
        saveSettings()
    }

    fun setInAppTtsEnabled(enabled: Boolean) {
        _inAppTtsEnabled.value = enabled
        saveSettings()
    }

    fun setAppActive(active: Boolean) {
        _isAppActive.value = active
    }

    fun setContinuousVoiceMode(enabled: Boolean) {
        _continuousVoiceMode.value = enabled
        saveSettings()
    }

    fun setUltraConciseMode(enabled: Boolean) {
        _ultraConciseMode.value = enabled
        saveSettings()
    }

    fun setTtsEngineOption(engine: String) {
        _ttsEngineOption.value = engine
        saveSettings()
    }

    fun speakText(text: String, onComplete: (() -> Unit)? = null) {
        ttsManager.speak(text, getVoicePreferences(), onComplete)
    }

    fun stopSpeaking() {
        ttsManager.stop()
    }

    fun startListening(languageCode: String = "th-TH", onResult: (String) -> Unit) {
        sttManager.startListening(languageCode, onResult)
    }

    fun stopListening() {
        sttManager.stopListening()
    }

    private val _connectionStatus = MutableStateFlow<String?>(null)
    val connectionStatus = _connectionStatus.asStateFlow()

    private val _availableModels = MutableStateFlow<List<com.example.api.OpenAiModelInfo>>(emptyList())
    val availableModels = _availableModels.asStateFlow()

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels = _isFetchingModels.asStateFlow()

    private val _currentTokens = MutableStateFlow(0)
    val currentTokens = _currentTokens.asStateFlow()

    private val _maxTokens = MutableStateFlow(16000)
    val maxTokens = _maxTokens.asStateFlow()

    fun fetchModels(overrideProvider: String? = null, overrideBaseUrl: String? = null, overrideApiKey: String? = null) {
        viewModelScope.launch {
            _isFetchingModels.value = true
            try {
                val provider = overrideProvider ?: _provider.value
                var callUrl = overrideBaseUrl ?: _baseUrl.value
                val callApiKey = if (overrideApiKey != null) {
                    if (overrideApiKey.isNotBlank()) overrideApiKey else if (provider == "Gemini") BuildConfig.GEMINI_API_KEY else ""
                } else if (_apiKey.value.isNotBlank()) {
                    _apiKey.value
                } else if (provider == "Gemini") {
                    BuildConfig.GEMINI_API_KEY
                } else {
                    ""
                }
                
                if (callUrl.isBlank()) {
                     callUrl = when (provider) {
                         "OpenRouter" -> "https://openrouter.ai/api/v1"
                         "Groq" -> "https://api.groq.com/openai/v1"
                         "Claude" -> "https://api.anthropic.com/v1"
                         "OpenAI" -> "https://api.openai.com/v1"
                         else -> "https://generativelanguage.googleapis.com/"
                     }
                }
                
                val service = RetrofitClient.getService(callUrl)
                val trimmedPath = callUrl.removeSuffix("/")
                
                if (provider == "Gemini" || callUrl.contains("generativelanguage.googleapis")) {
                    val modelInfoUrl = if (trimmedPath.endsWith("/v1") || trimmedPath.endsWith("/v1beta")) {
                        "$trimmedPath/models"
                    } else {
                        "$trimmedPath/v1beta/models"
                    }
                    val response = service.getGeminiModels(url = modelInfoUrl, apiKey = callApiKey)
                    val mappedList = response.models?.map { 
                        com.example.api.OpenAiModelInfo(id = it.name.removePrefix("models/"), context_length = it.inputTokenLimit) 
                    } ?: emptyList()
                    _availableModels.value = mappedList
                } else if (provider == "Claude" || callUrl.contains("api.anthropic.com")) {
                    val modelInfoUrl = if (trimmedPath.endsWith("/v1")) {
                        "$trimmedPath/models"
                    } else {
                        "$trimmedPath/v1/models"
                    }
                    val response = service.getClaudeModels(url = modelInfoUrl, apiKey = callApiKey, version = "2023-06-01")
                    if (response.data != null) {
                        _availableModels.value = response.data
                    }
                } else {
                    val authHeader = if (callApiKey.isNotBlank()) "Bearer $callApiKey" else null
                    val candidateUrls = mutableListOf<String>()
                    if (trimmedPath.endsWith("/models")) {
                        candidateUrls.add(trimmedPath)
                    } else if (trimmedPath.endsWith("/v1") || trimmedPath.endsWith("/v1beta")) {
                        candidateUrls.add("$trimmedPath/models")
                        candidateUrls.add("$trimmedPath/v1/models")
                    } else {
                        candidateUrls.add("$trimmedPath/v1/models")
                        candidateUrls.add("$trimmedPath/models")
                        candidateUrls.add("$trimmedPath/api/tags")
                    }

                    var fetchedModels: List<com.example.api.OpenAiModelInfo>? = null
                    for (url in candidateUrls) {
                        try {
                            val modelsResponse = service.getOpenAiModels(url = url, authHeader = authHeader)
                            if (!modelsResponse.data.isNullOrEmpty()) {
                                fetchedModels = modelsResponse.data
                                break
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("AgentViewModel", "Candidate model url failed: $url - ${e.message}")
                        }
                    }

                    if (fetchedModels != null) {
                        _availableModels.value = fetchedModels
                    } else {
                        _availableModels.value = emptyList()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AgentViewModel", "Failed to fetch models", e)
                _availableModels.value = emptyList()
            } finally {
                _isFetchingModels.value = false
            }
        }
    }

    fun verifyConnection() {
        viewModelScope.launch {
            _connectionStatus.value = "Verifying..."
            var lastErr: String? = null
            val maxRetries = 3
            for (attempt in 1..maxRetries) {
                try {
                    val callUrl = if (_baseUrl.value.isNotBlank()) _baseUrl.value else "https://generativelanguage.googleapis.com/"
                    val callApiKey = if (_apiKey.value.isNotBlank()) {
                        _apiKey.value
                    } else if (_provider.value == "Gemini") {
                        BuildConfig.GEMINI_API_KEY
                    } else {
                        ""
                    }
                    val callModel = _modelName.value

                    if (_provider.value == "Gemini") {
                        val fullPath = "${callUrl.removeSuffix("/")}/v1beta/models/$callModel:generateContent"
                        val request = GenerateContentRequest(contents = listOf(Content(parts = listOf(Part(text = "Hello")), role = "user")))
                        val response = RetrofitClient.getService(callUrl).generateContent(fullPath, callApiKey, request)
                        if (response.candidates?.isNotEmpty() == true) {
                            _connectionStatus.value = "Connection Successful"
                            return@launch
                        } else {
                            lastErr = "Empty response"
                        }
                    } else {
                        val trimmedPath = callUrl.removeSuffix("/")
                        val fullPath = if (trimmedPath.endsWith("/v1/chat/completions")) trimmedPath
                            else if (trimmedPath.endsWith("/v1")) "$trimmedPath/chat/completions"
                            else "$trimmedPath/v1/chat/completions"
                        
                        val authHeader = if (callApiKey.isNotBlank()) "Bearer $callApiKey" else null
                        val openAiRequest = mapOf<String, Any>(
                            "model" to callModel,
                            "messages" to listOf(
                                mapOf<String, Any>(
                                    "role" to "user",
                                    "content" to "Hello"
                                )
                            )
                        )
                        
                        val response = RetrofitClient.getService(callUrl).generateOpenAiContent(
                            url = fullPath,
                            authHeader = authHeader,
                            request = openAiRequest
                        )
                        if (response.choices?.isNotEmpty() == true) {
                            _connectionStatus.value = "Connection Successful"
                            return@launch
                        } else {
                            lastErr = "Empty response"
                        }
                    }
                } catch (e: Exception) {
                    val errorDetails = if (e is HttpException) {
                        val body = try { e.response()?.errorBody()?.string() } catch (ex: Exception) { null }
                        "HTTP ${e.code()}: ${body ?: e.message()}"
                    } else {
                        e.message ?: "Unknown error"
                    }
                    lastErr = errorDetails
                    if (attempt < maxRetries) {
                        _connectionStatus.value = "Retrying ($attempt/$maxRetries)..."
                        delay(attempt * 1000L)
                    }
                }
            }
            _connectionStatus.value = "Connection Failed: ${lastErr ?: "Unknown error"}"
        }
    }

    fun clearConnectionStatus() {
        _connectionStatus.value = null
    }

    // Hold API conversation history
    private val conversationHistory = mutableListOf<Content>()

    // Short rolling log of executed steps; older steps collapse into this log so
    // old screen trees and tool responses can be pruned from history.
    private val runningActionLog = com.example.utils.RunningActionLog()

    fun startNewSession() {
        currentSessionId = null
        currentSessionIdFlow.value = null
        _messages.value = emptyList()
        conversationHistory.clear()
        runningActionLog.clear()
        _actionHistory.value = emptyList()
        _currentTokens.value = 0
        _isProcessing.value = false
        _agentState.value = AgentState.IDLE
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            chatDao.deleteSession(sessionId)
            if (currentSessionId == sessionId) {
                startNewSession()
            }
        }
    }

    fun deletePreference(key: String) {
        viewModelScope.launch {
            prefDao.deletePreference(key)
        }
    }

    fun setSelectedPersona(persona: String) {
        _selectedPersona.value = persona
        viewModelScope.launch {
            prefDao.insertPreference(UserPreference("cfg_selected_persona", persona))
        }
    }

    fun setCustomPersonaPrompt(prompt: String) {
        _customPersonaPrompt.value = prompt
        viewModelScope.launch {
            prefDao.insertPreference(UserPreference("cfg_custom_persona_prompt", prompt))
        }
    }

    fun setDuckMediaOnSpeech(enabled: Boolean) {
        _duckMediaOnSpeech.value = enabled
        viewModelScope.launch {
            prefDao.insertPreference(UserPreference("cfg_duck_media_on_speech", enabled.toString()))
        }
    }

    fun saveUserMemory(key: String, value: String) {
        viewModelScope.launch {
            prefDao.insertPreference(UserPreference(key, value))
        }
    }

    fun deleteUserMemory(key: String) {
        viewModelScope.launch {
            prefDao.deletePreference(key)
        }
    }

    private fun getRelevantMemoriesString(userQuery: String): String {
        val memoriesList = userPreferences.value.filter { !it.key.startsWith("cfg_") }
        if (memoriesList.isEmpty()) {
            return "- No saved memories yet."
        }

        // Core static user facts that are always preserved
        val coreKeys = setOf("user_name", "user_nickname", "language", "preferred_language", "role", "city", "occupation")
        val coreMemories = memoriesList.filter { it.key.lowercase() in coreKeys }
        val otherMemories = memoriesList.filter { it.key.lowercase() !in coreKeys }

        val queryTokens = userQuery.lowercase()
            .split(Regex("[^a-zA-Z0-9ก-๙]+"))
            .filter { it.length >= 2 }

        val selected = mutableListOf<UserPreference>()
        selected.addAll(coreMemories)

        if (queryTokens.isNotEmpty()) {
            val ranked = otherMemories.map { mem ->
                val combinedText = "${mem.key} ${mem.value}".lowercase()
                val score = queryTokens.count { token -> combinedText.contains(token) }
                Pair(mem, score)
            }.filter { it.second > 0 }
             .sortedByDescending { it.second }

            for ((mem, _) in ranked) {
                if (selected.size >= 8) break
                if (!selected.contains(mem)) {
                    selected.add(mem)
                }
            }
        }

        // Fallback cap: if under limit, take top recent items up to 6 total items max
        if (selected.size < 6) {
            for (mem in otherMemories) {
                if (selected.size >= 6) break
                if (!selected.contains(mem)) {
                    selected.add(mem)
                }
            }
        }

        return selected.joinToString("\n") { "- [MEM]: ${it.key} = ${it.value}" }
    }

    fun isScreenQuery(text: String): Boolean {
        val lower = text.lowercase()
        val keywords = listOf(
            "หน้าจอ", "ดูหน้าจอ", "แปลหน้าจอ", "อ่านหน้าจอ", "หน้าจอนี้", "วิเคราะห์หน้าจอ",
            "ช่วยดูหน้าจอ", "แปลภาษาหน้าจอ", "แปลข้อความบนหน้าจอ", "แคปหน้าจอ",
            "screen", "look at screen", "translate screen", "what's on screen",
            "see screen", "analyze screen", "read screen", "screen context"
        )
        return keywords.any { lower.contains(it) }
    }

    fun loadSession(sessionId: Long) {
        viewModelScope.launch {
            val sessionWithMsgs = chatDao.getSessionWithMessagesSync(sessionId)
            sessionWithMsgs?.let {
                currentSessionId = it.session.id
                currentSessionIdFlow.value = it.session.id
                
                val newHistory = mutableListOf<Content>()
                val newMsgs = mutableListOf<ChatMessage>()
                
                for (entity in it.messages) {
                    val role = entity.role
                    val contentText = entity.content
                    when (role) {
                        "user" -> {
                            newMsgs.add(ChatMessage.User(contentText))
                            newHistory.add(Content(parts = listOf(Part(text = contentText)), role = "user"))
                        }
                        "agent", "model" -> {
                            newMsgs.add(ChatMessage.Agent(contentText))
                            newHistory.add(Content(parts = listOf(Part(text = contentText)), role = "model"))
                        }
                        "system" -> {
                            newMsgs.add(ChatMessage.System(contentText))
                        }
                        "task_execution" -> {
                            val actType = entity.actionType ?: "ACTION"
                            val actTarget = entity.actionTarget ?: contentText
                            val status = entity.status ?: "Completed"
                            val snippet = entity.resultSnippet
                            newMsgs.add(
                                ChatMessage.TaskExecution(
                                    action = AgentAction(actType, actTarget),
                                    status = status,
                                    resultSnippet = snippet
                                )
                            )
                        }
                    }
                }
                
                _messages.value = newMsgs
                conversationHistory.clear()
                runningActionLog.clear()
                conversationHistory.addAll(newHistory)
                _actionHistory.value = emptyList()
                
                // Set initial tokens estimation based on loaded history length
                _currentTokens.value = getEstimatedTokenCount()
                
                _isProcessing.value = false
                _agentState.value = AgentState.IDLE
            }
        }
    }

    private val tools = listOf(
        mapOf(
            "functionDeclarations" to listOf(
                mapOf(
                    "name" to "control_phone",
                    "description" to "Perform the user's Android screen task through fresh screenshots and one action per observation. Ask for user help for sign-in, CAPTCHA, and sensitive actions.",
                    "parameters" to mapOf("type" to "OBJECT", "properties" to mapOf(
                        "task" to mapOf("type" to "STRING", "description" to "The complete user-authorized screen task")
                    ), "required" to listOf("task"))
                ),
                mapOf(
                    "name" to "remember_preference",
                    "description" to "Remember a user preference or fact like their favorite color, schedule, etc.",
                    "parameters" to mapOf(
                        "type" to "OBJECT",
                        "properties" to mapOf(
                            "key" to mapOf("type" to "STRING"),
                            "value" to mapOf("type" to "STRING")
                        ),
                        "required" to listOf("key", "value")
                    )
                ),
                mapOf(
                    "name" to "execute_linux_command",
                    "description" to "Execute a real linux / bash command inside the local PRoot guest environment sandbox (e.g., ls, pwd, neofetch, apt install, python3). Returns the exact standard output from the guest bash terminal shell.",
                    "parameters" to mapOf(
                        "type" to "OBJECT",
                        "properties" to mapOf(
                            "command" to mapOf(
                                "type" to "STRING",
                                "description" to "The full linux / bash command line to run (e.g. 'neofetch', 'ls -la', 'apt install python3', 'python3 -c \"print(2+2)\"')"
                            ),
                            "distro" to mapOf(
                                "type" to "STRING",
                                "description" to "Optional. The alias of the proot-distro to execute this command in (e.g. 'ubuntu', 'debian', 'alpine', 'kali', 'archlinux'). If specified, you will login to this distro and REMAIN in it for subsequent commands."
                            ),
                            "session_name" to mapOf(
                                "type" to "STRING",
                                "description" to "Optional. The name of the terminal session/tab to run this command in (e.g. 'Chrome', 'ScriptRunner', 'PackageInstaller'). If specified, it will execute this command in that specific terminal session. If the session doesn't exist, a new terminal session with this name is automatically created/summoned!"
                            )
                        ),
                        "required" to listOf("command")
                    )
                ),

                mapOf(
                    "name" to "read_file",
                    "description" to "Directly read any file on Android storage or PRoot Linux container (supports all file extensions, text files UTF-8, or binary files via Base64 encoding).",
                    "parameters" to mapOf(
                        "type" to "OBJECT",
                        "properties" to mapOf(
                            "file_path" to mapOf(
                                "type" to "STRING",
                                "description" to "Path to the file to read (e.g. '/sdcard/Download/notes.txt', '/root/script.py', '~/data.json', '/etc/os-release')"
                            ),
                            "is_binary_base64" to mapOf(
                                "type" to "BOOLEAN",
                                "description" to "Optional. Set to true if reading binary files (images, PDF, zip, binaries) to receive Base64 encoded output."
                            ),
                            "distro" to mapOf(
                                "type" to "STRING",
                                "description" to "Optional target PRoot guest distro alias (e.g. 'ubuntu', 'debian', 'alpine')."
                            )
                        ),
                        "required" to listOf("file_path")
                    )
                ),
                mapOf(
                    "name" to "write_file",
                    "description" to "Directly write or append content to any file on Android storage or PRoot Linux container (supports text content or Base64 binary data). Automatically creates parent folders.",
                    "parameters" to mapOf(
                        "type" to "OBJECT",
                        "properties" to mapOf(
                            "file_path" to mapOf(
                                "type" to "STRING",
                                "description" to "Path to write or create file (e.g. '/sdcard/Documents/output.txt', '/root/app.py', '/sdcard/Download/image.png')"
                            ),
                            "content" to mapOf(
                                "type" to "STRING",
                                "description" to "Text content OR Base64 encoded string if writing binary data."
                            ),
                            "is_binary_base64" to mapOf(
                                "type" to "BOOLEAN",
                                "description" to "Optional. Set to true if content is Base64 encoded binary data."
                            ),
                            "append" to mapOf(
                                "type" to "BOOLEAN",
                                "description" to "Optional. Set to true to append to existing file instead of overwriting."
                            ),
                            "distro" to mapOf(
                                "type" to "STRING",
                                "description" to "Optional target PRoot guest distro alias (e.g. 'ubuntu', 'debian', 'alpine')."
                            )
                        ),
                        "required" to listOf("file_path", "content")
                    )
                ),
                mapOf(
                    "name" to "list_directory",
                    "description" to "List contents of a directory on Android storage or PRoot Linux container, including file names, sizes, types, and modification timestamps.",
                    "parameters" to mapOf(
                        "type" to "OBJECT",
                        "properties" to mapOf(
                            "path" to mapOf(
                                "type" to "STRING",
                                "description" to "Directory path to list (e.g. '/sdcard/Download', '/root', '/etc')."
                            ),
                            "distro" to mapOf(
                                "type" to "STRING",
                                "description" to "Optional target PRoot guest distro alias (e.g. 'ubuntu', 'debian', 'alpine')."
                            )
                        ),
                        "required" to listOf("path")
                    )
                )
            )
        )
    )

    fun setListening(listening: Boolean) {
        if (listening) {
            _agentState.value = AgentState.LISTENING
        } else {
            if (_agentState.value == AgentState.LISTENING) {
                _agentState.value = AgentState.IDLE
            }
        }
    }

    private var currentAgentJob: kotlinx.coroutines.Job? = null
    private var liveScreenStreamJob: kotlinx.coroutines.Job? = null


    fun stopExecution() {
        currentAgentJob?.cancel()
        currentAgentJob = null
        stopInactivityWatchdog()
        wasWaitingForScreenLoad = false
        _isProcessing.value = false
        _agentState.value = AgentState.IDLE
        _activeAction.value = null
        addReasoningStep("Execution Interrupted", "User manually stopped AI execution.", "WARNING", "USER")
        _messages.value += ChatMessage.Agent("Execution has been stopped at your command. You can give new instructions anytime.")
    }

    fun sendCommand(text: String) {
        if (text.isBlank()) return

        // Confirmation gate: a sensitive action (send/pay/confirm) is waiting for the user's answer
        val pendingSensitive = pendingSensitiveActionDesc
        if (pendingSensitive != null) {
            val norm = text.trim().lowercase()
            val affirm = norm in setOf("y", "yes", "ok", "okay", "confirm", "sure", "go", "go ahead",
                "ยืนยัน", "ใช่", "ตกลง", "ทำเลย", "เอาเลย", "ดำเนินการ", "ส่ง", "จ่าย", "โอเค")
            val deny = norm in setOf("n", "no", "cancel", "stop", "skip",
                "ไม่", "ยกเลิก", "อย่า", "ไม่ต้อง", "ไม่เอา", "พอ")
            if (affirm || deny) {
                pendingSensitiveActionDesc = null
                _messages.value += ChatMessage.User(text)
                if (affirm) {
                    sensitiveActionApproved = true
                    addReasoningStep("Sensitive action confirmed", "User approved: $pendingSensitive", "SUCCESS", "USER")
                    conversationHistory.add(Content(parts = listOf(Part(
                        text = "[USER_CONFIRMATION]: The user explicitly CONFIRMED the sensitive action ($pendingSensitive). You may perform it now, exactly once."
                    )), role = "user"))
                } else {
                    addReasoningStep("Sensitive action declined", "User declined: $pendingSensitive", "WARNING", "USER")
                    conversationHistory.add(Content(parts = listOf(Part(
                        text = "[USER_CONFIRMATION]: The user DECLINED the sensitive action ($pendingSensitive). Do NOT perform it; continue the task without it or ask for an alternative."
                    )), role = "user"))
                }
                _isProcessing.value = true
                _agentState.value = AgentState.THINKING
                startInactivityWatchdog()
                currentAgentJob = appScope.launch { executeGeminiCall() }
                return
            }
            // Any other message supersedes the pending confirmation
            pendingSensitiveActionDesc = null
        }

        if (_isProcessing.value) {
            currentAgentJob?.cancel()
            currentAgentJob = null
                addReasoningStep("Task Interrupted & Replaced", "New instruction received. Cancelling current task and starting new request.", "WARNING", "USER")
        }
        
        _messages.value += ChatMessage.User(text)

        if (!isProviderAndModelConfigured()) {
            _messages.value += ChatMessage.ProviderConfigRequired(
                "AI Provider or Model is not configured yet. Please select your AI Provider, Model, and enter your API Key before sending commands."
            )
            _isProcessing.value = false
            _agentState.value = AgentState.IDLE
            return
        }

        _isProcessing.value = true
        _agentState.value = AgentState.THINKING
        wasWaitingForScreenLoad = true
        autoContinueCount = 0
        fallbackRetryCount = 0
        actionsExecutedInCurrentTurn = 0
        _currentStep.value = 0
        parseRetryCount = 0
        installedAppsCache = null
        com.example.agent.AgentDiagnostics.recordEvent("backend", "task started: " + text.take(120))
        startInactivityWatchdog()

        clearReasoningChain()
        addReasoningStep("User Query Received", "Processing command text: \"$text\"", "SUCCESS", "USER")
        addReasoningStep("Initiating Reasoning Engine", "Loading historical conversational context.", "RUNNING", "SYSTEM")

        conversationHistory.add(Content(parts = listOf(Part(text = text)), role = "user"))

        currentAgentJob = appScope.launch {
            try {
                if (currentSessionId == null) {
                    val newSession = ChatSession(
                        title = if (text.length > 30) text.take(27) + "..." else text,
                        provider = _provider.value,
                        modelName = _modelName.value.trim()
                    )
                    val id = chatDao.insertSession(newSession)
                    currentSessionId = id
                    currentSessionIdFlow.value = id
                } else {
                    currentSessionId?.let { 
                        chatDao.updateSessionTimestamp(it, System.currentTimeMillis()) 
                    }
                }
                executeGeminiCall()
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("Zhypix", "Agent job cancelled via user interrupt")
                _isProcessing.value = false
                _agentState.value = AgentState.IDLE
                _activeAction.value = null
            } catch (e: Exception) {
                Log.e("Zhypix", "Error in agent job execution", e)
                _isProcessing.value = false
                _agentState.value = AgentState.IDLE
                _activeAction.value = null
            }
        }
    }

    private suspend fun executeGeminiCall() {
        try {
            val callApiKey = if (_apiKey.value.isNotBlank()) {
                _apiKey.value
            } else if (_provider.value == "Gemini") {
                BuildConfig.GEMINI_API_KEY
            } else {
                ""
            }
            
            if (callApiKey.isBlank() || (callApiKey == "MY_GEMINI_API_KEY" && _provider.value == "Gemini") || _modelName.value.isBlank() || _provider.value.isBlank()) {
                _messages.value += ChatMessage.ProviderConfigRequired(
                    "AI Provider or Model is not configured yet. Please select your AI Provider, Model, and enter your API Key before sending commands."
                )
                _isProcessing.value = false
                _agentState.value = AgentState.IDLE
                return
            }
            
            val callUrl = if (_baseUrl.value.isNotBlank()) _baseUrl.value else "https://generativelanguage.googleapis.com/"
            val callModel = _modelName.value
            
            val isVisionActive = isVisionActive()
                
            checkAndSummarizeHistory(callApiKey, callUrl, callModel)

                val isLinuxDesktop = com.example.utils.LinuxTerminalSimulator.isDesktopScreenActive.value
                val metrics = getApplication<android.app.Application>().resources.displayMetrics
                val screenWidth = if (isLinuxDesktop) 1280 else metrics.widthPixels
                val screenHeight = if (isLinuxDesktop) 720 else metrics.heightPixels
                val screenDensity = if (isLinuxDesktop) 1.0f else metrics.density

                val sysInstruction: Content? = null

                // Legacy system prompts removed.

                val thinkingCfg = if (_thinkingLevel.value == "NONE") {
                    null
                } else {
                    ThinkingConfig(_thinkingLevel.value)
                }

                // Give ample room for complete reasoning and JSON tool calls to prevent truncations
                val dynamicMaxTokens = if (thinkingCfg != null) {
                    null // CRITICAL: Do NOT set maxOutputTokens when using thinking mode (high thinking level)
                } else {
                    8192
                }

                // Dynamically construct tools. If webSearch is enabled, add Jina tools (and googleSearch for Gemini).
                val finalTools = if (_webSearchEnabled.value) {
                    val searchTools = listOf(
                        mapOf(
                            "name" to "web_search",
                            "description" to "Search the web using DuckDuckGo to get up-to-date information and links. If the snippet provided in the search results is not enough to answer the user's question, use the read_url tool on the most relevant link to read its full content.",
                            "parameters" to mapOf(
                                "type" to "OBJECT",
                                "properties" to mapOf("query" to mapOf("type" to "STRING", "description" to "The search query.")),
                                "required" to listOf("query")
                            )
                        ),
                        mapOf(
                            "name" to "read_url",
                            "description" to "Read the full contents of a specific URL. It extracts and returns the core text of the webpage as Markdown.",
                            "parameters" to mapOf(
                                "type" to "OBJECT",
                                "properties" to mapOf("url" to mapOf("type" to "STRING", "description" to "The exact URL to fetch and read.")),
                                "required" to listOf("url")
                            )
                        )
                    )
                    
                    val combinedTools = tools.toMutableList()
                    val functionDecls = (combinedTools[0]["functionDeclarations"] as List<Map<String, Any>>).toMutableList()
                    functionDecls.addAll(searchTools)
                    combinedTools[0] = mapOf("functionDeclarations" to functionDecls.toList())
                    
                    if (_provider.value == "Gemini") {
                        combinedTools + mapOf("googleSearchRetrieval" to emptyMap<String, Any>())
                    } else {
                        combinedTools
                    }
                } else {
                    tools
                }

                pruneOldImagesFromHistory(1)
                com.example.utils.ContextBudget.pruneOldHierarchies(conversationHistory, 1)

                val requestContents = if (isVisionActive) {
                    conversationHistory.toList()
                } else {
                    conversationHistory.map { content ->
                        content.copy(parts = content.parts.filter { it.inlineData == null })
                    }
                }

                val request = GenerateContentRequest(
                    contents = requestContents,
                    generationConfig = GenerationConfig(
                        temperature = 0.2f,
                        thinkingConfig = thinkingCfg,
                        maxOutputTokens = dynamicMaxTokens
                    ),
                    tools = finalTools,
                    systemInstruction = sysInstruction
                )

                addReasoningStep("Querying AI Provider (${_provider.value})", "Model: $callModel. Waiting for response candidates...", "RUNNING", "AI")

                // Retry loop for transient HTTP 5xx, 429 rate-limit, or network glitch issues
                var lastApiException: Exception? = null
                val maxRetries = 4
                for (attempt in 1..maxRetries) {
                    try {
                        // Construct the correct path format based on the selected provider
                        if (_provider.value == "Gemini") {
                    val fullPath = "${callUrl.removeSuffix("/")}/v1beta/models/$callModel:generateContent"
                    
                    val historyTrace = conversationHistory.joinToString("\n") { msg ->
                        val partDesc = msg.parts.joinToString(", ") { part ->
                            when {
                                part.text != null -> "Text"
                                part.functionCall != null -> "FuncCall(${part.functionCall.name})"
                                part.functionResponse != null -> "FuncResp(${part.functionResponse.name})"
                                part.inlineData != null -> "Image(${part.inlineData.mimeType})"
                                else -> "Unknown"
                            }
                        }
                        "> [${msg.role ?: "no-role"}] parts: [$partDesc]"
                    }
                    addReasoningStep("Payload Assembly", "History trace:\n$historyTrace", "INFO", "SYSTEM")
                    
                    val response = RetrofitClient.getService(callUrl).generateContent(fullPath, callApiKey, request)
                    val candidatesList = response.candidates
                    val firstCandidate = candidatesList?.firstOrNull()
                    val feedback = response.promptFeedback
                    
                    if (candidatesList.isNullOrEmpty()) {
                        Log.w("Zhypix", "No candidates found in Gemini response. Prompt feedback: $feedback")
                        if (feedback != null) {
                            addReasoningStep("API Blocked", "Request was blocked. Reason: ${feedback.blockReason}. Safety Ratings: ${feedback.safetyRatings?.joinToString { "${it.category}: ${it.probability}" }}", "FAILED", "AI")
                        } else {
                            addReasoningStep("Empty Response", "Received 0 response candidates from the Gemini server.", "FAILED", "AI")
                        }
                    } else if (firstCandidate?.content == null) {
                        Log.w("Zhypix", "First candidate has null content. Finish reason: ${firstCandidate?.finishReason}")
                        addReasoningStep("API Candidate Null Content", "Finish reason: ${firstCandidate?.finishReason}. Safety: ${firstCandidate?.safetyRatings?.joinToString { "${it.category}: ${it.probability}" }}", "FAILED", "AI")
                    } else {
                        addReasoningStep("API Success", "Response processed successfully.", "SUCCESS", "AI")
                    }
                    
                    firstCandidate?.groundingMetadata?.let { metadata ->
                        val queries = metadata.webSearchQueries
                        if (!queries.isNullOrEmpty()) {
                            addReasoningStep(
                                title = "Google Web Search Grounding",
                                description = "Queries run on Google Search: ${queries.joinToString(", ") { "\"$it\"" }}",
                                status = "SUCCESS",
                                type = "SYSTEM"
                            )
                        }
                        val chunks = metadata.groundingChunks?.mapNotNull { it.web }
                        if (!chunks.isNullOrEmpty()) {
                            val sourcesText = chunks.joinToString("\n") { chunk ->
                                val title = chunk.title ?: "Resource"
                                val uri = chunk.uri ?: ""
                                "• $title\n  $uri"
                            }
                            addReasoningStep(
                                title = "Retrieved Web Citations",
                                description = "Live verified sources consulted:\n$sourcesText",
                                status = "SUCCESS",
                                type = "INFO"
                            )
                        }
                    }
                    
                    handleResponse(firstCandidate)
                } else {
                    // Custom providers & Claude via OpenAI compatible facade
                    val trimmedPath = callUrl.removeSuffix("/")
                    val fullPath = if (trimmedPath.endsWith("/v1/chat/completions")) trimmedPath
                        else if (trimmedPath.endsWith("/v1")) "$trimmedPath/chat/completions"
                        else "$trimmedPath/v1/chat/completions"
                    
                    val openAiMessages = mutableListOf<Map<String, Any>>()
                    
                    // System prompt
                    // No legacy system instruction.
                    
                    // Conversation history
                    for (msg in conversationHistory) {
                        val role = when (msg.role) {
                            "model" -> "assistant"
                            "function" -> "tool"
                            else -> "user"
                        }
                        
                        val toolCalls = msg.parts.mapNotNull { it.functionCall }.map {
                            mapOf(
                                "id" to "call_${it.name}", // stable id based on name
                                "type" to "function",
                                "function" to mapOf(
                                    "name" to it.name,
                                    "arguments" to org.json.JSONObject(it.args ?: emptyMap<String,Any>()).toString()
                                )
                            )
                        }.takeIf { it.isNotEmpty() }
                        
                        val funcResponses = msg.parts.mapNotNull { it.functionResponse }
                        if (funcResponses.isNotEmpty()) {
                            for (resp in funcResponses) {
                                openAiMessages.add(mapOf(
                                    "role" to "tool",
                                    "tool_call_id" to "call_${resp.name}",
                                    "name" to resp.name,
                                    "content" to org.json.JSONObject(resp.response).toString()
                                ))
                            }
                        }
                        
                        // Mixed content (Text + Image)
                        val contentPartsList = mutableListOf<Map<String, Any>>()
                        val textContent = msg.parts.mapNotNull { it.text }.joinToString("\n")
                        if (textContent.isNotBlank()) {
                            contentPartsList.add(mapOf("type" to "text", "text" to textContent))
                        }
                        
                        val visionModeVal = _visionMode.value
                        val isVisionModel = when (visionModeVal) {
                            "YES" -> true
                            "NO" -> false
                            else -> modelSupportsVision(callModel)
                        }
                        
                        if (isVisionModel) {
                            msg.parts.forEach { part ->
                                part.inlineData?.let { img ->
                                    contentPartsList.add(mapOf(
                                        "type" to "image_url",
                                        "image_url" to mapOf("url" to "data:${img.mimeType};base64,${img.data}")
                                    ))
                                }
                            }
                        }
                        
                        if (contentPartsList.isNotEmpty() || toolCalls != null) {
                            val msgMap = mutableMapOf<String, Any>("role" to role)
                            if (contentPartsList.isNotEmpty()) {
                                msgMap["content"] = if (contentPartsList.size == 1 && textContent.isNotBlank()) textContent else contentPartsList
                            } else {
                                msgMap["content"] = ""
                            }
                            if (toolCalls != null) {
                                msgMap["tool_calls"] = toolCalls
                            }
                            openAiMessages.add(msgMap)
                        }
                    }
                    
                    val baseOpenAiTools = mutableListOf(
                        mapOf("type" to "function", "function" to mapOf(
                            "name" to "control_phone",
                            "description" to "Perform the user's Android screen task. Sign-in and sensitive actions need user participation.",
                            "parameters" to mapOf("type" to "object", "properties" to mapOf(
                                "task" to mapOf("type" to "string")
                            ), "required" to listOf("task"))
                        )),
                        mapOf(
                            "type" to "function",
                            "function" to mapOf(
                                "name" to "remember_preference",
                                "description" to "Remember a user preference or fact like their favorite color, schedule, etc.",
                                "parameters" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "key" to mapOf("type" to "string"),
                                        "value" to mapOf("type" to "string")
                                    ),
                                    "required" to listOf("key", "value")
                                )
                            )
                        ),
                        mapOf(
                            "type" to "function",
                            "function" to mapOf(
                                "name" to "execute_linux_command",
                                "description" to "Execute a linux / bash command inside the local PRoot guest environment sandbox. The shell state is stateful (e.g. if you 'cd' or switch distro, it remembers).",
                                "parameters" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "command" to mapOf(
                                            "type" to "string",
                                            "description" to "The full linux / bash command line to run (e.g. 'neofetch', 'ls -la', 'apt install python3')."
                                        ),
                                        "distro" to mapOf(
                                            "type" to "string",
                                            "description" to "Optional. The alias of the proot-distro to execute this command in (e.g. 'ubuntu', 'debian', 'alpine'). If specified, you will login to this distro and REMAIN in it for subsequent commands."
                                        )
                                    ),
                                    "required" to listOf("command")
                                )
                            )
                        ),

                    )
                    
                    baseOpenAiTools.add(mapOf(
                        "type" to "function",
                        "function" to mapOf(
                            "name" to "read_file",
                            "description" to "Directly read any file on Android storage or PRoot Linux container (supports text files or binary Base64).",
                            "parameters" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "file_path" to mapOf("type" to "string", "description" to "Path to the file to read."),
                                    "is_binary_base64" to mapOf("type" to "boolean", "description" to "Set to true for binary files to receive Base64 output."),
                                    "distro" to mapOf("type" to "string", "description" to "Optional target PRoot guest distro alias.")
                                ),
                                "required" to listOf("file_path")
                            )
                        )
                    ))
                    baseOpenAiTools.add(mapOf(
                        "type" to "function",
                        "function" to mapOf(
                            "name" to "write_file",
                            "description" to "Directly write or append content to any file on Android storage or PRoot Linux container (supports text or Base64 binary).",
                            "parameters" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "file_path" to mapOf("type" to "string", "description" to "Path to write or create file."),
                                    "content" to mapOf("type" to "string", "description" to "Text content OR Base64 encoded binary string."),
                                    "is_binary_base64" to mapOf("type" to "boolean", "description" to "Set to true if content is Base64 binary."),
                                    "append" to mapOf("type" to "boolean", "description" to "Set to true to append to existing file."),
                                    "distro" to mapOf("type" to "string", "description" to "Optional target PRoot guest distro alias.")
                                ),
                                "required" to listOf("file_path", "content")
                            )
                        )
                    ))
                    baseOpenAiTools.add(mapOf(
                        "type" to "function",
                        "function" to mapOf(
                            "name" to "list_directory",
                            "description" to "List contents of a directory on Android storage or PRoot Linux container.",
                            "parameters" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "path" to mapOf("type" to "string", "description" to "Directory path to list."),
                                    "distro" to mapOf("type" to "string", "description" to "Optional target PRoot guest distro alias.")
                                ),
                                "required" to listOf("path")
                            )
                        )
                    ))

                    if (_webSearchEnabled.value) {
                        baseOpenAiTools.add(mapOf(
                            "type" to "function",
                            "function" to mapOf(
                                "name" to "web_search",
                                "description" to "Search the web using DuckDuckGo to get up-to-date information and links. If the snippet provided in the search results is not enough to answer the user's question, use the read_url tool on the most relevant link to read its full content.",
                                "parameters" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf("query" to mapOf("type" to "string", "description" to "The search query.")),
                                    "required" to listOf("query")
                                )
                            )
                        ))
                        baseOpenAiTools.add(mapOf(
                            "type" to "function",
                            "function" to mapOf(
                                "name" to "read_url",
                                "description" to "Read the full contents of a specific URL. It extracts and returns the core text of the webpage as Markdown.",
                                "parameters" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf("url" to mapOf("type" to "string", "description" to "The exact URL to fetch and read.")),
                                    "required" to listOf("url")
                                )
                            )
                        ))
                    }
                    
                    val openAiTools = baseOpenAiTools.toList()

                    val openAiRequest = mutableMapOf<String, Any>(
                        "model" to callModel,
                        "messages" to openAiMessages,
                        "tools" to openAiTools,
                        "tool_choice" to "auto",
                        "temperature" to 0.2
                    )
                    
                    if (dynamicMaxTokens != null && dynamicMaxTokens > 0) {
                        openAiRequest["max_tokens"] = dynamicMaxTokens
                    }
                    
                    if (_thinkingLevel.value != "NONE") {
                        val reasoningEffortString = when (_thinkingLevel.value) {
                            "HIGH", "MAX", "ULTRA" -> "high"
                            "MEDIUM", "STANDARD" -> "medium"
                            else -> "low"
                        }
                        openAiRequest["reasoning_effort"] = reasoningEffortString
                    }
                    
                    val authHeader = if (callApiKey.isNotBlank()) "Bearer $callApiKey" else null
                    
                    val historyTrace = openAiMessages.joinToString("\n") { msg ->
                        "> [${msg["role"] ?: "no-role"}] keys: ${msg.keys}, content type: ${msg["content"]?.javaClass?.simpleName ?: "null"}"
                    }
                    addReasoningStep("Payload Assembly", "History trace (OpenAI):\n$historyTrace", "INFO", "SYSTEM")
                    
                    val response = RetrofitClient.getService(callUrl).generateOpenAiContent(
                        url = fullPath,
                        authHeader = authHeader,
                        request = openAiRequest
                    )
                    
                    val choice = response.choices?.firstOrNull()
                    val answerText = choice?.message?.content as? String
                    
                    val partsList = mutableListOf<Part>()
                    if (!answerText.isNullOrBlank()) {
                        partsList.add(Part(text = answerText))
                    }
                    
                    choice?.message?.tool_calls?.forEach { tc ->
                        partsList.add(Part(functionCall = FunctionCall(
                            name = tc.function.name,
                            args = try {
                                val obj = org.json.JSONObject(tc.function.arguments)
                                jsonObjectToMap(obj)
                            } catch (e: Exception) { emptyMap() }
                        )))
                    }

                    if (partsList.isNotEmpty()) {
                        addReasoningStep("API Success (OpenAI)", "Response candidate processed successfully with ${partsList.size} parts.", "SUCCESS", "AI")
                        val genContent = Content(parts = partsList, role = "model")
                        handleResponse(Candidate(content = genContent, finishReason = choice?.finish_reason ?: "STOP"))
                    } else {
                        addReasoningStep("Empty OpenAI Response", "No text content or tool calls found in the response choices. Choice object: $choice", "FAILED", "AI")
                        handleResponse(null)
                    }
                }

                lastApiException = null
                break // Call succeeded, exit retry loop!
            } catch (e: HttpException) {
                lastApiException = e
                val code = e.code()
                if ((code in 500..599 || code == 429) && attempt < maxRetries) {
                    val waitMs = attempt * 2000L
                    _agentState.value = AgentState.THINKING
                    addReasoningStep("API Transient Error (HTTP $code)", "Server returned HTTP $code on attempt $attempt/$maxRetries. Retrying automatically in ${waitMs / 1000}s...", "WARNING", "SYSTEM")
                    delay(waitMs)
                } else {
                    throw e
                }
            } catch (e: java.io.IOException) {
                lastApiException = e
                if (attempt < maxRetries) {
                    val waitMs = attempt * 2000L
                    _agentState.value = AgentState.THINKING
                    addReasoningStep("API Network Glitch", "Network glitch on attempt $attempt/$maxRetries (${e.message ?: "Connection issue"}). Retrying automatically in ${waitMs / 1000}s...", "WARNING", "SYSTEM")
                    delay(waitMs)
                } else {
                    throw e
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastApiException = e
                if (attempt < maxRetries) {
                    val waitMs = attempt * 2000L
                    _agentState.value = AgentState.THINKING
                    addReasoningStep("API Connection Error", "Connection error on attempt $attempt/$maxRetries (${e.message ?: "Unknown error"}). Retrying automatically in ${waitMs / 1000}s...", "WARNING", "SYSTEM")
                    delay(waitMs)
                } else {
                    throw e
                }
            }
        }

        if (lastApiException != null) {
            throw lastApiException
        }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("Zhypix", "executeGeminiCall job cancelled")
                stopInactivityWatchdog()
                wasWaitingForScreenLoad = false
                _isProcessing.value = false
                _agentState.value = AgentState.IDLE
                _activeAction.value = null
                throw e
            } catch (e: Exception) {
                Log.e("Zhypix", "API Error", e)
                val errorDetails = if (e is HttpException) {
                    val body = try { e.response()?.errorBody()?.string() } catch (ex: Exception) { null }
                    "HTTP ${e.code()} ${e.message() ?: ""}\nResponse details: ${body ?: "No details available"}"
                } else {
                    e.message ?: "Unknown error"
                }
                addReasoningStep("API execution failed", errorDetails, "FAILED", "SYSTEM")

                if (fallbackRetryCount < 3) {
                    fallbackRetryCount++
                    addReasoningStep(
                        "Fallback Recovery Triggered",
                        "Encountered error ($errorDetails). Automatically continuing execution (Attempt $fallbackRetryCount/3) with fallback command: \"Continue from where you left off.\"",
                        "WARNING",
                        "SYSTEM"
                    )
                    conversationHistory.add(Content(
                        role = "user",
                        parts = listOf(Part(text = "Continue from where you left off."))
                    ))
                    delay(2000L)
                    _agentState.value = AgentState.THINKING
                    executeGeminiCall()
                } else {
                    fallbackRetryCount = 0
                    _messages.value += ChatMessage.Agent("Connection error after multiple fallback attempts: $errorDetails")
                    stopInactivityWatchdog()
                    wasWaitingForScreenLoad = false
                    _isProcessing.value = false
                    _agentState.value = AgentState.IDLE
                }
            }
        }

    private suspend fun streamAgentMessage(fullText: String) {
        val messageId = java.util.UUID.randomUUID().toString()
        val agentMsg = ChatMessage.Agent("", id = messageId)
        _messages.value = _messages.value + agentMsg

        val length = fullText.length
        var currentIndex = 0
        while (currentIndex < length) {
            val remaining = length - currentIndex
            val chunkSize = if (remaining > 6) {
                (2..5).random()
            } else {
                remaining
            }
            currentIndex += chunkSize
            val currentText = fullText.substring(0, currentIndex)

            val currentMsgs = _messages.value.toMutableList()
            val idx = currentMsgs.indexOfFirst { it.id == messageId }
            if (idx != -1) {
                currentMsgs[idx] = ChatMessage.Agent(currentText, id = messageId)
            }
            _messages.value = currentMsgs
            
            val d = if (length > 400) 8L else 18L
            delay(d)
        }

        if (fullText.isNotBlank()) {
            com.example.utils.SystemNotificationHelper.showAiResponseNotification(
                context = getApplication(),
                title = "Zhypix AI Response",
                messageText = fullText
            )
        }

        val shouldSpeak = if (_isAppActive.value) _inAppTtsEnabled.value else _ttsAutoSpeak.value
        if (shouldSpeak && fullText.isNotBlank()) {
            speakText(fullText) {
                if (_continuousVoiceMode.value) {
                    viewModelScope.launch {
                        delay(1200) // Wait for speaker audio to fully finish and reverberation to clear
                        while (ttsManager.isPlaying.value) {
                            delay(200)
                        }
                        startListening { text ->
                            val cleanedText = text.trim()
                            val cleanedFull = fullText.trim()
                            if (cleanedText.isNotBlank() && cleanedText != cleanedFull && !cleanedFull.contains(cleanedText)) {
                                sendCommand(cleanedText)
                            } else {
                                Log.d("AgentViewModel", "Ignored echoed TTS audio input: $cleanedText")
                            }
                        }
                    }
                }
            }
        } else if (_continuousVoiceMode.value) {
            viewModelScope.launch {
                delay(800)
                startListening { text ->
                    val cleanedText = text.trim()
                    if (cleanedText.isNotBlank()) {
                        sendCommand(cleanedText)
                    }
                }
            }
        }
    }

    private fun jsonObjectToMap(jsonObject: org.json.JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = jsonObject.get(key)
            map[key] = jsonValueToAny(value)
        }
        return map
    }

    private fun jsonValueToAny(value: Any): Any {
        return when (value) {
            is org.json.JSONObject -> jsonObjectToMap(value)
            is org.json.JSONArray -> {
                val list = mutableListOf<Any>()
                for (i in 0 until value.length()) {
                    list.add(jsonValueToAny(value.get(i)))
                }
                list
            }
            org.json.JSONObject.NULL -> ""
            else -> value
        }
    }

    private suspend fun handleResponse(candidate: Candidate?) {
        if (candidate == null || candidate.content == null) {
            addReasoningStep("No Response Candidates", "Model response container was null or empty.", "FAILED", "AI")
            _messages.value += ChatMessage.Agent("No response from AI.")
            _isProcessing.value = false
            _agentState.value = AgentState.IDLE
            return
        }

        fallbackRetryCount = 0
        addReasoningStep("AI Generative Response Received", "Processing generation candidates & decision pathways.", "SUCCESS", "AI")

        val parts = candidate.content.parts
        val modelTextParts = parts.filter { it.text != null }.map { it.text }

        // Add response text to chat if present
        if (modelTextParts.isNotEmpty()) {
            val fullText = modelTextParts.joinToString("\n")
            addReasoningStep("Displaying Text Response", fullText, "SUCCESS", "AI")
            streamAgentMessage(fullText)
            
            // Save to DB
            if (_autoSaveSettings.value) {
                currentSessionId?.let {
                    chatDao.insertMessage(ChatMessageEntity(sessionId = it, role = "model", content = fullText))
                    chatDao.updateSessionTimestamp(it, System.currentTimeMillis())
                }
            }
        }

        // ALWAYS preserve the original complete model content (including both text and tool calls) in the conversation history
        conversationHistory.add(candidate.content)

        // Process function calls sequentially
        val functionCalls = parts.mapNotNull { it.functionCall }
        if (functionCalls.isNotEmpty()) {
            addReasoningStep("Evaluating Tool Invocation Graph", "Parsing structural function calls (${functionCalls.size} tasks proposed).", "RUNNING", "SYSTEM")
            val responseParts = mutableListOf<Part>()
            for (call in functionCalls) {
                when (call.name) {
                    "control_phone" -> {
                        val task = call.args?.get("task") as? String ?: ""
                        val terminal = runScreenTask(task)
                        pendingTerminalStatus = when (terminal) {
                            is com.example.agent.ScreenAction.Finished -> "finished"
                            is com.example.agent.ScreenAction.NeedsConfirmation -> "needs_confirmation"
                            is com.example.agent.ScreenAction.NeedLogin -> "need_login"
                            is com.example.agent.ScreenAction.EncounteredCaptcha -> "encountered_captcha"
                            else -> "message_user"
                        }
                        pendingTerminalMessage = terminal.message
                        responseParts.add(Part(functionResponse = FunctionResponse(
                            name = call.name, response = mapOf("status" to pendingTerminalStatus.orEmpty(), "message" to terminal.message)
                        )))
                    }

                    "remember_preference" -> {
                        val k = call.args?.get("key") as? String ?: ""
                        val v = call.args?.get("value") as? String ?: ""
                        addReasoningStep("Remember preference tool called", "Key: '$k', Value: '$v'. Saving preference values.", "RUNNING", "TOOL")
                        prefDao.insertPreference(UserPreference(k, v))
                        _messages.value += ChatMessage.TaskExecution(AgentAction("MEMORY", "Saved: $k = $v"), "Completed")
                        addReasoningStep("Memory preference recorded", "Preference SQLite table synchronization succeeded.", "SUCCESS", "TOOL")
                        
                        responseParts.add(Part(functionResponse = FunctionResponse(
                             name = call.name,
                             response = mapOf("status" to "success", "message" to "Saved preference $k")
                        )))
                    }
                    "web_search" -> {
                        val query = call.args?.get("query") as? String ?: ""
                        addReasoningStep("Web Search Dispatch", "Query: \"$query\"", "RUNNING", "TOOL")
                        _messages.value += ChatMessage.TaskExecution(AgentAction("SEARCH", query), "Executing...")
                        
                        var searchSuccess = false
                        var lastSearchErr = ""
                        for (attempt in 1..3) {
                            try {
                                val client = okhttp3.OkHttpClient()
                                val request = okhttp3.Request.Builder()
                                    .url("https://r.jina.ai/https://html.duckduckgo.com/html/?q=${java.net.URLEncoder.encode(query, "UTF-8")}")
                                    .build()
                                    
                                val response = kotlinx.coroutines.Dispatchers.IO.let { dispatcher ->
                                    kotlinx.coroutines.withContext(dispatcher) {
                                        client.newCall(request).execute()
                                    }
                                }
                                
                                if (response.isSuccessful) {
                                    val body = response.body?.string() ?: ""
                                    val sanitizedBody = sanitizeAndTruncateToolOutput(body, 20000)
                                    addReasoningStep("Web Search Completed", "Result returned: ${body.length} bytes (sanitized and truncated to ${sanitizedBody.length} bytes)", "SUCCESS", "TOOL")
                                    responseParts.add(Part(functionResponse = FunctionResponse(
                                        name = call.name,
                                        response = mapOf("status" to "success", "output" to sanitizedBody)
                                    )))
                                    val snippet = if (sanitizedBody.length > 200) sanitizedBody.substring(0, 200).replace("\n", " ") + "..." else sanitizedBody.replace("\n", " ")
                                    val currentMsgs = _messages.value.toMutableList()
                                    val lastIdx = currentMsgs.indexOfLast { it is ChatMessage.TaskExecution && it.action.actionType == "SEARCH" && it.action.target == query }
                                    if (lastIdx != -1) currentMsgs[lastIdx] = ChatMessage.TaskExecution(AgentAction("SEARCH", query), "Completed", resultSnippet = snippet)
                                    searchSuccess = true
                                    break
                                } else {
                                    lastSearchErr = "HTTP ${response.code}"
                                    if (attempt < 3) {
                                        addReasoningStep("Web Search Retry", "Attempt $attempt/3 failed: HTTP ${response.code}. Retrying...", "WARNING", "TOOL")
                                        delay(attempt * 1000L)
                                    }
                                }
                            } catch (e: Exception) {
                                lastSearchErr = e.message ?: "Unknown connection error"
                                if (attempt < 3) {
                                    addReasoningStep("Web Search Retry", "Attempt $attempt/3 connection error: $lastSearchErr. Retrying...", "WARNING", "TOOL")
                                    delay(attempt * 1000L)
                                }
                            }
                        }

                        if (!searchSuccess) {
                            addReasoningStep("Web Search Failed", lastSearchErr, "FAILED", "TOOL")
                            responseParts.add(Part(functionResponse = FunctionResponse(
                                name = call.name,
                                response = mapOf("status" to "error", "error" to lastSearchErr)
                            )))
                            val currentMsgs = _messages.value.toMutableList()
                            val lastIdx = currentMsgs.indexOfLast { it is ChatMessage.TaskExecution && it.action.actionType == "SEARCH" && it.action.target == query }
                            if (lastIdx != -1) currentMsgs[lastIdx] = ChatMessage.TaskExecution(AgentAction("SEARCH", query), "Failed")
                        }
                    }
                    "read_url" -> {
                        val url = call.args?.get("url") as? String ?: ""
                        addReasoningStep("Read URL Dispatch", "URL: \"$url\"", "RUNNING", "TOOL")
                        _messages.value += ChatMessage.TaskExecution(AgentAction("READ_URL", url), "Executing...")
                        
                        var readSuccess = false
                        var lastReadErr = ""
                        for (attempt in 1..3) {
                            try {
                                val client = okhttp3.OkHttpClient()
                                val request = okhttp3.Request.Builder()
                                    .url("https://r.jina.ai/${url}")
                                    .build()
                                    
                                val response = kotlinx.coroutines.Dispatchers.IO.let { dispatcher ->
                                    kotlinx.coroutines.withContext(dispatcher) {
                                        client.newCall(request).execute()
                                    }
                                }
                                
                                if (response.isSuccessful) {
                                    val body = response.body?.string() ?: ""
                                    val sanitizedBody = sanitizeAndTruncateToolOutput(body, 35000)
                                    addReasoningStep("Read URL Completed", "Result returned: ${body.length} bytes (sanitized and truncated to ${sanitizedBody.length} bytes)", "SUCCESS", "TOOL")
                                    responseParts.add(Part(functionResponse = FunctionResponse(
                                        name = call.name,
                                        response = mapOf("status" to "success", "output" to sanitizedBody)
                                    )))
                                    val snippet = if (sanitizedBody.length > 200) sanitizedBody.substring(0, 200).replace("\n", " ") + "..." else sanitizedBody.replace("\n", " ")
                                    val currentMsgs = _messages.value.toMutableList()
                                    val lastIdx = currentMsgs.indexOfLast { it is ChatMessage.TaskExecution && it.action.actionType == "READ_URL" && it.action.target == url }
                                    if (lastIdx != -1) currentMsgs[lastIdx] = ChatMessage.TaskExecution(AgentAction("READ_URL", url), "Completed", resultSnippet = snippet)
                                    readSuccess = true
                                    break
                                } else {
                                    lastReadErr = "HTTP ${response.code}"
                                    if (attempt < 3) {
                                        addReasoningStep("Read URL Retry", "Attempt $attempt/3 failed: HTTP ${response.code}. Retrying...", "WARNING", "TOOL")
                                        delay(attempt * 1000L)
                                    }
                                }
                            } catch (e: Exception) {
                                lastReadErr = e.message ?: "Unknown connection error"
                                if (attempt < 3) {
                                    addReasoningStep("Read URL Retry", "Attempt $attempt/3 connection error: $lastReadErr. Retrying...", "WARNING", "TOOL")
                                    delay(attempt * 1000L)
                                }
                            }
                        }

                        if (!readSuccess) {
                            addReasoningStep("Read URL Failed", lastReadErr, "FAILED", "TOOL")
                            responseParts.add(Part(functionResponse = FunctionResponse(
                                name = call.name,
                                response = mapOf("status" to "error", "error" to lastReadErr)
                            )))
                            val currentMsgs = _messages.value.toMutableList()
                            val lastIdx = currentMsgs.indexOfLast { it is ChatMessage.TaskExecution && it.action.actionType == "READ_URL" && it.action.target == url }
                            if (lastIdx != -1) currentMsgs[lastIdx] = ChatMessage.TaskExecution(AgentAction("READ_URL", url), "Failed")
                        }
                    }
                    "execute_linux_command" -> {
                        val command = call.args?.get("command") as? String ?: ""
                        val distro = call.args?.get("distro") as? String
                        val sessionName = call.args?.get("session_name") as? String
                        
                        addReasoningStep("Linux Guest Command Dispatch", "Command: \"$command\", Distro: ${distro ?: "current"}", "RUNNING", "TOOL")
                        
                        val isInstalled = com.example.utils.LinuxTerminalSimulator.isInstalled.value
                        if (!isInstalled) {
                            addReasoningStep("Command Execution Failed", "Linux Guest Container is not installed. Requesting user initialization.", "FAILED", "TOOL")
                            _messages.value += ChatMessage.TaskExecution(AgentAction("SHELL_ERR", command), "Failed (Container Not Installed)")
                            responseParts.add(Part(functionResponse = FunctionResponse(
                                name = call.name,
                                response = mapOf(
                                    "status" to "error",
                                    "message" to "Linux guest container is NOT installed. The user must navigate to the terminal screen by tapping 'Linux Terminal' in the drawer and tap 'Download & Setup Guest Container' first before any commands can be executed."
                                )
                            )))
                        } else {
                            val actionObj = AgentAction("BASH", command)
                            _messages.value = _messages.value + ChatMessage.TaskExecution(actionObj, "Running...")
                            
                            val liveOutputBuilder = StringBuilder()
                            val output = com.example.utils.LinuxTerminalSimulator.executeCommand(command, distro, sessionName) { line ->
                                liveOutputBuilder.append(line).append("\n")
                                val currentMsgs = _messages.value.toMutableList()
                                val lastIdx = currentMsgs.indexOfLast { it is ChatMessage.TaskExecution && it.action.actionType == "BASH" && it.action.target == command && it.status == "Running..." }
                                if (lastIdx != -1) {
                                    currentMsgs[lastIdx] = ChatMessage.TaskExecution(actionObj, "Running...", resultSnippet = liveOutputBuilder.toString())
                                    _messages.value = currentMsgs
                                }
                            }
                            addReasoningStep("Command standard output captured", "Lines returned: ${output.lines().size}", "SUCCESS", "TOOL")
                            
                            val snippet = output
                            val currentMsgs = _messages.value.toMutableList()
                            val lastIdx = currentMsgs.indexOfLast { it is ChatMessage.TaskExecution && it.action.actionType == "BASH" && it.action.target == command && it.status == "Running..." }
                            if (lastIdx != -1) {
                                currentMsgs[lastIdx] = ChatMessage.TaskExecution(actionObj, "Completed", resultSnippet = snippet)
                                _messages.value = currentMsgs
                            } else {
                                _messages.value = _messages.value + ChatMessage.TaskExecution(actionObj, "Completed", resultSnippet = snippet)
                            }
                            
                            val sanitizedOutput = sanitizeAndTruncateToolOutput(output, 20000)
                            responseParts.add(Part(functionResponse = FunctionResponse(
                                name = call.name,
                                response = mapOf(
                                    "status" to "success",
                                    "output" to sanitizedOutput,
                                    "current_directory" to com.example.utils.LinuxTerminalSimulator.currentDirectory.value
                                 )
                            )))
                        }
                    }
                    "read_file" -> {
                        val filePath = call.args?.get("file_path") as? String ?: ""
                        val isBinary = call.args?.get("is_binary_base64") as? Boolean
                        val distro = call.args?.get("distro") as? String
                        
                        addReasoningStep("Read File Dispatch", "Path: \"$filePath\"", "RUNNING", "TOOL")
                        val actionObj = AgentAction("READ_FILE", filePath)
                        _messages.value = _messages.value + ChatMessage.TaskExecution(actionObj, "Reading...")

                        val res = com.example.utils.FileUtils.readFile(getApplication(), filePath, isBinary, distro)
                        val status = res["status"] as? String ?: "error"
                        val isSuccess = status == "success"

                        val snippet = if (isSuccess) {
                            val content = res["content"] as? String ?: ""
                            val enc = res["encoding"] as? String ?: "utf-8"
                            if (enc == "base64") "[Binary Base64 Data (${res["size_bytes"]} bytes)]"
                            else if (content.length > 200) content.substring(0, 200) + "..." else content
                        } else {
                            res["message"] as? String ?: "Failed to read file"
                        }

                        val currentMsgs = _messages.value.toMutableList()
                        val lastIdx = currentMsgs.indexOfLast { it is ChatMessage.TaskExecution && it.action.actionType == "READ_FILE" && it.action.target == filePath }
                        if (lastIdx != -1) {
                            currentMsgs[lastIdx] = ChatMessage.TaskExecution(actionObj, if (isSuccess) "Completed" else "Failed", resultSnippet = snippet)
                            _messages.value = currentMsgs
                        }

                        if (isSuccess) {
                            addReasoningStep("Read File Completed", "Successfully read file ($filePath)", "SUCCESS", "TOOL")
                        } else {
                            addReasoningStep("Read File Failed", snippet, "FAILED", "TOOL")
                        }

                        responseParts.add(Part(functionResponse = FunctionResponse(
                            name = call.name,
                            response = res
                        )))
                    }
                    "write_file" -> {
                        val filePath = call.args?.get("file_path") as? String ?: ""
                        val content = call.args?.get("content") as? String ?: ""
                        val isBinary = call.args?.get("is_binary_base64") as? Boolean
                        val append = call.args?.get("append") as? Boolean
                        val distro = call.args?.get("distro") as? String

                        addReasoningStep("Write File Dispatch", "Path: \"$filePath\"", "RUNNING", "TOOL")
                        val actionObj = AgentAction("WRITE_FILE", filePath)
                        _messages.value = _messages.value + ChatMessage.TaskExecution(actionObj, "Writing...")

                        val res = com.example.utils.FileUtils.writeFile(getApplication(), filePath, content, isBinary, append, distro)
                        val status = res["status"] as? String ?: "error"
                        val isSuccess = status == "success"

                        val snippet = if (isSuccess) {
                            "Wrote ${res["bytes_written"]} bytes to ${res["file_path"]}"
                        } else {
                            res["message"] as? String ?: "Failed to write file"
                        }

                        val currentMsgs = _messages.value.toMutableList()
                        val lastIdx = currentMsgs.indexOfLast { it is ChatMessage.TaskExecution && it.action.actionType == "WRITE_FILE" && it.action.target == filePath }
                        if (lastIdx != -1) {
                            currentMsgs[lastIdx] = ChatMessage.TaskExecution(actionObj, if (isSuccess) "Completed" else "Failed", resultSnippet = snippet)
                            _messages.value = currentMsgs
                        }

                        if (isSuccess) {
                            addReasoningStep("Write File Completed", snippet, "SUCCESS", "TOOL")
                        } else {
                            addReasoningStep("Write File Failed", snippet, "FAILED", "TOOL")
                        }

                        responseParts.add(Part(functionResponse = FunctionResponse(
                            name = call.name,
                            response = res
                        )))
                    }
                    "list_directory" -> {
                        val path = call.args?.get("path") as? String ?: "."
                        val distro = call.args?.get("distro") as? String

                        addReasoningStep("List Directory Dispatch", "Path: \"$path\"", "RUNNING", "TOOL")
                        val actionObj = AgentAction("LIST_DIR", path)
                        _messages.value = _messages.value + ChatMessage.TaskExecution(actionObj, "Listing...")

                        val res = com.example.utils.FileUtils.listDirectory(getApplication(), path, distro)
                        val status = res["status"] as? String ?: "error"
                        val isSuccess = status == "success"

                        val snippet = if (isSuccess) {
                            "Items: ${res["total_items"]}"
                        } else {
                            res["message"] as? String ?: "Failed to list directory"
                        }

                        val currentMsgs = _messages.value.toMutableList()
                        val lastIdx = currentMsgs.indexOfLast { it is ChatMessage.TaskExecution && it.action.actionType == "LIST_DIR" && it.action.target == path }
                        if (lastIdx != -1) {
                            currentMsgs[lastIdx] = ChatMessage.TaskExecution(actionObj, if (isSuccess) "Completed" else "Failed", resultSnippet = snippet)
                            _messages.value = currentMsgs
                        }

                        if (isSuccess) {
                            addReasoningStep("List Directory Completed", "Found ${res["total_items"]} items in $path", "SUCCESS", "TOOL")
                        } else {
                            addReasoningStep("List Directory Failed", snippet, "FAILED", "TOOL")
                        }

                        responseParts.add(Part(functionResponse = FunctionResponse(
                            name = call.name,
                            response = res
                        )))
                    }

                }
            }
            
            // Provide tools response back to model to finish its turn (Split functionResponse and inlineData for Gemini schema compliance)
            val funcParts = responseParts.filter { it.functionResponse != null }
            if (funcParts.isNotEmpty()) {
                conversationHistory.add(Content(parts = funcParts, role = "function"))
            }
            
            val imgParts = responseParts.filter { it.inlineData != null || it.text != null }
            if (imgParts.isNotEmpty()) {
                conversationHistory.add(Content(
                    parts = imgParts,
                    role = "user"
                ))
            }

            if (touchPauseRequested || pendingSensitiveActionDesc != null || turnAbortRequested || pendingTerminalStatus != null) {
                val wasAbort = turnAbortRequested
                val terminalStatus = pendingTerminalStatus
                val terminalMessage = pendingTerminalMessage
                turnAbortRequested = false
                touchPauseRequested = false
                pendingTerminalStatus = null
                pendingTerminalMessage = ""
                _isProcessing.value = false
                _agentState.value = AgentState.IDLE
                _activeAction.value = null
                        stopInactivityWatchdog()
                if (terminalStatus != null) {
                    val userNote = when (terminalStatus) {
                        "finished" -> if (terminalMessage.isNotBlank()) terminalMessage else "Task finished."
                        "failed", "failed_to_complete_task" -> if (terminalMessage.isNotBlank()) "I couldn't complete this: $terminalMessage" else "I couldn't complete this task."
                        "need_login" -> if (terminalMessage.isNotBlank()) "Please log in: $terminalMessage. Tell me when done and I'll continue." else "Please log in to the app on screen, then tell me to continue."
                        "encountered_captcha" -> "A captcha is on screen - please solve it, then tell me to continue."
                        "max_steps" -> terminalMessage
                        else -> if (terminalMessage.isNotBlank()) terminalMessage + "\nReply and I'll continue." else "I need your input to continue."
                    }
                    _messages.value += ChatMessage.System(userNote)
                }
                val pauseReason = when {
                    terminalStatus != null -> "Terminal state reached: $terminalStatus."
                    wasAbort -> "Stopped by a safety guard (action limit or stuck detection). Say anything to continue."
                    pendingSensitiveActionDesc != null -> "Waiting for your confirmation of a sensitive action."
                    else -> "Paused because the user touched the screen. Say anything to continue."
                }
                addReasoningStep("Agent paused", pauseReason, "WARNING", "SYSTEM")
            } else {
                _agentState.value = AgentState.THINKING
                autoContinueCount = 0
                addReasoningStep("Recycling Tool Outcomes", "Returning functional responses back to model coordinate loop.", "RUNNING", "SYSTEM")
                executeGeminiCall() // Re-trigger call
            }
        } else {
             val modelText = modelTextParts.joinToString("\n")
             stopInactivityWatchdog()
             _isProcessing.value = false
             _agentState.value = AgentState.IDLE
             _activeAction.value = null

        }
    }

    /** Comma-separated "Label (package)" list, rebuilt once per task. */
    private suspend fun runScreenTask(task: String): com.example.agent.ScreenAction.Terminal {
        if (task.isBlank()) return com.example.agent.ScreenAction.MessageUser("What would you like me to do on screen?")
        val service = com.example.service.ZhypixAccessibilityService.instance
            ?: return com.example.agent.ScreenAction.MessageUser("Enable Zhypix accessibility service in Android Settings first.")
        val key = _apiKey.value.ifBlank { if (_provider.value == "Gemini") BuildConfig.GEMINI_API_KEY else "" }
        val base = _baseUrl.value.ifBlank { if (_provider.value == "Gemini") "https://generativelanguage.googleapis.com" else "" }
        val model = com.example.agent.ScreenModel(key, base, _modelName.value, _provider.value == "Gemini")
        val loop = com.example.agent.ScreenLoop(
            capture = {
                if (touchPauseRequested) throw kotlinx.coroutines.CancellationException("Paused by user")
                service.observation.capture()
            },
            decide = { observation, history -> model.decide(task, observation, history) },
            execute = { action ->
                _agentState.value = AgentState.ACTING
                val result = service.device.execute(action)
                addReasoningStep("Screen action", "$action: ${result.message}", if (result.success) "SUCCESS" else "WARNING", "TOOL")
                _agentState.value = AgentState.THINKING
                result
            },
            onStep = { step, max -> _currentStep.value = step; _maxStepsPerTask.value = max }
        )
        return loop.run()
    }

    private fun getInstalledAppsSummary(): String {
        installedAppsCache?.let { return it }
        return try {
            val pm = getApplication<android.app.Application>().packageManager
            val summary = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                .map { pm.getApplicationLabel(it).toString() to it.packageName }
                .filter { it.first.isNotBlank() }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
                .joinToString(", ") { "${it.first} (${it.second})" }
            installedAppsCache = summary
            summary
        } catch (e: Exception) {
            ""
        }
    }

    private fun getEstimatedTokenCount(): Int =
        com.example.utils.ContextBudget.estimateHistoryTokens(conversationHistory)

    private fun getFallbackMaxTokens(callModel: String): Int {
        // Look up the selected model in availableModels to check for its dynamically fetched context limit
        val matchedModel = _availableModels.value.find { it.modelId == callModel || it.modelId.removePrefix("models/") == callModel }
        val realLength = matchedModel?.context_length ?: matchedModel?.max_position_embeddings
        if (realLength != null && realLength > 0) {
            return realLength
        }

        // Unknown model: safe high default so we don't restrict or prune context
        // unnecessarily. The dynamic lookup above (from the provider's model list)
        // is the preferred source; no hardcoded per-model table.
        return 1000000
    }

    private fun sanitizeAndTruncateToolOutput(text: String, maxLength: Int): String {
        if (text.isBlank()) return ""
        // Remove ANSI escape codes (like terminal colors)
        val ansiRegex = "\u001B\\[[;\\d]*[a-zA-Z]".toRegex()
        val textWithoutAnsi = text.replace(ansiRegex, "")
        // Remove null bytes and filter out non-printable control characters (except tab, newline, carriage return)
        val sanitized = textWithoutAnsi.filter { char ->
            char == '\n' || char == '\r' || char == '\t' || (char.code in 32..126) || char.code > 127
        }
        return if (sanitized.length > maxLength) {
            sanitized.substring(0, maxLength) + "\n\n...[Truncated due to length limits]..."
        } else {
            sanitized
        }
    }

    private suspend fun checkAndSummarizeHistory(callApiKey: String, callUrl: String, callModel: String) {
        val estimatedTokens = getEstimatedTokenCount()
        val maxTokens = getFallbackMaxTokens(callModel)

        _currentTokens.value = estimatedTokens
        _maxTokens.value = maxTokens
        
        val threshold = (maxTokens * com.example.utils.ContextBudget.SUMMARIZE_FRACTION).toInt()

        if (estimatedTokens > threshold || conversationHistory.size > 2000) { // Keep a very generous upper bound of 2000 messages just in case
            try {
                _messages.value += ChatMessage.System("Token optimization: Context at ${(estimatedTokens*100)/maxTokens}% ($estimatedTokens / $maxTokens max). Summarizing history...")
                val summary = com.example.service.ChatSummarizationService.summarizeHistory(
                    history = conversationHistory.toList(),
                    provider = _provider.value,
                    apiKey = callApiKey,
                    baseUrl = callUrl,
                    modelName = callModel
                )
                if (!summary.isNullOrBlank()) {
                    conversationHistory.clear()
                    conversationHistory.add(Content(parts = listOf(Part(text = "Summary of our conversation so far: $summary")), role = "user"))
                    conversationHistory.add(Content(parts = listOf(Part(text = "Understood. I will use this context for our continued conversation.")), role = "model"))
                    
                    _messages.value += ChatMessage.System("Chat history has been gracefully summarized to prevent token overflow.")
                }
            } catch (e: Exception) {
                Log.e("Zhypix", "Summarization failed", e)
            }
        }
    }

    private fun pruneOldImagesFromHistory(maxImagesToKeep: Int = 1) {
        var imageEncountered = 0
        for (i in conversationHistory.indices.reversed()) {
            val oldContent = conversationHistory[i]
            if (oldContent.parts.any { it.inlineData != null }) {
                imageEncountered++
                if (imageEncountered > maxImagesToKeep) {
                    val filteredParts = oldContent.parts.filter { it.inlineData == null }
                    if (filteredParts.size != oldContent.parts.size) {
                        conversationHistory[i] = oldContent.copy(parts = filteredParts)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (instance == this) {
            instance = null
        }
    }
}
