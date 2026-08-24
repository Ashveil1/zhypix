package com.example

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.ui.MainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.model.*
import com.example.viewmodel.AgentViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val viewModel: AgentViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    
    // Initialize terminal simulator
    com.example.utils.LinuxTerminalSimulator.initialize(this)
    
    // Create system notification channel
    com.example.utils.SystemNotificationHelper.createNotificationChannel(this)

    // Request notification permission on Android 13+
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
      }
    }
    
    // Process incoming assist intents on app startup
    handleAssistIntent(intent)
    handleSettingsIntent(intent)
    
    // Auto-minimize when AI starts ACTING to let user see launcher/behind screens
    lifecycleScope.launch {
      viewModel.agentState.collectLatest { state ->
        if (state == AgentState.ACTING) {
          Log.d("Zhypix", "AI is starting screen control. Minimizing main screen with beautiful native OS transitions.")
          
          // Auto-minimize when AI starts ACTING to let user see launcher/behind screens
          // Only minimize if we can draw overlays so the user still has feedback via the floating head
          try {
            if (android.provider.Settings.canDrawOverlays(this@MainActivity)) {
              val intent = Intent(this@MainActivity, com.example.service.FloatingAgentService::class.java)
              startService(intent)
              
              // Native OS minimization - puts our app perfectly in background so underlying system is visible
              moveTaskToBack(true)
            } else {
              Log.w("Zhypix", "Cannot draw overlays, not minimizing.")
            }
          } catch (e: Exception) {
            Log.e("Zhypix", "Could not start floating service", e)
          }
        }
      }
    }

    setContent {
      MyApplicationTheme {
        MainScreen(viewModel = viewModel)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleAssistIntent(intent)
    handleSettingsIntent(intent)
  }

  private fun handleSettingsIntent(intent: Intent?) {
    val subScreen = intent?.getStringExtra("OPEN_SETTINGS_SUBSCREEN")
    if (subScreen == "PROFILE") {
      viewModel.openSettings(SettingsScreenType.PROFILE)
    }
  }

  private fun handleAssistIntent(intent: Intent?) {
    if (intent != null && intent.action == Intent.ACTION_ASSIST) {
      Log.d("Zhypix", "Received standard system ASSIST intent. Triggering floating assistant.")
      try {
        if (android.provider.Settings.canDrawOverlays(this)) {
          val serviceIntent = Intent(this, com.example.service.FloatingAgentService::class.java).apply {
            action = "ACTION_EXPAND_INPUT"
          }
          startService(serviceIntent)
          
          // Move task to back instantly to overlay seamlessly on top of their active screen
          moveTaskToBack(true)
        } else {
          Log.w("Zhypix", "Missing Display Over Other Apps permission for Assist launch.")
        }
      } catch (e: Exception) {
        Log.e("Zhypix", "Could not handle assist intent", e)
      }
    }
  }
}

