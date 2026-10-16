package com.aistudio.mynaatnotebook.ui.theme

import androidx.compose.ui.graphics.Color

// Light Colors (Total White Mode)
val LightBg = Color(0xFFFFFFFF)
val LightText = Color(0xFF000000)
val LightAccent = Color(0xFF121212)
val LightSurface = Color(0xFFF8F8F8)
val LightBorder = Color(0xFFE5E5E5)

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
