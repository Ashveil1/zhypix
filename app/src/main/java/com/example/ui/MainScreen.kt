package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.*
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.zIndex
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.content.Context
import android.os.PowerManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.Manifest
import kotlinx.coroutines.launch
import com.example.ui.theme.*
import com.example.viewmodel.AgentViewModel
import com.example.model.*
import com.example.ui.components.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner

import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.zhypix.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: AgentViewModel) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSettingsModal by remember { mutableStateOf(false) }
    var showModelDropdown by remember { mutableStateOf(false) }
    var showTerminalScreen by remember { mutableStateOf(false) }
    
    val currentTokens by viewModel.currentTokens.collectAsState()
    val maxTokens by viewModel.maxTokens.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val agentState by viewModel.agentState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val requestedSettingsSubScreen by viewModel.requestedSettingsSubScreen.collectAsState()
    val userPreferences by viewModel.userPreferences.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(requestedSettingsSubScreen) {
        if (requestedSettingsSubScreen != null) {
            showSettingsModal = true
        }
    }
    
    var sessionToDelete by remember { mutableStateOf<com.example.db.ChatSession?>(null) }

    if (sessionToDelete != null) {
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = {
                Text(
                    text = "Delete Conversation?",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to permanently delete \"${sessionToDelete?.title}\"? This action cannot be undone.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sessionToDelete?.let { viewModel.deleteSession(it.id) }
                        sessionToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = CrimsonRose)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { sessionToDelete = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Cancel")
                }
            },
            containerColor = SurfaceElevated,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            AppDrawerContent(
                viewModel = viewModel,
                onCloseDrawer = { scope.launch { drawerState.close() } },
                onShowSettings = { showSettingsModal = true },
                onShowTerminal = { showTerminalScreen = true },
                onDeleteSession = { sessionToDelete = it }
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        val modelNameState by viewModel.modelName.collectAsState()
                        val fetchedModelsState by viewModel.availableModels.collectAsState()
                        val providerState by viewModel.provider.collectAsState()
                        val activeModel = if (modelNameState.isNotBlank()) modelNameState else "none"
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(SurfaceElevated)
                                    .border(1.dp, SurfaceLightBorder, RoundedCornerShape(24.dp))
                                    .clickable { showModelDropdown = true }
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (isProcessing) SuccessGreen else TextTertiary)
                                )
                                Text(
                                    activeModel,
                                    color = TextPrimary,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Change Model",
                                    tint = TextTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showModelDropdown,
                                onDismissRequest = { showModelDropdown = false },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .background(SurfaceElevated)
                                    .border(1.dp, SurfaceLightBorder, RoundedCornerShape(16.dp))
                            ) {
                                Text(
                                    text = "PROVIDER: ${providerState.uppercase()}",
                                    color = Color(0xFFA1A1AA),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                                HorizontalDivider(color = SurfaceLightBorder)

                                // Models come only from the configured provider's live model list.
                                val currentFetchedModels = fetchedModelsState.mapNotNull { it.modelId ?: it.id }.distinct()

                                if (currentFetchedModels.isEmpty()) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "No models loaded - tap to fetch from provider",
                                                color = Color(0xFFA1A1AA),
                                                fontSize = 13.sp
                                            )
                                        },
                                        onClick = { viewModel.fetchModels() }
                                    )
                                }

                                currentFetchedModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    model,
                                                    color = if (model == modelNameState) Color(0xFFFAFAFA) else Color(0xFFA1A1AA),
                                                    fontWeight = if (model == modelNameState) FontWeight.SemiBold else FontWeight.Normal,
                                                    fontSize = 13.sp
                                                )
                                                if (model == modelNameState) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFFFAFAFA), modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        },
                                        onClick = {
                                            viewModel.updateSetting("modelName", model)
                                            viewModel.saveSettings()
                                            showModelDropdown = false
                                        }
                                    )
                                }

                                HorizontalDivider(color = SurfaceLightBorder)
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFFA1A1AA), modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("AI Provider Settings...", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                                        }
                                    },
                                    onClick = {
                                        showModelDropdown = false
                                        showSettingsModal = true
                                    }
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        Box(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(SurfaceElevated)
                                .border(1.dp, SurfaceLightBorder, CircleShape)
                                .clickable { scope.launch { drawerState.open() } },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Menu",
                                tint = TextPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },

                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF09090B)
                    )
                )
            },
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.statusBars
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(BackgroundGradientStart, BackgroundGradientEnd)
                        )
                    )
                    .padding(top = innerPadding.calculateTopPadding())
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 840.dp)
                        .align(Alignment.TopCenter)
                ) {
                    ChatScreenBody(viewModel)
                }
            }
            
            if (showSettingsModal) {
                BackHandler {
                    showSettingsModal = false
                    viewModel.clearRequestedSettingsSubScreen()
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(90f)
                        .background(Brush.verticalGradient(listOf(BackgroundGradientStart, BackgroundGradientEnd)))
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 720.dp)
                            .align(Alignment.TopCenter)
                    ) {
                        SettingsScreen(
                            viewModel = viewModel,
                            onClose = {
                                showSettingsModal = false
                                viewModel.clearRequestedSettingsSubScreen()
                            },
                            initialSubScreen = requestedSettingsSubScreen ?: com.example.model.SettingsScreenType.MAIN
                        )
                    }
                }
            }
            
            if (showTerminalScreen) {
                BackHandler {
                    showTerminalScreen = false
                }
                Box(modifier = Modifier.fillMaxSize().zIndex(100f).background(Color.Black)) {
                    TerminalScreen(onClose = { showTerminalScreen = false })
                }
            }
        }
    }
}

object PermissionUtils {
    fun isAccessibilityEnabled(context: Context): Boolean {
        val service = "com.example.service.ZhypixAccessibilityService"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabled.contains(service) || com.example.service.ZhypixAccessibilityService.instance != null
    }

    fun isOverlayEnabled(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isMicrophoneGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isNotificationsGranted(context: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}

@Composable
fun WizardChecklistRow(
    title: String,
    description: String,
    isActive: Boolean,
    actionLabel: String,
    onClick: () -> Unit
) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isActive) SuccessGreen.copy(alpha = 0.22f) else SurfaceLightBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = (-0.2).sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isActive) "Authorized" else "Action required",
                        color = if (isActive) SuccessGreen else OrangeWarning,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                if (!isActive) {
                    Button(
                        onClick = onClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            actionLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Surface(
                        color = SuccessGreen.copy(alpha = 0.10f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.30f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = SuccessGreen,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = "Active",
                                color = SuccessGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = description,
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
fun ClaudeGroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, SurfaceLightBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
fun ClaudeSettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    badgeText: String? = null,
    badgeColor: Color = CyanAccent,
    isExpanded: Boolean = false,
    showDivider: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    expandedContent: (@Composable () -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        if (isExpanded) Color.White.copy(alpha = 0.12f) else SurfaceElevated,
                        RoundedCornerShape(10.dp)
                    )
                    .border(
                        1.dp,
                        if (isExpanded) Color.White.copy(alpha = 0.30f) else SurfaceLightBorder,
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isExpanded) Accent else TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (badgeText != null) {
                        Surface(
                            color = badgeColor.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = badgeText,
                                color = badgeColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            if (trailingContent != null) {
                trailingContent()
            } else if (expandedContent != null) {
                val rotationState by animateFloatAsState(
                    targetValue = if (isExpanded) 180f else 0f,
                    animationSpec = tween(300)
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = if (isExpanded) CyanAccent else TextSecondary,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotationState)
                )
            }
        }

        if (expandedContent != null) {
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(250)) + fadeIn(),
                exit = shrinkVertically(animationSpec = tween(250)) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.25f))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    expandedContent()
                }
            }
        }

        if (showDivider) {
            HorizontalDivider(
                color = SurfaceLightBorder.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 66.dp)
            )
        }
    }
}

@Composable
fun SettingSectionCard(
    title: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SurfaceLightBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (title != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (icon != null) {
                            Icon(icon, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                        }
                        Text(title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    if (subtitle != null) {
                        Text(subtitle, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
            }
            content()
        }
    }
}


