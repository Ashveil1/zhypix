package com.example.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.Intent
import android.provider.Settings
import android.graphics.BitmapFactory
import android.util.Base64
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import com.example.ui.theme.*
import com.example.viewmodel.AgentViewModel
import com.example.model.*
import com.example.ui.components.*
import kotlinx.coroutines.launch
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner

import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset

@Composable
fun ChatScreenBody(viewModel: AgentViewModel) {
    val messages by viewModel.messages.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val agentState by viewModel.agentState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val context = LocalContext.current
    var isOverlayEnabled by remember { mutableStateOf(true) }

    fun checkOverlayPermission() {
        isOverlayEnabled = Settings.canDrawOverlays(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        viewModel.setAppActive(true)
        onDispose {
            viewModel.setAppActive(false)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkOverlayPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        checkOverlayPermission()
    }

    LaunchedEffect(messages.size, isProcessing) {
        val targetIndex = if (isProcessing) messages.size else if (messages.isNotEmpty()) messages.size - 1 else -1
        if (targetIndex >= 0) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BackgroundGradientStart, BackgroundGradientEnd)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (maxWidth > 650.dp) 24.dp else 0.dp)
        ) {
            if (!isOverlayEnabled) {
                Surface(
                    color = SurfaceDark,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit",
                                tint = Color.White,
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(22.dp)
                            )
                            Column {
                                Text(
                                    text = "Display Over Other Apps",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Please grant this permission to allow the AI to overlay touch visualizers and action indicators.",
                                    color = TextPrimary.copy(alpha = 0.85f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    try {
                                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                        context.startActivity(intent)
                                    } catch (ex: Exception) {
                                        android.util.Log.e("AI Assistant", "Could not launch overlay settings", ex)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Settings", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            MessageBubbleList(
                messages = messages,
                isProcessing = isProcessing,
                listState = listState,
                modifier = Modifier.weight(1f),
                onSelectPrompt = { prompt -> viewModel.sendCommand(prompt) }
            )

            BottomInputBar(
                viewModel = viewModel,
                onSend = { text -> viewModel.sendCommand(text) },
                isProcessing = isProcessing
            )
        }

        // Overlay UI for Listening only
        AnimatedVisibility(
            visible = agentState == AgentState.LISTENING,
            enter = fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            AgentStateOverlay(state = agentState)
        }


    }
}



@Composable
fun MessageBubbleList(
    messages: List<ChatMessage>,
    isProcessing: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    onSelectPrompt: (String) -> Unit
) {
    AnimatedContent(
        targetState = messages.isEmpty() && !isProcessing,
        transitionSpec = {
            if (targetState) {
                (fadeIn(animationSpec = tween(400, delayMillis = 100)) + scaleIn(initialScale = 0.95f, animationSpec = tween(400, delayMillis = 100)))
                    .togetherWith(fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.95f, animationSpec = tween(200)))
            } else {
                (fadeIn(animationSpec = tween(450, delayMillis = 120)) + slideInVertically(initialOffsetY = { 60 }, animationSpec = tween(450, delayMillis = 120)))
                    .togetherWith(fadeOut(animationSpec = tween(200)) + slideOutVertically(targetOffsetY = { -60 }, animationSpec = tween(200)))
            }
        },
        label = "chat_screen_idle_active_transition",
        modifier = modifier.fillMaxSize()
    ) { isEmptyGreeting ->
        if (isEmptyGreeting) {
            EmptyTerminalGreeting(onSelectPrompt, Modifier.fillMaxSize())
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(
                    count = messages.size,
                    key = { index -> messages[index].id }
                ) { index ->
                    MessageItem(messages[index])
                }
                if (isProcessing) {
                    item(key = "processing_indicator") {
                        ThinkingIndicator(Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
fun TypewriterText(
    phrases: List<String>,
    onSelectPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var textToDisplay by remember { mutableStateOf("") }
    var phraseIndex by remember { mutableIntStateOf(0) }
    var showCursor by remember { mutableStateOf(true) }

    // Cursor blinking effect
    LaunchedEffect(Unit) {
        while (isActive) {
            showCursor = !showCursor
            delay(500)
        }
    }

    // Typewriter effect cycle
    LaunchedEffect(phraseIndex) {
        val currentPhrase = phrases[phraseIndex % phrases.size]
        
        // Type forward
        for (i in 1..currentPhrase.length) {
            textToDisplay = currentPhrase.substring(0, i)
            delay(50L)
        }
        
        // Pause at full text
        delay(2200L)
        
        // Delete backward
        for (i in currentPhrase.length downTo 0) {
            textToDisplay = currentPhrase.substring(0, i)
            delay(25L)
        }
        
        // Brief pause before next phrase
        delay(350L)
        phraseIndex++
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                val currentPhrase = phrases[phraseIndex % phrases.size]
                onSelectPrompt(currentPhrase)
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = textToDisplay,
                color = Color(0xFFFAFAFA),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Text(
                text = if (showCursor) "_" else " ",
                color = Color.White,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun EmptyTerminalGreeting(onSelectPrompt: (String) -> Unit, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    
    val phrases = remember {
        listOf(
            "How can I help you today?",
            "Run terminal commands & execute scripts...",
            "Automate device navigation & app tasks...",
            "Inspect screen content & analyze UI...",
            "Generate code, build tools, & solve tasks...",
            "Type any prompt or tap to try it out!"
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(start = 24.dp, end = 24.dp, top = 130.dp, bottom = 24.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Centered Isometric Brand Logo
            IsometricCube(
                modifier = Modifier.size(52.dp)
            )

            // Animated Typewriter Guidance Text
            TypewriterText(
                phrases = phrases,
                onSelectPrompt = onSelectPrompt
            )
        }
    }
}

