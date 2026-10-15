package com.aistudio.mynaatnotebook.ui.components

import com.aistudio.mynaatnotebook.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryDisplayTest {

    @Test
    fun `all eight canonical stored values map to their own resource ids`() {
        val matrix = mapOf(
            "Naat" to R.string.category_name_naat,
            "Hamd" to R.string.category_name_hamd,
            "Manqabat" to R.string.category_name_manqabat,
            "Salam" to R.string.category_name_salam,
            "Qasida" to R.string.category_name_qasida,
            "Nasheed" to R.string.category_name_nasheed,
            "My Kalam" to R.string.category_name_my_kalam,
            "Others" to R.string.category_name_others
        )
        matrix.forEach { (stored, expectedRes) ->
            assertEquals(stored, expectedRes, CategoryDisplay.displayNameRes(stored))
        }
        // Distinct nameplates: no two categories share a string resource.
        assertEquals(matrix.size, matrix.values.toSet().size)
    }

    @Test
    fun `legacy and non-canonical input displays as its normalized bucket`() {
        assertEquals(
            R.string.category_name_naat,
            CategoryDisplay.displayNameRes("hamd-o-naat")
        )
        assertEquals(
            R.string.category_name_salam,
            CategoryDisplay.displayNameRes(" SALAM & QASIDA ")
        )
        assertEquals(
            R.string.category_name_my_kalam,
            CategoryDisplay.displayNameRes("audio only")
        )
    }

    @Test
    fun `unknown or blank input falls back to Others`() {
        assertEquals(R.string.category_name_others, CategoryDisplay.displayNameRes("Not a category"))
        assertEquals(R.string.category_name_others, CategoryDisplay.displayNameRes(""))
    }

    @Test
    fun `mapping is case-insensitive like storage normalization`() {
        assertTrue(
            CategoryDisplay.displayNameRes("nAaT") == R.string.category_name_naat &&
                CategoryDisplay.displayNameRes("HAMD") == R.string.category_name_hamd
        )
    }
}
