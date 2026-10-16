package com.aistudio.mynaatnotebook.ui.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * Font choice keys shared by Settings, the reader, and title rendering.
 * A choice is either a built-in key below or "custom:<id>" pointing at a
 * user-imported font (see CustomFontStore). Resolution is centralized here
 * so every surface agrees on what a stored choice means, and a missing
 * custom font always falls back to the pre-choice behavior.
 */
object FontChoices {
    const val AUTO = "auto"
    const val SERIF = "serif"
    const val SANS = "sans"
    const val NASTALIQ = "nastaliq"

    private const val CUSTOM_PREFIX = "custom:"

    fun customKey(id: String): String = CUSTOM_PREFIX + id

    fun customId(choice: String): String? =
        choice.takeIf { it.startsWith(CUSTOM_PREFIX) }?.removePrefix(CUSTOM_PREFIX)

    val READER_KEYS = listOf(AUTO, SERIF, SANS, NASTALIQ)
    val TITLE_KEYS = listOf(SANS, SERIF, NASTALIQ)

    /** Reader lyrics: AUTO keeps the original rule (Nastaliq for Urdu script, Serif otherwise). */
    fun resolveReader(
        choice: String,
        urduScript: Boolean,
        customFamilies: Map<String, FontFamily>
    ): FontFamily = when {
        choice == AUTO -> if (urduScript) NastaliqFamily else FontFamily.Serif
        choice == SERIF -> FontFamily.Serif
        choice == SANS -> FontFamily.SansSerif
        choice == NASTALIQ -> NastaliqFamily
        else -> customId(choice)?.let { customFamilies[it] }
            ?: if (urduScript) NastaliqFamily else FontFamily.Serif
    }

    /** Entry titles: default is the plain system sans. */
    fun resolveTitle(
        choice: String,
        customFamilies: Map<String, FontFamily>
    ): FontFamily = when (choice) {
        SERIF -> FontFamily.Serif
        NASTALIQ -> NastaliqFamily
        SANS -> FontFamily.SansSerif
        else -> customId(choice)?.let { customFamilies[it] } ?: FontFamily.SansSerif
    }

    /** Built-in family for previews/picker samples of a non-custom key. */
    fun builtinFamily(key: String): FontFamily = when (key) {
        SERIF -> FontFamily.Serif
        NASTALIQ -> NastaliqFamily
        else -> FontFamily.SansSerif
    }

    /**
     * Per-entry reader-font precedence: the entry's own override wins;
     * null means "follow the global Settings reader font".
     */
    fun effectiveReaderChoice(override: String?, global: String): String =
        override ?: global
}
