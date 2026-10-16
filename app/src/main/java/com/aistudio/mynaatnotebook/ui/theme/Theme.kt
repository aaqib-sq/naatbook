package com.aistudio.mynaatnotebook.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * Theme-aware secondary text color. Resolves to [HighContrastGray] in dark
 * theme and [HighContrastGrayLight] in light theme (the dark value alone
 * failed WCAG AA small-text contrast on white).
 */
private val LocalSecondaryText = staticCompositionLocalOf { HighContrastGray }

@Composable
fun secondaryTextColor(): Color = LocalSecondaryText.current

private val DarkColorScheme = darkColorScheme(
    primary = DarkText,
    onPrimary = DarkBg,
    secondary = DarkAccent,
    onSecondary = DarkBg,
    background = DarkBg,
    onBackground = DarkText,
    surface = DarkBg,
    onSurface = DarkText,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkAccent,
    outline = DarkBorder
)

private val LightColorScheme = lightColorScheme(
    primary = LightText,
    onPrimary = LightBg,
    secondary = LightAccent,
    onSecondary = LightBg,
    background = LightBg,
    onBackground = LightText,
    surface = LightBg,
    onSurface = LightText,
    surfaceVariant = LightSurface,
    onSurfaceVariant = LightAccent,
    outline = LightBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(
        LocalSecondaryText provides if (darkTheme) HighContrastGray else HighContrastGrayLight
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
