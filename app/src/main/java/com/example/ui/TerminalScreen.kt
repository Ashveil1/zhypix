package com.example.ui

import android.widget.Toast
import android.graphics.BitmapFactory
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.ui.theme.*
import com.example.utils.parseAnsiToAnnotatedString
import com.example.utils.LinuxTerminalSimulator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onClose: () -> Unit) {
    val installedDistros by LinuxTerminalSimulator.installedDistros.collectAsState()
    val activeDistro by LinuxTerminalSimulator.activeDistro.collectAsState()
    val isDesktopScreenActive by LinuxTerminalSimulator.isDesktopScreenActive.collectAsState()
    val activeDesktopAppName by LinuxTerminalSimulator.activeDesktopAppName.collectAsState()
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf("terminal") } // "terminal", "desktop", or "manager"

    var isInstallingDistro by remember { mutableStateOf<String?>(null) }
    var installProgress by remember { mutableFloatStateOf(0f) }
    var installStatus by remember { mutableStateOf("") }

    val context = LocalContext.current
    val localContext = context
    val clipboardManager = LocalClipboardManager.current
    var copiedNotice by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        LinuxTerminalSimulator.initialize(context)
    }
    val terminalLines = LinuxTerminalSimulator.terminalLines
    val lazyListState = rememberLazyListState()

    // Custom animation screens for sl and cmatrix inside terminal
    var cmatrixActive by remember { mutableStateOf(false) }
    var slActive by remember { mutableStateOf(false) }
    var slOffset by remember { mutableFloatStateOf(1.0f) }

    // Terminal Font Size State
    var terminalFontSize by remember { mutableStateOf(11.sp) }
    val maxFontSize = 30.sp
    val minFontSize = 6.sp

    var showStoragePermissionDialog by remember { mutableStateOf(false) }
    var pendingCommandToExecute by remember { mutableStateOf<String?>(null) }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(localContext, "Storage permission granted!", Toast.LENGTH_SHORT).show()
            pendingCommandToExecute?.let { cmd ->
                scope.launch {
                    val result = LinuxTerminalSimulator.executeCommand(cmd)
                    if (result == "TRIGGER_CMATRIX") {
                        cmatrixActive = true
                    } else if (result == "TRIGGER_SL") {
                        slActive = true
                    }
                }
                pendingCommandToExecute = null
            }
        } else {
            Toast.makeText(localContext, "Storage permission is required to access external storage.", Toast.LENGTH_LONG).show()
            pendingCommandToExecute = null
        }
    }

    // Enhanced input state with cursor & selection support
    var commandValue by remember { mutableStateOf(TextFieldValue("")) }
    var isCtrlActive by remember { mutableStateOf(false) }
    var isAltActive by remember { mutableStateOf(false) }

    // Bash History
    val commandHistory = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(-1) }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(activeTab) {
        if (activeTab == "terminal") {
            delay(150L)
            try {
                focusRequester.requestFocus()
                keyboardController?.show()
            } catch (e: Exception) {}
        }
    }

    val activeSessionId by LinuxTerminalSimulator.activeSessionId.collectAsState()

    LaunchedEffect(activeSessionId) {
        val currentSession = LinuxTerminalSimulator.sessions.find { it.id == activeSessionId }
        if (currentSession != null) {
            commandValue = TextFieldValue(currentSession.currentInputText, TextRange(currentSession.currentInputText.length))
        }
    }

    // Auto-scroll terminal
    LaunchedEffect(terminalLines.size, cmatrixActive, slActive) {
        if (terminalLines.isNotEmpty()) {
            lazyListState.scrollToItem(terminalLines.size)
        }
    }

    LaunchedEffect(copiedNotice) {
        if (copiedNotice) {
            delay(2000L)
            copiedNotice = false
        }
    }

    fun insertTextAtCursor(str: String) {
        val text = commandValue.text
        val start = commandValue.selection.start
        val end = commandValue.selection.end
        val newText = text.substring(0, start) + str + text.substring(end)
        val newCursor = start + str.length
        commandValue = TextFieldValue(newText, selection = TextRange(newCursor))
    }

    fun handleCtrlShortcut(keyChar: Char) {
        val lowerKey = keyChar.lowercaseChar()
        isCtrlActive = false
        val currentText = commandValue.text
        val prompt = LinuxTerminalSimulator.getPrompt()

        when (lowerKey) {
            'c' -> {
                // SIGINT / Cancel current command
                LinuxTerminalSimulator.terminalLines.add("$prompt$currentText^C")
                commandValue = TextFieldValue("")
                historyIndex = -1
            }
            'l' -> {
                // Clear terminal screen
                LinuxTerminalSimulator.terminalLines.clear()
                cmatrixActive = false
                slActive = false
            }
            'u' -> {
                // Clear input line
                commandValue = TextFieldValue("")
            }
            'd' -> {
                // EOF / Logout
                if (currentText.isEmpty()) {
                    scope.launch {
                        LinuxTerminalSimulator.executeCommand("exit")
                    }
                } else {
                    commandValue = TextFieldValue("")
                }
            }
            'a' -> {
                // Move cursor to start
                commandValue = commandValue.copy(selection = TextRange(0))
            }
            'e' -> {
                // Move cursor to end
                commandValue = commandValue.copy(selection = TextRange(currentText.length))
            }
            'w' -> {
                // Delete previous word
                val words = currentText.trimEnd().split("\\s+".toRegex())
                if (words.size > 1) {
                    val newText = words.dropLast(1).joinToString(" ") + " "
                    commandValue = TextFieldValue(newText, selection = TextRange(newText.length))
                } else {
                    commandValue = TextFieldValue("")
                }
            }
            'z' -> {
                // SIGTSTP / Suspend
                LinuxTerminalSimulator.terminalLines.add("$prompt$currentText^Z")
                commandValue = TextFieldValue("")
            }
            else -> {
                insertTextAtCursor(keyChar.toString())
            }
        }
    }

    fun performTabCompletion() {
        val text = commandValue.text
        val cursor = commandValue.selection.start
        if (text.isBlank()) {
            commandValue = TextFieldValue("ls ", selection = TextRange(3))
            return
        }
        val prefixToCursor = text.substring(0, cursor)
        val parts = prefixToCursor.split("\\s+".toRegex())

        if (parts.size == 1) {
            val arg = parts[0]
            val candidates = listOf(
                "proot-distro", "apt", "apt-get", "pkg", "ls", "cd", "cat", "echo", "pwd",
                "mkdir", "rm", "cp", "mv", "touch", "chmod", "clear", "python", "git",
                "uname", "whoami", "ps", "pkill", "top", "cmatrix", "sl", "help", "history",
                "exit", "curl", "wget", "tar", "gzip", "grep", "find", "nano", "vim", "df", "free"
            )
            val matches = candidates.filter { it.startsWith(arg, ignoreCase = true) }
            if (matches.size == 1) {
                val completed = matches[0] + " "
                val remaining = text.substring(cursor)
                commandValue = TextFieldValue(
                    text = completed + remaining,
                    selection = TextRange(completed.length)
                )
            } else if (matches.size > 1) {
                val prompt = LinuxTerminalSimulator.getPrompt()
                LinuxTerminalSimulator.terminalLines.add("$prompt$text")
                LinuxTerminalSimulator.terminalLines.add(matches.joinToString("  "))
            }
        } else {
            val lastArg = parts.last()
            val currentDir = File(LinuxTerminalSimulator.currentDirectory.value)
            val files = currentDir.listFiles() ?: emptyArray()
            val matches = files.filter { it.name.startsWith(lastArg, ignoreCase = true) }

            if (matches.size == 1) {
                val match = matches[0]
                val appendName = match.name + if (match.isDirectory) "/" else " "
                val baseText = prefixToCursor.substring(0, prefixToCursor.length - lastArg.length)
                val completed = baseText + appendName
                val remaining = text.substring(cursor)
                commandValue = TextFieldValue(
                    text = completed + remaining,
                    selection = TextRange(completed.length)
                )
            } else if (matches.size > 1) {
                val prompt = LinuxTerminalSimulator.getPrompt()
                LinuxTerminalSimulator.terminalLines.add("$prompt$text")
                LinuxTerminalSimulator.terminalLines.add(matches.joinToString("  ") { if (it.isDirectory) "${it.name}/" else it.name })
            }
        }
    }

    fun submitCommand() {
        val rawCmd = commandValue.text
        commandValue = TextFieldValue("")
        historyIndex = -1
        isCtrlActive = false
        isAltActive = false
        if (rawCmd.isNotBlank()) {
            if (commandHistory.isEmpty() || commandHistory.last() != rawCmd) {
                commandHistory.add(rawCmd)
            }
            
            val isAccessingStorage = rawCmd.contains("sdcard", ignoreCase = true) || rawCmd.contains("storage", ignoreCase = true)
            if (isAccessingStorage && !com.example.utils.PermissionUtils.isStorageGranted(context)) {
                pendingCommandToExecute = rawCmd
                showStoragePermissionDialog = true
                return
            }

            scope.launch {
                val result = LinuxTerminalSimulator.executeCommand(rawCmd)
                if (result == "TRIGGER_CMATRIX") {
                    cmatrixActive = true
                } else if (result == "TRIGGER_SL") {
                    slActive = true
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column(modifier = Modifier.background(SurfaceDark)) {
                // Terminal Header Bar (Termux Full Black CLI style)
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = SuccessGreen,
                                modifier = Modifier.size(18.dp)
                            )

                            Spacer(modifier = Modifier.width(10.dp))

                            Column {
                                Text(
                                    text = "Termux",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = if (activeDistro != null) "proot:$activeDistro (root)" else "termux@localhost (~)",
                                    fontSize = 10.sp,
                                    color = if (activeDistro != null) SuccessGreen else TextSecondary,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    },
                    actions = {
                        // Quick action: Copy ALL output
                        IconButton(onClick = {
                            val fullText = LinuxTerminalSimulator.terminalLines.joinToString("\n")
                            clipboardManager.setText(AnnotatedString(fullText))
                            copiedNotice = true
                            Toast.makeText(context, "Full terminal logs copied (${LinuxTerminalSimulator.terminalLines.size} lines)", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy output",
                                tint = if (copiedNotice) SuccessGreen else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Quick action: Copy ERROR logs only
                        IconButton(onClick = {
                            val errorLines = LinuxTerminalSimulator.terminalLines.filter { line ->
                                line.contains("error", ignoreCase = true) || 
                                line.contains("failed", ignoreCase = true) || 
                                line.contains("exception", ignoreCase = true) ||
                                line.contains("fatal", ignoreCase = true) ||
                                line.contains("denied", ignoreCase = true) ||
                                line.contains("permission", ignoreCase = true)
                            }
                            val textToCopy = if (errorLines.isNotEmpty()) {
                                errorLines.joinToString("\n")
                            } else {
                                LinuxTerminalSimulator.terminalLines.takeLast(40).joinToString("\n")
                            }
                            clipboardManager.setText(AnnotatedString(textToCopy))
                            copiedNotice = true
                            Toast.makeText(context, if (errorLines.isNotEmpty()) "Copied ${errorLines.size} error lines!" else "Copied last 40 lines!", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = "Copy Error Logs",
                                tint = CyanAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(onClick = {
                            LinuxTerminalSimulator.terminalLines.clear()
                            cmatrixActive = false
                            slActive = false
                        }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear terminal",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = SurfaceDark,
                        titleContentColor = Color.White
                    )
                )

                // Navigation Tabs & Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(SurfaceDark)
                        .border(1.dp, Color.White.copy(alpha = 0.05f)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { activeTab = "terminal" }
                            .background(if (activeTab == "terminal") Color.White.copy(alpha = 0.06f) else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terminal, contentDescription = null, tint = if (activeTab == "terminal") SuccessGreen else TextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "CLI",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeTab == "terminal") SuccessGreen else TextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1.3f)
                            .fillMaxHeight()
                            .clickable { activeTab = "desktop" }
                            .background(if (activeTab == "desktop") Color.White.copy(alpha = 0.06f) else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Monitor, contentDescription = null, tint = if (activeTab == "desktop") SuccessGreen else if (isDesktopScreenActive) Color(0xFF60A5FA) else TextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "AI Screen",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeTab == "desktop") SuccessGreen else TextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                            if (isDesktopScreenActive) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(SuccessGreen)
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight()
                            .clickable { activeTab = "manager" }
                            .background(if (activeTab == "manager") Color.White.copy(alpha = 0.06f) else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Dns, contentDescription = null, tint = if (activeTab == "manager") SuccessGreen else TextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "PRoot Distros",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeTab == "manager") SuccessGreen else TextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Zoom Controls
                    if (activeTab == "terminal") {
                        Row(
                            modifier = Modifier.padding(end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            IconButton(
                                onClick = { if (terminalFontSize > minFontSize) terminalFontSize = (terminalFontSize.value - 1).sp },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Zoom Out", tint = TextSecondary, modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = { if (terminalFontSize < maxFontSize) terminalFontSize = (terminalFontSize.value + 1).sp },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.AddCircleOutline, contentDescription = "Zoom In", tint = TextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
        ) {
            if (activeTab == "terminal") {
                // Live Interactive Terminal View
                Column(modifier = Modifier.fillMaxSize()) {
                    val sessions = LinuxTerminalSimulator.sessions
                    val activeSessionId by LinuxTerminalSimulator.activeSessionId.collectAsState()
                    var showRenameDialogId by remember { mutableStateOf<String?>(null) }
                    var renameInputText by remember { mutableStateOf("") }

                    if (showRenameDialogId != null) {
                        AlertDialog(
                            onDismissRequest = { showRenameDialogId = null },
                            title = { Text("Rename Terminal Session", color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace) },
                            text = {
                                OutlinedTextField(
                                    value = renameInputText,
                                    onValueChange = { renameInputText = it },
                                    label = { Text("Session Name", color = TextSecondary) },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = SuccessGreen,
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                        focusedLabelColor = SuccessGreen,
                                        cursorColor = SuccessGreen,
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    ),
                                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showRenameDialogId?.let { id ->
                                            if (renameInputText.isNotBlank()) {
                                                LinuxTerminalSimulator.renameSession(id, renameInputText.trim())
                                            }
                                        }
                                        showRenameDialogId = null
                                    }
                                ) {
                                    Text("SAVE", color = SuccessGreen, fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRenameDialogId = null }) {
                                    Text("CANCEL", color = TextSecondary)
                                }
                            },
                            containerColor = SurfaceDark,
                            tonalElevation = 6.dp
                        )
                    }

                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF121214))
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(sessions) { session ->
                            val isActive = session.id == activeSessionId
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isActive) Color(0xFF1E1E24) else Color.Transparent)
                                    .border(1.dp, if (isActive) SuccessGreen.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
                                    .clickable {
                                        // Save current input value
                                        val oldSession = sessions.find { it.id == activeSessionId }
                                        if (oldSession != null) {
                                            oldSession.currentInputText = commandValue.text
                                        }
                                        LinuxTerminalSimulator.switchSession(session.id)
                                        // Restore selected session's input text
                                        commandValue = TextFieldValue(session.currentInputText, TextRange(session.currentInputText.length))
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = if (isActive) SuccessGreen else TextSecondary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = session.name,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isActive) Color.White else TextSecondary
                                )
                                
                                // Pencil/Edit Icon
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Rename",
                                    tint = if (isActive) Color.White.copy(alpha = 0.6f) else TextSecondary.copy(alpha = 0.4f),
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clickable {
                                            renameInputText = session.name
                                            showRenameDialogId = session.id
                                        }
                                )
                                
                                if (sessions.size > 1) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Terminal",
                                        tint = if (isActive) Color.White.copy(alpha = 0.6f) else TextSecondary.copy(alpha = 0.4f),
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                LinuxTerminalSimulator.removeSession(session.id)
                                                val activeNow = LinuxTerminalSimulator.sessions.find { it.id == LinuxTerminalSimulator.activeSessionId.value }
                                                if (activeNow != null) {
                                                    commandValue = TextFieldValue(activeNow.currentInputText, TextRange(activeNow.currentInputText.length))
                                                } else {
                                                    commandValue = TextFieldValue("")
                                                }
                                            }
                                    )
                                }
                            }
                        }
                        
                        item {
                            IconButton(
                                onClick = {
                                    val count = sessions.size + 1
                                    LinuxTerminalSimulator.createSession("Terminal $count")
                                    commandValue = TextFieldValue("")
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "New Terminal",
                                    tint = SuccessGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color.Black)
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
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            }
                    ) {
                        if (cmatrixActive) {
                            CMatrixRain {
                                cmatrixActive = false
                            }
                        } else if (slActive) {
                            SteamLocomotiveScreen(
                                offset = slOffset,
                                onFinished = {
                                    slActive = false
                                }
                            )
                            LaunchedEffect(slActive) {
                                slOffset = 1.0f
                                while (slOffset > -1.0f) {
                                    delay(40L)
                                    slOffset -= 0.02f
                                }
                                slActive = false
                            }
                        } else {
                            SelectionContainer {
                                LazyColumn(
                                    state = lazyListState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    itemsIndexed(
                                        items = terminalLines,
                                        key = { index, _ -> index }
                                    ) { _, line ->
                                        val lineScrollState = rememberScrollState()
                                        val annotatedLine = remember(line) { parseAnsiToAnnotatedString(line) }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(lineScrollState)
                                        ) {
                                            Text(
                                                text = annotatedLine,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = terminalFontSize,
                                                lineHeight = terminalFontSize * 1.3f,
                                                softWrap = false
                                            )
                                        }
                                    }

                                    // Interactive input line
                                    item {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = LinuxTerminalSimulator.getPrompt(),
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = terminalFontSize,
                                                color = if (activeDistro == null) Color(0xFFFAFAFA) else Color(0xFFE4E4E7)
                                            )

                                            BasicTextField(
                                                value = commandValue,
                                                onValueChange = { newValue ->
                                                    if (isCtrlActive) {
                                                        val oldText = commandValue.text
                                                        val newText = newValue.text
                                                        if (newText.length > oldText.length) {
                                                            val addedChar = newText.last()
                                                            handleCtrlShortcut(addedChar)
                                                        } else {
                                                            isCtrlActive = false
                                                            commandValue = newValue
                                                        }
                                                    } else {
                                                        commandValue = newValue
                                                    }
                                                },
                                                textStyle = TextStyle(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = terminalFontSize,
                                                    color = Color.White
                                                ),
                                                cursorBrush = SolidColor(SuccessGreen),
                                                keyboardOptions = KeyboardOptions(
                                                    imeAction = ImeAction.Go,
                                                    autoCorrectEnabled = false
                                                ),
                                                keyboardActions = KeyboardActions(
                                                    onGo = { submitCommand() },
                                                    onSend = { submitCommand() },
                                                    onDone = { submitCommand() }
                                                ),
                                                modifier = Modifier
                                                    .focusRequester(focusRequester)
                                                    .weight(1f)
                                                    .padding(start = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Copy notification bar
                    AnimatedVisibility(visible = copiedNotice) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SuccessGreen)
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✓ Terminal output copied to clipboard",
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Active Ctrl Shortcut Pills (When Ctrl Modifier is Active)
                    AnimatedVisibility(visible = isCtrlActive) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF27272A))
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "[CTRL ACTIVE]",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 4.dp)
                            )

                            listOf(
                                "C" to 'c', "L" to 'l', "U" to 'u', "D" to 'd',
                                "A" to 'a', "E" to 'e', "W" to 'w', "Z" to 'z'
                            ).forEach { (label, char) ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.White)
                                        .clickable { handleCtrlShortcut(char) }
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "^$label",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                    }

                    // Stable CLI Keyboard Accessory Bar (Termux Exact Two-Row Layout)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E1E24))
                            .border(1.dp, Color.White.copy(alpha = 0.08f))
                            .padding(vertical = 3.dp, horizontal = 4.dp)
                    ) {
                        // Row 1: ESC | TAB | CTRL | ALT | - | _ | UP | DOWN
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // ESC
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF2C2C34))
                                    .clickable {
                                        isCtrlActive = false
                                        isAltActive = false
                                        if (commandValue.text.isNotEmpty()) {
                                            commandValue = TextFieldValue("")
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("ESC", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White, fontWeight = FontWeight.Bold)
                            }

                            // TAB
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF2C2C34))
                                    .clickable { performTabCompletion() }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("TAB", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White, fontWeight = FontWeight.Bold)
                            }

                            // CTRL
                            Box(
                                modifier = Modifier
                                    .weight(1.1f)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (isCtrlActive) Color.White else Color(0xFF2C2C34))
                                    .clickable { isCtrlActive = !isCtrlActive }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("CTRL", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (isCtrlActive) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                            }

                            // ALT
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (isAltActive) Color.White else Color(0xFF2C2C34))
                                    .clickable { isAltActive = !isAltActive }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("ALT", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (isAltActive) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                            }

                            // Hyphen -
                            Box(
                                modifier = Modifier
                                    .weight(0.8f)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF2C2C34))
                                    .clickable { insertTextAtCursor("-") }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("-", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.White, fontWeight = FontWeight.Bold)
                            }

                            // Underscore _
                            Box(
                                modifier = Modifier
                                    .weight(0.8f)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF2C2C34))
                                    .clickable { insertTextAtCursor("_") }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("_", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.White, fontWeight = FontWeight.Bold)
                            }

                            // Up Arrow ▲
                            Box(
                                modifier = Modifier
                                    .weight(0.9f)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF2C2C34))
                                    .clickable {
                                        if (commandHistory.isNotEmpty()) {
                                            if (historyIndex == -1) {
                                                historyIndex = commandHistory.size - 1
                                            } else if (historyIndex > 0) {
                                                historyIndex--
                                            }
                                            val cmd = commandHistory[historyIndex]
                                            commandValue = TextFieldValue(cmd, selection = TextRange(cmd.length))
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("▲", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White, fontWeight = FontWeight.Bold)
                            }

                            // Down Arrow ▼
                            Box(
                                modifier = Modifier
                                    .weight(0.9f)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF2C2C34))
                                    .clickable {
                                        if (historyIndex >= 0) {
                                            if (historyIndex < commandHistory.size - 1) {
                                                historyIndex++
                                                val cmd = commandHistory[historyIndex]
                                                commandValue = TextFieldValue(cmd, selection = TextRange(cmd.length))
                                            } else {
                                                historyIndex = -1
                                                commandValue = TextFieldValue("")
                                            }
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("▼", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        // Row 2: PASTE | ◄ | ► | / | | | ~ | $ | KEYBOARD
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // PASTE BUTTON
                            Box(
                                modifier = Modifier
                                    .weight(1.3f)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(SuccessGreen.copy(alpha = 0.2f))
                                    .border(1.dp, SuccessGreen.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
                                    .clickable {
                                        val clipText = clipboardManager.getText()?.text ?: ""
                                        if (clipText.isNotEmpty()) {
                                            insertTextAtCursor(clipText)
                                            Toast.makeText(context, "Pasted", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("PASTE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = SuccessGreen, fontWeight = FontWeight.Bold)
                            }

                            // Left Arrow ◄
                            Box(
                                modifier = Modifier
                                    .weight(0.9f)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF2C2C34))
                                    .clickable {
                                        val newPos = (commandValue.selection.start - 1).coerceAtLeast(0)
                                        commandValue = commandValue.copy(selection = TextRange(newPos))
                                    }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("◄", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White, fontWeight = FontWeight.Bold)
                            }

                            // Right Arrow ►
                            Box(
                                modifier = Modifier
                                    .weight(0.9f)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF2C2C34))
                                    .clickable {
                                        val newPos = (commandValue.selection.start + 1).coerceAtMost(commandValue.text.length)
                                        commandValue = commandValue.copy(selection = TextRange(newPos))
                                    }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("►", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White, fontWeight = FontWeight.Bold)
                            }

                            listOf("/", "|", "~", "$", ">").forEach { symbol ->
                                Box(
                                    modifier = Modifier
                                        .weight(0.8f)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color(0xFF2C2C34))
                                        .clickable { insertTextAtCursor(symbol) }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(symbol, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.White, fontWeight = FontWeight.Medium)
                                }
                            }

                            // Keyboard Toggle Button
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF2C2C34))
                                    .clickable {
                                        focusRequester.requestFocus()
                                        keyboardController?.show()
                                    }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Keyboard, contentDescription = "Keyboard", tint = Color.White, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }
            } else if (activeTab == "desktop") {
                // Linux Desktop Screen / AI Screen View
                LinuxDesktopScreenView(
                    isDesktopActive = isDesktopScreenActive,
                    activeAppName = activeDesktopAppName ?: "Linux Virtual Display (:99)",
                    onLaunchDemo = {
                        LinuxTerminalSimulator.setDesktopScreenActive(true, "Linux Virtual Display (:99)")
                    },
                    onCloseDesktop = {
                        LinuxTerminalSimulator.setDesktopScreenActive(false)
                    }
                )
            } else {
                // Graphical PRoot Distribution Manager Dashboard
                Box(modifier = Modifier.fillMaxSize()) {
                    if (isInstallingDistro != null) {
                        // Installation progress overlay
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { installProgress },
                                color = SuccessGreen,
                                strokeWidth = 4.dp,
                                modifier = Modifier.size(64.dp)
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            Text(
                                text = "Extracting & Provisioning rootfs.tar.xz...",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = installStatus,
                                fontSize = 12.sp,
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            LinearProgressIndicator(
                                progress = { installProgress },
                                color = SuccessGreen,
                                trackColor = SurfaceDark,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "${(installProgress * 100).toInt()}% Completed",
                                fontSize = 11.sp,
                                color = SuccessGreen,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(SurfaceDark)
                                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                                        .padding(16.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "Termux Chroot PRoot Manager",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = Color.White
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Manage root filesystem environments without root privileges. Download officially compiled architectures and login inside guest environments seamlessly.",
                                            fontSize = 12.sp,
                                            color = TextSecondary,
                                            lineHeight = 17.sp
                                        )
                                    }
                                }
                            }

                            items(LinuxTerminalSimulator.availableDistros) { distro ->
                                val isInstalled = installedDistros.contains(distro.alias)
                                val isActive = activeDistro == distro.alias

                                val badgeColor = getDistroBadgeColor(distro.name)

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(SurfaceDark)
                                        .border(
                                            1.dp,
                                            if (isActive) SuccessGreen.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.05f),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(14.dp)
                                ) {
                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            // Distro initial letter circle
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .background(badgeColor.copy(alpha = 0.15f), CircleShape)
                                                    .border(1.dp, badgeColor.copy(alpha = 0.4f), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = distro.name.take(1),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                    color = badgeColor
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(12.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = distro.name,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        fontSize = 14.sp
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .background(
                                                                Color.White.copy(alpha = 0.08f),
                                                                RoundedCornerShape(4.dp)
                                                            )
                                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = distro.version,
                                                            fontSize = 9.sp,
                                                            color = TextSecondary,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = distro.description,
                                                    color = TextSecondary,
                                                    fontSize = 11.sp,
                                                    modifier = Modifier.padding(top = 2.dp)
                                                )
                                            }

                                            // Status Badge
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        if (isActive) SuccessGreen.copy(alpha = 0.12f)
                                                        else if (isInstalled) Color.White.copy(alpha = 0.05f)
                                                        else Color.Transparent,
                                                        RoundedCornerShape(6.dp)
                                                    )
                                                    .border(
                                                        1.dp,
                                                        if (isActive) SuccessGreen.copy(alpha = 0.3f)
                                                        else if (isInstalled) Color.White.copy(alpha = 0.1f)
                                                        else Color.White.copy(alpha = 0.05f),
                                                        RoundedCornerShape(6.dp)
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = if (isActive) "LOGGED IN" else if (isInstalled) "INSTALLED" else "AVAILABLE",
                                                    color = if (isActive) SuccessGreen else if (isInstalled) Color.White else TextSecondary.copy(alpha = 0.5f),
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(14.dp))

                                        // Action buttons
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            if (!isInstalled) {
                                                Button(
                                                    onClick = {
                                                        isInstallingDistro = distro.alias
                                                        installProgress = 0f
                                                        scope.launch {
                                                            LinuxTerminalSimulator.installDistroDirect(
                                                                distroAlias = distro.alias,
                                                                onProgress = { progress, status ->
                                                                    installProgress = progress
                                                                    installStatus = status
                                                                },
                                                                onComplete = { success ->
                                                                    isInstallingDistro = null
                                                                    activeTab = "terminal"
                                                                }
                                                            )
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = SuccessGreen,
                                                        contentColor = Color.Black
                                                    ),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.weight(1f).height(38.dp)
                                                ) {
                                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Install distro", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            } else {
                                                Button(
                                                    onClick = {
                                                        if (isActive) {
                                                            // Log out
                                                            scope.launch {
                                                                LinuxTerminalSimulator.executeCommand("exit")
                                                            }
                                                        } else {
                                                            // Log in
                                                            LinuxTerminalSimulator.loginDistroDirect(distro.alias)
                                                            activeTab = "terminal"
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = if (isActive) Color.White.copy(alpha = 0.08f) else Color.White,
                                                        contentColor = if (isActive) Color.White else Color.Black
                                                    ),
                                                    border = if (isActive) BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)) else null,
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.weight(1.3f).height(38.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = if (isActive) Icons.Default.Logout else Icons.Default.Login,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = if (isActive) "Disconnect" else "Boot/Console",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }

                                                IconButton(
                                                    onClick = {
                                                        LinuxTerminalSimulator.removeDistroDirect(distro.alias)
                                                    },
                                                    modifier = Modifier
                                                        .size(38.dp)
                                                        .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                                                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "Uninstall distro",
                                                        tint = Color.Red.copy(alpha = 0.7f),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showStoragePermissionDialog) {
        AlertDialog(
            onDismissRequest = { 
                showStoragePermissionDialog = false 
                pendingCommandToExecute = null
            },
            title = { Text("ต้องการสิทธิ์เข้าถึงพื้นที่เก็บข้อมูล", fontWeight = FontWeight.Bold, color = Color.White) },
            text = { Text("คำสั่งนี้พยายามเข้าถึง /sdcard หรือหน่วยความจำของเครื่อง จำเป็นต้องได้รับสิทธิ์เพื่อทำงานในตู้จำลอง PRoot", color = Color.White.copy(alpha = 0.8f)) },
            containerColor = Color(0xFF1E1E2E),
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Color.Black),
                    onClick = {
                        showStoragePermissionDialog = false
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            com.example.utils.PermissionUtils.requestStoragePermission(localContext)
                            Toast.makeText(localContext, "กรุณาเปิดสิทธิ์ 'เข้าถึงไฟล์ทั้งหมด' แล้วลองรันคำสั่งอีกครั้ง", Toast.LENGTH_LONG).show()
                            pendingCommandToExecute = null
                        } else {
                            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    }
                ) {
                    Text("ให้สิทธิ์", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showStoragePermissionDialog = false 
                        pendingCommandToExecute = null
                    }
                ) {
                    Text("ยกเลิก", color = Color.White.copy(alpha = 0.6f))
                }
            }
        )
    }
}

// Custom parser to color code different Terminal outputs beautifully
private fun getTerminalLineColor(line: String): Color {
    return when {
        line.startsWith("root@omni") -> Color(0xFFFFFFFF) // High-contrast crisp white prompt
        line.startsWith("~") && line.contains("$") -> Color(0xFFE4E4E7) // Silvery zinc sub-prompt
        line.startsWith("bash:") || line.startsWith("rm:") || line.lowercase(Locale.ROOT).contains("error") -> Color(0xFFE4E4E7) // Silvery zinc for errors
        line.startsWith("Successfully") || line.contains("boot completed") || line.startsWith("[+]") -> Color(0xFFFFFFFF) // Crisp titanium white for successes
        line.startsWith("[*]") -> Color(0xFFA1A1AA) // Muted steel zinc for headers
        line.startsWith("logout") -> Color(0xFFD1D1D6) // Platinum silver for logout state
        else -> Color.White
    }
}

private fun getDistroBadgeColor(name: String): Color {
    return when (name) {
        "Debian" -> Color(0xFFFFFFFF)       // Pure White
        "Ubuntu" -> Color(0xFFE5E5EA)       // Premium Silver
        "Alpine" -> Color(0xFFD1D1D6)       // Soft Platinum
        "Arch Linux" -> Color(0xFFC7C7CC)   // Steel Metal
        "Fedora" -> Color(0xFFEAEAEA)       // Mercury Silver
        "Kali Linux" -> Color(0xFF8E8E93)   // Tech Titanium
        "Void Linux" -> Color(0xFF71717A)   // Dark Charcoal
        else -> Color.White
    }
}

@Composable
fun CMatrixRain(onExit: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(100L)
            tick++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onExit() }
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MATRIX DIGITAL STREAM",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = SuccessGreen,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tap anywhere to exit",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                items(20) { rowIndex ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        repeat(12) { colIndex ->
                            val active = (rowIndex + colIndex + tick) % 6 == 0
                            val chars = "0123456789"
                            val charStr = if (active) {
                                val charIdx = (rowIndex * 7 + colIndex * 13 + tick) % 10
                                chars[charIdx].toString()
                            } else {
                                " "
                            }
                            Text(
                                text = charStr,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                color = if (rowIndex % 2 == 0) SuccessGreen else SuccessGreen.copy(alpha = 0.4f),
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SteamLocomotiveScreen(offset: Float, onFinished: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "STEAM LOCOMOTIVE (sl)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color.Yellow,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Choo Choo!",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .offset(x = (offset * 300).dp)
                ) {
                    Text(
                        text = """
                                     (@@) (  ) (@)
                                 ( @  @ )
                               (@@@@)
                            ____||____     ____
                           //________\\   |  _ |
                          (____________)  |____|
                           O-O      O-O   O    O
                        """.trimIndent(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color.White,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun LinuxDesktopScreenView(
    isDesktopActive: Boolean,
    activeAppName: String,
    onLaunchDemo: () -> Unit,
    onCloseDesktop: () -> Unit
) {
    val context = LocalContext.current
    var refreshCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(isDesktopActive, refreshCount) {
        if (isDesktopActive) {
            while (true) {
                val base64 = com.example.utils.LinuxTerminalSimulator.captureX11Screenshot()
                if (base64 != null) {
                    com.example.viewmodel.AgentViewModel.instance?.updateScreenshot(base64)
                }
                kotlinx.coroutines.delay(2000L)
            }
        }
    }

    val screenshotBase64 by (com.example.viewmodel.AgentViewModel.instance?.lastScreenshot?.collectAsState() ?: remember { mutableStateOf(null) })
    val imageBitmap = remember(screenshotBase64) {
        screenshotBase64?.let { b64 ->
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                bmp?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "livePulse")
    val alphaPulse by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alphaPulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090A0E))
    ) {
        if (!isDesktopActive) {
            // Idle State View (English UI)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Monitor,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "AI SCREEN IDLE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "AI Display Server Inactive",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "When the AI Agent executes GUI applications, browser automation, or Computer Use tasks in Ubuntu PRoot, the virtual display stream will broadcast here automatically.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onLaunchDemo,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SuccessGreen,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Virtual Display Server (X11)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        } else {
            // Clean Full-Screen Active Desktop Stream (No text blocking, clean edge-to-edge layout)
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Minimal Top Control Bar
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF121318),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(SuccessGreen.copy(alpha = alphaPulse))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "LIVE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = activeAppName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    refreshCount++
                                    Toast.makeText(context, "Refreshed display stream", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White, modifier = Modifier.size(16.dp))
                            }

                            IconButton(
                                onClick = onCloseDesktop,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close Display", tint = Color(0xFFFF5F56), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // Pure Full-Screen Display Viewport
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (imageBitmap != null) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = "AI Screen View",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        // Clean Canvas representation when screen image is initializing
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF0F111A)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.Monitor,
                                    contentDescription = null,
                                    tint = SuccessGreen.copy(alpha = 0.8f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "DISPLAY STREAM ACTIVE",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


