package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Zhypix "Obsidian" palette
//
// Brand identity is a black & white isometric cube, so the core UI stays
// monochrome: a layered zinc ramp on near-black with pure white as the
// interactive accent. Semantic status colors (success / warning / error /
// info) are the ONLY hues in the app and are reserved for communicating
// state, never decoration.
// ---------------------------------------------------------------------------

// Base surfaces (dark-first design)
val ObsidianBase = Color(0xFF050506) // deepest app background
val BackgroundGradientStart = Color(0xFF0B0B0E) // top of the app background gradient
val BackgroundGradientEnd = Color(0xFF050506) // bottom of the app background gradient
val SurfaceDark = Color(0xFF121215) // primary elevated card surface
val SurfaceElevated = Color(0xFF1A1A1E) // sheets, menus, raised cards
val SurfaceOverlay = Color(0xFF232328) // highest elevation (dialogs, islands)

// Hairlines and borders
val SurfaceLightBorder = Color(0xFF26262B) // default hairline on dark surfaces
val BorderStrong = Color(0xFF3A3A41) // hover/selected border
val CardBorderColor = Color(0xFF26262B)

// Text ramp
val TextPrimary = Color(0xFFFAFAFA) // high-contrast primary text
val TextSecondary = Color(0xFFA1A1AA) // secondary text and icons
val TextTertiary = Color(0xFF6E6E76) // captions, timestamps, hints

// Brand accent: pure white (kept under the legacy name used across the app)
val CyanAccent = Color(0xFFFFFFFF)
val CyanAccentGlow = Color(0xFFA1A1AA)
val Accent = Color(0xFFFFFFFF)
val AccentDim = Color(0xFFE4E4E7)
val OnAccent = Color(0xFF000000)

// Semantic status colors - the only hues in the app
val SuccessGreen = Color(0xFF34D399) // running / active / completed
val OrangeWarning = Color(0xFFFBBF24) // warnings, attention needed
val CrimsonRose = Color(0xFFFB7185) // errors, destructive actions
val InfoBlue = Color(0xFF60A5FA) // informational highlights
val VioletAccent = Color(0xFFA78BFA) // rare tertiary highlight

// Legacy Material template colors (kept for compatibility)
val Purple80 = Color(0xFFFFFFFF)
val PurpleGrey80 = Color(0xFFE5E5EA)
val Pink80 = Color(0xFF86868B)
val Purple40 = Color(0xFF000000)
val PurpleGrey40 = Color(0xFF1C1C1E)
val Pink40 = Color(0xFF8E8E93)
