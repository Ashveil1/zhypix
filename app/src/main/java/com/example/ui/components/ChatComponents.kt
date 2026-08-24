package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.theme.*
import com.example.viewmodel.AgentViewModel
import kotlinx.coroutines.delay
import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.example.utils.PermissionUtils
import com.example.utils.parseAnsiToAnnotatedString

@Composable
fun MessageItem(message: ChatMessage, modifier: Modifier = Modifier) {
    val state = remember(message.id) {
        MutableTransitionState(false).apply { targetState = true }
    }
    AnimatedVisibility(
        visibleState = state,
        modifier = modifier,
        enter = fadeIn(
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        ) + slideInVertically(
            initialOffsetY = { 45 },
            animationSpec = spring(
                dampingRatio = 0.75f,
                stiffness = Spring.StiffnessMediumLow
            )
        ) + scaleIn(
            initialScale = 0.93f,
            animationSpec = spring(
                dampingRatio = 0.75f,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (message is ChatMessage.User) Arrangement.End else Arrangement.Start
        ) {
            when (message) {
                is ChatMessage.User -> {
                    UserMessageBubble(message)
                }
                is ChatMessage.Agent -> {
                    AgentMessageBubble(message)
                }
                is ChatMessage.TaskExecution -> {
                    TaskExecutionItem(message)
                }
                is ChatMessage.ProviderConfigRequired -> {
                    ProviderConfigRequiredBubble(message)
                }
                else -> {
                    SystemMessageItem(message)
                }
            }
        }
    }
}

@Composable
fun UserMessageBubble(message: ChatMessage.User) {
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(RoundedCornerShape(12.dp, 12.dp, 2.dp, 12.dp))
                .background(Color.White)
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp, 12.dp, 2.dp, 12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.text,
                color = Color.Black,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun TtsSpeaker(text: String, onToggleSpeaking: () -> Unit) {
    val agentVm = com.example.viewmodel.AgentViewModel.instance
    val isTtsPlaying by (agentVm?.ttsManager?.isPlaying?.collectAsState() ?: remember { mutableStateOf(false) })
    val isTtsSynthesizing by (agentVm?.ttsManager?.isSynthesizing?.collectAsState() ?: remember { mutableStateOf(false) })
    val currentTtsText by (agentVm?.ttsManager?.currentText?.collectAsState() ?: remember { mutableStateOf(null) })

    val isThisSpeaking by remember(text, isTtsPlaying, isTtsSynthesizing, currentTtsText) {
        derivedStateOf {
            (isTtsPlaying || isTtsSynthesizing) && currentTtsText == text
        }
    }

    Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isThisSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
            contentDescription = "Read aloud",
            tint = if (isThisSpeaking) Color(0xFF06B6D4) else TextSecondary,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (isThisSpeaking) "STOP" else "LISTEN",
            color = if (isThisSpeaking) Color(0xFF06B6D4) else TextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun AgentMessageBubble(message: ChatMessage.Agent) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000L)
            copied = false
        }
    }

    Surface(
        color = SurfaceDark.copy(alpha = 0.4f),
        shape = RoundedCornerShape(2.dp, 12.dp, 12.dp, 12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.04f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                FormattedAgentMessage(text = message.text)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val agentVm = com.example.viewmodel.AgentViewModel.instance
                
                Surface(
                    color = Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.clickable {
                        val isPlaying = agentVm?.ttsManager?.isPlaying?.value ?: false
                        val isSynthesizing = agentVm?.ttsManager?.isSynthesizing?.value ?: false
                        val currentText = agentVm?.ttsManager?.currentText?.value
                        val isThisSpeaking = (isPlaying || isSynthesizing) && currentText == message.text
                        
                        if (isThisSpeaking) {
                            agentVm?.stopSpeaking()
                        } else {
                            agentVm?.speakText(message.text)
                        }
                    }
                ) {
                    TtsSpeaker(
                        text = message.text,
                        onToggleSpeaking = {
                            val agentVm = com.example.viewmodel.AgentViewModel.instance
                            agentVm?.speakText(message.text)
                        }
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    color = if (copied) SuccessGreen.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.clickable {
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(message.text))
                        copied = true
                        android.widget.Toast.makeText(context, "AI message copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                            contentDescription = "Copy message",
                            tint = if (copied) SuccessGreen else TextSecondary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (copied) "COPIED" else "COPY",
                            color = if (copied) SuccessGreen else TextSecondary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SystemMessageItem(message: ChatMessage) {
    val text = when (message) {
        is ChatMessage.System -> message.text
        else -> ""
    }
    
    if (text.isNotBlank()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = TextSecondary.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun ProviderConfigRequiredBubble(message: ChatMessage.ProviderConfigRequired) {
    val agentVm = com.example.viewmodel.AgentViewModel.instance

    Surface(
        color = Color(0xFF09090B),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF27272A)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF18181B))
                        .border(1.dp, Color(0xFF3F3F46), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings Required",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Provider & Model Not Configured",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Configuration Required",
                        color = Color(0xFFA1A1AA),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (message.text.isNotBlank()) message.text else "AI Provider or Model is not configured yet. Please select your AI Provider, Model, and enter your API Key to use Zhypix AI.",
                color = Color(0xFFD4D4D8),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = {
                    agentVm?.openSettings(com.example.model.SettingsScreenType.PROFILE)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Configure Provider & Model Settings",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
fun FormattedAgentMessage(text: String, modifier: Modifier = Modifier) {
    val parts = remember(text) {
        val list = mutableListOf<MessageContentPart>()
        val segments = text.split("```")
        for (i in segments.indices) {
            val segment = segments[i]
            if (i % 2 == 1) {
                val lines = segment.trim().split("\n")
                val language = if (lines.isNotEmpty() && lines[0].trim().length < 15 && !lines[0].contains(" ") && lines[0].isNotBlank()) {
                    lines[0].trim()
                } else {
                    ""
                }
                val codeContent = if (language.isNotEmpty()) {
                    lines.drop(1).joinToString("\n")
                } else {
                    segment
                }
                list.add(MessageContentPart.CodeBlock(language, codeContent.trim()))
            } else {
                if (segment.isNotEmpty()) {
                    list.add(MessageContentPart.TextPart(segment))
                }
            }
        }
        list
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        parts.forEach { part ->
            when (part) {
                is MessageContentPart.TextPart -> {
                    MarkdownTextPart(content = part.content)
                }
                is MessageContentPart.CodeBlock -> {
                    CodeBlockCard(language = part.language, code = part.code)
                }
            }
        }
    }
}

sealed interface MessageContentPart {
    data class TextPart(val content: String) : MessageContentPart
    data class CodeBlock(val language: String, val code: String) : MessageContentPart
}

sealed interface MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class BulletItem(val text: String) : MarkdownBlock
    data class NumberedItem(val number: String, val text: String) : MarkdownBlock
}

fun parseMarkdownBlocks(content: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = content.split("\n")
    var currentParagraph = StringBuilder()

    fun flushParagraph() {
        if (currentParagraph.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(currentParagraph.toString().trimEnd()))
            currentParagraph = StringBuilder()
        }
    }

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            flushParagraph()
            continue
        }

        // Headers
        if (trimmed.startsWith("#")) {
            flushParagraph()
            val level = trimmed.takeWhile { it == '#' }.length
            if (level in 1..6 && trimmed.length > level && trimmed[level] == ' ') {
                val headerText = trimmed.substring(level + 1).trim()
                blocks.add(MarkdownBlock.Header(level, headerText))
            } else {
                currentParagraph.append(line).append("\n")
            }
            continue
        }

        // Bullet list item
        val isBullet = (trimmed.startsWith("* ") || trimmed.startsWith("- ") || trimmed.startsWith("• "))
        if (isBullet) {
            flushParagraph()
            val itemText = trimmed.substring(2).trim()
            blocks.add(MarkdownBlock.BulletItem(itemText))
            continue
        }

        // Numbered list item: e.g. "1. Item"
        val numberRegex = Regex("^(\\d+\\.)\\s+(.*)$")
        val matchResult = numberRegex.matchEntire(trimmed)
        if (matchResult != null) {
            flushParagraph()
            val number = matchResult.groupValues[1]
            val itemText = matchResult.groupValues[2]
            blocks.add(MarkdownBlock.NumberedItem(number, itemText))
            continue
        }

        // Standard paragraph line accumulation
        if (currentParagraph.isNotEmpty()) {
            currentParagraph.append("\n")
        }
        currentParagraph.append(line)
    }

    flushParagraph()
    return blocks
}

fun parseMarkdownInline(text: String): androidx.compose.ui.text.AnnotatedString {
    return androidx.compose.ui.text.buildAnnotatedString {
        var i = 0
        val len = text.length
        while (i < len) {
            when {
                // Bold-Italic (***bold italic*** or ___bold italic___)
                text.startsWith("***", i) && len - i >= 6 -> {
                    val end = text.indexOf("***", i + 3)
                    if (end != -1) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                        append(text.substring(i + 3, end))
                        pop()
                        i = end + 3
                    } else {
                        append("***")
                        i += 3
                    }
                }
                // Bold (**bold**)
                text.startsWith("**", i) && len - i >= 4 -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                        append(text.substring(i + 2, end))
                        pop()
                        i = end + 2
                    } else {
                        append("**")
                        i += 2
                    }
                }
                // Italic (*italic*)
                text.startsWith("*", i) && len - i >= 2 && text[i + 1] != ' ' -> {
                    val end = text.indexOf("*", i + 1)
                    if (end != -1) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontStyle = FontStyle.Italic))
                        append(text.substring(i + 1, end))
                        pop()
                        i = end + 1
                    } else {
                        append("*")
                        i++
                    }
                }
                // Inline code (`code`)
                text.startsWith("`", i) && len - i >= 2 -> {
                    val end = text.indexOf("`", i + 1)
                    if (end != -1) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color.White.copy(alpha = 0.08f),
                            color = CyanAccent,
                            fontSize = 13.sp
                        ))
                        append(" ")
                        append(text.substring(i + 1, end))
                        append(" ")
                        pop()
                        i = end + 1
                    } else {
                        append("`")
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

@Composable
fun MarkdownTextPart(content: String, modifier: Modifier = Modifier) {
    val blocks = remember(content) {
        parseMarkdownBlocks(content)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Header -> {
                    val fontSize = when (block.level) {
                        1 -> 20.sp
                        2 -> 17.sp
                        else -> 15.sp
                    }
                    val fontWeight = FontWeight.Bold
                    val textColor = if (block.level == 1) CyanAccent else TextPrimary
                    
                    Text(
                        text = parseMarkdownInline(block.text),
                        color = textColor,
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                        lineHeight = fontSize * 1.3f
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = parseMarkdownInline(block.text),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
                is MarkdownBlock.BulletItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            color = CyanAccent,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = parseMarkdownInline(block.text),
                            color = TextPrimary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MarkdownBlock.NumberedItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = block.number,
                            color = CyanAccent,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = parseMarkdownInline(block.text),
                            color = TextPrimary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CodeBlockCard(language: String, code: String) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000L)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.85f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.03f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language.uppercase().ifEmpty { "CODE TERMINAL" },
                color = CyanAccent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Surface(
                color = if (copied) SuccessGreen.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.clickable {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(code))
                    copied = true
                    android.widget.Toast.makeText(context, "Code copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        tint = if (copied) SuccessGreen else TextSecondary,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (copied) "COPIED!" else "COPY",
                        color = if (copied) SuccessGreen else TextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = code,
                color = Color(0xFFE5E7EB),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun ThinkingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing, delayMillis = 200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing, delayMillis = 400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp, 12.dp, 12.dp, 2.dp))
            .background(SurfaceDark.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(modifier = Modifier.size(4.dp).background(CyanAccent.copy(alpha = dot1Alpha), CircleShape))
        Box(modifier = Modifier.size(4.dp).background(CyanAccent.copy(alpha = dot2Alpha), CircleShape))
        Box(modifier = Modifier.size(4.dp).background(CyanAccent.copy(alpha = dot3Alpha), CircleShape))
        Spacer(modifier = Modifier.width(4.dp))
        Text("AI is thinking...", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun BottomInputBar(
    viewModel: AgentViewModel,
    onSend: (String) -> Unit,
    isProcessing: Boolean,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    val isListening by viewModel.sttManager.isListening.collectAsState()
    val partialText by viewModel.sttManager.partialText.collectAsState()

    // Setup permission launcher for voice recording
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startListening("th-TH") { result ->
                if (result.isNotBlank()) {
                    text = result
                }
            }
        } else {
            Toast.makeText(context, "Microphone permission is required for voice input.", Toast.LENGTH_SHORT).show()
        }
    }

    // Bind partial text to input field while listening
    LaunchedEffect(isListening, partialText) {
        if (isListening && partialText.isNotBlank()) {
            text = partialText
        }
    }

    Surface(
        color = SurfaceDark.copy(alpha = 0.6f),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...", color = TextSecondary, fontSize = 14.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanAccent.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                    focusedContainerColor = Color.Black.copy(alpha = 0.2f),
                    unfocusedContainerColor = Color.Black.copy(alpha = 0.2f),
                    cursorColor = CyanAccent
                ),
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                trailingIcon = {
                    val micColor = if (isListening) CyanAccent else TextSecondary
                    IconButton(
                        onClick = {
                            if (isListening) {
                                viewModel.stopListening()
                            } else {
                                if (PermissionUtils.isMicrophoneGranted(context)) {
                                    viewModel.startListening("th-TH") { result ->
                                        if (result.isNotBlank()) {
                                            text = result
                                        }
                                    }
                                } else {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice",
                            tint = micColor,
                            modifier = if (isListening) {
                                Modifier.scale(1.2f)
                            } else {
                                Modifier
                            }
                        )
                    }
                }
            )

            if (isProcessing) {
                IconButton(
                    onClick = {
                        viewModel.stopExecution()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFFEF4444).copy(alpha = 0.2f), CircleShape)
                        .border(1.dp, Color(0xFFEF4444), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSend(text)
                            text = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Transparent)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (text.isNotBlank()) Color.White else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AgentFab(
    isActing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(72.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulsing ring
        val infiniteTransition = rememberInfiniteTransition(label = "fab_pulse")
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
        
        if (isActing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(scale)
                    .background(Color.Red.copy(alpha = 0.1f), CircleShape)
                    .border(1.dp, Color.Red.copy(alpha = 0.2f), CircleShape)
            )
        }

        LargeFloatingActionButton(
            onClick = onClick,
            containerColor = if (isActing) Color(0xFFD32F2F) else SurfaceDark,
            contentColor = if (isActing) Color.White else CyanAccent,
            shape = CircleShape,
            modifier = Modifier
                .size(60.dp)
                .border(1.5.dp, if (isActing) Color.Red else CyanAccent.copy(alpha = 0.6f), CircleShape),
            elevation = FloatingActionButtonDefaults.elevation(4.dp, 6.dp, 4.dp, 4.dp)
        ) {
            AiCoreIcon(
                modifier = Modifier.size(28.dp),
                color = if (isActing) Color.White else CyanAccent,
                animate = isActing
            )
        }
    }
}

@Composable
fun AgentStateOverlay(state: AgentState) {
    if (state == AgentState.IDLE) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            color = SurfaceDark.copy(alpha = 0.9f),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.3f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RipplePulseIndicator(isProcessing = true, color = if (state == AgentState.ACTING) Color.Red else CyanAccent)
                Text(
                    text = when (state) {
                        AgentState.THINKING -> "AI IS REASONING..."
                        AgentState.ACTING -> "AI IS EXECUTING ACTIONS..."
                        else -> "ACTIVE"
                    },
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun TaskExecutionItem(message: ChatMessage.TaskExecution, modifier: Modifier = Modifier) {
    val action = message.action
    val isBash = action.actionType == "BASH" || action.actionType == "SHELL_ERR"

    if (isBash) {
        TerminalBox(
            command = action.target,
            status = message.status,
            output = message.resultSnippet,
            modifier = modifier
        )
    } else {
        ToolExecutionCard(
            actionType = action.actionType,
            target = action.target,
            status = message.status,
            resultSnippet = message.resultSnippet,
            modifier = modifier
        )
    }
}

@Composable
fun TerminalBox(
    command: String,
    status: String,
    output: String?,
    modifier: Modifier = Modifier
) {
    var showExpanded by remember { mutableStateOf(false) }
    var terminalFontSize by remember { mutableStateOf(11.sp) }
    val maxFontSize = 30.sp
    val minFontSize = 6.sp

    if (showExpanded) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showExpanded = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
                    .clip(RoundedCornerShape(16.dp)),
                color = Color(0xFF07070A),
                border = BorderStroke(1.dp, Color(0xFF333340))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Computer,
                                contentDescription = null,
                                tint = CyanAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Terminal Output (Expanded)",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        // Zoom Controls
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = { if (terminalFontSize > minFontSize) terminalFontSize = (terminalFontSize.value - 1).sp },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Zoom Out", tint = TextSecondary, modifier = Modifier.size(20.dp))
                            }
                            Text(
                                text = "${terminalFontSize.value.toInt()}",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                            IconButton(
                                onClick = { if (terminalFontSize < maxFontSize) terminalFontSize = (terminalFontSize.value + 1).sp },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.AddCircleOutline, contentDescription = "Zoom In", tint = TextSecondary, modifier = Modifier.size(20.dp))
                            }
                        }

                        IconButton(onClick = { showExpanded = false }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = Color.White.copy(alpha = 0.1f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = "$ ",
                            color = CyanAccent,
                            fontSize = terminalFontSize,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = command,
                            color = Color.White,
                            fontSize = terminalFontSize,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF030305))
                            .border(1.dp, Color(0xFF1F1F2E), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val changes = event.changes
                                        if (changes.size >= 2) {
                                            val p1 = changes[0]
                                            val p2 = changes[1]
                                            if (p1.pressed && p2.pressed) {
                                                val prevDist = (p1.previousPosition - p2.previousPosition).getDistance()
                                                val currDist = (p1.position - p2.position).getDistance()
                                                if (prevDist > 0f && currDist > 0f) {
                                                    val scale = currDist / prevDist
                                                    if (scale != 1.0f) {
                                                        val newSize = terminalFontSize.value * scale
                                                        if (newSize in minFontSize.value..maxFontSize.value) {
                                                            terminalFontSize = newSize.sp
                                                        }
                                                        changes.forEach { it.consume() }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        val verticalScrollState = rememberScrollState()
                        val horizontalScrollState = rememberScrollState()
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(verticalScrollState)
                                .horizontalScroll(horizontalScrollState)
                        ) {
                            Text(
                                text = if (!output.isNullOrBlank()) parseAnsiToAnnotatedString(output) else AnnotatedString("Executing command or waiting for output..."),
                                color = Color(0xFFD1D5DB),
                                fontSize = terminalFontSize,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = terminalFontSize * 1.5f
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { showExpanded = false },
                        modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent.copy(alpha = 0.2f))
                    ) {
                        Text(text = "Close", color = CyanAccent, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF07070A))
            .border(1.dp, Color(0xFF22222A), RoundedCornerShape(8.dp))
            .clickable { showExpanded = true }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111116))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Computer,
                    contentDescription = null,
                    tint = CyanAccent,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "sh - guest@zhypix",
                    color = TextPrimary.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusColor = when {
                    status.contains("Completed", ignoreCase = true) -> SuccessGreen
                    status.contains("Running", ignoreCase = true) -> CyanAccent
                    status.contains("Failed", ignoreCase = true) -> Color(0xFFEF4444)
                    else -> TextSecondary
                }

                Surface(
                    color = statusColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = status.uppercase(),
                        color = statusColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Icon(
                    imageVector = Icons.Default.Fullscreen,
                    contentDescription = "Expand",
                    tint = TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "$ ",
                    color = CyanAccent,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = command,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                )
            }

            if (!output.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = Color.White.copy(alpha = 0.05f))
                Spacer(modifier = Modifier.height(8.dp))
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = parseAnsiToAnnotatedString(output),
                        color = Color(0xFFD1D5DB),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp
                    )
                }
            } else if (status.contains("Running", ignoreCase = true)) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(500, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "alpha"
                    )
                    Box(
                        modifier = Modifier
                            .width(8.dp)
                            .height(14.dp)
                            .background(Color.White.copy(alpha = alpha))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Executing command...",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontStyle = FontStyle.Italic
                    )
                }
            }
        }
    }
}

@Composable
fun ToolExecutionCard(
    actionType: String,
    target: String,
    status: String,
    resultSnippet: String?,
    modifier: Modifier = Modifier
) {
    val cardIcon = when (actionType) {
        "SEARCH" -> Icons.Default.Search
        "READ_URL" -> Icons.Default.Language
        "MEMORY" -> Icons.Default.Memory
        "CLICK", "TYPE", "SWIPE", "KEY" -> Icons.Default.PlayArrow
        else -> Icons.Default.Build
    }

    val actionName = when (actionType) {
        "SEARCH" -> "Web Search"
        "READ_URL" -> "Read URL"
        "MEMORY" -> "Knowledge Core"
        "CLICK" -> "Simulating Click"
        "TYPE" -> "Simulating Text Input"
        "SWIPE" -> "Simulating Swipe"
        "KEY" -> "Simulating Key Press"
        else -> actionType
    }

    val statusColor = when {
        status.contains("Completed", ignoreCase = true) || status.contains("Success", ignoreCase = true) -> SuccessGreen
        status.contains("Running", ignoreCase = true) || status.contains("Executing", ignoreCase = true) -> CyanAccent
        status.contains("Failed", ignoreCase = true) -> Color(0xFFEF4444)
        else -> TextSecondary
    }

    Surface(
        color = SurfaceDark.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color.White.copy(alpha = 0.03f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = cardIcon,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = actionName,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    color = statusColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.2f))
                ) {
                    Text(
                        text = status.uppercase(),
                        color = statusColor,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = target,
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp,
                modifier = Modifier.padding(start = 32.dp)
            )

            if (!resultSnippet.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = Color.Black.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp)
                ) {
                    Text(
                        text = resultSnippet,
                        color = TextSecondary.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}
