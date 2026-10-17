package com.aistudio.mynaatnotebook.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aistudio.mynaatnotebook.R

// Typography with letterSpacing pinned to ZERO on every style.
//
// Material3 baselines carry tracking (0.1–0.5 sp) on most styles. Compose
// applies letterSpacing as advance between glyphs, which stops the shaping
// engine from JOINING Arabic-script letters — any Urdu/Arabic string set in
// a tracked style renders with broken joins. The app is bilingual by
// design, so no style may carry tracking; sizes and line heights stay at
// the Material3 baselines. (Root fix for the recurring letterSpacing
// findings — reader audit 2026-10-17 caught the reader header title at
// titleMedium 0.15 sp and the poet line at bodySmall 0.4 sp.)
private val baselineTypography = Typography()

private fun TextStyle.untracked(): TextStyle = copy(letterSpacing = 0.sp)

val Typography =
  Typography(
    displayLarge = baselineTypography.displayLarge.untracked(),
    displayMedium = baselineTypography.displayMedium.untracked(),
    displaySmall = baselineTypography.displaySmall.untracked(),
    headlineLarge = baselineTypography.headlineLarge.untracked(),
    headlineMedium = baselineTypography.headlineMedium.untracked(),
    headlineSmall = baselineTypography.headlineSmall.untracked(),
    titleLarge = baselineTypography.titleLarge.untracked(),
    titleMedium = baselineTypography.titleMedium.untracked(),
    titleSmall = baselineTypography.titleSmall.untracked(),
    bodyLarge =
      TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
      ),
    bodyMedium = baselineTypography.bodyMedium.untracked(),
    bodySmall = baselineTypography.bodySmall.untracked(),
    labelLarge = baselineTypography.labelLarge.untracked(),
    labelMedium = baselineTypography.labelMedium.untracked(),
    labelSmall = baselineTypography.labelSmall.untracked(),
  )

/**
 * Bundled Noto Nastaliq Urdu (SIL Open Font License, downloaded from the
 * google/fonts repository). It is the authentic script for Urdu lyrics and is
 * applied automatically wherever a text contains Arabic-script characters.
 * Bundled in res/font on purpose: the app must stay 100% offline, so no
 * downloadable-fonts / Play-Services font fetch is ever used.
 */
val NastaliqFamily = FontFamily(
    Font(R.font.noto_nastaliq_urdu, FontWeight.Normal)
)
