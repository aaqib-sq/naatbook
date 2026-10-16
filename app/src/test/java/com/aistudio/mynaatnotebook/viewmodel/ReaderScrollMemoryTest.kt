package com.aistudio.mynaatnotebook.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderScrollMemoryTest {

    @Test
    fun `nothing saved means no restore`() {
        assertEquals(0, clampedRestoreOffset(0, 2000))
    }

    @Test
    fun `negative saved offset means no restore`() {
        assertEquals(0, clampedRestoreOffset(-50, 2000))
    }

    @Test
    fun `short content means no restore`() {
        // The content fits the viewport: scrolling is meaningless.
        assertEquals(0, clampedRestoreOffset(500, 0))
        assertEquals(0, clampedRestoreOffset(500, -10))
    }

    @Test
    fun `saved offset within content restores exactly`() {
        assertEquals(500, clampedRestoreOffset(500, 2000))
    }

    @Test
    fun `overshooting saved offset is clamped to content height`() {
        // The entry was edited shorter after the position was saved.
        assertEquals(2000, clampedRestoreOffset(5000, 2000))
    }

    @Test
    fun `saved offset exactly at max is fine`() {
        assertEquals(2000, clampedRestoreOffset(2000, 2000))
    }
}
