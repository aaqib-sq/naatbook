package com.aistudio.mynaatnotebook.ui.theme

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FontChoicesOverrideTest {

    @Test
    fun `override beats the global reader font`() {
        assertEquals(
            FontChoices.NASTALIQ,
            FontChoices.effectiveReaderChoice(FontChoices.NASTALIQ, FontChoices.SERIF)
        )
    }

    @Test
    fun `null override falls back to the global reader font`() {
        assertEquals(
            FontChoices.SERIF,
            FontChoices.effectiveReaderChoice(null, FontChoices.SERIF)
        )
    }

    @Test
    fun `auto override keeps the Nastaliq-for-Urdu script rule`() {
        assertEquals(
            FontChoices.effectiveReaderChoice(FontChoices.AUTO, FontChoices.SANS),
            FontChoices.AUTO
        )
        assertSame(
            NastaliqFamily,
            FontChoices.resolveReader(
                FontChoices.effectiveReaderChoice(FontChoices.AUTO, FontChoices.SANS),
                urduScript = true,
                customFamilies = emptyMap()
            )
        )
        assertSame(
            FontFamily.Serif,
            FontChoices.resolveReader(
                FontChoices.effectiveReaderChoice(FontChoices.AUTO, FontChoices.SANS),
                urduScript = false,
                customFamilies = emptyMap()
            )
        )
    }

    @Test
    fun `dangling custom override falls back to the script default like follow-settings`() {
        val dangling = FontChoices.customKey("gone-font-id")
        assertSame(
            NastaliqFamily,
            FontChoices.resolveReader(dangling, urduScript = true, customFamilies = emptyMap())
        )
        assertSame(
            FontFamily.Serif,
            FontChoices.resolveReader(dangling, urduScript = false, customFamilies = emptyMap())
        )
    }

    @Test
    fun `present custom override resolves to the imported family`() {
        val present = FontFamily.SansSerif
        assertSame(
            present,
            FontChoices.resolveReader(
                FontChoices.customKey("known"),
                urduScript = true,
                customFamilies = mapOf("known" to present)
            )
        )
    }

    @Test
    fun `override beats the global title font`() {
        assertEquals(
            FontChoices.NASTALIQ,
            FontChoices.effectiveTitleChoice(FontChoices.NASTALIQ, FontChoices.SANS)
        )
    }

    @Test
    fun `null override falls back to the global title font`() {
        assertEquals(
            FontChoices.SERIF,
            FontChoices.effectiveTitleChoice(null, FontChoices.SERIF)
        )
    }

    @Test
    fun `auto title is Nastaliq for Urdu script`() {
        assertSame(
            NastaliqFamily,
            FontChoices.resolveTitle(
                FontChoices.effectiveTitleChoice(FontChoices.AUTO, FontChoices.SANS),
                urduScript = true,
                customFamilies = emptyMap()
            )
        )
    }

    @Test
    fun `auto title is plain sans for Latin script`() {
        assertSame(
            FontFamily.SansSerif,
            FontChoices.resolveTitle(
                FontChoices.effectiveTitleChoice(FontChoices.AUTO, FontChoices.SANS),
                urduScript = false,
                customFamilies = emptyMap()
            )
        )
    }

    @Test
    fun `explicit title override beats the global title font`() {
        // Nastaliq override on a Latin title still wins: an explicit choice
        // is never re-judged by script — only AUTO is script-aware.
        assertSame(
            NastaliqFamily,
            FontChoices.resolveTitle(
                FontChoices.effectiveTitleChoice(FontChoices.NASTALIQ, FontChoices.SANS),
                urduScript = false,
                customFamilies = emptyMap()
            )
        )
    }

    @Test
    fun `dangling custom title override falls back to the plain sans`() {
        assertSame(
            FontFamily.SansSerif,
            FontChoices.resolveTitle(
                FontChoices.customKey("gone-font-id"),
                urduScript = true,
                customFamilies = emptyMap()
            )
        )
    }

    @Test
    fun `title override is independent from the lyrics override`() {
        // The two per-entry choices travel as separate fields; the title
        // path reads only the title override. A set lyrics override must
        // never bleed into the title.
        val lyricsOverride: String? = FontChoices.SANS
        assertEquals(
            FontChoices.SERIF,
            FontChoices.effectiveTitleChoice(null, FontChoices.SERIF)
        )
        assertEquals(
            FontChoices.NASTALIQ,
            FontChoices.effectiveTitleChoice(FontChoices.NASTALIQ, FontChoices.SERIF)
        )
        assertNotEquals(
            lyricsOverride,
            FontChoices.effectiveTitleChoice(null, FontChoices.SERIF)
        )
    }

    @Test
    fun `mini player title follows the same per-entry rule`() {
        // The player shows the playing entry's own override when set, else
        // the global title font; AUTO is script-aware on that title.
        assertSame(
            NastaliqFamily,
            FontChoices.resolveTitle(
                FontChoices.effectiveTitleChoice(FontChoices.NASTALIQ, FontChoices.SERIF),
                urduScript = false,
                customFamilies = emptyMap()
            )
        )
        assertSame(
            NastaliqFamily,
            FontChoices.resolveTitle(
                FontChoices.effectiveTitleChoice(null, FontChoices.AUTO),
                urduScript = true,
                customFamilies = emptyMap()
            )
        )
    }
}
