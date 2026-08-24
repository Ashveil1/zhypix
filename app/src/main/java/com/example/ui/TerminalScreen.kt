package com.example.ui

import android.graphics.BitmapFactory
import android.util.Log
import android.view.MotionEvent
import android.view.KeyEvent
import android.graphics.Typeface
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextSecondary
import com.example.utils.LinuxTerminalSimulator
import com.example.utils.ZhypixTerminalManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch

private val TerminalBg = Color(0xFF000000)
private val SurfaceDark = Color(0xFF111114)

@Composable
fun TerminalScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val installedDistros by LinuxTerminalSimulator.installedDistros.collectAsState()
    val terminals by ZhypixTerminalManager.terminals.collectAsState()
    val activeId by ZhypixTerminalManager.activeTerminalId.collectAsState()
    val hostCwd by ZhypixTerminalManager.activeHostCwd.collectAsState()

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

    Column(modifier = Modifier.fillMaxSize().background(TerminalBg)) {
        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close terminal", tint = Color.White)
            }
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                terminals.forEach { t ->
                    val isActive = t.id == activeId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isActive) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { ZhypixTerminalManager.switchTo(t.id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = t.name,
                            color = if (isActive) Color.White else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Monospace
                        )
                        if (!t.isRunning) {
                            Spacer(Modifier.width(4.dp))
                            Box(modifier = Modifier.size(6.dp).background(Color.Red, CircleShape))
                        }
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close session",
                            tint = TextSecondary,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(14.dp)
                                .clickable { ZhypixTerminalManager.removeSession(context, t.id) }
                        )
                    }
                }
            }
            IconButton(onClick = { showNewTabDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "New session", tint = Color.White)
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("PRoot Distros") },
                        onClick = { panel = "distros"; showMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("X11 Desktop") },
                        onClick = { panel = "desktop"; showMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Paste") },
                        onClick = {
                            showMenu = false
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (!text.isNullOrEmpty()) activeTerminal?.write(text)
                        }
                    )
                    DropdownMenuItem(text = { Text("Font +") }, onClick = { textSize++; showMenu = false })
                    DropdownMenuItem(text = { Text("Font -") }, onClick = { if (textSize > 8) textSize--; showMenu = false })
                    DropdownMenuItem(
                        text = { Text("Toggle wake lock") },
                        onClick = {
                            showMenu = false
                            val enabled = LinuxTerminalSimulator.toggleWakeLock(context)
                            Toast.makeText(context, if (enabled) "Wake lock ON" else "Wake lock OFF", Toast.LENGTH_SHORT).show()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Kill process") },
                        onClick = {
                            showMenu = false
                            activeTerminal?.let { ZhypixTerminalManager.removeSession(context, it.id) }
                        }
                    )
                }
            }
        }

        // Panel switcher hint bar
        if (panel != "terminal") {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.04f))
                    .clickable { panel = "terminal" }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (panel == "distros") "PRoot Distro Manager — tap to return to terminal" else "X11 Desktop — tap to return to terminal",
                    color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }
        }

        // ── Content ──────────────────────────────────────────────────────────
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
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            TerminalView(ctx, null).apply {
                                setTerminalViewClient(object : TerminalViewClient {
                                    override fun onScale(scale: Float): Float = 1f
                                    override fun onSingleTapUp(e: MotionEvent?) {}
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
                                        // consume the sticky CTRL after one keypress (Termux behavior)
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
                                setTypeface(Typeface.MONOSPACE)
                                setTextSize(textSize)
                                lastAppliedTextSize.value = textSize
                                isFocusableInTouchMode = true
                                isVerticalScrollBarEnabled = true
                                terminalViewRef.value = this
                            }
                        },
                        update = { view ->
                            if (lastAppliedTextSize.value != textSize) {
                                view.setTextSize(textSize)
                                lastAppliedTextSize.value = textSize
                            }
                            val t = activeTerminal
                            if (t != null && view.currentSession !== t.session) {
                                view.attachSession(t.session)
                                view.requestFocus()
                            }
                        }
                    )

                    // Process exited banner
                    if (activeTerminal != null && !activeTerminal.isRunning) {
                        Column(
                            modifier = Modifier.align(Alignment.Center)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xCC1A1A1F))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Process exited${activeTerminal.session.getExitStatus().let { " (code $it)" }}",
                                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { ZhypixTerminalManager.restartSession(context, activeTerminal.id) },
                                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Color.Black)
                                ) { Text("Restart") }
                                OutlinedButton(onClick = { ZhypixTerminalManager.removeSession(context, activeTerminal.id) }) {
                                    Text("Close tab")
                                }
                            }
                        }
                    }
                }
            }
        }

        // cwd status line
        if (panel == "terminal" && hostCwd != null) {
            Text(
                text = hostCwd!!.removePrefix("/data/data/com.zhypix.app.jdufyx/files"),
                color = TextSecondary.copy(alpha = 0.7f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 12.dp, vertical = 2.dp),
                maxLines = 1
            )
        }

        // ── Extra keys row ───────────────────────────────────────────────────
        if (panel == "terminal") {
            ExtraKeysRow(
                ctrlSticky = ctrlSticky,
                onCtrlToggle = {
                    ctrlSticky = !ctrlSticky
                    ctrlState.value = ctrlSticky
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

@Composable
private fun ExtraKeysRow(
    ctrlSticky: Boolean,
    onCtrlToggle: () -> Unit,
    onKey: (ByteArray) -> Unit
) {
    @Composable
    fun RowScope.ExtraKey(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (highlighted) SuccessGreen.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = if (highlighted) SuccessGreen else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            ExtraKey("ESC") { onKey(byteArrayOf(0x1b)) }
            ExtraKey("CTRL", highlighted = ctrlSticky, onClick = onCtrlToggle)
            ExtraKey("TAB") { onKey(byteArrayOf(0x09)) }
            ExtraKey("-") { onKey("-".toByteArray()) }
            ExtraKey("/") { onKey("/".toByteArray()) }
            ExtraKey("|") { onKey("|".toByteArray()) }
            ExtraKey("~") { onKey("~".toByteArray()) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            ExtraKey("HOME") { onKey("\u001b[H".toByteArray()) }
            ExtraKey("END") { onKey("\u001b[F".toByteArray()) }
            ExtraKey("^") { onKey("\u001b[A".toByteArray()) }
            ExtraKey("v") { onKey("\u001b[B".toByteArray()) }
            ExtraKey("<") { onKey("\u001b[D".toByteArray()) }
            ExtraKey(">") { onKey("\u001b[C".toByteArray()) }
            ExtraKey("PGUP") { onKey("\u001b[5~".toByteArray()) }
            ExtraKey("PGDN") { onKey("\u001b[6~".toByteArray()) }
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
        containerColor = Color(0xFF17171C),
        title = { Text("New Session", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name", color = TextSecondary) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Environment", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                listOf<String?>(null).plus(installedDistros).forEach { distro ->
                    val label = if (distro == null) "Host shell (busybox)" else distro
                    val isSelected = selected == distro
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                            .clickable { selected = distro; if (name.isBlank()) name = label }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        RadioButton(selected = isSelected, onClick = { selected = distro; if (name.isBlank()) name = label }, colors = RadioButtonDefaults.colors(selectedColor = SuccessGreen))
                        Text(label, color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name.ifBlank { "Terminal" }, selected) },
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Color.Black)
            ) { Text("Create") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
        }
    )
}

/**
 * PRoot Distro Manager — ported feature from the legacy line-based terminal.
 * Boot now creates a real PTY-backed interactive session instead of a one-shot process.
 */
@Composable
private fun DistroManagerPanel(onBootDistro: (String) -> Unit) {
    val context = LocalContext.current
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Monitor, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("PRoot Distro Manager", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                    Text(
                        "Install a distro, then tap Boot to open it in the PTY terminal.",
                        fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp
                    )
                }
            }
        }

        items(LinuxTerminalSimulator.availableDistros) { distro ->
            val isInstalled = installedDistros.contains(distro.alias)
            val isRunning = runningDistros.any { it.distroAlias == distro.alias && it.isRunning }
            val badgeColor = getDistroBadgeColor(distro.name)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .border(1.dp, if (isRunning) SuccessGreen.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier.size(36.dp).background(badgeColor.copy(alpha = 0.15f), CircleShape).border(1.dp, badgeColor.copy(alpha = 0.4f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(distro.name.take(1), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = badgeColor)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(distro.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(modifier = Modifier.background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text(distro.version, fontSize = 9.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Text(distro.description, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Box(
                            modifier = Modifier
                                .background(if (isRunning) SuccessGreen.copy(alpha = 0.12f) else if (isInstalled) Color.White.copy(alpha = 0.05f) else Color.Transparent, RoundedCornerShape(6.dp))
                                .border(1.dp, if (isRunning) SuccessGreen.copy(alpha = 0.3f) else if (isInstalled) Color.White.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isRunning) "RUNNING" else if (isInstalled) "INSTALLED" else "AVAILABLE",
                                color = if (isRunning) SuccessGreen else if (isInstalled) Color.White else TextSecondary.copy(alpha = 0.5f),
                                fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (isInstallingDistro == distro.alias) {
                        LinearProgressIndicator(
                            progress = { installProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = SuccessGreen
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(installStatus.ifBlank { "Installing…" }, color = TextSecondary, fontSize = 11.sp, maxLines = 2)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
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
                                                    if (success) onBootDistro(distro.alias)
                                                }
                                            )
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Color.Black),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f).height(38.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Install distro", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Button(
                                    onClick = { onBootDistro(distro.alias) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isRunning) Color.White.copy(alpha = 0.08f) else Color.White,
                                        contentColor = if (isRunning) Color.White else Color.Black
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1.3f).height(38.dp)
                                ) {
                                    Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isRunning) "New session" else "Boot / Console", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                IconButton(
                                    onClick = {
                                        runningDistros.filter { it.distroAlias == distro.alias }
                                            .forEach { ZhypixTerminalManager.removeSession(context, it.id) }
                                        LinuxTerminalSimulator.removeDistroDirect(distro.alias)
                                    },
                                    modifier = Modifier.size(38.dp)
                                        .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Uninstall distro", tint = Color.Red.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
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
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Monitor, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(36.dp))
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("AI Display Server Inactive", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "When the AI Agent executes GUI applications in Ubuntu PRoot, the virtual display stream will broadcast here.",
                    fontSize = 13.sp, color = TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp), lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onLaunchDemo,
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Color.Black),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Virtual Display Server (X11)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(8.dp).background(SuccessGreen, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(activeAppName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onCloseDesktop) {
                        Icon(Icons.Default.Close, contentDescription = "Stop display", tint = Color.White, modifier = Modifier.size(18.dp))
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
                        CircularProgressIndicator(color = SuccessGreen)
                    }
                }
            }
        }
    }
}
