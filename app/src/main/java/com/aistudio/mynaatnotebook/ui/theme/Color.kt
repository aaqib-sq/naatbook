package com.aistudio.mynaatnotebook.ui.theme

import androidx.compose.ui.graphics.Color

// Light Colors (Total White Mode)
val LightBg = Color(0xFFFFFFFF)
val LightText = Color(0xFF000000)
val LightAccent = Color(0xFF121212)
val LightSurface = Color(0xFFF8F8F8)
val LightBorder = Color(0xFFE5E5E5)

// Container steps (audit 04): Material3's default surfaceContainer* and
// inverseSurface roles carry a purple/warm-grey tint that broke the
// monochrome identity in dialogs, menus and the snackbar. These neutral
// greys are the theme's own container ladder in both modes.
val LightContainerLow = Color(0xFFFAFAFA)
val LightContainer = Color(0xFFF1F1F1)
val LightContainerHigh = Color(0xFFE9E9E9)
val LightContainerHighest = Color(0xFFE0E0E0)
val DarkContainerLow = Color(0xFF0A0A0A)
val DarkContainerHigh = Color(0xFF1A1A1A)
val DarkContainerHighest = Color(0xFF242424)
val DarkBright = Color(0xFF2A2A2A)

// Dark Colors (Total Black OLED Friendly Mode)
val DarkBg = Color(0xFF000000)
val DarkText = Color(0xFFFFFFFF)
val DarkAccent = Color(0xFFE5E5E5)
val DarkSurface = Color(0xFF121212)
val DarkBorder = Color(0xFF1F1F1F)

// Favorite tint and highlight
val HighContrastRed = Color(0xFFFF3B30)

// Secondary text: theme-aware. The single dark value (#8E8E93) passed on
// black but failed AA on white, so light theme gets a darker step while
// dark keeps the original. Use secondaryTextColor() at call sites.
val HighContrastGray = Color(0xFF8E8E93)
val HighContrastGrayLight = Color(0xFF636366)
