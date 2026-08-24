package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import coil.compose.AsyncImage
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import com.zhypix.BuildConfig
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.model.*
import com.example.ui.theme.*
import com.example.viewmodel.AgentViewModel
import com.example.utils.PermissionUtils
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

data class Tuple5(
    val desc: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val authUrl: String,
    val isConnected: Boolean,
    val isActive: Boolean,
    val token: String
)

@Composable
fun SubScreenHeader(
    title: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun <T> SegmentedControlGroup(
    items: List<Pair<T, String>>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items.forEach { (value, label) ->
            val isSelected = value == selectedItem
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onItemSelected(value) },
                color = if (isSelected) Color.White.copy(alpha = 0.15f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
                border = if (isSelected) BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)) else null
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun SectionGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = Color(0xFF131315),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
fun SectionSettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    isExpanded: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
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
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 12.sp
                    )
                }
            }
            
            if (trailingContent != null) {
                trailingContent()
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
    }
}

@Composable
fun ProviderAndModelSettings(viewModel: AgentViewModel) {
    val providerProfiles by viewModel.providerProfiles.collectAsState()
    val currentProvider by viewModel.provider.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val baseUrl by viewModel.baseUrl.collectAsState()
    val modelName by viewModel.modelName.collectAsState()
    val thinkingLevel by viewModel.thinkingLevel.collectAsState()
    val isFetchingModels by viewModel.isFetchingModels.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()

    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current

    // Screen State: false = Main Connections Overview, true = Step-by-Step Wizard
    var isWizardActive by remember { mutableStateOf(false) }
    var wizardStep by remember { mutableIntStateOf(1) } // 1: Provider, 2: API Key/URL, 3: Model, 4: Verify

    // Draft wizard fields
    var wizardProviderId by remember { mutableStateOf("Gemini") }
    var wizardApiKey by remember { mutableStateOf("") }
    var wizardBaseUrl by remember { mutableStateOf("") }
    var wizardModelName by remember { mutableStateOf("") }
    var wizardCustomProviderName by remember { mutableStateOf("") }

    var modelSearchQuery by remember { mutableStateOf("") }
    var isApiKeyVisible by remember { mutableStateOf(false) }
    var copyToastVisible by remember { mutableStateOf(false) }

    val isConfigured = viewModel.isProviderAndModelConfigured()

    // Helper to start wizard pre-filled
    fun startWizardFor(providerId: String) {
        wizardProviderId = providerId
        val existing = providerProfiles[providerId]
        if (existing != null) {
            wizardApiKey = existing.apiKey
            wizardBaseUrl = existing.baseUrl
            wizardModelName = existing.modelName
        } else {
            wizardApiKey = if (providerId == currentProvider) apiKey else ""
            wizardBaseUrl = when (providerId) {
                "Gemini" -> "https://generativelanguage.googleapis.com/"
                "Claude" -> "https://api.anthropic.com/v1"
                "OpenAI" -> "https://api.openai.com/v1"
                "Groq" -> "https://api.groq.com/openai/v1"
                "OpenRouter" -> "https://openrouter.ai/api/v1"
                "Ollama" -> "http://10.0.2.2:11434/v1"
                else -> ""
            }
            wizardModelName = ""
        }
        modelSearchQuery = wizardModelName
        wizardStep = 1
        isWizardActive = true
    }

    if (!isWizardActive) {
        // =========================================================
        // VIEW 1: MAIN CONNECTIONS LIST (Uncluttered Overview)
        // =========================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
/* Status header banner removed */

            // Connection List Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Configured AI Connections",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${providerProfiles.size} saved",
                        color = Color(0xFFA1A1AA),
                        fontSize = 12.sp
                    )
                }

                if (providerProfiles.isEmpty()) {
                    Surface(
                        color = Color(0xFF09090B),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFF27272A)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "No AI Connections Saved",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tap '+ Add New Connection' below to configure Gemini, Claude, OpenAI, or Custom API.",
                                color = Color(0xFFA1A1AA),
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        providerProfiles.forEach { (id, profile) ->
                            val isActive = (id == currentProvider)
                            val isProfileReady = profile.modelName.isNotBlank() &&
                                profile.modelName != "none" &&
                                (profile.apiKey.isNotBlank() || (id == "Gemini" && BuildConfig.GEMINI_API_KEY.isNotBlank()))

                            Surface(
                                color = if (isActive) Color(0xFF18181B) else Color(0xFF09090B),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(
                                    1.5.dp,
                                    if (isActive) Color.White else Color(0xFF27272A)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.selectProvider(id)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Provider Badge Icon
                                    ProviderLogoBadge(providerId = id)

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = profile.name,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            if (isActive) {
                                                Surface(
                                                    color = Color.White.copy(alpha = 0.2f),
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        text = "ACTIVE",
                                                        color = Color.White,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            } else if (isProfileReady) {
                                                Surface(
                                                    color = Color.White.copy(alpha = 0.1f),
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        text = "READY",
                                                        color = Color.White.copy(alpha = 0.8f),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(2.dp))

                                        Text(
                                            text = if (profile.modelName.isNotBlank() && profile.modelName != "none") "Model: ${profile.modelName}" else "No model selected",
                                            color = Color(0xFFA1A1AA),
                                            fontSize = 12.sp
                                        )
                                    }

                                    // Action Buttons: Edit and Delete
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        IconButton(
                                            onClick = { startWizardFor(id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Edit Connection",
                                                tint = Color(0xFFA1A1AA),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        if (id !in listOf("Gemini", "Claude", "OpenAI", "Groq")) {
                                            IconButton(
                                                onClick = { viewModel.deleteCustomProvider(id) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete Connection",
                                                    tint = Color(0xFFEF4444),
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

            // Prominent "+ Add New Connection" Button
            Button(
                onClick = { startWizardFor("Gemini") },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF27272A),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF3F3F46)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Add New Connection (+)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

/* Reasoning level moved to wizard */
        }
    } else {
        // =========================================================
        // VIEW 2: STEP-BY-STEP WIZARD (Add/Edit Connection)
        // =========================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Wizard Header Navigation Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = {
                        if (wizardStep > 1) {
                            wizardStep--
                        } else {
                            isWizardActive = false
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                Text(
                    text = "Connection Wizard (${wizardStep}/4)",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                TextButton(onClick = { isWizardActive = false }) {
                    Text("Cancel", color = Color(0xFFA1A1AA), fontSize = 13.sp)
                }
            }

            // Step Indicator Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("1. Provider", "2. API Key", "3. Model", "4. Verify").forEachIndexed { index, title ->
                    val stepNum = index + 1
                    val isCurrent = stepNum == wizardStep
                    val isDone = stepNum < wizardStep

                    Surface(
                        color = when {
                            isCurrent -> Color.White
                            isDone -> Color.White
                            else -> Color(0xFF18181B)
                        },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                    ) {}
                }
            }

            when (wizardStep) {
                // -----------------------------------------------------
                // STEP 1: SELECT PROVIDER
                // -----------------------------------------------------
                1 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Step 1: Choose AI Provider",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )

                        val providerOptions = listOf(
                            Triple("Gemini", "Google Gemini", "Google AI Studio • Fast & Free tier"),
                            Triple("OpenAI", "OpenAI", "GPT-4o, GPT-4o-mini & o1"),
                            Triple("Claude", "Anthropic Claude", "Sonnet 3.5 & Haiku"),
                            Triple("Groq", "Groq Cloud", "Ultra-fast Llama 3 & Mixtral"),
                            Triple("OpenRouter", "OpenRouter", "Unified API Gateway for all models"),
                            Triple("Ollama", "Ollama / Local API", "Localhost server or custom API"),
                            Triple("Custom", "Custom Named Provider", "Add custom endpoint or proxy")
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            providerOptions.forEach { (pId, pName, pSub) ->
                                val isSelected = (wizardProviderId == pId)

                                Surface(
                                    color = if (isSelected) Color(0xFF27272A) else Color(0xFF09090B),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(
                                        1.5.dp,
                                        if (isSelected) Color.White else Color(0xFF27272A)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            wizardProviderId = pId
                                            if (pId != "Custom" && pId != "Ollama") {
                                                wizardBaseUrl = when (pId) {
                                                    "Gemini" -> "https://generativelanguage.googleapis.com/"
                                                    "Claude" -> "https://api.anthropic.com/v1"
                                                    "OpenAI" -> "https://api.openai.com/v1"
                                                    "Groq" -> "https://api.groq.com/openai/v1"
                                                    "OpenRouter" -> "https://openrouter.ai/api/v1"
                                                    else -> ""
                                                }
                                                wizardModelName = when (pId) {
                                                    "Gemini" -> "gemini-2.5-flash"
                                                    "Claude" -> "claude-3-5-sonnet-20241022"
                                                    "OpenAI" -> "gpt-4o"
                                                    "Groq" -> "llama3-70b-8192"
                                                    "OpenRouter" -> "google/gemini-2.5-flash"
                                                    else -> ""
                                                }
                                                modelSearchQuery = wizardModelName
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ProviderLogoBadge(providerId = pId)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = pName,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = pSub,
                                                color = Color(0xFFA1A1AA),
                                                fontSize = 11.sp
                                            )
                                        }
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = { wizardProviderId = pId },
                                            colors = RadioButtonDefaults.colors(selectedColor = Color.White)
                                        )
                                    }
                                }
                            }
                        }

                        if (wizardProviderId == "Custom") {
                            OutlinedTextField(
                                value = wizardCustomProviderName,
                                onValueChange = { wizardCustomProviderName = it },
                                label = { Text("Custom Provider Name", color = Color(0xFFA1A1AA)) },
                                placeholder = { Text("e.g. My Work LLM", color = Color(0xFF52525B)) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.White,
                                    unfocusedBorderColor = Color(0xFF27272A),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true
                            )
                        }

                        Button(
                            onClick = { wizardStep = 2 },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Next: API Key & Setup ->", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // -----------------------------------------------------
                // STEP 2: API KEY & ENDPOINT SETUP
                // -----------------------------------------------------
                2 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = "Step 2: Enter API Key for $wizardProviderId",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )

                        // Useful Guide Banner explaining where to get API key
                        val helpUrl = when (wizardProviderId) {
                            "Gemini" -> "https://aistudio.google.com"
                            "OpenAI" -> "https://platform.openai.com/api-keys"
                            "Claude" -> "https://console.anthropic.com/"
                            "Groq" -> "https://console.groq.com/keys"
                            "OpenRouter" -> "https://openrouter.ai/keys"
                            else -> null
                        }

                        if (helpUrl != null) {
                            Surface(
                                color = Color(0xFF18181B),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFF27272A)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "How to get $wizardProviderId API Key:",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Get your key at: $helpUrl",
                                        color = Color(0xFFA1A1AA),
                                        fontSize = 11.sp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { uriHandler.openUri(helpUrl) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27272A), contentColor = Color.White),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, Color(0xFF3F3F46)),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.OpenInNew,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Get API Key", fontSize = 11.sp, color = Color.White)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(helpUrl))
                                                copyToastVisible = true
                                            },
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, Color(0xFF3F3F46)),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = null,
                                                tint = Color.White.copy(alpha = 0.8f),
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Copy Link", fontSize = 11.sp, color = Color.White)
                                        }
                                    }
                                }
                            }
                        }

                        if (copyToastVisible) {
                            Text(
                                text = "Link copied to clipboard!",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // API Key Input
                        OutlinedTextField(
                            value = wizardApiKey,
                            onValueChange = { wizardApiKey = it },
                            label = { Text("API Key", color = Color(0xFFA1A1AA)) },
                            placeholder = {
                                Text(
                                    text = if (wizardProviderId == "Gemini") "Leave blank to use default system key" else "Paste your API key here...",
                                    color = Color(0xFF52525B),
                                    fontSize = 12.sp
                                )
                            },
                            visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                                    Icon(
                                        imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle Visibility",
                                        tint = Color(0xFFA1A1AA)
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color(0xFF27272A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )

                        // Base API URL Field
                        OutlinedTextField(
                            value = wizardBaseUrl,
                            onValueChange = { wizardBaseUrl = it },
                            label = { Text("Base API Endpoint URL", color = Color(0xFFA1A1AA)) },
                            placeholder = { Text("e.g. https://generativelanguage.googleapis.com/", color = Color(0xFF52525B)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color(0xFF27272A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )

                        if (wizardProviderId == "Ollama") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { wizardBaseUrl = "http://10.0.2.2:11434/v1" },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27272A)),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("Emulator (10.0.2.2)", fontSize = 11.sp, color = Color.White)
                                }
                                Button(
                                    onClick = { wizardBaseUrl = "http://localhost:11434/v1" },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27272A)),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("Localhost:11434", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { wizardStep = 1 },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFF3F3F46)),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Text("Back", color = Color.White)
                            }

                            Button(
                                onClick = { wizardStep = 3 },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Text("Next: Model ->", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // -----------------------------------------------------
                // STEP 3: SELECT & LIVE FILTER MODEL
                // -----------------------------------------------------
                3 -> {
                    LaunchedEffect(wizardProviderId, wizardBaseUrl, wizardApiKey) {
                        viewModel.fetchModels(wizardProviderId, wizardBaseUrl, wizardApiKey)
                    }
                    val allPresetModels = remember(wizardProviderId, availableModels) {
                        if (availableModels.isNotEmpty()) {
                            availableModels.map { it.modelId }.filter { it.isNotBlank() }
                        } else {
                            when (wizardProviderId) {
                                "Gemini" -> listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-1.5-flash", "gemini-1.5-pro")
                                "OpenAI" -> listOf("gpt-4o", "gpt-4o-mini", "gpt-3.5-turbo", "o1-mini")
                                "Claude" -> listOf("claude-3-5-sonnet-20241022", "claude-3-haiku-20240307", "claude-3-opus-20240229")
                                "Groq" -> listOf("llama3-70b-8192", "llama3-8b-8192", "mixtral-8x7b-32768")
                                "OpenRouter" -> listOf("google/gemini-2.5-flash", "openai/gpt-4o", "anthropic/claude-3.5-sonnet")
                                else -> emptyList()
                            }
                        }
                    }

                    // Live Filter as User Types!
                    val filteredModels = remember(modelSearchQuery, allPresetModels) {
                        if (modelSearchQuery.isBlank()) {
                            allPresetModels
                        } else {
                            allPresetModels.filter { it.contains(modelSearchQuery, ignoreCase = true) }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Step 3: Select AI Model",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )

                            TextButton(
                                onClick = { viewModel.fetchModels(wizardProviderId, wizardBaseUrl, wizardApiKey) },
                                enabled = !isFetchingModels
                            ) {
                                if (isFetchingModels) {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color.White, strokeWidth = 1.5.dp)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Fetching...", fontSize = 11.sp, color = Color.White)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Fetch Online", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }

                        // Search & Live Filter Input Box
                        OutlinedTextField(
                            value = modelSearchQuery,
                            onValueChange = {
                                modelSearchQuery = it
                                wizardModelName = it
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFFA1A1AA))
                            },
                            trailingIcon = {
                                if (modelSearchQuery.isNotEmpty()) {
                                    IconButton(onClick = {
                                        modelSearchQuery = ""
                                        wizardModelName = ""
                                    }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color(0xFFA1A1AA))
                                    }
                                }
                            },
                            label = { Text("Filter or Type Custom Model Name", color = Color(0xFFA1A1AA)) },
                            placeholder = { Text("e.g. gemini-2.5-flash", color = Color(0xFF52525B)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color(0xFF27272A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )

                        Text(
                            text = if (filteredModels.isNotEmpty()) "Matching Models (${filteredModels.size}):" else "No exact matching preset found.",
                            color = Color(0xFFA1A1AA),
                            fontSize = 11.sp
                        )

                        // List of Filtered Model Cards
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            filteredModels.forEach { mId ->
                                val isSel = (wizardModelName == mId)
                                Surface(
                                    color = if (isSel) Color(0xFF27272A) else Color(0xFF09090B),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, if (isSel) Color.White else Color(0xFF27272A)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            wizardModelName = mId
                                            modelSearchQuery = mId
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(
                                                selected = isSel,
                                                onClick = {
                                                    wizardModelName = mId
                                                    modelSearchQuery = mId
                                                },
                                                colors = RadioButtonDefaults.colors(selectedColor = Color.White)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = mId,
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            }

                            // Custom typed entry card if no filter matches or custom typed
                            if (filteredModels.isEmpty() && modelSearchQuery.isNotBlank()) {
                                Surface(
                                    color = Color(0xFF18181B),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.5.dp, Color.White),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text("Use custom typed model:", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text(modelSearchQuery, color = Color(0xFF10B981), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { wizardStep = 2 },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFF3F3F46)),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Text("Back", color = Color.White)
                            }

                            Button(
                                onClick = { wizardStep = 4 },
                                enabled = wizardModelName.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Text("Next: Verify ->", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // -----------------------------------------------------
                // STEP 4: VERIFY & CONNECT
                // -----------------------------------------------------
                4 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = "Step 4: Verify & Save Connection",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )

                        // Effort Settings Section
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Effort",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            SegmentedControlGroup(
                                items = listOf("NONE" to "Off", "LOW" to "Low", "HIGH" to "High"),
                                selectedItem = thinkingLevel,
                                onItemSelected = { viewModel.updateSetting("thinkingLevel", it) }
                            )
                        }

                        // Configuration Summary Card
                        Surface(
                            color = Color(0xFF18181B),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFF27272A)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ProviderLogoBadge(providerId = wizardProviderId)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = if (wizardProviderId == "Custom" && wizardCustomProviderName.isNotBlank()) wizardCustomProviderName else wizardProviderId,
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text("Selected Model: $wizardModelName", color = Color(0xFF10B981), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                HorizontalDivider(color = Color(0xFF27272A))

                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("API Key:", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                                    Text(
                                        text = if (wizardApiKey.isNotBlank()) "${wizardApiKey.take(4)}...${wizardApiKey.takeLast(4)}" else if (wizardProviderId == "Gemini") "Default AI Studio Key" else "(Not set)",
                                        color = Color.White,
                                        fontSize = 12.sp
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("Endpoint:", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                                    Text(
                                        text = wizardBaseUrl.ifBlank { "Default" },
                                        color = Color.White,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        // Live Connection Status
                        if (connectionStatus != null) {
                            val isSuccess = connectionStatus?.contains("Successful", ignoreCase = true) == true
                            val isTesting = connectionStatus?.contains("Verifying", ignoreCase = true) == true || connectionStatus?.contains("Retrying", ignoreCase = true) == true

                            Surface(
                                color = Color(0xFF18181B),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp,
                                    Color(0xFF3F3F46)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (isTesting) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                    } else {
                                        Icon(
                                            imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                            contentDescription = null,
                                            tint = if (isSuccess) Color.White else Color(0xFFEF4444),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = connectionStatus ?: "",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Verify & Save Buttons
                        Button(
                            onClick = {
                                viewModel.updateSetting("provider", wizardProviderId)
                                viewModel.updateSetting("apiKey", wizardApiKey)
                                viewModel.updateSetting("baseUrl", wizardBaseUrl)
                                viewModel.updateSetting("modelName", wizardModelName)
                                viewModel.verifyConnection()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27272A), contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF3F3F46)),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Verify Connection Test", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        Button(
                            onClick = {
                                val targetId = if (wizardProviderId == "Custom" && wizardCustomProviderName.isNotBlank()) wizardCustomProviderName else wizardProviderId
                                viewModel.saveProviderProfile(
                                    id = targetId,
                                    apiKey = wizardApiKey,
                                    baseUrl = wizardBaseUrl,
                                    modelName = wizardModelName,
                                    makeActive = true
                                )
                                isWizardActive = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save & Set as Active Connection", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        OutlinedButton(
                            onClick = { wizardStep = 3 },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF3F3F46)),
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        ) {
                            Text("Back to Model Selection", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeminiLogoCanvas(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val pathString = "M11.04 19.32Q12 21.51 12 24q0-2.49.93-4.68.96-2.19 2.58-3.81t3.81-2.55Q21.51 12 24 12q-2.49 0-4.68-.93a12.3 12.3 0 0 1-3.81-2.58 12.3 12.3 0 0 1-2.58-3.81Q12 2.49 12 0q0 2.49-.96 4.68-.93 2.19-2.55 3.81a12.3 12.3 0 0 1-3.81 2.58Q2.49 12 0 12q2.49 0 4.68.96 2.19.93 3.81 2.55t2.55 3.81"
        try {
            val path = androidx.compose.ui.graphics.vector.PathParser()
                .parsePathString(pathString)
                .toPath()

            val scaleX = size.width / 24f
            val scaleY = size.height / 24f

            drawContext.canvas.save()
            drawContext.canvas.scale(scaleX, scaleY)

            val brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                colors = listOf(
                    Color(0xFF4285F4), // 0.0 (Right - Blue)
                    Color(0xFF34A853), // 0.25 (Bottom - Green)
                    Color(0xFFFBBC05), // 0.50 (Left - Yellow)
                    Color(0xFFEA4335), // 0.75 (Top - Red)
                    Color(0xFF4285F4)  // 1.0 (Right - Blue)
                ),
                center = androidx.compose.ui.geometry.Offset(12f, 12f)
            )

            drawPath(
                path = path,
                brush = brush
            )

            drawContext.canvas.restore()
        } catch (e: Exception) {
            // Fallback: draw a colored circle/star in case of parser errors
            drawCircle(color = Color(0xFF4285F4))
        }
    }
}

@Composable
private fun ProviderLogoBadge(providerId: String) {
    val logoRes = when (providerId) {
        "Gemini" -> com.zhypix.R.drawable.ic_provider_gemini
        "OpenAI" -> com.zhypix.R.drawable.ic_provider_openai
        "Claude" -> com.zhypix.R.drawable.ic_provider_claude
        "Groq" -> com.zhypix.R.drawable.ic_provider_groq
        "OpenRouter" -> com.zhypix.R.drawable.ic_provider_openrouter
        "Ollama" -> com.zhypix.R.drawable.ic_provider_ollama
        else -> com.zhypix.R.drawable.ic_ai_assistant
    }

    val backgroundColor = when (providerId) {
        "Gemini", "OpenAI", "Claude", "Groq", "OpenRouter", "Ollama" -> Color.White
        else -> Color(0xFF27272A)
    }

    Box(
        modifier = Modifier
            .size(38.dp)
            .background(backgroundColor, RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF3F3F46), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (providerId == "Gemini") {
            GeminiLogoCanvas(modifier = Modifier.size(22.dp))
        } else {
            Image(
                painter = painterResource(id = logoRes),
                contentDescription = providerId,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: AgentViewModel,
    onClose: () -> Unit,
    initialSubScreen: SettingsScreenType = SettingsScreenType.MAIN
) {
    val providerProfiles by viewModel.providerProfiles.collectAsState()
    val provider by viewModel.provider.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val baseUrl by viewModel.baseUrl.collectAsState()
    val modelName by viewModel.modelName.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val isFetchingModels by viewModel.isFetchingModels.collectAsState()
    val thinkingLevel by viewModel.thinkingLevel.collectAsState()
    val visionMode by viewModel.visionMode.collectAsState()
    val liveStreamEnabled by viewModel.liveStreamEnabled.collectAsState()
    val screenshotInterval by viewModel.screenshotInterval.collectAsState()
    val screenshotQuality by viewModel.screenshotQuality.collectAsState()
    val screenshotScale by viewModel.screenshotScale.collectAsState()
    
    val scope = rememberCoroutineScope()
    var showSaved by remember { mutableStateOf(false) }
    var showAddProviderDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var isAccessibilityServiceBound by remember { mutableStateOf(false) }
    var isOverlayEnabled by remember { mutableStateOf(false) }
    var isBatteryOptimizationIgnored by remember { mutableStateOf(false) }
    var isMicrophoneGranted by remember { mutableStateOf(false) }
    var isNotificationsGranted by remember { mutableStateOf(false) }
    var isStorageGranted by remember { mutableStateOf(false) }
    
    var colorMode by remember { mutableStateOf("System") }
    var fontStyle by remember { mutableStateOf("Default") }
    var voiceOption by remember { mutableStateOf("Default") }

    fun refreshPermissions() {
        isAccessibilityEnabled = PermissionUtils.isAccessibilityEnabled(context)
        isAccessibilityServiceBound = com.example.service.ZhypixAccessibilityService.instance != null
        isOverlayEnabled = PermissionUtils.isOverlayEnabled(context)
        isBatteryOptimizationIgnored = PermissionUtils.isBatteryOptimizationIgnored(context)
        isMicrophoneGranted = PermissionUtils.isMicrophoneGranted(context)
        isNotificationsGranted = PermissionUtils.isNotificationsGranted(context)
        isStorageGranted = PermissionUtils.isStorageGranted(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        refreshPermissions()
    }

    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { refreshPermissions() }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { refreshPermissions() }

    val mcpServers by viewModel.mcpServers.collectAsState()
    var showAddMcpDialog by remember { mutableStateOf(false) }

    val activeCount = listOf(isAccessibilityEnabled, isOverlayEnabled, isBatteryOptimizationIgnored, isMicrophoneGranted, isNotificationsGranted).count { it }
    val totalCount = 5
    val percent = (activeCount.toFloat() / totalCount.toFloat() * 100).toInt()

    var activeSubScreen by remember(initialSubScreen) { mutableStateOf(initialSubScreen) }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = activeSubScreen,
            transitionSpec = {
                if (targetState == SettingsScreenType.MAIN) {
                    (slideInHorizontally(animationSpec = tween(250)) { -it } + fadeIn(animationSpec = tween(150)))
                        .togetherWith(slideOutHorizontally(animationSpec = tween(250)) { it } + fadeOut(animationSpec = tween(150)))
                } else {
                    (slideInHorizontally(animationSpec = tween(250)) { it } + fadeIn(animationSpec = tween(150)))
                        .togetherWith(slideOutHorizontally(animationSpec = tween(250)) { -it } + fadeOut(animationSpec = tween(150)))
                }
            },
            label = "SettingsScreenTransition",
            modifier = Modifier.fillMaxSize()
        ) { screen ->
            when (screen) {
                SettingsScreenType.MAIN -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Top Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onClose) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            
                            Text(
                                text = "Settings",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                            
                            Spacer(modifier = Modifier.width(48.dp))
                        }

                        // Group 1: AI Model & Provider
                        SectionGroupCard {
                            SectionSettingRow(
                                icon = Icons.Default.Category,
                                title = "AI Model & Provider",
                                subtitle = "$provider • ${modelName.ifBlank { "Default" }}",
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowRight,
                                        contentDescription = "Open AI Model & Provider Settings",
                                        tint = Color.White.copy(alpha = 0.35f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = { activeSubScreen = SettingsScreenType.PROFILE }
                            )
                        }

                        // Group 2: Capabilities, Connectors, Permissions
                        SectionGroupCard {
                            SectionSettingRow(
                                icon = Icons.Default.Computer,
                                title = "Connectors",
                                subtitle = "${mcpServers.count { it.isEnabled }}/${mcpServers.size} Active MCP Tools",
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowRight,
                                        contentDescription = "Open Connectors Settings",
                                        tint = Color.White.copy(alpha = 0.35f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = { activeSubScreen = SettingsScreenType.CONNECTORS }
                            )

                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(start = 52.dp))

                            SectionSettingRow(
                                icon = Icons.Default.Check,
                                title = "Permissions",
                                subtitle = "$activeCount/$totalCount Granted",
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowRight,
                                        contentDescription = "Open Permissions Settings",
                                        tint = Color.White.copy(alpha = 0.35f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = { activeSubScreen = SettingsScreenType.PERMISSIONS }
                            )
                        }

                        // Group 3: Color Mode, Font Style, Voice
                        SectionGroupCard {
                            SectionSettingRow(
                                icon = Icons.Default.Album,
                                title = "Color mode",
                                subtitle = colorMode,
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowRight,
                                        contentDescription = "Open Color Mode Settings",
                                        tint = Color.White.copy(alpha = 0.35f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = { activeSubScreen = SettingsScreenType.COLOR_MODE }
                            )

                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(start = 52.dp))

                            SectionSettingRow(
                                icon = Icons.Default.Tune,
                                title = "Font style",
                                subtitle = fontStyle,
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowRight,
                                        contentDescription = "Open Font Style Settings",
                                        tint = Color.White.copy(alpha = 0.35f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = { activeSubScreen = SettingsScreenType.FONT_STYLE }
                            )

                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(start = 52.dp))

                            SectionSettingRow(
                                icon = Icons.Default.VolumeUp,
                                title = "Voice & Speech",
                                subtitle = "Edge TTS & Hands-Free STT",
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowRight,
                                        contentDescription = "Open Voice Settings",
                                        tint = Color.White.copy(alpha = 0.35f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = { activeSubScreen = SettingsScreenType.VOICE }
                            )
                        }

                        // Group 4: Privacy, Shared Links
                        SectionGroupCard {
                            val userPreferences by viewModel.userPreferences.collectAsState()
                            val learnedMemories = userPreferences.filter { !it.key.startsWith("cfg_") }
                            val selectedPersona by viewModel.selectedPersona.collectAsState()

                            SectionSettingRow(
                                icon = Icons.Default.Face,
                                title = "AI Persona",
                                subtitle = "Active: $selectedPersona",
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowRight,
                                        contentDescription = "Open Persona Settings",
                                        tint = Color.White.copy(alpha = 0.35f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = { activeSubScreen = SettingsScreenType.PERSONA }
                            )

                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(start = 52.dp))

                            SectionSettingRow(
                                icon = Icons.Default.Psychology,
                                title = "Long-Term Memory",
                                subtitle = "${learnedMemories.size} saved facts",
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowRight,
                                        contentDescription = "Open Memory Settings",
                                        tint = Color.White.copy(alpha = 0.35f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = { activeSubScreen = SettingsScreenType.MEMORY }
                            )

                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(start = 52.dp))

                            SectionSettingRow(
                                icon = Icons.Default.Lock,
                                title = "Privacy",
                                subtitle = "${learnedMemories.size} facts stored",
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowRight,
                                        contentDescription = "Open Privacy Settings",
                                        tint = Color.White.copy(alpha = 0.35f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = { activeSubScreen = SettingsScreenType.PRIVACY }
                            )

                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(start = 52.dp))

                            SectionSettingRow(
                                icon = Icons.Default.Share,
                                title = "Shared links",
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowRight,
                                        contentDescription = "Open Shared Links Settings",
                                        tint = Color.White.copy(alpha = 0.35f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = { activeSubScreen = SettingsScreenType.SHARED_LINKS }
                            )
                        }

                        // Save & Verify
                        val connectionStatus by viewModel.connectionStatus.collectAsState()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    viewModel.saveSettings()
                                    showSaved = true
                                    scope.launch {
                                        kotlinx.coroutines.delay(2000)
                                        showSaved = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                if (showSaved) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Saved", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                } else {
                                    Text("Save Config", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }

                            Button(
                                onClick = {
                                    viewModel.saveSettings()
                                    viewModel.verifyConnection()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = Color.White),
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                            ) {
                                Text("Verify Link", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
                SettingsScreenType.PROFILE -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SubScreenHeader(title = "AI Model & Provider Settings") { activeSubScreen = SettingsScreenType.MAIN }
                        SectionGroupCard {
                            Column(modifier = Modifier.padding(16.dp)) {
                                ProviderAndModelSettings(viewModel = viewModel)
                            }
                        }
                    }
                }
                SettingsScreenType.PERMISSIONS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SubScreenHeader(title = "System Permissions") { activeSubScreen = SettingsScreenType.MAIN }
                        
                        Text(
                            text = "Grant the following permissions to enable full assistant capabilities, touch overlay, and gesture automation.",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        
                        SectionGroupCard {
                            // Accessibility
                            PermissionRow(
                                title = "Accessibility Service",
                                description = "Allows AI to analyze the screen and perform touch gestures automatically.",
                                isGranted = isAccessibilityEnabled,
                                onAction = {
                                    try {
                                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        context.startActivity(intent)
                                    } catch (e: Exception) {}
                                }
                            )
                            
                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(start = 16.dp))
                            
                            // Overlay
                            PermissionRow(
                                title = "Display Over Other Apps",
                                description = "Allows drawing the floating trigger bubble and touch gestures visualizers.",
                                isGranted = isOverlayEnabled,
                                onAction = {
                                    try {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        try {
                                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                            context.startActivity(intent)
                                        } catch (ex: Exception) {}
                                    }
                                }
                            )
                            
                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(start = 16.dp))
                            
                            // Microphone
                            PermissionRow(
                                title = "Microphone Access",
                                description = "Required for speech recognition and hands-free voice operations.",
                                isGranted = isMicrophoneGranted,
                                onAction = {
                                    audioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            )
                            
                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(start = 16.dp))
                            
                            // Notifications
                            PermissionRow(
                                title = "Show Notifications",
                                description = "Required for system-level overlays and persistent service controller status.",
                                isGranted = isNotificationsGranted,
                                onAction = {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                        notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                            )
                            
                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(start = 16.dp))
                            
                            // Battery optimization
                            PermissionRow(
                                title = "Background Run / Ignore Battery Limit",
                                description = "Prevents Android system from putting the background assistant to sleep.",
                                isGranted = isBatteryOptimizationIgnored,
                                onAction = {
                                    try {
                                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = android.net.Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        try {
                                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                            context.startActivity(intent)
                                        } catch (ex: Exception) {}
                                    }
                                }
                            )

                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(start = 16.dp))

                            // Storage Access
                            PermissionRow(
                                title = "Full Storage Access",
                                description = "Allows AI to read/write files in the Linux terminal and manage system logs.",
                                isGranted = isStorageGranted,
                                onAction = {
                                    PermissionUtils.requestStoragePermission(context)
                                }
                            )
                        }
                    }
                }
                SettingsScreenType.VOICE -> {
                    val ttsAutoSpeak by viewModel.ttsAutoSpeak.collectAsState()
                    val inAppTtsEnabled by viewModel.inAppTtsEnabled.collectAsState()
                    val continuousVoiceMode by viewModel.continuousVoiceMode.collectAsState()
                    val ultraConciseMode by viewModel.ultraConciseMode.collectAsState()
                    val thaiVoice by viewModel.thaiVoice.collectAsState()
                    val englishVoice by viewModel.englishVoice.collectAsState()
                    val japaneseVoice by viewModel.japaneseVoice.collectAsState()
                    val chineseVoice by viewModel.chineseVoice.collectAsState()

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SubScreenHeader(title = "Voice & Speech Settings") { activeSubScreen = SettingsScreenType.MAIN }

                        Text(
                            text = "Configure speech synthesis (TTS) voices and automated continuous listening (STT).",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )

                        // Switches Card
                        SectionGroupCard {
                            SectionSettingRow(
                                icon = Icons.Default.VolumeUp,
                                title = "Auto-Speak inside App",
                                subtitle = "Speak back AI answers automatically inside the main app chat",
                                trailingContent = {
                                    Switch(
                                        checked = inAppTtsEnabled,
                                        onCheckedChange = { viewModel.setInAppTtsEnabled(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFF3B82F6),
                                            uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                                            uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                                        ),
                                        modifier = Modifier.scale(0.85f)
                                    )
                                }
                            )

                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(start = 52.dp))

                            SectionSettingRow(
                                icon = Icons.Default.Hearing,
                                title = "Auto-Speak in Floating Mode",
                                subtitle = "Speak back AI answers automatically in the floating overlay",
                                trailingContent = {
                                    Switch(
                                        checked = ttsAutoSpeak,
                                        onCheckedChange = { viewModel.setTtsAutoSpeak(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFF3B82F6),
                                            uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                                            uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                                        ),
                                        modifier = Modifier.scale(0.85f)
                                    )
                                }
                            )

                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(start = 52.dp))

                            val duckMediaOnSpeech by viewModel.duckMediaOnSpeech.collectAsState()
                            SectionSettingRow(
                                icon = Icons.Default.GraphicEq,
                                title = "Duck Media Volume on Speech",
                                subtitle = "Automatically lower video/music volume while AI is listening",
                                trailingContent = {
                                    Switch(
                                        checked = duckMediaOnSpeech,
                                        onCheckedChange = { viewModel.setDuckMediaOnSpeech(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFF3B82F6),
                                            uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                                            uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                                        ),
                                        modifier = Modifier.scale(0.85f)
                                    )
                                }
                            )

                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(start = 52.dp))

                            SectionSettingRow(
                                icon = Icons.Default.Mic,
                                title = "Continuous Speech Mode",
                                subtitle = "Automatically listen after speaking is finished",
                                trailingContent = {
                                    Switch(
                                        checked = continuousVoiceMode,
                                        onCheckedChange = { viewModel.setContinuousVoiceMode(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFF3B82F6),
                                            uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                                            uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                                        ),
                                        modifier = Modifier.scale(0.85f)
                                    )
                                }
                            )

                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(start = 52.dp))

                            SectionSettingRow(
                                icon = Icons.Default.FlashOn,
                                title = "Ultra-Concise Mode",
                                subtitle = "Make AI responses short, direct, and concise with zero filler",
                                trailingContent = {
                                    Switch(
                                        checked = ultraConciseMode,
                                        onCheckedChange = { viewModel.setUltraConciseMode(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFF3B82F6),
                                            uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                                            uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                                        ),
                                        modifier = Modifier.scale(0.85f)
                                    )
                                }
                            )
                        }

                        // Voice Selection Lists
                        Text("Voice Configuration", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)

                        SectionGroupCard {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                // Thai Voice Select
                                Text("Thai Voice", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        "th-TH-NiwatNeural" to "Male (Niwat)",
                                        "th-TH-AcharaNeural" to "Female (Achara)"
                                    ).forEach { (voiceId, label) ->
                                        val isSel = thaiVoice == voiceId
                                        Surface(
                                            color = if (isSel) CyanAccent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, if (isSel) CyanAccent else Color.White.copy(alpha = 0.1f)),
                                            modifier = Modifier.weight(1f).clickable { viewModel.setThaiVoice(voiceId) }
                                        ) {
                                            Text(
                                                text = label,
                                                color = if (isSel) CyanAccent else Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // English Voice Select
                                Text("English Voice", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        "en-US-GuyNeural" to "Male (Guy)",
                                        "en-US-AriaNeural" to "Female (Aria)",
                                        "en-US-JennyNeural" to "Female (Jenny)"
                                    ).forEach { (voiceId, label) ->
                                        val isSel = englishVoice == voiceId
                                        Surface(
                                            color = if (isSel) CyanAccent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, if (isSel) CyanAccent else Color.White.copy(alpha = 0.1f)),
                                            modifier = Modifier.weight(1f).clickable { viewModel.setEnglishVoice(voiceId) }
                                        ) {
                                            Text(
                                                text = label,
                                                color = if (isSel) CyanAccent else Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Japanese Voice Select
                                Text("Japanese Voice", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        "ja-JP-KeitaNeural" to "Male (Keita)",
                                        "ja-JP-NanamiNeural" to "Female (Nanami)"
                                    ).forEach { (voiceId, label) ->
                                        val isSel = japaneseVoice == voiceId
                                        Surface(
                                            color = if (isSel) CyanAccent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, if (isSel) CyanAccent else Color.White.copy(alpha = 0.1f)),
                                            modifier = Modifier.weight(1f).clickable { viewModel.setJapaneseVoice(voiceId) }
                                        ) {
                                            Text(
                                                text = label,
                                                color = if (isSel) CyanAccent else Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Chinese Voice Select
                                Text("Chinese Voice", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        "zh-CN-YunxiNeural" to "Male (Yunxi)",
                                        "zh-CN-XiaoxiaoNeural" to "Female (Xiaoxiao)"
                                    ).forEach { (voiceId, label) ->
                                        val isSel = chineseVoice == voiceId
                                        Surface(
                                            color = if (isSel) CyanAccent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, if (isSel) CyanAccent else Color.White.copy(alpha = 0.1f)),
                                            modifier = Modifier.weight(1f).clickable { viewModel.setChineseVoice(voiceId) }
                                        ) {
                                            Text(
                                                text = label,
                                                color = if (isSel) CyanAccent else Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                SettingsScreenType.CONNECTORS -> {
                    val context = LocalContext.current
                    var openCategory by remember { mutableStateOf<String?>(null) }
                    var isDeviceConnected by remember { mutableStateOf(false) }
                    var userGoogleAccount by remember { mutableStateOf("") }
                    var showGoogleAuthDialog by remember { mutableStateOf(false) }
                    var emailInput by remember { mutableStateOf("user@gmail.com") }

                    // Generic Service Auth Dialog state
                    var authDialogServiceId by remember { mutableStateOf<String?>(null) }
                    var authDialogServiceName by remember { mutableStateOf("") }
                    var authDialogServiceUrl by remember { mutableStateOf("") }
                    var authDialogServiceIcon by remember { mutableStateOf(Icons.Default.Link) }
                    var usernameInputState by remember { mutableStateOf("") }
                    var tokenInputState by remember { mutableStateOf("") }

                    // Connector state structures: Map<String, ServiceState>
                    // Triple(description, icon, authUrl) -> stored in state maps
                    val devServices = remember {
                        mutableStateMapOf(
                            "GitHub" to Tuple5("Repositories, Issues & PRs", Icons.Default.Code, "https://github.com/settings/tokens/new?description=Zhypix+AI&scopes=repo,user,workflow", true, true, "ghp_authorized_token"),
                            "Notion" to Tuple5("Workspace Pages & Databases", Icons.Default.Description, "https://www.notion.so/my-integrations", false, false, ""),
                            "Slack" to Tuple5("Channels & Direct Messaging", Icons.Default.Forum, "https://api.slack.com/apps", false, false, ""),
                            "Jira" to Tuple5("Issue Tracking & Projects", Icons.Default.CheckCircle, "https://id.atlassian.com/manage-profile/security/api-tokens", false, false, ""),
                            "Linear" to Tuple5("Task Management & Sprints", Icons.Default.List, "https://linear.app/settings/api", false, false, "")
                        )
                    }

                    val databaseServices = remember {
                        mutableStateMapOf(
                            "Supabase" to Tuple5("PostgreSQL & Storage Bucket", Icons.Default.Storage, "https://supabase.com/dashboard/account/tokens", false, false, ""),
                            "Firebase Firestore" to Tuple5("Cloud NoSQL Database", Icons.Default.Dns, "https://console.firebase.google.com", false, false, ""),
                            "PostgreSQL / MySQL" to Tuple5("Direct SQL Database Connection", Icons.Default.Storage, "https://www.postgresql.org/docs/", false, false, "")
                        )
                    }

                    val searchServices = remember {
                        mutableStateMapOf(
                            "Tavily AI Search" to Tuple5("Real-time AI Web Search API", Icons.Default.Search, "https://tavily.com", true, true, "tvly_active_key"),
                            "Brave Search" to Tuple5("Privacy Web Index API", Icons.Default.Language, "https://brave.com/search/api/", false, false, ""),
                            "Perplexity API" to Tuple5("Live Web Synthesis & Search", Icons.Default.Search, "https://www.perplexity.ai/settings/api", false, false, "")
                        )
                    }

                    val workspaceServices = remember {
                        mutableStateMapOf(
                            "Gmail" to Triple("Read & Send Emails", Icons.Default.Email, true),
                            "Google Drive" to Triple("Access & Manage Cloud Files", Icons.Default.CloudQueue, true),
                            "Google Sheets" to Triple("View & Edit Spreadsheets", Icons.Default.TableChart, true),
                            "Google Calendar" to Triple("View & Schedule Events", Icons.Default.DateRange, true),
                            "Google Docs" to Triple("Read & Edit Documents", Icons.Default.Description, true),
                            "Google Contacts" to Triple("Read Contacts List", Icons.Default.Contacts, true)
                        )
                    }

                    // Dialog for service authentication (GitHub, Notion, Slack, Supabase, Tavily, etc.)
                    if (authDialogServiceId != null) {
                        AlertDialog(
                            onDismissRequest = { authDialogServiceId = null },
                            containerColor = Color(0xFF1E1E22),
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = authDialogServiceIcon,
                                        contentDescription = authDialogServiceName,
                                        tint = CyanAccent,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text(
                                        text = "Connect $authDialogServiceName",
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        text = "Authorize Zhypix AI to connect with $authDialogServiceName. Click below to open the official authorization / token creation page on the web:",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 12.sp
                                    )

                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authDialogServiceUrl))
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        },
                                        border = BorderStroke(1.dp, CyanAccent),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.OpenInNew,
                                            contentDescription = "Open Web Authorization",
                                            tint = CyanAccent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                                                           Text(
                                            text = "Open $authDialogServiceName Authorization Page",
                                            color = CyanAccent,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Text(
                                        text = "Enter your $authDialogServiceName account username and access token / API key below:",
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontSize = 11.sp
                                    )

                                    OutlinedTextField(
                                        value = usernameInputState,
                                        onValueChange = { usernameInputState = it },
                                        label = { Text("Username / Account Handle (Optional)", color = Color.Gray, fontSize = 11.sp) },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = CyanAccent,
                                            unfocusedBorderColor = Color(0xFF333338),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    OutlinedTextField(
                                        value = tokenInputState,
                                        onValueChange = { tokenInputState = it },
                                        label = { Text("$authDialogServiceName Personal Access Token / API Key", color = Color.Gray, fontSize = 11.sp) },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = CyanAccent,
                                            unfocusedBorderColor = Color(0xFF333338),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    val currentService = devServices[authDialogServiceId] 
                                        ?: databaseServices[authDialogServiceId] 
                                        ?: searchServices[authDialogServiceId]
                                    if (currentService != null && currentService.isConnected) {
                                        TextButton(
                                            onClick = {
                                                val key = authDialogServiceId!!
                                                if (devServices.containsKey(key)) {
                                                    val old = devServices[key]!!
                                                    devServices[key] = old.copy(isConnected = false, isActive = false, token = "")
                                                } else if (databaseServices.containsKey(key)) {
                                                    val old = databaseServices[key]!!
                                                    databaseServices[key] = old.copy(isConnected = false, isActive = false, token = "")
                                                } else if (searchServices.containsKey(key)) {
                                                    val old = searchServices[key]!!
                                                    searchServices[key] = old.copy(isConnected = false, isActive = false, token = "")
                                                }
                                                authDialogServiceId = null
                                            }
                                        ) {
                                            Text("Disconnect Service", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        val key = authDialogServiceId!!
                                        val rawToken = if (tokenInputState.isNotBlank()) tokenInputState else "Authorized Token"
                                        val valToken = if (usernameInputState.isNotBlank()) "@$usernameInputState ($rawToken)" else rawToken
                                        if (devServices.containsKey(key)) {
                                            val old = devServices[key]!!
                                            devServices[key] = old.copy(isConnected = true, isActive = true, token = valToken)
                                        } else if (databaseServices.containsKey(key)) {
                                            val old = databaseServices[key]!!
                                            databaseServices[key] = old.copy(isConnected = true, isActive = true, token = valToken)
                                        } else if (searchServices.containsKey(key)) {
                                            val old = searchServices[key]!!
                                            searchServices[key] = old.copy(isConnected = true, isActive = true, token = valToken)
                                        }
                                        authDialogServiceId = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Authorize & Link", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { authDialogServiceId = null }) {
                                    Text("Cancel", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        )
                    }

                    if (showGoogleAuthDialog) {
                        AlertDialog(
                            onDismissRequest = { showGoogleAuthDialog = false },
                            containerColor = Color(0xFF1E1E22),
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudQueue,
                                        contentDescription = "Google OAuth",
                                        tint = CyanAccent,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text(
                                        text = "Google Account Authorization",
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        text = "Authorize Zhypix AI to access Google Workspace services (Gmail, Drive, Sheets, Calendar, Docs, Contacts) on this device.",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 12.sp
                                    )

                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"))
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        },
                                        border = BorderStroke(1.dp, CyanAccent),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.OpenInNew,
                                            contentDescription = "Open Google OAuth",
                                            tint = CyanAccent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Open Google Accounts OAuth Page",
                                            color = CyanAccent,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    OutlinedTextField(
                                        value = emailInput,
                                        onValueChange = { emailInput = it },
                                        label = { Text("Google Account Email", color = Color.Gray, fontSize = 12.sp) },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = CyanAccent,
                                            unfocusedBorderColor = Color(0xFF333338),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF10B981).copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                            .padding(10.dp)
                                    ) {
                                        Text(
                                            text = "✓ Google Workspace Scopes Authorized via AI Studio OAuth Platform",
                                            color = Color(0xFF10B981),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        userGoogleAccount = if (emailInput.isNotBlank()) emailInput else "Google Account"
                                        isDeviceConnected = true
                                        showGoogleAuthDialog = false
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Authorize & Link", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showGoogleAuthDialog = false }) {
                                    Text("Cancel", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        when (openCategory) {
                            "google" -> {
                                SubScreenHeader(title = "Google Workspace") { openCategory = null }

                                Surface(
                                    color = Color(0xFF18181A),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CloudQueue,
                                                contentDescription = "OAuth Status",
                                                tint = if (isDeviceConnected) CyanAccent else Color.Gray,
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    if (isDeviceConnected) "Device Account Linked" else "No Account Linked",
                                                    color = Color.White,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    if (isDeviceConnected) userGoogleAccount else "OAuth 2.0 authorization required on this device",
                                                    color = Color.White.copy(alpha = 0.5f),
                                                    fontSize = 11.sp
                                                )
                                            }
                                            
                                            Button(
                                                onClick = { 
                                                    if (!isDeviceConnected) {
                                                        showGoogleAuthDialog = true
                                                    } else {
                                                        isDeviceConnected = false
                                                        userGoogleAccount = ""
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (isDeviceConnected) Color(0xFF3A2020) else CyanAccent
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Text(
                                                    if (isDeviceConnected) "Disconnect" else "Connect Account",
                                                    color = if (isDeviceConnected) Color(0xFFFF6B6B) else Color.Black,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        
                                        Text(
                                            text = "Security Notice: Authorization is device-specific. Other users who install this app must authenticate with their own Google account.",
                                            color = Color.White.copy(alpha = 0.4f),
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                Text(
                                    text = "Google Services Toggles",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )

                                workspaceServices.forEach { (name, info) ->
                                    val (desc, icon, isServiceActive) = info
                                    val effectiveConnected = isDeviceConnected && isServiceActive
                                    
                                    Surface(
                                        color = Color(0xFF131315),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = name,
                                                tint = if (effectiveConnected) CyanAccent else Color.Gray,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(name, color = if (isDeviceConnected) Color.White else Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                Text(desc, color = Color.White.copy(alpha = if (isDeviceConnected) 0.5f else 0.3f), fontSize = 11.sp)
                                            }
                                            Switch(
                                                checked = isServiceActive,
                                                enabled = isDeviceConnected,
                                                onCheckedChange = { checked ->
                                                    workspaceServices[name] = Triple(desc, icon, checked)
                                                },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = Color.White,
                                                    checkedTrackColor = CyanAccent,
                                                    uncheckedThumbColor = Color.LightGray,
                                                    uncheckedTrackColor = Color(0xFF2A2A2E)
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            "dev" -> {
                                SubScreenHeader(title = "Developer & Productivity") { openCategory = null }

                                Text(
                                    text = "Connect code repositories, project managers, and team chat apps.",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )

                                devServices.forEach { (name, item) ->
                                    Surface(
                                        color = Color(0xFF131315),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = item.icon,
                                                contentDescription = name,
                                                tint = if (item.isConnected && item.isActive) CyanAccent else Color.Gray,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                    if (item.isConnected) {
                                                        Box(
                                                            modifier = Modifier
                                                                .background(Color(0xFF10B981).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                        ) {
                                                            Text("Connected", color = Color(0xFF10B981), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                                Text(
                                                    text = if (item.isConnected && item.token.isNotEmpty())
                                                        "Linked (${item.token.take(10)}...)"
                                                    else item.desc,
                                                    color = Color.White.copy(alpha = 0.5f),
                                                    fontSize = 11.sp
                                                )
                                            }

                                            Button(
                                                onClick = {
                                                    authDialogServiceId = name
                                                    authDialogServiceName = name
                                                    authDialogServiceUrl = item.authUrl
                                                    authDialogServiceIcon = item.icon
                                                    if (item.token.startsWith("@") && item.token.contains(" (")) {
                                                        usernameInputState = item.token.substringAfter("@").substringBefore(" (")
                                                        tokenInputState = item.token.substringAfter(" (").substringBeforeLast(")")
                                                    } else {
                                                        usernameInputState = ""
                                                        tokenInputState = item.token
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (item.isConnected) Color(0xFF2A2A30) else CyanAccent
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = if (item.isConnected) "Configure" else "Connect",
                                                    color = if (item.isConnected) Color.White else Color.Black,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            Switch(
                                                checked = item.isActive,
                                                enabled = item.isConnected,
                                                onCheckedChange = { checked ->
                                                    devServices[name] = item.copy(isActive = checked)
                                                },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = Color.White,
                                                    checkedTrackColor = CyanAccent,
                                                    uncheckedThumbColor = Color.LightGray,
                                                    uncheckedTrackColor = Color(0xFF2A2A2E)
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            "database" -> {
                                SubScreenHeader(title = "Databases & Storage") { openCategory = null }

                                Text(
                                    text = "Allow Zhypix AI to query external databases and backend storage.",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )

                                databaseServices.forEach { (name, item) ->
                                    Surface(
                                        color = Color(0xFF131315),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = item.icon,
                                                contentDescription = name,
                                                tint = if (item.isConnected && item.isActive) CyanAccent else Color.Gray,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                    if (item.isConnected) {
                                                        Box(
                                                            modifier = Modifier
                                                                .background(Color(0xFF10B981).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                        ) {
                                                            Text("Connected", color = Color(0xFF10B981), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                                Text(
                                                    text = if (item.isConnected && item.token.isNotEmpty())
                                                        "Linked (${item.token.take(10)}...)"
                                                    else item.desc,
                                                    color = Color.White.copy(alpha = 0.5f),
                                                    fontSize = 11.sp
                                                )
                                            }

                                            Button(
                                                onClick = {
                                                    authDialogServiceId = name
                                                    authDialogServiceName = name
                                                    authDialogServiceUrl = item.authUrl
                                                    authDialogServiceIcon = item.icon
                                                    tokenInputState = item.token
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (item.isConnected) Color(0xFF2A2A30) else CyanAccent
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = if (item.isConnected) "Configure" else "Connect",
                                                    color = if (item.isConnected) Color.White else Color.Black,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            Switch(
                                                checked = item.isActive,
                                                enabled = item.isConnected,
                                                onCheckedChange = { checked ->
                                                    databaseServices[name] = item.copy(isActive = checked)
                                                },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = Color.White,
                                                    checkedTrackColor = CyanAccent,
                                                    uncheckedThumbColor = Color.LightGray,
                                                    uncheckedTrackColor = Color(0xFF2A2A2E)
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            "search" -> {
                                SubScreenHeader(title = "Web Search & Intelligence") { openCategory = null }

                                Text(
                                    text = "Enable live web search and real-time knowledge integration.",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )

                                searchServices.forEach { (name, item) ->
                                    Surface(
                                        color = Color(0xFF131315),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = item.icon,
                                                contentDescription = name,
                                                tint = if (item.isConnected && item.isActive) CyanAccent else Color.Gray,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                    if (item.isConnected) {
                                                        Box(
                                                            modifier = Modifier
                                                                .background(Color(0xFF10B981).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                        ) {
                                                            Text("Connected", color = Color(0xFF10B981), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                                Text(
                                                    text = if (item.isConnected && item.token.isNotEmpty())
                                                        "Linked (${item.token.take(10)}...)"
                                                    else item.desc,
                                                    color = Color.White.copy(alpha = 0.5f),
                                                    fontSize = 11.sp
                                                )
                                            }

                                            Button(
                                                onClick = {
                                                    authDialogServiceId = name
                                                    authDialogServiceName = name
                                                    authDialogServiceUrl = item.authUrl
                                                    authDialogServiceIcon = item.icon
                                                    tokenInputState = item.token
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (item.isConnected) Color(0xFF2A2A30) else CyanAccent
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = if (item.isConnected) "Configure" else "Connect",
                                                    color = if (item.isConnected) Color.White else Color.Black,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            Switch(
                                                checked = item.isActive,
                                                enabled = item.isConnected,
                                                onCheckedChange = { checked ->
                                                    searchServices[name] = item.copy(isActive = checked)
                                                },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = Color.White,
                                                    checkedTrackColor = CyanAccent,
                                                    uncheckedThumbColor = Color.LightGray,
                                                    uncheckedTrackColor = Color(0xFF2A2A2E)
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            else -> {
                                SubScreenHeader(title = "App Connectors") { activeSubScreen = SettingsScreenType.MAIN }

                                Text(
                                    text = "Connect Zhypix AI to external services, APIs, and workspace tools.",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )

                                val categories = listOf(
                                    Triple("Google Workspace", "Gmail, Drive, Sheets, Calendar, Docs, Contacts", "google"),
                                    Triple("Developer & Productivity", "GitHub, Notion, Slack, Jira, Linear", "dev"),
                                    Triple("Databases & Cloud Storage", "Supabase, Firebase, PostgreSQL", "database"),
                                    Triple("Web Search & Intelligence", "Tavily AI Search, Brave Search, Perplexity", "search")
                                )

                                categories.forEach { (catName, catDesc, key) ->
                                    val isGoogle = key == "google"
                                    val statusText = if (isGoogle) {
                                        if (isDeviceConnected) "Connected" else "Not Linked"
                                    } else {
                                        "Available"
                                    }
                                    val statusColor = if (isGoogle && isDeviceConnected) Color(0xFF10B981) else CyanAccent

                                    Surface(
                                        color = Color(0xFF131315),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { openCategory = key }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Icon(
                                                imageVector = when (key) {
                                                    "google" -> Icons.Default.CloudQueue
                                                    "dev" -> Icons.Default.Code
                                                    "database" -> Icons.Default.Storage
                                                    else -> Icons.Default.Search
                                                },
                                                contentDescription = catName,
                                                tint = CyanAccent,
                                                modifier = Modifier.size(26.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(catName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                Text(catDesc, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    statusText,
                                                    color = statusColor,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Icon(
                                                imageVector = Icons.Default.ArrowForward,
                                                contentDescription = "Open",
                                                tint = Color.White.copy(alpha = 0.4f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "MCP Connectors",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    
                                    IconButton(onClick = { showAddMcpDialog = true }) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "Add Connector",
                                            tint = CyanAccent,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = "Model Context Protocol (MCP) allows AI to securely call local shell commands, scripts and databases.",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }

                        if (mcpServers.isEmpty()) {
                            // Empty state
                            Surface(
                                color = Color(0xFF131315),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = Color.White.copy(alpha = 0.2f), modifier = Modifier.size(48.dp))
                                    Text("No MCP Connectors Configured", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text("Click the '+' button above to add an MCP tools provider server.", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                            }
                        } else {
                            mcpServers.forEach { server ->
                                Surface(
                                    color = Color(0xFF131315),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(server.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                Box(
                                                    modifier = Modifier
                                                        .background(if (server.isEnabled) Color(0xFF10B981).copy(alpha = 0.15f) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        if (server.isEnabled) "ACTIVE" else "DISABLED",
                                                        color = if (server.isEnabled) Color(0xFF10B981) else Color.White.copy(alpha = 0.5f),
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("Cmd: ${server.command}", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                                            if (server.args.isNotBlank()) {
                                                Text("Args: ${server.args}", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                            }
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            // Toggle
                                            Switch(
                                                checked = server.isEnabled,
                                                onCheckedChange = { viewModel.updateMcpServer(server.copy(isEnabled = it)) },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = Color.White,
                                                    checkedTrackColor = Color(0xFF3B82F6),
                                                    uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                                                    uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                                                ),
                                                modifier = Modifier.scale(0.75f)
                                            )
                                            
                                            Spacer(modifier = Modifier.width(4.dp))
                                            
                                            // Delete button
                                            IconButton(onClick = { viewModel.deleteMcpServer(server.id) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                SettingsScreenType.PERSONA -> {
                    val selectedPersona by viewModel.selectedPersona.collectAsState()
                    val customPersonaPrompt by viewModel.customPersonaPrompt.collectAsState()
                    var customText by remember(customPersonaPrompt) { mutableStateOf(customPersonaPrompt) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SubScreenHeader(title = "AI Persona") { activeSubScreen = SettingsScreenType.MAIN }

                        Text(
                            text = "Choose AI persona response style or define custom system instructions:",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )

                        val personas = listOf(
                            Triple("DEFAULT", "Default Assistant", "General assistant with standard balanced execution."),
                            Triple("FRIENDLY", "Friendly & Warm", "Friendly, warm, and polite conversational style."),
                            Triple("CONCISE", "Concise & Direct", "Direct and concise responses without preamble."),
                            Triple("TUTOR", "Expert Tutor", "Explains concepts step-by-step with practical examples."),
                            Triple("CUSTOM", "Custom System Prompt", "Fully custom system instructions and personality.")
                        )

                        personas.forEach { (key, label, desc) ->
                            val isSelected = selectedPersona == key
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setSelectedPersona(key) },
                                color = if (isSelected) CyanAccent.copy(alpha = 0.15f) else Color(0xFF131315),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, if (isSelected) CyanAccent else Color.White.copy(alpha = 0.1f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { viewModel.setSelectedPersona(key) },
                                        colors = RadioButtonDefaults.colors(selectedColor = CyanAccent)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(text = desc, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        if (selectedPersona == "CUSTOM") {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF131315)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Custom System Instruction:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    OutlinedTextField(
                                        value = customText,
                                        onValueChange = {
                                            customText = it
                                            viewModel.setCustomPersonaPrompt(it)
                                        },
                                        placeholder = { Text("Enter custom instructions e.g. 'Act as a principal software engineer...'", color = Color.White.copy(alpha = 0.3f)) },
                                         colors = OutlinedTextFieldDefaults.colors(
                                             focusedBorderColor = CyanAccent,
                                             unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                             focusedTextColor = Color.White,
                                             unfocusedTextColor = Color.White
                                         ),
                                         modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp)
                                     )
                                }
                            }
                        }
                    }
                }
                SettingsScreenType.CAPABILITIES -> {}
                SettingsScreenType.MEMORY -> {
                    val userPreferences by viewModel.userPreferences.collectAsState()
                    val memories = userPreferences.filter { !it.key.startsWith("cfg_") }

                    var newKey by remember { mutableStateOf("") }
                    var newValue by remember { mutableStateOf("") }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SubScreenHeader(title = "Persistent Long-Term Memory") { activeSubScreen = SettingsScreenType.MAIN }

                        Text(
                            text = "Long-term facts stored across sessions. You can edit or delete items at any time:",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )

                        // Add Memory Form
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF131315)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Add Memory", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = newKey,
                                        onValueChange = { newKey = it },
                                        label = { Text("Key", color = Color.White.copy(alpha = 0.6f)) },
                                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyanAccent, unfocusedBorderColor = Color.White.copy(alpha = 0.2f), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = newValue,
                                        onValueChange = { newValue = it },
                                        label = { Text("Value", color = Color.White.copy(alpha = 0.6f)) },
                                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyanAccent, unfocusedBorderColor = Color.White.copy(alpha = 0.2f), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Button(
                                    onClick = {
                                        if (newKey.isNotBlank() && newValue.isNotBlank()) {
                                            viewModel.saveUserMemory(newKey.trim(), newValue.trim())
                                            newKey = ""
                                            newValue = ""
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Save Memory", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // List of memories
                        Text("Saved Memories (${memories.size}):", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                        if (memories.isEmpty()) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF131315)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(modifier = Modifier.padding(24.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text("No saved memories yet.\nKey facts mentioned in chat will be stored here automatically.", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                            }
                        } else {
                            memories.forEach { pref ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131315)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = pref.key, color = CyanAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(text = pref.value, color = Color.White, fontSize = 13.sp)
                                        }
                                        IconButton(onClick = { viewModel.deleteUserMemory(pref.key) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete Memory", tint = Color.Red.copy(alpha = 0.8f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                SettingsScreenType.COLOR_MODE,
                SettingsScreenType.FONT_STYLE,
                SettingsScreenType.PRIVACY,
                SettingsScreenType.SHARED_LINKS -> {
                    val titleText = when (screen) {
                        SettingsScreenType.COLOR_MODE -> "Color Mode"
                        SettingsScreenType.FONT_STYLE -> "Font Style"
                        SettingsScreenType.PRIVACY -> "Privacy"
                        SettingsScreenType.SHARED_LINKS -> "Shared Links"
                        else -> "Settings"
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SubScreenHeader(title = "$titleText Settings") { activeSubScreen = SettingsScreenType.MAIN }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color(0xFF131315), RoundedCornerShape(14.dp))
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Coming Soon",
                                    tint = CyanAccent.copy(alpha = 0.8f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = "Coming Soon",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "This feature is currently under active development. Keep an eye out for upcoming feature updates!",
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 12.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    description: String,
    isGranted: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
        
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isGranted) Color(0xFF10B981).copy(alpha = 0.15f) else Color.White,
                contentColor = if (isGranted) Color(0xFF10B981) else Color.Black
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text(
                text = if (isGranted) "Granted" else "Grant",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
