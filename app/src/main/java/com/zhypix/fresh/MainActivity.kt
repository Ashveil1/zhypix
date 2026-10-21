package com.zhypix.fresh

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.zhypix.fresh.ui.AgentViewModel
import com.zhypix.fresh.ui.ChatScreen
import com.zhypix.fresh.ui.ProvidersScreen

class MainActivity : ComponentActivity() {

    private var showProviders by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = ViewModelProvider(this)[AgentViewModel::class.java]
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    if (showProviders) {
                        ProvidersScreen(vm = vm, onBack = { showProviders = false })
                    } else {
                        ChatScreen(
                            vm = vm,
                            onOpenProviders = { showProviders = true },
                            onOpenAccessibilitySettings = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        )
                    }
                }
            }
        }
    }
}
