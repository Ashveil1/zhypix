package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.zhypix.R
import androidx.compose.ui.unit.sp
import com.example.db.ChatSession
import com.example.model.*
import com.example.ui.theme.*
import com.example.viewmodel.AgentViewModel

@Composable
fun AppDrawerContent(
    viewModel: AgentViewModel,
    onCloseDrawer: () -> Unit,
    onShowSettings: () -> Unit,
    onShowTerminal: () -> Unit,
    onDeleteSession: (ChatSession) -> Unit
) {
    val chatSessions by viewModel.chatSessions.collectAsState()
    val currentSessionId by viewModel.currentSessionIdFlow.collectAsState()

    ModalDrawerSheet(
        drawerContainerColor = SurfaceDark,
        drawerContentColor = TextPrimary,
        modifier = Modifier.width(320.dp)
    ) {
        // Header with clean vector branding
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.5f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        IsometricCube(
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(14.dp))
                
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Zhypix",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = BackgroundGradientStart, modifier = Modifier.padding(bottom = 8.dp))

        // New Chat Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .clickable {
                    viewModel.startNewSession()
                    onCloseDrawer()
                }
                .padding(vertical = 11.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("New Conversation", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        // Sessions List
        Text(
            text = "Recent Conversations",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TextSecondary.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp)
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(chatSessions, key = { it.id }) { session ->
                val isSelected = currentSessionId == session.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) Color.White.copy(alpha = 0.06f) else Color.Transparent)
                        .clickable {
                            viewModel.loadSession(session.id)
                            onCloseDrawer()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Chat,
                        contentDescription = null,
                        tint = if (isSelected) TextPrimary else TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = session.title,
                        color = if (isSelected) TextPrimary else TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        IconButton(
                            onClick = { onDeleteSession(session) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = TextSecondary.copy(alpha = 0.4f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = BackgroundGradientStart)

        // Controls Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Controls",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
            )

            // Settings
            DrawerActionButton(
                icon = Icons.Default.Settings,
                text = "Settings",
                onClick = {
                    onShowSettings()
                    onCloseDrawer()
                }
            )

            // Linux Emulator
            val isTerminalActive by com.example.utils.LinuxTerminalSimulator.isInstalled.collectAsState()
            DrawerActionButton(
                icon = Icons.Default.Computer,
                text = "Linux Emulator",
                isActive = isTerminalActive,
                activeColor = SuccessGreen,
                onClick = {
                    onShowTerminal()
                    onCloseDrawer()
                },
                badge = if (isTerminalActive) "ACTIVE" else "OFFLINE"
            )
        }

        HorizontalDivider(color = BackgroundGradientStart)

        // Footer Profile
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(Color.White.copy(alpha = 0.02f), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .clickable {
                    onShowSettings()
                    onCloseDrawer()
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Color.Black, CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "U", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("My Workspace", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Icon(Icons.Default.Settings, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DrawerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    isActive: Boolean = false,
    activeColor: Color = CyanAccent,
    onClick: () -> Unit,
    badge: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) activeColor.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.02f))
            .border(1.dp, if (isActive) activeColor.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(if (isActive) activeColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.04f), CircleShape)
                .border(1.dp, if (isActive) activeColor.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = if (isActive) activeColor else TextPrimary, modifier = Modifier.size(14.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (badge != null) {
            Box(
                modifier = Modifier
                    .background(if (isActive) activeColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = badge,
                    color = if (isActive) activeColor else TextSecondary.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
    }
}
