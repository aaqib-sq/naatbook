package com.aistudio.mynaatnotebook.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-function tests: no Android runtime needed. */
class RecentCardContentTest {

    @Test
    fun `null lyrics yields no preview`() {
        assertNull(firstLyricsLine(null))
    }

    @Test
    fun `blank lyrics yields no preview`() {
        assertNull(firstLyricsLine(""))
        assertNull(firstLyricsLine("   "))
        assertNull(firstLyricsLine("\n \n\t\n"))
    }

    @Test
    fun `leading blank lines are skipped`() {
        assertEquals(
            "Pehla misra",
            firstLyricsLine("\n\n  \nPehla misra\nDoosra misra")
        )
    }

    @Test
    fun `crlf line endings are handled`() {
        assertEquals("First line", firstLyricsLine("First line\r\nSecond line"))
    }

    @Test
    fun `the returned line is trimmed`() {
        assertEquals("spaced line", firstLyricsLine("  \t spaced line \t "))
    }

    @Test
    fun `a single line returns itself`() {
        assertEquals("Only line", firstLyricsLine("Only line"))
    }

    @Test
    fun `only the first non-blank line is returned`() {
        assertEquals("One", firstLyricsLine("One\nTwo\nThree"))
    }
}
