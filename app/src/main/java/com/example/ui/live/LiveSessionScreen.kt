package com.example.ui.live

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.live.LiveController
import com.example.live.LiveSessionManager
import com.example.ui.PermissionUtils
import com.example.ui.components.IsometricCube
import com.example.ui.theme.*
import com.example.viewmodel.AgentViewModel

private fun stateColor(state: LiveSessionManager.State): Color =
    when (state) {
        LiveSessionManager.State.IDLE -> TextTertiary
        LiveSessionManager.State.CONNECTING -> OrangeWarning
        LiveSessionManager.State.LISTENING -> Accent
        LiveSessionManager.State.SPEAKING -> InfoBlue
        LiveSessionManager.State.ACTING -> SuccessGreen
        LiveSessionManager.State.ERROR -> CrimsonRose
        LiveSessionManager.State.CLOSED -> TextTertiary
    }

private fun stateLabel(state: LiveSessionManager.State): String =
    when (state) {
        LiveSessionManager.State.IDLE -> "Ready"
        LiveSessionManager.State.CONNECTING -> "Connecting"
        LiveSessionManager.State.LISTENING -> "Listening"
        LiveSessionManager.State.SPEAKING -> "Speaking"
        LiveSessionManager.State.ACTING -> "Acting on screen"
        LiveSessionManager.State.ERROR -> "Connection error"
        LiveSessionManager.State.CLOSED -> "Session ended"
    }

@Composable
internal fun LiveStatePill(state: LiveSessionManager.State) {
    val active = state == LiveSessionManager.State.CONNECTING ||
        state == LiveSessionManager.State.LISTENING ||
        state == LiveSessionManager.State.ACTING
    val pulse by rememberInfiniteTransition(label = "live_pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )
    Surface(
        color = SurfaceElevated,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceLightBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (active) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .scale(pulse)
                            .background(stateColor(state).copy(alpha = 0.25f), CircleShape)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(stateColor(state), CircleShape)
                )
            }
            Text(
                text = stateLabel(state),
                color = TextPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
fun LiveSessionScreen(
    viewModel: AgentViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val controller by LiveSessionHost.controller.collectAsState()
    val pendingConfirm by LiveSessionHost.pendingConfirm.collectAsState()
    val (available, liveModel) = LiveSessionHost.liveAvailability(viewModel)

    val state by (controller?.state ?: kotlinx.coroutines.flow.flowOf(LiveSessionManager.State.IDLE))
        .collectAsState(initial = LiveSessionManager.State.IDLE)
    val transcript by (controller?.transcript ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList<LiveController.TranscriptEntry>())

    var voiceWanted by remember { mutableStateOf(true) }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            LiveSessionHost.toggleVoice(context, viewModel, voice = true)
            voiceWanted = true
        } else {
            Toast.makeText(context, "Microphone permission is required for Live voice.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.size - 1)
    }

    // Sensitive-action confirmation dialog (the Live confirm-before-send gate)
    pendingConfirm?.let { req ->
        AlertDialog(
            onDismissRequest = { req.answer(false) },
            icon = {
                Icon(Icons.Default.Warning, contentDescription = null, tint = OrangeWarning)
            },
            title = {
                Text("Confirm action", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Text(
                    "Zhypix Live wants to ${req.actionType} \"${req.target}\". This may send, pay, or confirm something.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = { req.answer(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Confirm", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(
                    onClick = { req.answer(false) },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                ) { Text("Cancel") }
            },
            containerColor = SurfaceElevated,
            shape = RoundedCornerShape(24.dp),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BackgroundGradientStart, BackgroundGradientEnd)))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IsometricCube(modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("Live", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text(
                            liveModel ?: "Gemini Live",
                            color = TextTertiary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveStatePill(state)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(SurfaceElevated)
                            .border(1.dp, SurfaceLightBorder, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconButton(onClick = {
                            LiveSessionHost.endSession()
                            onClose()
                        }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close Live",
                                tint = TextPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // Transcript
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (transcript.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 80.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            IsometricCube(modifier = Modifier.size(44.dp))
                            Text(
                                if (available) "Start a Live session to talk and share your screen."
                                else "Live needs a Gemini provider with a Live-capable model.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                } else {
                    items(
                        count = transcript.size,
                        key = { it },
                    ) { index ->
                        val entry = transcript[index]
                        if (entry.fromUser) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                Box(
                                    modifier = Modifier
                                        .widthIn(min = 40.dp, max = 520.dp)
                                        .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(Color.White, Color(0xFFE9E9EC))
                                            )
                                        )
                                        .padding(horizontal = 16.dp, vertical = 11.dp)
                                ) {
                                    Text(
                                        entry.text,
                                        color = OnAccent,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        } else {
                            Surface(
                                color = SurfaceDark,
                                shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceLightBorder),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    entry.text,
                                    color = TextPrimary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Bottom control bar
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        enabled = controller != null && state != LiveSessionManager.State.CLOSED,
                        placeholder = {
                            Text(
                                "Type to Live...",
                                color = TextTertiary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.White.copy(alpha = 0.35f),
                            unfocusedBorderColor = SurfaceLightBorder,
                            focusedContainerColor = SurfaceDark,
                            unfocusedContainerColor = SurfaceDark,
                            cursorColor = Accent,
                        ),
                        shape = RoundedCornerShape(26.dp),
                        maxLines = 3,
                    )

                    val sessionActive = state == LiveSessionManager.State.CONNECTING ||
                        state == LiveSessionManager.State.LISTENING ||
                        state == LiveSessionManager.State.SPEAKING ||
                        state == LiveSessionManager.State.ACTING

                    // Mic / voice toggle
                    IconButton(
                        onClick = {
                            if (voiceWanted && PermissionUtils.isMicrophoneGranted(context)) {
                                voiceWanted = false
                                if (sessionActive) LiveSessionHost.toggleVoice(context, viewModel, voice = false)
                            } else if (PermissionUtils.isMicrophoneGranted(context)) {
                                voiceWanted = true
                                LiveSessionHost.toggleVoice(context, viewModel, voice = true)
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                if (voiceWanted) Color.White.copy(alpha = 0.10f) else SurfaceDark,
                                CircleShape
                            )
                            .border(
                                1.dp,
                                if (voiceWanted) Color.White.copy(alpha = 0.35f) else SurfaceLightBorder,
                                CircleShape
                            ),
                    ) {
                        Icon(
                            imageVector = if (voiceWanted) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = if (voiceWanted) "Voice on" else "Voice off",
                            tint = if (voiceWanted) Accent else TextTertiary,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    if (sessionActive) {
                        if (inputText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    controller?.sendText(inputText)
                                    inputText = ""
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Accent, CircleShape),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = OnAccent,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { LiveSessionHost.stopSession() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(CrimsonRose.copy(alpha = 0.14f), CircleShape)
                                    .border(1.dp, CrimsonRose.copy(alpha = 0.5f), CircleShape),
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = "Stop session",
                                    tint = CrimsonRose,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    } else {
                        val canStart = available
                        IconButton(
                            onClick = {
                                if (canStart) {
                                    LiveSessionHost.startSession(context, viewModel, voice = voiceWanted)
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Set a Gemini provider and fetch a Live model first.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    if (canStart) Accent else Color.White.copy(alpha = 0.06f),
                                    CircleShape
                                )
                                .border(
                                    1.dp,
                                    if (canStart) Accent else SurfaceLightBorder,
                                    CircleShape
                                ),
                        ) {
                            IsometricCube(
                                modifier = Modifier.size(20.dp),
                                color = if (canStart) OnAccent else TextTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}
