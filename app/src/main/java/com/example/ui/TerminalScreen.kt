package com.example.ui

import android.graphics.BitmapFactory
import android.util.Log
import android.view.MotionEvent
import android.view.KeyEvent
import android.graphics.Typeface
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceLightBorder
import com.example.utils.LinuxTerminalSimulator
import com.example.utils.ZhypixTerminalManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch

private val TerminalBg = Color(0xFF070709)
private val TopBarDark = Color(0xFF0F0F13)
private val CardBgDark = Color(0xFF14141A)

@Composable
fun TerminalScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val installedDistros by LinuxTerminalSimulator.installedDistros.collectAsState()
    val terminals by ZhypixTerminalManager.terminals.collectAsState()
    val activeId by ZhypixTerminalManager.activeTerminalId.collectAsState()
    val hostCwd by ZhypixTerminalManager.activeHostCwd.collectAsState()
    val isWakeLockHeld by LinuxTerminalSimulator.isWakeLockHeld.collectAsState()

    var panel by remember { mutableStateOf("terminal") } // terminal | distros | desktop
    var showMenu by remember { mutableStateOf(false) }
    var showNewTabDialog by remember { mutableStateOf(false) }
    var textSize by remember { mutableIntStateOf(28) }
    var ctrlSticky by remember { mutableStateOf(false) }

    val terminalViewRef = remember { mutableStateOf<TerminalView?>(null) }
    val ctrlState = remember { mutableStateOf(false) }
    val lastAppliedTextSize = remember { mutableIntStateOf(28) }

    DisposableEffect(Unit) {
        ZhypixTerminalManager.listener = object : ZhypixTerminalManager.Listener {
            override fun onOutputChanged(sessionId: String) {
                terminalViewRef.value?.post { terminalViewRef.value?.onScreenUpdated() }
            }
            override fun onTitleOrCwdChanged(sessionId: String) {}
            override fun onSessionFinished(sessionId: String) {}
        }
        ZhypixTerminalManager.startCwdPolling()
        onDispose { ZhypixTerminalManager.listener = null }
    }

    LaunchedEffect(Unit) {
        if (terminals.isEmpty()) {
            val defaultDistro = LinuxTerminalSimulator.activeDistro.value ?: installedDistros.firstOrNull()
            ZhypixTerminalManager.createSession(context, "Terminal", defaultDistro)
        }
    }

    val activeTerminal = terminals.find { it.id == activeId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBg)
            .windowInsetsPadding(
                WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)
            )
    ) {
        // ── Top Bar with Obsidian Styling & Tab Pills ─────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = TopBarDark,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                // Primary Action & Session Tabs Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .height(44.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClose()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Return to chat",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    // Scrollable Session Tabs
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        terminals.forEach { t ->
                            val isActive = t.id == activeId
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isActive) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.04f))
                                    .border(
                                        1.dp,
                                        if (isActive) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.07f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        ZhypixTerminalManager.switchTo(t.id)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                // Live status indicator dot
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (t.isRunning) SuccessGreen else Color(0xFFEF4444))
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = t.name,
                                    color = if (isActive) Color.White else TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close session",
                                    tint = if (isActive) Color.White.copy(alpha = 0.7f) else TextSecondary.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .size(13.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            ZhypixTerminalManager.removeSession(context, t.id)
                                        }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    // New Tab Button
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showNewTabDialog = true
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color.White.copy(alpha = 0.06f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "New session",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    // Menu Options
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Menu",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier
                                .background(CardBgDark)
                                .border(1.dp, SurfaceLightBorder, RoundedCornerShape(12.dp))
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.DeveloperBoard, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("PRoot Distros", color = Color.White, fontSize = 13.sp)
                                    }
                                },
                                onClick = { panel = "distros"; showMenu = false }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Monitor, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("X11 Virtual Display", color = Color.White, fontSize = 13.sp)
                                    }
                                },
                                onClick = { panel = "desktop"; showMenu = false }
                            )
                            Divider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 4.dp))
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Keyboard, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("Toggle Keyboard", color = Color.White, fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    terminalViewRef.value?.let { tv ->
                                        tv.requestFocus()
                                        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                                        imm?.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ContentPaste, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("Paste Clipboard", color = Color.White, fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                    val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                                    if (!text.isNullOrEmpty()) activeTerminal?.write(text)
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ZoomIn, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("Font Larger (+)", color = Color.White, fontSize = 13.sp)
                                    }
                                },
                                onClick = { textSize++; showMenu = false }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ZoomOut, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("Font Smaller (-)", color = Color.White, fontSize = 13.sp)
                                    }
                                },
                                onClick = { if (textSize > 8) textSize--; showMenu = false }
                            )
                            Divider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 4.dp))
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.PowerSettingsNew,
                                            contentDescription = null,
                                            tint = if (isWakeLockHeld) SuccessGreen else TextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            if (isWakeLockHeld) "Keep Alive (ACTIVE)" else "Keep Alive (Wake Lock)",
                                            color = if (isWakeLockHeld) SuccessGreen else Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = if (isWakeLockHeld) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    val enabled = LinuxTerminalSimulator.toggleWakeLock(context)
                                    Toast.makeText(context, if (enabled) "Keep Alive: ON (CPU & Wifi active)" else "Keep Alive: OFF", Toast.LENGTH_SHORT).show()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("Disable Battery Restriction", color = Color.White, fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    LinuxTerminalSimulator.requestIgnoreBatteryOptimization(context)
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("Kill Session", color = Color(0xFFEF4444), fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    activeTerminal?.let { ZhypixTerminalManager.removeSession(context, it.id) }
                                }
                            )
                        }
                    }
                }

                // Sub-header Status HUD (Active Distro + CWD + WakeLock indicator)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val activeDistroName = activeTerminal?.distroAlias ?: "Host"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = activeDistroName.uppercase(),
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = (hostCwd ?: "/root").removePrefix("/data/data/com.zhypix.app.jdufyx/files"),
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (isWakeLockHeld) {
                        Spacer(Modifier.width(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(SuccessGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 5.dp, vertical = 1.5.dp)
                        ) {
                            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(SuccessGreen))
                            Spacer(Modifier.width(4.dp))
                            Text("KEEP-ALIVE", color = SuccessGreen, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // Panel Switcher Return Banner
        if (panel != "terminal") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f))
                    .clickable { panel = "terminal" }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (panel == "distros") "PRoot Distro Manager — Tap to return to Console" else "X11 Desktop Viewer — Tap to return to Console",
                    color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }
        }

        // ── Main Content Area ────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(TerminalBg)) {
            when (panel) {
                "distros" -> DistroManagerPanel(
                    onBootDistro = { alias ->
                        ZhypixTerminalManager.createSession(context, alias, alias)
                        panel = "terminal"
                    }
                )
                "desktop" -> LinuxDesktopScreenView(
                    isDesktopScreenActive = LinuxTerminalSimulator.isDesktopScreenActive.collectAsState().value,
                    activeAppName = LinuxTerminalSimulator.activeDesktopAppName.collectAsState().value ?: "",
                    onLaunchDemo = { LinuxTerminalSimulator.setDesktopScreenActive(true, "Linux Virtual Display (:99)") },
                    onCloseDesktop = { LinuxTerminalSimulator.setDesktopScreenActive(false) }
                )
                else -> {
                    if (terminals.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Initializing Linux Environment...",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = {
                                    val defaultDistro = LinuxTerminalSimulator.activeDistro.value ?: installedDistros.firstOrNull()
                                    ZhypixTerminalManager.createSession(context, "Terminal", defaultDistro)
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                            ) {
                                Text("Retry Launch", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    } else {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                try {
                                    TerminalView(ctx, null).apply {
                                        setTerminalViewClient(object : TerminalViewClient {
                                            override fun onScale(scale: Float): Float {
                                                if (scale < 0.92f || scale > 1.08f) {
                                                    val delta = when {
                                                        scale > 1.25f -> 2
                                                        scale > 1.08f -> 1
                                                        scale < 0.75f -> -2
                                                        else -> -1
                                                    }
                                                    val newSize = (textSize + delta).coerceIn(8, 48)
                                                    if (newSize != textSize) {
                                                        textSize = newSize
                                                        setTextSize(newSize)
                                                        lastAppliedTextSize.value = newSize
                                                    }
                                                    return 1.0f
                                                }
                                                return scale
                                            }
                                            override fun onSingleTapUp(e: MotionEvent?) {
                                                post {
                                                    requestFocus()
                                                    val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                                                    imm?.showSoftInput(this@apply, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                                                }
                                            }
                                            override fun shouldBackButtonBeMappedToEscape() = false
                                            override fun shouldEnforceCharBasedInput() = true
                                            override fun shouldUseCtrlSpaceWorkaround() = false
                                            override fun isTerminalViewSelected() = true
                                            override fun copyModeChanged(copyMode: Boolean) {}
                                            override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
                                            override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
                                            override fun onLongPress(event: MotionEvent?) = false
                                            override fun readControlKey(): Boolean = ctrlState.value
                                            override fun readAltKey() = false
                                            override fun readShiftKey() = false
                                            override fun readFnKey() = false
                                            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
                                                if (ctrlState.value) {
                                                    ctrlState.value = false
                                                    ctrlSticky = false
                                                }
                                                return false
                                            }
                                            override fun onEmulatorSet() {}
                                            override fun logError(tag: String?, message: String?) { Log.e("ZhypixTerm", "$tag: $message") }
                                            override fun logWarn(tag: String?, message: String?) {}
                                            override fun logInfo(tag: String?, message: String?) {}
                                            override fun logDebug(tag: String?, message: String?) {}
                                            override fun logVerbose(tag: String?, message: String?) {}
                                            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
                                            override fun logStackTrace(tag: String?, e: Exception?) {}
                                        })
                                        setTextSize(textSize)
                                        setTypeface(Typeface.MONOSPACE)
                                        lastAppliedTextSize.value = textSize
                                        isFocusable = true
                                        isFocusableInTouchMode = true
                                        isVerticalScrollBarEnabled = true
                                        terminalViewRef.value = this

                                        post {
                                            requestFocus()
                                            val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                                            imm?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("TerminalScreen", "Error creating TerminalView", e)
                                    TerminalView(ctx, null).also { terminalViewRef.value = it }
                                }
                            },
                            update = { view ->
                                try {
                                    if (lastAppliedTextSize.value != textSize) {
                                        view.setTextSize(textSize)
                                        lastAppliedTextSize.value = textSize
                                    }
                                    val t = activeTerminal
                                    if (t != null && view.currentSession !== t.session) {
                                        view.attachSession(t.session)
                                        view.requestFocus()
                                    }
                                } catch (e: Exception) {
                                    Log.e("TerminalScreen", "Error updating TerminalView", e)
                                }
                            }
                        )

                        // Clean Process Exited Floating HUD
                        if (activeTerminal != null && !activeTerminal.isRunning) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(CardBgDark.copy(alpha = 0.95f))
                                    .border(1.dp, SurfaceLightBorder, RoundedCornerShape(14.dp))
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFEF4444)))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Process Finished${activeTerminal.session.getExitStatus().let { " (exit code $it)" }}",
                                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                                    )
                                }
                                Spacer(Modifier.height(14.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            ZhypixTerminalManager.restartSession(context, activeTerminal.id)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                        shape = RoundedCornerShape(8.dp)
                                    ) { Text("Restart", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                    OutlinedButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            ZhypixTerminalManager.removeSession(context, activeTerminal.id)
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                                    ) {
                                        Text("Close Tab", color = Color.White, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Tactile Capsule Extra Keys Bar ───────────────────────────────────
        if (panel == "terminal") {
            TactileExtraKeysRow(
                ctrlSticky = ctrlSticky,
                onCtrlToggle = {
                    ctrlSticky = !ctrlSticky
                    ctrlState.value = ctrlSticky
                },
                onToggleKeyboard = {
                    terminalViewRef.value?.let { tv ->
                        tv.requestFocus()
                        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                        imm?.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0)
                    }
                },
                onKey = { bytes ->
                    activeTerminal?.write(bytes)
                }
            )
        }
    }

    if (showNewTabDialog) {
        NewSessionDialog(
            installedDistros = installedDistros,
            onDismiss = { showNewTabDialog = false },
            onCreate = { name, distro ->
                ZhypixTerminalManager.createSession(context, name, distro)
                panel = "terminal"
                showNewTabDialog = false
            }
        )
    }
}

/**
 * Tactile, modern capsule extra keys row with subtle haptic vibration and clear active states.
 */
@Composable
private fun TactileExtraKeysRow(
    ctrlSticky: Boolean,
    onCtrlToggle: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onKey: (ByteArray) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    @Composable
    fun KeyCap(
        label: String,
        isModifier: Boolean = false,
        isActive: Boolean = false,
        onClick: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .height(34.dp)
                .defaultMinSize(minWidth = 38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        isActive -> Color.White.copy(alpha = 0.22f)
                        isModifier -> Color.White.copy(alpha = 0.08f)
                        else -> Color.White.copy(alpha = 0.04f)
                    }
                )
                .border(
                    1.dp,
                    when {
                        isActive -> Color.White.copy(alpha = 0.6f)
                        isModifier -> Color.White.copy(alpha = 0.16f)
                        else -> Color.White.copy(alpha = 0.08f)
                    },
                    RoundedCornerShape(8.dp)
                )
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
                .padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (isActive) Color.White else if (isModifier) Color.White.copy(alpha = 0.9f) else TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isActive || isModifier) FontWeight.Bold else FontWeight.Medium
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TopBarDark,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Essential Modifiers
            KeyCap("ESC", isModifier = true) { onKey(byteArrayOf(0x1b)) }
            KeyCap("CTRL", isModifier = true, isActive = ctrlSticky, onClick = onCtrlToggle)
            KeyCap("TAB", isModifier = true) { onKey(byteArrayOf(0x09)) }
            KeyCap("KEY", isModifier = true, onClick = onToggleKeyboard)

            Spacer(Modifier.width(2.dp))
            Box(modifier = Modifier.width(1.dp).height(20.dp).background(Color.White.copy(alpha = 0.1f)))
            Spacer(Modifier.width(2.dp))

            // Navigation Arrows & Cursors
            KeyCap("↑") { onKey("\u001b[A".toByteArray()) }
            KeyCap("↓") { onKey("\u001b[B".toByteArray()) }
            KeyCap("←") { onKey("\u001b[D".toByteArray()) }
            KeyCap("→") { onKey("\u001b[C".toByteArray()) }
            KeyCap("HOME") { onKey("\u001b[H".toByteArray()) }
            KeyCap("END") { onKey("\u001b[F".toByteArray()) }
            KeyCap("PGUP") { onKey("\u001b[5~".toByteArray()) }
            KeyCap("PGDN") { onKey("\u001b[6~".toByteArray()) }

            Spacer(Modifier.width(2.dp))
            Box(modifier = Modifier.width(1.dp).height(20.dp).background(Color.White.copy(alpha = 0.1f)))
            Spacer(Modifier.width(2.dp))

            // Developer / CLI Symbol Quick Keys
            KeyCap("-") { onKey("-".toByteArray()) }
            KeyCap("/") { onKey("/".toByteArray()) }
            KeyCap("|") { onKey("|".toByteArray()) }
            KeyCap("~") { onKey("~".toByteArray()) }
            KeyCap("\\") { onKey("\\".toByteArray()) }
            KeyCap("$") { onKey("$".toByteArray()) }
            KeyCap("&") { onKey("&".toByteArray()) }
            KeyCap("\"") { onKey("\"".toByteArray()) }
        }
    }
}

@Composable
private fun NewSessionDialog(
    installedDistros: Collection<String>,
    onDismiss: () -> Unit,
    onCreate: (name: String, distroAlias: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(installedDistros.firstOrNull()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBgDark,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text("Create Terminal Session", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Session Name", color = TextSecondary, fontSize = 12.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.White.copy(alpha = 0.6f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                Text("Select Subsystem Environment", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                listOf<String?>(null).plus(installedDistros).forEach { distro ->
                    val label = if (distro == null) "Host Shell (BusyBox)" else distro
                    val isSelected = selected == distro
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                            .clickable { selected = distro; if (name.isBlank()) name = label }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { selected = distro; if (name.isBlank()) name = label },
                            colors = RadioButtonDefaults.colors(selectedColor = Color.White)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(label, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name.ifBlank { "Terminal" }, selected) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) { Text("Launch", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
            ) { Text("Cancel", color = Color.White) }
        }
    )
}

/**
 * PRoot Distro Manager — Modern M3 Developer Dashboard.
 */
@Composable
private fun DistroManagerPanel(onBootDistro: (String) -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val installedDistros by LinuxTerminalSimulator.installedDistros.collectAsState()
    val runningDistros by ZhypixTerminalManager.terminals.collectAsState()

    var isInstallingDistro by remember { mutableStateOf<String?>(null) }
    var installProgress by remember { mutableFloatStateOf(0f) }
    var installStatus by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = CardBgDark,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceLightBorder)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.DeveloperBoard, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text("Linux Subsystems (PRoot)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Deploy standalone rootfs environments with independent package managers and userlands.",
                            fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        items(LinuxTerminalSimulator.availableDistros) { distro ->
            val isInstalled = installedDistros.contains(distro.alias)
            val isRunning = runningDistros.any { it.distroAlias == distro.alias && it.isRunning }
            val badgeColor = getDistroBadgeColor(distro.name)

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = CardBgDark,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isRunning) SuccessGreen.copy(alpha = 0.35f) else SurfaceLightBorder
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(badgeColor.copy(alpha = 0.15f))
                                .border(1.dp, badgeColor.copy(alpha = 0.4f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(distro.name.take(1), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = badgeColor)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(distro.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(distro.version, fontSize = 9.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                                }
                            }
                            Text(distro.description, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isRunning) SuccessGreen.copy(alpha = 0.15f) else if (isInstalled) Color.White.copy(alpha = 0.08f) else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isRunning) SuccessGreen.copy(alpha = 0.4f) else if (isInstalled) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.06f),
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (isRunning) "RUNNING" else if (isInstalled) "INSTALLED" else "AVAILABLE",
                                color = if (isRunning) SuccessGreen else if (isInstalled) Color.White else TextSecondary.copy(alpha = 0.5f),
                                fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (isInstallingDistro == distro.alias) {
                        LinearProgressIndicator(
                            progress = { installProgress },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(installStatus.ifBlank { "Downloading and unpacking rootfs…" }, color = TextSecondary, fontSize = 10.sp, maxLines = 2, fontFamily = FontFamily.Monospace)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            if (!isInstalled) {
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
                                                    if (success) onBootDistro(distro.alias)
                                                }
                                            )
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f).height(38.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Install Rootfs", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onBootDistro(distro.alias)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isRunning) Color.White.copy(alpha = 0.12f) else Color.White,
                                        contentColor = if (isRunning) Color.White else Color.Black
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1.3f).height(38.dp)
                                ) {
                                    Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isRunning) "Open Session" else "Boot Terminal", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        runningDistros.filter { it.distroAlias == distro.alias }
                                            .forEach { ZhypixTerminalManager.removeSession(context, it.id) }
                                        LinuxTerminalSimulator.removeDistroDirect(distro.alias)
                                    },
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Uninstall distro", tint = Color(0xFFEF4444).copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getDistroBadgeColor(name: String): Color = when {
    name.contains("Ubuntu", true) -> Color(0xFFE95420)
    name.contains("Debian", true) -> Color(0xFFD70A53)
    name.contains("Arch", true) -> Color(0xFF1793D1)
    name.contains("Alpine", true) -> Color(0xFF0D597F)
    name.contains("Fedora", true) -> Color(0xFF51A2DA)
    name.contains("Kali", true) -> Color(0xFF557C94)
    else -> Color(0xFF8B8B96)
}

/** Ported from legacy TerminalScreen — X11 virtual display broadcast viewer. */
@Composable
fun LinuxDesktopScreenView(
    isDesktopScreenActive: Boolean,
    activeAppName: String,
    onLaunchDemo: () -> Unit,
    onCloseDesktop: () -> Unit
) {
    val context = LocalContext.current
    var refreshCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(isDesktopScreenActive, refreshCount) {
        if (isDesktopScreenActive) {
            while (true) {
                val base64 = LinuxTerminalSimulator.captureX11Screenshot()
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090A0E))
    ) {
        if (!isDesktopScreenActive) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Monitor, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("Virtual Display Server Inactive", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "When GUI applications or browsers are executed inside the PRoot container, the live X11 display stream appears here.",
                    fontSize = 12.sp, color = TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 20.dp), lineHeight = 17.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onLaunchDemo,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Virtual Display Server (:99)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardBgDark)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(SuccessGreen))
                    Spacer(Modifier.width(8.dp))
                    Text(activeAppName.ifBlank { "Display :99" }, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    IconButton(onClick = onCloseDesktop, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Stop display", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
                if (imageBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = imageBitmap,
                        contentDescription = "X11 screenshot",
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}
