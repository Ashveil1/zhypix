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
    
    // Interactive Controller Overlay Views
    private lateinit var controllerView: View
    private lateinit var controllerParams: WindowManager.LayoutParams
    private lateinit var bubbleBtn: FrameLayout
    private lateinit var inputLayout: LinearLayout
    private lateinit var inputEt: EditText
    private lateinit var dismissOverlay: View
    private var bubblePreviewLayout: LinearLayout? = null
    private var bubblePreviewText: TextView? = null
    private var lastStreamMessage = ""
    private var previewHideJob: Job? = null

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var hideJob: Job? = null

    // Speech-to-text / Voice Input and Custom Button
    private lateinit var micBtn: FrameLayout
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningVoice = false

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
            val action = intent.action
            if (action == "ACTION_EXPAND_INPUT") {
                if (::inputLayout.isInitialized) {
                    expandInputBar()
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

        // 2. Interactive Controller Overlay (Floating Circular Button + Chat Input Bar)
        val density = resources.displayMetrics.density
        val dp56 = (56 * density).toInt()

        controllerParams = WindowManager.LayoutParams(
            dp56,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        controllerParams.gravity = Gravity.BOTTOM or Gravity.END
        controllerParams.x = 48
        controllerParams.y = 180 // Above typical soft navigation keys

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

    // Dynamic Preview Bubble popping out of the round button
    fun showBubblePreview(text: String) {
        val pLayout = bubblePreviewLayout ?: return
        val pTv = bubblePreviewText ?: return

        previewHideJob?.cancel()

        val density = resources.displayMetrics.density
        val dp320 = (320 * density).toInt()

        // Temporarily expand window width to show preview
        if (controllerParams.width < dp320) {
            controllerParams.width = dp320
            windowManager.updateViewLayout(controllerView, controllerParams)
        }

        pTv.text = text
        pLayout.visibility = View.VISIBLE
        pLayout.alpha = 0f
        pLayout.scaleX = 0.7f
        pLayout.scaleY = 0.7f
        
        pLayout.animate()
            .alpha(1f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
            .setDuration(400)
            .start()

        // Auto-hide after 5 seconds
        previewHideJob = scope.launch {
            delay(5000L)
            pLayout.animate()
                .alpha(0f)
                .scaleX(0.7f)
                .scaleY(0.7f)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .setDuration(250)
                .withEndAction {
                    pLayout.visibility = View.GONE
                    
                    // Collapse window width back to 56dp if input bar is also collapsed
                    if (inputLayout.visibility != View.VISIBLE) {
                        val dp56 = (56 * density).toInt()
                        controllerParams.width = dp56
                        windowManager.updateViewLayout(controllerView, controllerParams)
                    }
                }
                .start()
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
        val dp56 = (56 * density).toInt()
        val dp48 = (48 * density).toInt()
        val dp320 = (320 * density).toInt()

        val rootLayout = FrameLayout(this).apply {
            layoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        // Invisible Full-screen Dismiss Overlay to detect clicks outside the expanded input layout
        dismissOverlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            visibility = View.GONE
            setOnClickListener {
                collapseInputBar()
            }
        }

        // 1. Circular Bubble Button
        bubbleBtn = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp56, dp56)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0A0A0B")) // Premium matte black
                setStroke((1.5f * density).toInt(), Color.WHITE) // Titanium White border
            }
            elevation = 12f * density
        }

        val bubbleIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_ai_assistant)
            setColorFilter(Color.WHITE) // TINT PURE WHITE FOR LUXURY
            layoutParams = FrameLayout.LayoutParams(
                (24 * density).toInt(),
                (24 * density).toInt()
            ).apply {
                gravity = Gravity.CENTER
            }
        }
        bubbleBtn.addView(bubbleIcon)

        // Touch & Drag Support for Bubble Button
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        bubbleBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = controllerParams.x
                    initialY = controllerParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    
                    // Smooth press squish feedback
                    bubbleBtn.animate()
                        .scaleX(0.88f)
                        .scaleY(0.88f)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .setDuration(120)
                        .start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        // Gravity is Gravity.BOTTOM or Gravity.END, so adjust inverse translations
                        controllerParams.x = initialX - dx
                        controllerParams.y = initialY - dy
                        windowManager.updateViewLayout(controllerView, controllerParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Responsive spring-loaded bounce back on release
                    bubbleBtn.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2.2f))
                        .setDuration(350)
                        .start()
                        
                    if (event.action == MotionEvent.ACTION_UP && !isDragging) {
                        expandInputBar()
                    }
                    true
                }
                else -> false
            }
        }

        // 2. Expandable Input Capsule Bar Layout
        inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dp320, dp48).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f * density
                setColor(Color.parseColor("#0A0A0B")) // Deep premium black
                setStroke((1.5f * density).toInt(), Color.WHITE) // Fine titanium white border
            }
            elevation = 16f * density
        }

        val isThai = false

        // Model Selector Button
        val modelBtn = FrameLayout(this).apply {
            val size = (30 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = (6 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1C1C1E")) // SurfaceDark
                setStroke((1f * density).toInt(), Color.parseColor("#333333"))
            }
        }
        val modelTv = TextView(this).apply {
            val activeModel = AgentViewModel.instance?.modelName?.value ?: ""
            text = getModelAbbreviation(activeModel)
            setTextColor(Color.WHITE)
            textSize = 9f
            gravity = Gravity.CENTER
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        modelBtn.addView(modelTv)

        modelBtn.setOnClickListener {
            val popup = android.widget.PopupMenu(this@FloatingAgentService, modelBtn)
            val available = AgentViewModel.instance?.availableModels?.value ?: emptyList()
            val current = AgentViewModel.instance?.modelName?.value ?: ""
            
            val modelsToShow = if (available.isNotEmpty()) {
                available.map { it.modelId }.filter { it.isNotBlank() }
            } else {
                listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-1.5-flash", "gemini-1.5-pro", "gpt-4o", "gpt-3.5-turbo")
            }

            modelsToShow.forEachIndexed { index, mId ->
                val displayName = when (mId) {
                    "gemini-2.5-flash" -> "Gemini 2.5 Flash (Faster)"
                    "gemini-2.5-pro" -> "Gemini 2.5 Pro (Analytical)"
                    "gemini-1.5-pro" -> "Gemini 1.5 Pro"
                    "gemini-1.5-flash" -> "Gemini 1.5 Flash"
                    "gemini-2.5-flash" -> "Gemini 2.5 Flash"
                    "gpt-4o" -> "GPT-4o"
                    "gpt-3.5-turbo" -> "GPT-3.5 Turbo"
                    else -> mId
                }
                val titleText = if (current == mId || (current.isEmpty() && mId == "none")) "✓ $displayName" else displayName
                popup.menu.add(0, index, index, titleText)
            }

            popup.setOnMenuItemClickListener { menuItem ->
                val selectedModel = modelsToShow[menuItem.itemId]
                AgentViewModel.instance?.updateSetting("modelName", selectedModel)
                modelTv.text = getModelAbbreviation(selectedModel)
                true
            }
            popup.show()
        }
        inputLayout.addView(modelBtn)

        // Input EditText
        inputEt = EditText(this).apply {
            hint = "Chat with AI..."
            setHintTextColor(Color.parseColor("#94A3B8")) // Slate 400
            setTextColor(Color.WHITE)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = null
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                rightMargin = (6 * density).toInt()
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitQueryAndCollapse()
                    true
                } else {
                    false
                }
            }
        }
        inputLayout.addView(inputEt)

        // Speaker / Auto TTS Toggle Button
        val speakerBtn = FrameLayout(this).apply {
            val size = (30 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = (6 * density).toInt()
            }
        }
        val speakerTv = TextView(this).apply {
            text = "🔊"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        speakerBtn.addView(speakerTv)

        fun updateSpeakerState(enabled: Boolean) {
            if (enabled) {
                speakerTv.text = "🔊"
                speakerTv.setTextColor(Color.BLACK)
                speakerBtn.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE) // High-contrast White active
                    setStroke((1f * density).toInt(), Color.BLACK)
                }
            } else {
                speakerTv.text = "🔇"
                speakerTv.setTextColor(Color.WHITE)
                speakerBtn.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#1C1C1E")) // SurfaceDark inactive
                    setStroke((1f * density).toInt(), Color.parseColor("#333333"))
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
        inputLayout.addView(speakerBtn)

        // Voice/Mic Button
        micBtn = FrameLayout(this).apply {
            val size = (30 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = (6 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1C1C1E")) // SurfaceDark
                setStroke((1f * density).toInt(), Color.parseColor("#333333"))
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
        val micTv = TextView(this).apply {
            text = "🎙"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        micBtn.addView(micTv)

        scope.launch {
            AgentViewModel.instance?.continuousVoiceMode?.collect { continuous ->
                if (!isListeningVoice) {
                    if (continuous) {
                        micTv.setTextColor(Color.BLACK)
                        micBtn.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.WHITE) // High-contrast White background
                            setStroke((1f * density).toInt(), Color.BLACK)
                        }
                    } else {
                        micTv.setTextColor(Color.WHITE)
                        micBtn.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.parseColor("#1C1C1E"))
                            setStroke((1f * density).toInt(), Color.parseColor("#333333"))
                        }
                    }
                }
            }
        }

        inputLayout.addView(micBtn)

        // Stop / Interrupt Button
        val stopBtn = FrameLayout(this).apply {
            val size = (30 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = (6 * density).toInt()
            }
            visibility = View.GONE // Hidden initially, shown when AI is processing
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EF4444")) // Vivid Red Stop Button
            }
            setOnClickListener {
                AgentViewModel.instance?.stopExecution()
                showNotification("INTERRUPTED", "Operation cancelled by user", isFinal = true)
            }
        }
        val stopTv = TextView(this).apply {
            text = "⏹"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        stopBtn.addView(stopTv)
        inputLayout.addView(stopBtn)

        // Send Button
        val sendBtn = FrameLayout(this).apply {
            val size = (30 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = (6 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE) // Pure White Button
            }
            setOnClickListener {
                submitQueryAndCollapse()
            }
        }
        val sendTv = TextView(this).apply {
            text = "➔"
            setTextColor(Color.BLACK) // Pure Black Arrow for High Contrast
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        sendBtn.addView(sendTv)
        inputLayout.addView(sendBtn)

        // Observe isProcessing to toggle stopBtn and update bubble appearance dynamically
        scope.launch {
            AgentViewModel.instance?.isProcessing?.collect { processing ->
                if (processing) {
                    stopBtn.visibility = View.VISIBLE
                    (bubbleBtn.background as? GradientDrawable)?.apply {
                        setColor(Color.parseColor("#0A0A0B")) // Keep premium black even while active
                        setStroke((1.5f * density).toInt(), Color.WHITE) // White border
                    }
                } else {
                    stopBtn.visibility = View.GONE
                    (bubbleBtn.background as? GradientDrawable)?.apply {
                        setColor(Color.parseColor("#0A0A0B")) // Restore default titanium black
                        setStroke((1.5f * density).toInt(), Color.WHITE)
                    }
                }
            }
        }

        // Cancel / Close Button (Now closes service completely)
        val cancelBtn = FrameLayout(this).apply {
            val size = (30 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1C1C1E")) // SurfaceDark
                setStroke((1f * density).toInt(), Color.parseColor("#333333"))
            }
            setOnClickListener {
                closeServiceCompletely()
            }
        }
        val cancelTv = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        cancelBtn.addView(cancelTv)
        inputLayout.addView(cancelBtn)

        // 3. Dynamic Preview Bubble
        val previewLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            val lp = FrameLayout.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                rightMargin = (56 * density).toInt() + (8 * density).toInt()
            }
            layoutParams = lp
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(Color.parseColor("#0A0A0B")) // Premium matte black
                setStroke((1f * density).toInt(), Color.parseColor("#333333")) // Fine dark border
            }
            elevation = 10f * density
        }

        val previewTv = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                maxWidth = (160 * density).toInt()
            }
        }
        previewLayout.addView(previewTv)
        bubblePreviewLayout = previewLayout
        bubblePreviewText = previewTv

        rootLayout.addView(dismissOverlay)
        rootLayout.addView(previewLayout)
        rootLayout.addView(bubbleBtn)
        rootLayout.addView(inputLayout)

        return rootLayout
    }

    private fun expandInputBar() {
        // Hide preview immediately
        bubblePreviewLayout?.visibility = View.GONE
        previewHideJob?.cancel()

        bubbleBtn.visibility = View.GONE
        inputLayout.visibility = View.VISIBLE
        dismissOverlay.visibility = View.VISIBLE

        val density = resources.displayMetrics.density
        val dp320 = (320 * density).toInt()

        // Set layout margins dynamically to match the current dragged bubble position
        val lp = inputLayout.layoutParams as FrameLayout.LayoutParams
        lp.rightMargin = controllerParams.x
        lp.bottomMargin = controllerParams.y
        inputLayout.layoutParams = lp

        // Premium springy overshoot scaling entry transition
        inputLayout.alpha = 0f
        inputLayout.scaleX = 0.82f
        inputLayout.scaleY = 0.82f
        inputLayout.animate()
            .alpha(1f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.25f))
            .setDuration(320)
            .start()

        // Transition window width/height to full-screen to allow click-to-dismiss overlay
        controllerParams.width = WindowManager.LayoutParams.MATCH_PARENT
        controllerParams.height = WindowManager.LayoutParams.MATCH_PARENT
        controllerParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                 WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        windowManager.updateViewLayout(controllerView, controllerParams)

        inputEt.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(inputEt, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun collapseInputBar() {
        // Stop listening voice if active
        if (isListeningVoice) {
            stopSpeechToText()
        }

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(inputEt.windowToken, 0)

        val density = resources.displayMetrics.density
        val dp56 = (56 * density).toInt()

        dismissOverlay.visibility = View.GONE

        inputLayout.animate()
            .alpha(0f)
            .scaleX(0.82f)
            .scaleY(0.82f)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .setDuration(180)
            .withEndAction {
                inputLayout.visibility = View.GONE
                bubbleBtn.visibility = View.VISIBLE

                // Pop-up bubble with elegant springiness
                bubbleBtn.alpha = 0f
                bubbleBtn.scaleX = 0.6f
                bubbleBtn.scaleY = 0.6f
                bubbleBtn.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.6f))
                    .setDuration(350)
                    .start()

                // Collapse window width/height back to bubble size and restore non-focusable state
                controllerParams.width = dp56
                controllerParams.height = dp56
                controllerParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                         WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                windowManager.updateViewLayout(controllerView, controllerParams)
                inputEt.setText("")
            }
            .start()
    }

    private fun submitQueryAndCollapse() {
        val text = inputEt.text.toString().trim()
        if (text.isNotEmpty()) {
            inputEt.setText("")
            AgentViewModel.instance?.sendCommand(text)
        }
        collapseInputBar()
    }

    private fun getModelAbbreviation(modelId: String): String {
        return when (modelId) {
            "gemini-3.5-flash" -> "3.5F"
            "gemini-1.5-pro" -> "1.5P"
            "gemini-1.5-flash" -> "1.5F"
            "gemini-2.5-flash" -> "2.5F"
            "gpt-4o" -> "4O"
            "gpt-3.5-turbo" -> "3.5T"
            else -> {
                if (modelId.isBlank()) "AI"
                else {
                    val clean = modelId.removePrefix("models/")
                    if (clean.length > 4) clean.take(4).uppercase() else clean.uppercase()
                }
            }
        }
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

        inputEt.hint = "Listening..."
        inputEt.isEnabled = false
        isListeningVoice = true
        micBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#EF4444")) // Red alerting color
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
                        submitQueryAndCollapse()
                    } else {
                        inputEt.hint = "Error. Chat with AI..."
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = if (!matches.isNullOrEmpty()) matches[0] else inputEt.text.toString()
                    if (text.isNotBlank()) {
                        inputEt.setText(text)
                        submitQueryAndCollapse()
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
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
        
        val density = resources.displayMetrics.density
        micBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1C1C1E")) // SurfaceDark
            setStroke((1f * density).toInt(), Color.parseColor("#333333"))
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
                description = "Keeps Zhypix AI active in the background for processing instructions"
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

        val notificationBuilder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }

        val notification = notificationBuilder
            .setContentTitle("Zhypix AI Active")
            .setContentText("Background assistant is ready and running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
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
}
