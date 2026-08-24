package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = androidx.compose.ui.graphics.Color.White,
    onPrimary = androidx.compose.ui.graphics.Color.Black,
    secondary = androidx.compose.ui.graphics.Color(0xFFE5E5EA),
    onSecondary = androidx.compose.ui.graphics.Color.Black,
    tertiary = androidx.compose.ui.graphics.Color(0xFF86868B),
    background = androidx.compose.ui.graphics.Color(0xFF000000),
    onBackground = androidx.compose.ui.graphics.Color(0xFFF5F5F7),
    surface = androidx.compose.ui.graphics.Color(0xFF161618),
    onSurface = androidx.compose.ui.graphics.Color(0xFFF5F5F7),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFE5E5EA),
    outline = androidx.compose.ui.graphics.Color(0xFF27272A)
  )

private val LightColorScheme =
  lightColorScheme(
    primary = androidx.compose.ui.graphics.Color.Black,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
    onSecondary = androidx.compose.ui.graphics.Color.White,
    tertiary = androidx.compose.ui.graphics.Color(0xFF8E8E93),
    background = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    onBackground = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
    surface = androidx.compose.ui.graphics.Color(0xFFF2F2F7),
    onSurface = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE5E5EA),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
    outline = androidx.compose.ui.graphics.Color(0xFFD1D1D6)
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
