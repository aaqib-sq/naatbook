package com.aistudio.mynaatnotebook.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Audit fix (stage 1): a backup-declared font `name` goes straight into the
 * registry and the picker UI, so [CustomFontStore.sanitizeRestoredFontName]
 * must strip control characters, cap the length, and never return blank.
 */
class FontNameSanitizeTest {

    @Test
    fun `control characters are stripped`() {
        assertEquals(
            "MyFont",
            CustomFontStore.sanitizeRestoredFontName("My\u0000Font\u0007\u001B")
        )
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(
            "Noto Nastaliq",
            CustomFontStore.sanitizeRestoredFontName("   Noto Nastaliq  ")
        )
    }

    @Test
    fun `names are capped at sixty characters`() {
        val sanitized = CustomFontStore.sanitizeRestoredFontName("A".repeat(200))
        assertEquals(CustomFontStore.MAX_FONT_NAME_LENGTH, sanitized.length)
        assertEquals(60, sanitized.length)
    }

    @Test
    fun `blank after cleaning falls back to My Font`() {
        assertEquals("My Font", CustomFontStore.sanitizeRestoredFontName(" \u0000\u0007 "))
    }

    @Test
    fun `normal names pass through untouched`() {
        assertEquals(
            "Jameel Noori Nastaleeq",
            CustomFontStore.sanitizeRestoredFontName("Jameel Noori Nastaleeq")
        )
    }
}
