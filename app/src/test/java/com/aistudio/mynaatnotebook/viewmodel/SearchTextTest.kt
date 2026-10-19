package com.aistudio.mynaatnotebook.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/** Audit 03 M2: the query-side Arabic-script folding rules. */
class SearchTextTest {

    @Test
    fun harakatAreStripped() {
        assertEquals(
            normalizeSearchText("\u0645\u062D\u0645\u062F"),
            normalizeSearchText("\u0645\u064F\u062D\u064E\u0645\u0651\u062F")
        )
    }

    @Test
    fun arabicVariantsFoldToUrduForms() {
        assertEquals(
            normalizeSearchText("\u06A9\u0644\u06CC\u0645"),
            normalizeSearchText("\u0643\u0644\u064A\u0645")
        )
        assertEquals(
            normalizeSearchText("\u0627\u062D\u0645\u062F"),
            normalizeSearchText("\u0623\u062D\u0645\u062F")
        )
    }

    @Test
    fun latinIsLowercasedAndTrimmed() {
        assertEquals("madina", normalizeSearchText("  Madina "))
    }

    @Test
    fun blankStaysBlank() {
        assertEquals("", normalizeSearchText("   "))
    }
}
