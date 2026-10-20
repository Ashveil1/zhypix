package com.example.service

import android.widget.ImageView


import com.zhypix.R




import android.app.Service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.viewmodel.AgentViewModel
import com.example.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class FloatingAgentService : Service() {

    companion object {
        var instance: FloatingAgentService? = null
            private set
    }

    private lateinit var windowManager: WindowManager
    
    // Notification Overlay Views
    private lateinit var floatingView: View
    private var titleText: TextView? = null
    private var messageText: TextView? = null
    
    // Interactive Controller Overlay Views (Zhypix Floating Button + Dynamic Island)
    private lateinit var controllerView: View
    private lateinit var controllerParams: WindowManager.LayoutParams
    private lateinit var bubbleBtn: FrameLayout
    private lateinit var glowRing: FrameLayout
    private var hasBubbleBeenMoved = false
    
    // Island UI Elements
    private lateinit var islandContainer: LinearLayout
    private lateinit var islandBackground: GradientDrawable
    private lateinit var dismissOverlay: View
    
    // Active/Notification Mode Views
    private lateinit var activeLayout: LinearLayout
    private lateinit var activeIconLayout: FrameLayout
    private lateinit var activeIconIv: android.widget.ImageView
    private lateinit var activeTitleTv: TextView
    private lateinit var activeMessageTv: TextView
    private lateinit var activeVisualizerTv: TextView
    
    // Expanded Interaction Mode Views
    private lateinit var expandedLayout: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var inputEt: EditText
    private lateinit var micBtn: FrameLayout
    private lateinit var micIv: android.widget.ImageView
    private lateinit var speakerBtn: FrameLayout
    private lateinit var speakerIv: android.widget.ImageView
    private lateinit var stopBtn: FrameLayout
    private lateinit var sendBtn: FrameLayout
    private lateinit var modelTv: TextView
    
    // Island State Enum
    private enum class IslandMode {
        COLLAPSED,  // Resting Zhypix Logo round button in bottom right
        ACTIVE,     // Live processing / speaking / notification banner (~320dp x 64dp)
        EXPANDED    // Full interactive control panel with chat input (~340dp x wrap)
    }
    private var currentMode = IslandMode.COLLAPSED
    
    // Animators & Jobs
    private var islandMorphAnimator: android.animation.ValueAnimator? = null
    private var glowPulseAnimator: android.animation.ValueAnimator? = null
    private var idleBreathAnimator: android.animation.ValueAnimator? = null
    private var visualizerAnimator: android.animation.ValueAnimator? = null
    private var activeAutoCollapseJob: Job? = null
    private var lastStreamMessage = ""

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var hideJob: Job? = null

    // Speech-to-text / Voice Input
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningVoice = false

    // Hardware Cutout / Notch Coordinates
    private var cutoutTop = 0
    private var safeInsetTop = 0
    private var baseIslandY = 0

    fun hideFloatingView() {
        if (::floatingView.isInitialized) {
            floatingView.visibility = View.GONE
        }
    }

    fun showFloatingView() {
        if (::floatingView.isInitialized) {
            floatingView.visibility = View.VISIBLE
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                "ACTION_EXPAND_INPUT" -> {
                    morphIsland(IslandMode.EXPANDED)
                }
                "ACTION_TOGGLE_WAKELOCK" -> {
                    val enabled = com.example.utils.LinuxTerminalSimulator.toggleWakeLock(this)
                    startAsForeground()
                    android.widget.Toast.makeText(this, if (enabled) "Zhypix WakeLock: ACTIVE (High Performance)" else "Zhypix WakeLock: RELEASED", android.widget.Toast.LENGTH_SHORT).show()
                }
                "ACTION_UPDATE_WAKELOCK" -> {
                    startAsForeground()
                }
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startAsForeground()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Pre-fetch models on floating service start if empty
        val viewModel = AgentViewModel.instance
        if (viewModel != null && viewModel.availableModels.value.isEmpty()) {
            val provider = viewModel.provider.value
            val baseUrl = viewModel.baseUrl.value
            val apiKey = viewModel.apiKey.value
            if (apiKey.isNotBlank()) {
                viewModel.fetchModels(provider, baseUrl, apiKey)
            }
        }

        // 1. Text Notification Overlay
        val layoutParams = WindowManager.LayoutParams(
            480, // Set optimal width to handle wrapped text smoothly (~240dp)
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // Completely click-through
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        layoutParams.x = 24 // 24px padding margin from left edge of screen
        layoutParams.y = 0

        floatingView = createFloatingView(layoutParams)
        windowManager.addView(floatingView, layoutParams)

        // 2. Interactive Controller Overlay (Zhypix Floating Button + Dynamic Island)
        val density = resources.displayMetrics.density
        val dp56 = (56 * density).toInt()
        val collapsedSize = (dp56 * 1.6f).toInt()

        controllerParams = WindowManager.LayoutParams(
            collapsedSize,
            collapsedSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = (20 * density).toInt()
            y = (80 * density).toInt()
        }

        controllerView = createControllerView()
        windowManager.addView(controllerView, controllerParams)
    }

    private fun updateTextColors(isDarkMode: Boolean) {
        val (mainTextColor, subTextColor, shadowColor) = if (isDarkMode) {
            // Dark Mode environment: Crisp pure white text with dynamic black drop shadow
            Triple(Color.WHITE, Color.parseColor("#B3FFFFFF"), Color.BLACK)
        } else {
            // Light Mode environment: Crisp pure black text with dynamic white/soft-glow shadow
            Triple(Color.BLACK, Color.parseColor("#CC000000"), Color.WHITE)
        }

        titleText?.apply {
            setTextColor(subTextColor)
            setShadowLayer(8f, 0f, 1f, shadowColor)
        }
        messageText?.apply {
            setTextColor(mainTextColor)
            setShadowLayer(10f, 0f, 1.5f, shadowColor)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val isDarkMode = (newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        updateTextColors(isDarkMode)
    }

    private fun createFloatingView(windowLayoutParams: WindowManager.LayoutParams): View {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            background = null // 100% transparent background box as requested
            setPadding(24, 24, 24, 24)
            layoutParams = WindowManager.LayoutParams(480, WindowManager.LayoutParams.WRAP_CONTENT)
            alpha = 0f // Start hidden initially
        }

        // Subtitle or action type category label
        val titleTv = TextView(this).apply {
            text = ""
            textSize = 10.5f
            letterSpacing = 0.15f
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            gravity = Gravity.START
            setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 4
            }
        }
        titleText = titleTv

        // Main action / update content message
        val messageTv = TextView(this).apply {
            text = ""
            textSize = 17f
            setLineSpacing(4f, 1.15f)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            gravity = Gravity.START
            setTypeface(android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.BOLD))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        messageText = messageTv

        rootLayout.addView(titleTv)
        rootLayout.addView(messageTv)

        // Initial setup for the text colors based on the current system UI mode
        val isDarkMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        updateTextColors(isDarkMode)

        // Clean-up and format the technical logs into clear, user-friendly updates based on system language (Default is English, fallback to Thai if locale is Thai)
        fun formatStep(title: String, desc: String, type: String): Pair<String, String>? {
            if (type == "SYSTEM") {
                return null // Filter out any internal payload assemblies and screen capturing
            }

            var cleanTitle = title
            var cleanDesc = desc

            val titleUpper = title.uppercase(Locale.getDefault())
            val isThai = false

            when {
                titleUpper.contains("USER QUERY RECEIVED") || type == "USER" -> {
                    cleanTitle = "Your Request"
                    cleanDesc = desc.removePrefix("Processing command text:").trim(' ', '"')
                }
                titleUpper.contains("QUERYING AI PROVIDER") -> {
                    cleanTitle = "AI Assistant"
                    cleanDesc = "Analyzing request details..."
                }
                titleUpper.contains("DISPLAYING TEXT RESPONSE") || titleUpper.contains("AI GENERATIVE RESPONSE RECEIVED") || type == "AI" -> {
                    cleanTitle = "AI Assistant"
                    cleanDesc = desc
                }
                titleUpper.contains("ACTION DISPATCH QUEUED") || titleUpper.contains("ACTION SIMULATION") || type == "TOOL" -> {
                    var target = desc.substringAfter("target='").substringBefore("'").trim()
                    if (target == desc || target.isEmpty()) {
                        if (desc.contains("Query: \"")) target = desc.substringAfter("Query: \"").substringBeforeLast("\"")
                        else if (desc.contains("URL: \"")) target = desc.substringAfter("URL: \"").substringBeforeLast("\"")
                        else if (desc.contains("Command commandLine: \"")) target = desc.substringAfter("Command commandLine: \"").substringBeforeLast("\"")
                    }

                    val isClick = titleUpper.contains("CLICK") || desc.contains("CLICK") || desc.contains("click")
                    val isSwipe = titleUpper.contains("SWIPE") || desc.contains("SWIPE") || desc.contains("swipe")
                    val isType = titleUpper.contains("TYPE") || desc.contains("TYPE") || desc.contains("type")
                    val isOpenApp = titleUpper.contains("OPEN_APP") || desc.contains("OPEN_APP") || desc.contains("open_app")
                    val isObserve = titleUpper.contains("OBSERVE") || desc.contains("OBSERVE") || desc.contains("observe")
                    val isMemory = titleUpper.contains("REMEMBER") || desc.contains("MEMORY") || desc.contains("preference")
                    val isSearch = titleUpper.contains("SEARCH") || desc.contains("SEARCH") || desc.contains("search") || titleUpper.contains("WEB SEARCH")
                    val isReadUrl = titleUpper.contains("READ_URL") || titleUpper.contains("READ URL") || desc.contains("URL")
                    val isBash = titleUpper.contains("LINUX") || titleUpper.contains("COMMAND") || titleUpper.contains("BASH") || titleUpper.contains("SHELL")

                    when {
                        isBash -> {
                            cleanTitle = "Executing Terminal Command"
                            cleanDesc = if (target.isNotEmpty()) target else desc
                        }
                        isSearch -> {
                            cleanTitle = "Searching the Web"
                            cleanDesc = if (target.isNotEmpty()) "\"$target\"" else desc
                        }
                        isReadUrl -> {
                            cleanTitle = "Reading Webpage"
                            cleanDesc = if (target.isNotEmpty()) "URL: $target" else desc
                        }
                        isOpenApp -> {
                            cleanTitle = "Opening App"
                            val appName = if (target.isNotEmpty()) target else "Application"
                            cleanDesc = "Launching $appName..."
                        }
                        isClick -> {
                            cleanTitle = "Tapping Screen"
                            cleanDesc = if (target.isNotEmpty()) "Tapping at coordinates $target" else "Tapping target element..."
                        }
                        isSwipe -> {
                            cleanTitle = "Scrolling Screen"
                            cleanDesc = "Scrolling page content..."
                        }
                        isType -> {
                            cleanTitle = "Typing Text"
                            cleanDesc = if (target.isNotEmpty()) "Typing \"$target\"" else "Entering text input..."
                        }
                        isObserve -> {
                            cleanTitle = "Scanning Screen"
                            cleanDesc = "Analyzing page content and layout..."
                        }
                        isMemory -> {
                            cleanTitle = "Saving Memory"
                            cleanDesc = "Preferences successfully saved."
                        }
                        else -> {
                            cleanTitle = "Processing"
                            cleanDesc = "Simulating actions for next step..."
                        }
                    }
                }
            }

            // Strictly strip any lingering technical debug characters like [], <>
            if (cleanDesc.contains("<") || cleanDesc.contains(">") || cleanDesc.contains("[") || cleanDesc.contains("]")) {
                cleanDesc = cleanDesc.replace(Regex("[\\[\\]\\<\\>]"), "")
            }

            return Pair(cleanTitle, cleanDesc)
        }

        // Observe reasoningChain steps
        scope.launch {
            AgentViewModel.instance?.reasoningChain?.collect { steps ->
                val lastStep = steps.lastOrNull()
                if (lastStep != null) {
                    val formatted = formatStep(lastStep.title, lastStep.description, lastStep.type)
                    if (formatted != null) {
                        val isProcessing = AgentViewModel.instance?.isProcessing?.value == true
                        val isStepFinal = !isProcessing || lastStep.status == "FAILED"
                        showNotification(formatted.first, formatted.second, isFinal = isStepFinal)
                    }
                }
            }
        }

        // Observe final AI Text and User messages explicitly to guarantee display on the overlay
        scope.launch {
            AgentViewModel.instance?.messages?.collect { messages ->
                val lastMsg = messages.lastOrNull()
                if (lastMsg != null) {
                    if (lastMsg is ChatMessage.Agent) {
                        val cleanTitle = "AI Response"
                        showNotification(cleanTitle, lastMsg.text, isFinal = true)
                    } else if (lastMsg is ChatMessage.ProviderConfigRequired) {
                        showNotification("Settings Required", lastMsg.text, isFinal = true)
                        showBubblePreview("Tap to configure AI Provider & Model")
                    } else if (lastMsg is ChatMessage.User) {
                        val cleanTitle = "Your Request"
                        showNotification(cleanTitle, lastMsg.text, isFinal = true)
                        showBubblePreview(lastMsg.text)
                    }
                }
            }
        }

        return rootLayout
    }

    private fun cleanMarkdown(text: String): String {
        return text
            .replace(Regex("```[a-zA-Z]*\\n?"), "")
            .replace(Regex("\\*\\*|__|`|~~"), "")
            .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("^\\s*[*\\-+•]\\s+", RegexOption.MULTILINE), "• ")
    }

    // Helper to update text with transition animation and auto-hide timing
    fun showNotification(title: String, message: String, isFinal: Boolean) {
        if (!::floatingView.isInitialized) return
        hideJob?.cancel()
        
        // If empty, gracefully fade out and clear
        if (title.isEmpty() && message.isEmpty()) {
            floatingView.animate()
                .alpha(0f)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .setDuration(250)
                .start()
            return
        }

        val tTv = titleText ?: return
        val mTv = messageText ?: return
        val displayMessage = cleanMarkdown(message)

        // Route notifications into the Custom Dynamic Island as well
        showIslandNotification(title, displayMessage, isFinal)

        // If it's a streaming update (message starts with the previous message and previous was not empty, OR if we detect it's an extension of the last seen stream message)
        val isAppend = message.isNotEmpty() && lastStreamMessage.isNotEmpty() && message.startsWith(lastStreamMessage) && message != lastStreamMessage
        
        if (isAppend) {
            // Directly update text and make sure it is fully visible without repeating fade-out animations
            tTv.text = title.uppercase(Locale.getDefault())
            mTv.text = displayMessage
            lastStreamMessage = message
            
            if (floatingView.alpha < 1f) {
                floatingView.animate()
                    .alpha(1f)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .setDuration(250) // Beautiful smooth fade-in for the first characters
                    .start()
            }
            
            // Reschedule auto-hide (only when final or when streaming finishes/pauses)
            if (isFinal) {
                hideJob = scope.launch {
                    delay(5000L)
                    floatingView.animate()
                        .alpha(0f)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .setDuration(350)
                        .start()
                }
            }
            return
        }

        // If the content is identical, maintain visibility and reschedule auto-hide
        if (tTv.text.toString() == title && mTv.text.toString() == displayMessage) {
            lastStreamMessage = message
            if (floatingView.alpha < 1f) {
                floatingView.animate()
                    .alpha(1f)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .setDuration(250)
                    .start()
            }
            hideJob = scope.launch {
                delay(if (isFinal) 5000L else 3000L)
                floatingView.animate()
                    .alpha(0f)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .setDuration(350)
                    .start()
            }
            return
        }

        // Normal update: cross-fade transition
        lastStreamMessage = message
        floatingView.animate()
            .alpha(0f)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .setDuration(150)
            .withEndAction {
                tTv.text = title.uppercase(Locale.getDefault())
                mTv.text = displayMessage
                
                floatingView.animate()
                    .alpha(1f)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .setDuration(220)
                    .withEndAction {
                        // Automatically hide intermediate steps in 3 seconds, final answers in 5 seconds
                        hideJob = scope.launch {
                            delay(if (isFinal) 5000L else 3000L)
                            floatingView.animate()
                                .alpha(0f)
                                .setInterpolator(android.view.animation.DecelerateInterpolator())
                                .setDuration(350)
                                .start()
                        }
                    }
                    .start()
            }
            .start()
    }

    // Dynamic Preview / Status in Island
    fun showBubblePreview(text: String) {
        showIslandNotification("AI NOTIFICATION", text, isFinal = true)
    }

    private fun showIslandNotification(title: String, message: String, isFinal: Boolean = false) {
        if (!::islandContainer.isInitialized) return
        activeAutoCollapseJob?.cancel()

        activeTitleTv.text = title.uppercase(Locale.getDefault())
        activeMessageTv.text = cleanMarkdown(message)

        val titleUpper = title.uppercase(Locale.getDefault())
        when {
            titleUpper.contains("THINKING") || titleUpper.contains("QUERY") -> {
                activeIconIv.setImageResource(com.zhypix.R.drawable.ic_sparkle)
                (activeIconLayout.background as? GradientDrawable)?.setColor(Color.WHITE)
                activeIconIv.setColorFilter(Color.BLACK)
            }
            titleUpper.contains("SPEAKING") || titleUpper.contains("VOICE") -> {
                activeIconIv.setImageResource(com.zhypix.R.drawable.ic_volume_up)
                (activeIconLayout.background as? GradientDrawable)?.setColor(Color.WHITE)
                activeIconIv.setColorFilter(Color.BLACK)
            }
            titleUpper.contains("TERMINAL") || titleUpper.contains("SANDBOX") -> {
                activeIconIv.setImageResource(com.zhypix.R.drawable.ic_smart_toy)
                (activeIconLayout.background as? GradientDrawable)?.setColor(Color.WHITE)
                activeIconIv.setColorFilter(Color.BLACK)
            }
            titleUpper.contains("TOOL") || titleUpper.contains("ACTION") -> {
                activeIconIv.setImageResource(com.zhypix.R.drawable.ic_sparkle)
                (activeIconLayout.background as? GradientDrawable)?.setColor(Color.WHITE)
                activeIconIv.setColorFilter(Color.BLACK)
            }
            else -> {
                activeIconIv.setImageResource(com.zhypix.R.drawable.ic_sparkle)
                (activeIconLayout.background as? GradientDrawable)?.setColor(Color.WHITE)
                activeIconIv.setColorFilter(Color.BLACK)
            }
        }

        if (currentMode != IslandMode.EXPANDED) {
            morphIsland(IslandMode.ACTIVE)
        }

        if (isFinal) {
            activeAutoCollapseJob = scope.launch {
                delay(4500L)
                if (currentMode == IslandMode.ACTIVE) {
                    morphIsland(IslandMode.COLLAPSED)
                }
            }
        }
    }

    private fun closeServiceCompletely() {
        try {
            if (::controllerView.isInitialized) {
                windowManager.removeView(controllerView)
            }
            if (::floatingView.isInitialized) {
                windowManager.removeView(floatingView)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopSelf()
    }

    private fun createControllerView(): View {
        val density = resources.displayMetrics.density

        val rootLayout = FrameLayout(this).apply {
            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }

        // Invisible Full-screen Dismiss Overlay to detect clicks outside the expanded island
        dismissOverlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            setOnClickListener {
                morphIsland(IslandMode.COLLAPSED)
            }
        }

        // 1. Pulsing Outer Glow Ring behind Zhypix Bubble
        glowRing = FrameLayout(this).apply {
            val size = (56 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#50FFFFFF"))
            }
        }

        glowPulseAnimator = android.animation.ValueAnimator.ofFloat(0.9f, 1.35f).apply {
            duration = 1600
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                glowRing.scaleX = scale
                glowRing.scaleY = scale
                glowRing.alpha = 2.2f - scale
            }
        }
        glowPulseAnimator?.start()

        // 2. Circular Zhypix Logo Floating Button (Resting collapsed mode)
        bubbleBtn = FrameLayout(this).apply {
            val size = (56 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
            elevation = 14f * density
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F2000000")) // Obsidian Dark Glass
                setStroke((1.6f * density).toInt(), Color.parseColor("#FFFFFF")) // Pure White Stroke
            }
        }

        val logoCubeView = ZhypixCubeView(this).apply {
            val iconSize = (28 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
        }
        bubbleBtn.addView(logoCubeView)

        // Messenger Chat Head Style Remove Target Zone at Bottom Center
        val removeCircleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#D01C1C1E"))
            setStroke((1.5f * density).toInt(), Color.parseColor("#80FFFFFF"))
        }

        val removeCircle = FrameLayout(this).apply {
            val size = (60 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
            }
            background = removeCircleBg
            elevation = 16f * density
        }

        val removeIv = android.widget.ImageView(this).apply {
            val iconSize = (26 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(com.zhypix.R.drawable.ic_close)
            setColorFilter(Color.WHITE)
        }
        removeCircle.addView(removeIv)

        val removeTv = TextView(this).apply {
            text = "ลากมาที่นี่เพื่อปิด"
            setTextColor(Color.parseColor("#B0FFFFFF"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, (6 * density).toInt(), 0, 0)
        }

        val removeZoneContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (44 * density).toInt()
            }
        }
        removeZoneContainer.addView(removeCircle)
        removeZoneContainer.addView(removeTv)

        // Touch & Drag Support for Zhypix Bubble
        var touchInitialX = 0
        var touchInitialY = 0
        var touchRawX = 0f
        var touchRawY = 0f
        var isBubbleDragging = false
        var isHoveringRemove = false

        bubbleBtn.setOnTouchListener { v, event ->
            val displayMetrics = resources.displayMetrics
            val screenW = displayMetrics.widthPixels
            val screenH = displayMetrics.heightPixels

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchInitialX = controllerParams.x
                    touchInitialY = controllerParams.y
                    touchRawX = event.rawX
                    touchRawY = event.rawY
                    isBubbleDragging = false
                    isHoveringRemove = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(120).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (touchRawX - event.rawX).toInt()
                    val dy = (touchRawY - event.rawY).toInt()
                    if (!isBubbleDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                        isBubbleDragging = true
                        hasBubbleBeenMoved = true

                        // Expand window to full screen so remove target is visible
                        controllerParams.width = WindowManager.LayoutParams.MATCH_PARENT
                        controllerParams.height = WindowManager.LayoutParams.MATCH_PARENT
                        controllerParams.x = 0
                        controllerParams.y = 0
                        try {
                            windowManager.updateViewLayout(controllerView, controllerParams)
                        } catch (_: Exception) {}

                        // Show remove target
                        removeZoneContainer.visibility = View.VISIBLE
                        removeZoneContainer.alpha = 0f
                        removeZoneContainer.translationY = 40f * density
                        removeZoneContainer.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(200)
                            .start()
                    }

                    if (isBubbleDragging) {
                        val newX = (touchInitialX + dx).coerceAtLeast(0)
                        val newY = (touchInitialY + dy).coerceAtLeast(0)

                        controllerParams.x = newX
                        controllerParams.y = newY

                        val lpBubble = bubbleBtn.layoutParams as FrameLayout.LayoutParams
                        lpBubble.rightMargin = newX
                        lpBubble.bottomMargin = newY
                        bubbleBtn.layoutParams = lpBubble

                        val lpGlow = glowRing.layoutParams as FrameLayout.LayoutParams
                        lpGlow.rightMargin = newX
                        lpGlow.bottomMargin = newY
                        glowRing.layoutParams = lpGlow

                        val fingerX = event.rawX
                        val fingerY = event.rawY
                        val distFromBottom = screenH - fingerY
                        val distFromHorizCenter = Math.abs(fingerX - screenW / 2f)

                        val wasHovering = isHoveringRemove
                        isHoveringRemove = (distFromBottom < (180 * density)) && (distFromHorizCenter < (110 * density))

                        if (isHoveringRemove && !wasHovering) {
                            removeCircleBg.setColor(Color.parseColor("#E53935"))
                            removeCircleBg.setStroke((2 * density).toInt(), Color.WHITE)
                            removeCircle.animate().scaleX(1.28f).scaleY(1.28f).setDuration(160).start()
                            v.animate().scaleX(0.7f).scaleY(0.7f).alpha(0.6f).setDuration(160).start()
                        } else if (!isHoveringRemove && wasHovering) {
                            removeCircleBg.setColor(Color.parseColor("#D01C1C1E"))
                            removeCircleBg.setStroke((1.5f * density).toInt(), Color.parseColor("#80FFFFFF"))
                            removeCircle.animate().scaleX(1.0f).scaleY(1.0f).setDuration(160).start()
                            v.animate().scaleX(0.9f).scaleY(0.9f).alpha(1.0f).setDuration(160).start()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isBubbleDragging) {
                        if (isHoveringRemove && event.action == MotionEvent.ACTION_UP) {
                            removeCircle.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(160).start()
                            v.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(160).withEndAction {
                                closeServiceCompletely()
                            }.start()
                        } else {
                            removeZoneContainer.animate()
                                .alpha(0f)
                                .translationY(40f * density)
                                .setDuration(160)
                                .withEndAction {
                                    removeZoneContainer.visibility = View.GONE
                                }
                                .start()

                            v.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .alpha(1.0f)
                                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                                .setDuration(200)
                                .start()

                            val lpBubble = bubbleBtn.layoutParams as FrameLayout.LayoutParams
                            lpBubble.rightMargin = 0
                            lpBubble.bottomMargin = 0
                            bubbleBtn.layoutParams = lpBubble

                            val lpGlow = glowRing.layoutParams as FrameLayout.LayoutParams
                            lpGlow.rightMargin = 0
                            lpGlow.bottomMargin = 0
                            glowRing.layoutParams = lpGlow

                            val dp56 = (56 * density).toInt()
                            val collapsedSize = (dp56 * 1.6f).toInt()
                            controllerParams.width = collapsedSize
                            controllerParams.height = collapsedSize
                            try {
                                windowManager.updateViewLayout(controllerView, controllerParams)
                            } catch (_: Exception) {}
                        }
                    } else {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).setDuration(200).start()
                        if (event.action == MotionEvent.ACTION_UP) {
                            morphIsland(IslandMode.EXPANDED)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // 3. Dynamic Island Container (Morphing Card Overlay)
        islandBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(Color.parseColor("#F50A0A0A")) // Semi-translucent obsidian glass
            setStroke((1.5f * density).toInt(), Color.parseColor("#80FFFFFF")) // Pure White stroke
        }

        islandContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = islandBackground
            elevation = 18f * density
            visibility = View.GONE
            val initialWidth = (340 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(initialWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
            setOnClickListener {
                if (currentMode == IslandMode.ACTIVE) {
                    morphIsland(IslandMode.EXPANDED)
                }
            }
        }

        // --- SUB-VIEW 1: Active / Live Activity Layout ---
        activeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            setOnClickListener {
                if (currentMode == IslandMode.ACTIVE) {
                    morphIsland(IslandMode.EXPANDED)
                }
            }
        }

        activeIconLayout = FrameLayout(this).apply {
            val size = (28 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = (10 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        }
        activeIconIv = android.widget.ImageView(this).apply {
            val iconSize = (16 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(com.zhypix.R.drawable.ic_sparkle)
            setColorFilter(Color.BLACK)
        }
        activeIconLayout.addView(activeIconIv)

        val activeTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        activeTitleTv = TextView(this).apply {
            text = "ZHYPIX AI"
            setTextColor(Color.WHITE)
            textSize = 9.5f
            letterSpacing = 0.08f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }

        activeMessageTv = TextView(this).apply {
            text = "Ready"
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        activeTextCol.addView(activeTitleTv)
        activeTextCol.addView(activeMessageTv)

        activeVisualizerTv = TextView(this).apply {
            text = "∿∿"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = (8 * density).toInt()
            }
        }

        // Live Audio Equalizer Waveform Loop
        val waveFrames = arrayOf("∿∿", "∿∾", "∾∿", "≋≋")
        var waveIdx = 0
        visualizerAnimator = android.animation.ValueAnimator.ofInt(0, 3).apply {
            duration = 600
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
            addUpdateListener {
                waveIdx = (waveIdx + 1) % waveFrames.size
                activeVisualizerTv.text = waveFrames[waveIdx]
            }
        }
        visualizerAnimator?.start()

        activeLayout.addView(activeIconLayout)
        activeLayout.addView(activeTextCol)
        activeLayout.addView(activeVisualizerTv)

        // --- SUB-VIEW 2: Expanded Interactive Control Panel Layout ---
        expandedLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
        }

        // Row 1: Header / Controls Bar
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }

        var touchExpInitialX = 0
        var touchExpInitialY = 0
        var touchExpRawX = 0f
        var touchExpRawY = 0f
        var isExpDragging = false

        headerRow.setOnTouchListener { _, event ->
            val displayMetrics = resources.displayMetrics
            val screenW = displayMetrics.widthPixels
            val screenH = displayMetrics.heightPixels

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchExpInitialX = controllerParams.x
                    touchExpInitialY = controllerParams.y
                    touchExpRawX = event.rawX
                    touchExpRawY = event.rawY
                    isExpDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (touchExpRawX - event.rawX).toInt()
                    val dy = (touchExpRawY - event.rawY).toInt()

                    if (!isExpDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                        isExpDragging = true
                        hasBubbleBeenMoved = true
                    }

                    if (isExpDragging) {
                        val currW = islandContainer.width.takeIf { it > 0 } ?: (340 * density).toInt()
                        val currH = islandContainer.height.takeIf { it > 0 } ?: (360 * density).toInt()

                        val newX = (touchExpInitialX + dx).coerceIn((12 * density).toInt(), (screenW - currW - (12 * density).toInt()).coerceAtLeast(0))
                        val newY = (touchExpInitialY + dy).coerceIn((16 * density).toInt(), (screenH - currH - (32 * density).toInt()).coerceAtLeast(0))

                        controllerParams.x = newX
                        controllerParams.y = newY

                        val lp = islandContainer.layoutParams as FrameLayout.LayoutParams
                        lp.gravity = Gravity.BOTTOM or Gravity.END
                        lp.rightMargin = newX
                        lp.bottomMargin = newY
                        islandContainer.layoutParams = lp
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isExpDragging = false
                    true
                }
                else -> false
            }
        }

        // Model Selector Pill
        val modelBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (28 * density).toInt()
            ).apply {
                rightMargin = (8 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(Color.parseColor("#1C1C1E"))
                setStroke((1f * density).toInt(), Color.parseColor("#666666"))
            }
            setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
        }

        val modelContentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val modelIv = android.widget.ImageView(this).apply {
            val iconSize = (14 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                rightMargin = (4 * density).toInt()
            }
            setImageResource(com.zhypix.R.drawable.ic_smart_toy)
            setColorFilter(Color.WHITE)
        }
        modelContentRow.addView(modelIv)

        modelTv = TextView(this).apply {
            val activeModel = AgentViewModel.instance?.modelName?.value ?: ""
            text = getModelFullName(activeModel)
            setTextColor(Color.WHITE)
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }
        modelContentRow.addView(modelTv)
        modelBtn.addView(modelContentRow)

        modelBtn.setOnClickListener {
            val viewModel = AgentViewModel.instance
            if (viewModel != null && viewModel.availableModels.value.isEmpty()) {
                val provider = viewModel.provider.value
                val baseUrl = viewModel.baseUrl.value
                val apiKey = viewModel.apiKey.value
                if (apiKey.isNotBlank()) {
                    viewModel.fetchModels(provider, baseUrl, apiKey)
                }
            }

            val popup = android.widget.PopupMenu(this@FloatingAgentService, modelBtn)
            val available = AgentViewModel.instance?.availableModels?.value ?: emptyList()
            val current = AgentViewModel.instance?.modelName?.value ?: ""
            
            // Models come only from the provider's live model list; no hardcoded list.
            val modelsToShow = available.map { it.modelId }.filter { it.isNotBlank() }

            if (modelsToShow.isEmpty()) {
                popup.menu.add(0, 0, 0, "No models - set provider & key in Settings")
                popup.show()
                return@setOnClickListener
            }

            modelsToShow.forEachIndexed { index, mId ->
                val displayName = getModelFullName(mId)
                val titleText = if (current == mId || (current.isEmpty() && mId == "none")) "✓ $displayName" else displayName
                popup.menu.add(0, index, index, titleText)
            }

            popup.setOnMenuItemClickListener { menuItem ->
                val selectedModel = modelsToShow[menuItem.itemId]
                AgentViewModel.instance?.updateSetting("modelName", selectedModel)
                modelTv.text = getModelFullName(selectedModel)
                true
            }
            popup.show()
        }
        headerRow.addView(modelBtn)

        // Spacer
        val headerSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        headerRow.addView(headerSpacer)

        // Speaker Button
        speakerBtn = FrameLayout(this).apply {
            val size = (28 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = (8 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1C1C1E"))
                setStroke((1f * density).toInt(), Color.parseColor("#666666"))
            }
        }
        speakerIv = android.widget.ImageView(this).apply {
            val iconSize = (15 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(com.zhypix.R.drawable.ic_volume_up)
            setColorFilter(Color.WHITE)
        }
        speakerBtn.addView(speakerIv)

        fun updateSpeakerState(enabled: Boolean) {
            if (enabled) {
                speakerIv.setImageResource(com.zhypix.R.drawable.ic_volume_up)
                speakerIv.setColorFilter(Color.BLACK)
                speakerBtn.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                    setStroke((1f * density).toInt(), Color.WHITE)
                }
            } else {
                speakerIv.setImageResource(com.zhypix.R.drawable.ic_volume_off)
                speakerIv.setColorFilter(Color.parseColor("#888888"))
                speakerBtn.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#1C1C1E"))
                    setStroke((1f * density).toInt(), Color.parseColor("#444444"))
                }
            }
        }

        speakerBtn.setOnClickListener {
            val vm = AgentViewModel.instance
            if (vm != null) {
                val current = vm.ttsAutoSpeak.value
                val newState = !current
                vm.setTtsAutoSpeak(newState)
                if (!newState && vm.ttsManager.isPlaying.value) {
                    vm.stopSpeaking()
                }
                updateSpeakerState(newState)
                val toastMsg = if (newState) "Auto Read-Aloud ON" else "Auto Read-Aloud OFF"
                android.widget.Toast.makeText(this@FloatingAgentService, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        scope.launch {
            AgentViewModel.instance?.ttsAutoSpeak?.collect { enabled ->
                updateSpeakerState(enabled)
            }
        }
        headerRow.addView(speakerBtn)

        // Close / Minimize Island Button
        val closeBtn = FrameLayout(this).apply {
            val size = (28 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1C1C1E"))
                setStroke((1f * density).toInt(), Color.parseColor("#666666"))
            }
            setOnClickListener {
                morphIsland(IslandMode.COLLAPSED)
            }
        }
        val closeIv = android.widget.ImageView(this).apply {
            val iconSize = (14 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(com.zhypix.R.drawable.ic_close)
            setColorFilter(Color.WHITE)
        }
        closeBtn.addView(closeIv)
        headerRow.addView(closeBtn)

        expandedLayout.addView(headerRow)

        // Scrollable Chat History Container
        chatScrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = (6 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
        }

        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
        }
        chatScrollView.addView(chatContainer)
        expandedLayout.addView(chatScrollView)

        // Row 2: Chat Input & Action Buttons Bar
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (42 * density).toInt()
            )
            setPadding((10 * density).toInt(), 0, (6 * density).toInt(), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 21f * density
                setColor(Color.parseColor("#141414"))
                setStroke((1f * density).toInt(), Color.parseColor("#666666"))
            }
        }

        inputEt = EditText(this).apply {
            hint = "Ask AI or run command..."
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.WHITE)
            textSize = 13.5f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = null
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                rightMargin = (6 * density).toInt()
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitQuery()
                    true
                } else {
                    false
                }
            }
        }
        inputRow.addView(inputEt)

        // Mic Button
        micBtn = FrameLayout(this).apply {
            val size = (30 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = (6 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1C1C1E"))
                setStroke((1f * density).toInt(), Color.parseColor("#666666"))
            }
            setOnClickListener {
                if (isListeningVoice) {
                    stopSpeechToText()
                } else {
                    startSpeechToText()
                }
            }
            setOnLongClickListener {
                val vm = AgentViewModel.instance
                if (vm != null) {
                    val current = vm.continuousVoiceMode.value
                    val newState = !current
                    vm.setContinuousVoiceMode(newState)
                    val toastMsg = if (newState) "Continuous Voice Mode ON" else "Continuous Voice Mode OFF"
                    android.widget.Toast.makeText(this@FloatingAgentService, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
        micIv = android.widget.ImageView(this).apply {
            val iconSize = (16 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(com.zhypix.R.drawable.ic_mic)
            setColorFilter(Color.WHITE)
        }
        micBtn.addView(micIv)
        inputRow.addView(micBtn)

        // Stop Button
        stopBtn = FrameLayout(this).apply {
            val size = (30 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = (6 * density).toInt()
            }
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2C2C2E"))
                setStroke((1f * density).toInt(), Color.WHITE)
            }
            setOnClickListener {
                AgentViewModel.instance?.stopExecution()
                showIslandNotification("INTERRUPTED", "Operation cancelled by user", isFinal = true)
            }
        }
        val stopIv = android.widget.ImageView(this).apply {
            val iconSize = (14 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(com.zhypix.R.drawable.ic_stop)
            setColorFilter(Color.WHITE)
        }
        stopBtn.addView(stopIv)
        inputRow.addView(stopBtn)

        // Send Button
        sendBtn = FrameLayout(this).apply {
            val size = (30 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            setOnClickListener {
                submitQuery()
            }
        }
        val sendIv = android.widget.ImageView(this).apply {
            val iconSize = (16 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(com.zhypix.R.drawable.ic_send)
            setColorFilter(Color.BLACK)
        }
        sendBtn.addView(sendIv)
        inputRow.addView(sendBtn)

        expandedLayout.addView(inputRow)

        // Assemble sub-layouts into island container
        islandContainer.addView(activeLayout)
        islandContainer.addView(expandedLayout)

        rootLayout.addView(dismissOverlay)
        rootLayout.addView(glowRing)
        rootLayout.addView(bubbleBtn)
        rootLayout.addView(removeZoneContainer)
        rootLayout.addView(islandContainer)

        rootLayout.setOnApplyWindowInsetsListener { _, insets ->
            if (currentMode == IslandMode.EXPANDED) {
                val imeHeight = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetBottom
                }

                val lp = islandContainer.layoutParams as? FrameLayout.LayoutParams
                if (lp != null) {
                    val currentY = controllerParams.y
                    if (imeHeight > 0) {
                        if (currentY < imeHeight) {
                            lp.bottomMargin = imeHeight + (8 * density).toInt()
                            islandContainer.layoutParams = lp
                        }
                    } else {
                        lp.bottomMargin = currentY
                        islandContainer.layoutParams = lp
                    }
                }
            }
            insets
        }

        // Observe AgentViewModel states for dynamic reactivity
        scope.launch {
            AgentViewModel.instance?.isProcessing?.collect { processing ->
                if (processing) {
                    stopBtn.visibility = View.VISIBLE
                    if (currentMode != IslandMode.EXPANDED) {
                        showIslandNotification("THINKING...", "Analyzing with AI...", isFinal = false)
                    }
                } else {
                    stopBtn.visibility = View.GONE
                }
            }
        }

        scope.launch {
            AgentViewModel.instance?.ttsManager?.isPlaying?.collect { playing ->
                if (playing && currentMode != IslandMode.EXPANDED) {
                    showIslandNotification("SPEAKING...", "Voice playback in progress...", isFinal = false)
                }
            }
        }

        scope.launch {
            AgentViewModel.instance?.messages?.collect { messages ->
                updateChatHistory(messages)
            }
        }

        scope.launch {
            AgentViewModel.instance?.modelName?.collect { activeModel ->
                if (::modelTv.isInitialized) {
                    modelTv.text = getModelFullName(activeModel)
                }
            }
        }

        return rootLayout
    }

    private fun updateChatHistory(messages: List<ChatMessage>) {
        if (!::chatContainer.isInitialized) return
        val density = resources.displayMetrics.density

        chatContainer.removeAllViews()

        if (messages.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "ยังไม่มีประวัติการสนทนา\nพิมพ์ข้อความหรือกดไมค์เพื่อเริ่มคุยกับ AI"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11.5f
                gravity = Gravity.CENTER
                setPadding((12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt())
            }
            chatContainer.addView(emptyTv)
            return
        }

        val maxBubbleWidth = (280 * density).toInt()

        messages.forEach { msg ->
            val msgRow = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * density).toInt()
                    bottomMargin = (4 * density).toInt()
                }
            }

            when (msg) {
                is ChatMessage.User -> {
                    msgRow.gravity = Gravity.END
                    val bubbleBg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 14f * density
                        setColor(Color.parseColor("#2563EB"))
                    }
                    val bubbleLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = bubbleBg
                        setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.END
                        }
                    }
                    val tv = TextView(this).apply {
                        text = msg.text
                        setTextColor(Color.WHITE)
                        textSize = 12.5f
                        maxWidth = maxBubbleWidth
                        setTextIsSelectable(true)
                    }
                    bubbleLayout.addView(tv)
                    msgRow.addView(bubbleLayout)
                }
                is ChatMessage.Agent -> {
                    msgRow.gravity = Gravity.START
                    val bubbleBg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 14f * density
                        setColor(Color.parseColor("#1E1E24"))
                        setStroke((1f * density).toInt(), Color.parseColor("#33333E"))
                    }
                    val bubbleLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = bubbleBg
                        setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.START
                        }
                    }
                    val labelRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (3 * density).toInt()
                        }
                    }
                    val aiIv = android.widget.ImageView(this).apply {
                        val iconSize = (12 * density).toInt()
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                            rightMargin = (4 * density).toInt()
                        }
                        setImageResource(com.zhypix.R.drawable.ic_smart_toy)
                        setColorFilter(Color.parseColor("#38BDF8"))
                    }
                    val aiLabel = TextView(this).apply {
                        text = "Zhypix AI"
                        setTextColor(Color.parseColor("#38BDF8"))
                        textSize = 10f
                        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                    }
                    labelRow.addView(aiIv)
                    labelRow.addView(aiLabel)

                    val tv = TextView(this).apply {
                        text = cleanMarkdown(msg.text)
                        setTextColor(Color.parseColor("#E2E8F0"))
                        textSize = 12.5f
                        setTextIsSelectable(true)
                    }
                    bubbleLayout.addView(labelRow)
                    bubbleLayout.addView(tv)
                    msgRow.addView(bubbleLayout)
                }
                is ChatMessage.TaskExecution -> {
                    msgRow.gravity = Gravity.START
                    val bubbleBg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 10f * density
                        setColor(Color.parseColor("#121215"))
                        setStroke((1f * density).toInt(), Color.parseColor("#27272A"))
                    }
                    val bubbleLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = bubbleBg
                        setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    val statusTv = TextView(this).apply {
                        val actionName = msg.action.actionType
                        text = "$actionName • ${msg.status}"
                        setTextColor(Color.parseColor("#94A3B8"))
                        textSize = 10.5f
                    }
                    bubbleLayout.addView(statusTv)
                    if (!msg.resultSnippet.isNullOrEmpty()) {
                        val snippetTv = TextView(this).apply {
                            text = msg.resultSnippet
                            setTextColor(Color.parseColor("#64748B"))
                            textSize = 9.5f
                            setTextIsSelectable(true)
                        }
                        bubbleLayout.addView(snippetTv)
                    }
                    msgRow.addView(bubbleLayout)
                }
                is ChatMessage.System, is ChatMessage.ProviderConfigRequired -> {
                    msgRow.gravity = Gravity.CENTER_HORIZONTAL
                    val tv = TextView(this).apply {
                        text = when(msg) {
                            is ChatMessage.System -> msg.text
                            is ChatMessage.ProviderConfigRequired -> msg.text
                            else -> ""
                        }
                        setTextColor(Color.parseColor("#F59E0B"))
                        textSize = 10.5f
                        gravity = Gravity.CENTER
                        setPadding((6 * density).toInt(), (3 * density).toInt(), (6 * density).toInt(), (3 * density).toInt())
                    }
                    msgRow.addView(tv)
                }
            }

            chatContainer.addView(msgRow)
        }

        chatScrollView.post {
            chatScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun morphIsland(targetMode: IslandMode) {
        if (!::islandContainer.isInitialized) return
        val density = resources.displayMetrics.density
        val dp56 = (56 * density).toInt()
        val collapsedSize = (dp56 * 1.6f).toInt()
        currentMode = targetMode

        islandMorphAnimator?.cancel()

        when (targetMode) {
            IslandMode.COLLAPSED -> {
                // Dismiss keyboard
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(inputEt.windowToken, 0)
                dismissOverlay.visibility = View.GONE
                islandContainer.visibility = View.GONE

                glowRing.visibility = View.VISIBLE
                bubbleBtn.visibility = View.VISIBLE
                bubbleBtn.alpha = 0f
                bubbleBtn.scaleX = 0.5f
                bubbleBtn.scaleY = 0.5f
                bubbleBtn.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                    .setDuration(280)
                    .start()

                controllerParams.width = collapsedSize
                controllerParams.height = collapsedSize
                controllerParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                         WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                try {
                    windowManager.updateViewLayout(controllerView, controllerParams)
                } catch (_: Exception) {}
            }

            IslandMode.ACTIVE -> {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(inputEt.windowToken, 0)
                dismissOverlay.visibility = View.GONE
                glowRing.visibility = View.GONE
                bubbleBtn.visibility = View.GONE

                activeLayout.visibility = View.VISIBLE
                expandedLayout.visibility = View.GONE
                islandContainer.visibility = View.VISIBLE

                val targetWidth = (320 * density).toInt()
                val targetHeight = (64 * density).toInt()

                val startWidth = islandContainer.width.takeIf { it > 0 } ?: (130 * density).toInt()
                val startHeight = islandContainer.height.takeIf { it > 0 } ?: (36 * density).toInt()

                islandMorphAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 340
                    interpolator = android.view.animation.OvershootInterpolator(1.3f)
                    addUpdateListener { anim ->
                        val f = anim.animatedValue as Float
                        val currW = (startWidth + (targetWidth - startWidth) * f).toInt()
                        val currH = (startHeight + (targetHeight - startHeight) * f).toInt()

                        // Update window size dynamically
                        controllerParams.width = currW
                        controllerParams.height = currH

                        // Position window correctly
                        if (hasBubbleBeenMoved) {
                            controllerParams.gravity = Gravity.BOTTOM or Gravity.END
                            // Keep last dragged position for x and y
                        } else {
                            controllerParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                            controllerParams.x = 0
                            controllerParams.y = (40 * density).toInt()
                        }

                        controllerParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                                 WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        try {
                            windowManager.updateViewLayout(controllerView, controllerParams)
                        } catch (_: Exception) {}

                        // Ensure islandContainer fills the window exactly
                        val lp = islandContainer.layoutParams as FrameLayout.LayoutParams
                        lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                        lp.height = FrameLayout.LayoutParams.MATCH_PARENT
                        lp.rightMargin = 0
                        lp.bottomMargin = 0
                        lp.leftMargin = 0
                        lp.topMargin = 0
                        islandContainer.layoutParams = lp
                    }
                }
                islandMorphAnimator?.start()
            }

            IslandMode.EXPANDED -> {
                activeAutoCollapseJob?.cancel()
                glowRing.visibility = View.GONE
                bubbleBtn.visibility = View.GONE
                activeLayout.visibility = View.GONE
                expandedLayout.visibility = View.VISIBLE
                islandContainer.visibility = View.VISIBLE
                dismissOverlay.visibility = View.VISIBLE

                controllerParams.width = WindowManager.LayoutParams.MATCH_PARENT
                controllerParams.height = WindowManager.LayoutParams.MATCH_PARENT
                controllerParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                         WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                controllerParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                try {
                    windowManager.updateViewLayout(controllerView, controllerParams)
                } catch (_: Exception) {}

                val displayMetrics = resources.displayMetrics
                val screenW = displayMetrics.widthPixels
                val screenH = displayMetrics.heightPixels

                val targetWidth = (340 * density).toInt().coerceAtMost((screenW - (24 * density)).toInt())
                val targetHeight = (360 * density).toInt().coerceAtMost((screenH * 0.55f).toInt())

                val startWidth = islandContainer.width.takeIf { it > 0 } ?: (130 * density).toInt()
                val startHeight = islandContainer.height.takeIf { it > 0 } ?: (36 * density).toInt()

                islandMorphAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 320
                    interpolator = android.view.animation.OvershootInterpolator(1.2f)
                    addUpdateListener { anim ->
                        val f = anim.animatedValue as Float
                        val currW = (startWidth + (targetWidth - startWidth) * f).toInt()
                        val currH = (startHeight + (targetHeight - startHeight) * f).toInt()
                        val lp = islandContainer.layoutParams as FrameLayout.LayoutParams
                        lp.width = currW
                        lp.height = currH

                        if (hasBubbleBeenMoved) {
                            lp.gravity = Gravity.BOTTOM or Gravity.END
                            val rMargin = (controllerParams.x + (4 * density)).toInt()
                                .coerceIn((12 * density).toInt(), (screenW - currW - (12 * density).toInt()).coerceAtLeast(0))
                            val bMargin = (controllerParams.y + (4 * density)).toInt()
                                .coerceIn((16 * density).toInt(), (screenH - currH - (32 * density).toInt()).coerceAtLeast(0))
                            lp.rightMargin = rMargin
                            lp.bottomMargin = bMargin
                        } else {
                            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                            lp.rightMargin = 0
                            lp.leftMargin = 0
                            lp.bottomMargin = (40 * density).toInt()
                        }
                        islandContainer.layoutParams = lp
                    }
                }
                islandMorphAnimator?.start()

                updateChatHistory(AgentViewModel.instance?.messages?.value ?: emptyList())
                chatScrollView.post {
                    chatScrollView.fullScroll(View.FOCUS_DOWN)
                }

                inputEt.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(inputEt, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun submitQuery() {
        val text = inputEt.text.toString().trim()
        if (text.isNotEmpty()) {
            inputEt.setText("")
            AgentViewModel.instance?.sendCommand(text)
            updateChatHistory(AgentViewModel.instance?.messages?.value ?: emptyList())
            chatScrollView.post {
                chatScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun getModelFullName(modelId: String): String {
        if (modelId.isBlank()) {
            val provider = AgentViewModel.instance?.provider?.value ?: ""
            return if (provider.isNotBlank()) "Default ($provider)" else "Select Model"
        }
        
        // No hardcoded pretty-name table: show the provider-reported model id.
        return modelId.removePrefix("models/")
    }

    private fun startSpeechToText() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val errMsg = "Please grant microphone permission in the main app first."
            android.widget.Toast.makeText(this, errMsg, android.widget.Toast.LENGTH_LONG).show()
            return
        }

        // Stop active TTS speech output before listening to voice
        try {
            com.example.utils.EdgeTtsManager.stopAll()
        } catch (_: Exception) {}

        val density = resources.displayMetrics.density
        inputEt.hint = "Listening..."
        inputEt.isEnabled = false
        isListeningVoice = true
        micIv.setImageResource(com.zhypix.R.drawable.ic_mic)
        micIv.setColorFilter(Color.BLACK)
        micBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke((1f * density).toInt(), Color.WHITE)
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    val textToSubmit = inputEt.text.toString().trim()
                    stopSpeechToText()
                    if (textToSubmit.isNotBlank()) {
                        submitQuery()
                    } else {
                        inputEt.hint = "Error. Chat with AI..."
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = if (!matches.isNullOrEmpty()) matches[0] else inputEt.text.toString()
                    if (text.isNotBlank()) {
                        inputEt.setText(text)
                        submitQuery()
                    }
                    stopSpeechToText()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        inputEt.setText(matches[0])
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun stopSpeechToText() {
        isListeningVoice = false
        inputEt.isEnabled = true
        inputEt.hint = "Chat with AI..."
        micIv.setImageResource(com.zhypix.R.drawable.ic_mic)
        micIv.setColorFilter(Color.WHITE)
        
        val density = resources.displayMetrics.density
        micBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1C1C1E")) // SurfaceDark
            setStroke((1f * density).toInt(), Color.parseColor("#666666"))
        }
        
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (e: Exception) {}
        speechRecognizer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        hideJob?.cancel()
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {}
        if (::floatingView.isInitialized) {
            try {
                windowManager.removeView(floatingView)
            } catch (e: Exception) {
                // Safeguard against detached views
            }
        }
        if (::controllerView.isInitialized) {
            try {
                windowManager.removeView(controllerView)
            } catch (e: Exception) {
                // Safeguard against detached views
            }
        }
        instance = null
        islandMorphAnimator?.cancel()
        idleBreathAnimator?.cancel()
        visualizerAnimator?.cancel()
        activeAutoCollapseJob?.cancel()
        job.cancel()
    }

    private fun startAsForeground() {
        val channelId = "zhypix_bg_service_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Zhypix Background Assistant",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Zhypix AI & Linux Sandbox active in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val intent = Intent(this, com.example.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val isWakeLockOn = com.example.utils.LinuxTerminalSimulator.isWakeLockHeld.value
        val wakeLockToggleIntent = Intent(this, FloatingAgentService::class.java).apply {
            action = "ACTION_TOGGLE_WAKELOCK"
        }
        val wakeLockPendingIntent = android.app.PendingIntent.getService(
            this, 1, wakeLockToggleIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }

        val statusText = if (isWakeLockOn) "CPU & Terminal: WakeLock ACTIVE" else "Background assistant ready (Tap to keep alive)"
        val actionText = if (isWakeLockOn) "Release WakeLock" else "Acquire WakeLock"

        val notification = notificationBuilder
            .setContentTitle("Zhypix AI & Terminal Active")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_lock_idle_lock, actionText, wakeLockPendingIntent)
            .build()

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(2026, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(2026, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("Zhypix", "Error starting foreground service", e)
        }
    }

    private class ZhypixCubeView(context: Context) : View(context) {
        private val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        
        private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = android.graphics.Paint.Style.FILL
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            val cx = w / 2f
            val cy = h / 2f
            val r = Math.min(w, h) * 0.44f
            val strokeW = Math.max(2.2f, Math.min(w, h) * 0.08f)
            strokePaint.strokeWidth = strokeW

            val p0x = cx; val p0y = cy
            val p1x = cx; val p1y = cy - r
            val p2x = cx; val p2y = cy + r
            val p3x = cx - r * 0.866f; val p3y = cy - r * 0.5f
            val p4x = cx + r * 0.866f; val p4y = cy - r * 0.5f
            val p5x = cx - r * 0.866f; val p5y = cy + r * 0.5f
            val p6x = cx + r * 0.866f; val p6y = cy + r * 0.5f

            // Top face fill
            val topPath = android.graphics.Path().apply {
                moveTo(p0x, p0y); lineTo(p3x, p3y); lineTo(p1x, p1y); lineTo(p4x, p4y); close()
            }
            fillPaint.alpha = 30
            canvas.drawPath(topPath, fillPaint)

            // Left face fill
            val leftPath = android.graphics.Path().apply {
                moveTo(p0x, p0y); lineTo(p3x, p3y); lineTo(p5x, p5y); lineTo(p2x, p2y); close()
            }
            fillPaint.alpha = 15
            canvas.drawPath(leftPath, fillPaint)

            // Right face fill
            val rightPath = android.graphics.Path().apply {
                moveTo(p0x, p0y); lineTo(p4x, p4y); lineTo(p6x, p6y); lineTo(p2x, p2y); close()
            }
            fillPaint.alpha = 22
            canvas.drawPath(rightPath, fillPaint)

            // Outer hexagon wireframe
            val outerPath = android.graphics.Path().apply {
                moveTo(p1x, p1y); lineTo(p4x, p4y); lineTo(p6x, p6y); lineTo(p2x, p2y); lineTo(p5x, p5y); lineTo(p3x, p3y); close()
            }
            canvas.drawPath(outerPath, strokePaint)

            // 3D center edges
            canvas.drawLine(p0x, p0y, p3x, p3y, strokePaint)
            canvas.drawLine(p0x, p0y, p4x, p4y, strokePaint)
            canvas.drawLine(p0x, p0y, p2x, p2y, strokePaint)
        }
    }
}
