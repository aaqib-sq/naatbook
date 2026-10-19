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
    outline = DarkBorder,
    // Audit 04 H1/M1/M2: pin every tonal role Material3 would otherwise
    // fill with its purple-grey defaults (dialogs = surfaceContainerHigh,
    // menus = surfaceContainer, snackbar/hint = inverseSurface).
    surfaceContainerLowest = DarkBg,
    surfaceContainerLow = DarkContainerLow,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkContainerHigh,
    surfaceContainerHighest = DarkContainerHighest,
    surfaceDim = DarkBg,
    surfaceBright = DarkBright,
    inverseSurface = DarkAccent,
    inverseOnSurface = DarkSurface,
    inversePrimary = DarkBg
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
    outline = LightBorder,
    // Audit 04 H1/M1/M2: same pinning as the dark scheme — neutral greys
    // only, so dialogs, menus and the snackbar stay monochrome.
    surfaceContainerLowest = LightBg,
    surfaceContainerLow = LightContainerLow,
    surfaceContainer = LightContainer,
    surfaceContainerHigh = LightContainerHigh,
    surfaceContainerHighest = LightContainerHighest,
    surfaceDim = LightContainerHighest,
    surfaceBright = LightBg,
    inverseSurface = LightAccent,
    inverseOnSurface = LightBg,
    inversePrimary = LightBg
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
