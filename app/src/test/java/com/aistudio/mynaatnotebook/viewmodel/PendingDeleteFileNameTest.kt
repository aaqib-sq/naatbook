package com.aistudio.mynaatnotebook.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PendingDeleteFileNameTest {

    @Test
    fun `name carries entry id and index`() {
        val name = pendingDeleteFileName(42, 1, "voice.m4a")
        assertTrue(name.startsWith("entry42_1_"))
        assertTrue(name.endsWith("voice.m4a"))
    }

    @Test
    fun `unsafe characters are sanitized`() {
        val name = pendingDeleteFileName(7, 0, "../../evil m4a?")
        assertTrue(!name.contains('/') && !name.contains(' ') && !name.contains('?'))
    }

    @Test
    fun `long names are truncated`() {
        val name = pendingDeleteFileName(7, 0, "a".repeat(200) + ".m4a")
        assertTrue(name.length <= "entry7_0_".length + 40)
    }

    @Test
    fun `different entries never collide`() {
        assertTrue(
            pendingDeleteFileName(1, 0, "same.m4a") !=
                pendingDeleteFileName(2, 0, "same.m4a")
        )
    }

    @Test
    fun `primary and secondary of one entry never collide`() {
        assertTrue(
            pendingDeleteFileName(1, 0, "same.m4a") !=
                pendingDeleteFileName(1, 1, "same.m4a")
        )
    }

    @Test
    fun `result is a plain filename`() {
        val name = pendingDeleteFileName(3, 0, "rec.m4a")
        assertEquals("entry3_0_rec.m4a", name)
        assertTrue(!name.contains(File.separator))
    }
}
