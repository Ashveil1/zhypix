package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = SurfaceElevated,
    onPrimaryContainer = TextPrimary,
    secondary = AccentDim,
    onSecondary = OnAccent,
    secondaryContainer = SurfaceElevated,
    onSecondaryContainer = TextPrimary,
    tertiary = TextSecondary,
    onTertiary = OnAccent,
    background = ObsidianBase,
    onBackground = TextPrimary,
    surface = BackgroundGradientStart,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFF16161A),
    onSurfaceVariant = Color(0xFFD4D4D8),
    surfaceContainerLowest = ObsidianBase,
    surfaceContainerLow = Color(0xFF0E0E11),
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceElevated,
    surfaceContainerHighest = SurfaceOverlay,
    outline = SurfaceLightBorder,
    outlineVariant = Color(0xFF1B1B1F),
    error = CrimsonRose,
    onError = Color(0xFF1A0508),
    errorContainer = Color(0xFF3D141B),
    onErrorContainer = Color(0xFFFFD9DE),
    inverseSurface = TextPrimary,
    inverseOnSurface = Color(0xFF17171A),
    inversePrimary = Color(0xFF3F3F46),
    scrim = Color.Black,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E4E7),
    onPrimaryContainer = Color(0xFF111113),
    secondary = Color(0xFF1C1C1E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E4E7),
    onSecondaryContainer = Color(0xFF111113),
    tertiary = Color(0xFF52525B),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF111113),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111113),
    surfaceVariant = Color(0xFFF0F0F2),
    onSurfaceVariant = Color(0xFF3F3F46),
    outline = Color(0xFFD4D4D8),
    outlineVariant = Color(0xFFE9E9EC),
    error = Color(0xFFE11D48),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF4C0519),
    inverseSurface = Color(0xFF1C1C1E),
    inverseOnSurface = Color(0xFFF5F5F7),
    inversePrimary = Color(0xFFE4E4E7),
    scrim = Color.Black,
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

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    shapes = ZhypixShapes,
    content = content,
  )
}
