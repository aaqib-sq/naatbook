package com.aistudio.mynaatnotebook.viewmodel

import com.aistudio.mynaatnotebook.data.NaatSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRankingTest {

    private fun summary(id: Int, title: String, poet: String? = null) = NaatSummary(
        id = id,
        title = title,
        poet = poet,
        category = "Naat",
        audioType = "none",
        audioPath = null,
        isFavorite = false,
        createdAt = 1_000L * id,
        secondaryAudioType = "none",
        secondaryAudioPath = null
    )

    @Test
    fun `title match ranks above lyrics-only match`() {
        // DAO order is newest-first: the lyrics-only entry is newer.
        // (A DAO row that matched only inside lyrics has a title that does
        // not contain the query.)
        val lyricsOnly = summary(2, "Some kalaam", poet = "Someone")
        val titleMatch = summary(1, "Madina sharif")
        val ranked = rankSearchResults(listOf(lyricsOnly, titleMatch), "madina")
        assertEquals(listOf(titleMatch, lyricsOnly), ranked)
    }

    @Test
    fun `poet is not a ranking tier`() {
        // Poet left search (owner order 2026-11-12): a row matching only
        // in the poet ranks with lyrics-only rows, in DAO order — never
        // above them.
        val lyricsOnly = summary(3, "Evening kalaam")
        val poetOnly = summary(2, "Morning kalaam", poet = "Qari Madina")
        val ranked = rankSearchResults(listOf(lyricsOnly, poetOnly), "madina")
        assertEquals(listOf(lyricsOnly, poetOnly), ranked)
    }

    @Test
    fun `matching is case-insensitive`() {
        val match = summary(1, "Madina sharif")
        val ranked = rankSearchResults(listOf(match), "MADINA")
        assertEquals(listOf(match), ranked)
    }

    @Test
    fun `order is stable within a tier`() {
        // Both match in the title; DAO newest-first order must survive.
        val newer = summary(2, "Madina at dawn")
        val older = summary(1, "Madina at dusk")
        val ranked = rankSearchResults(listOf(newer, older), "madina")
        assertEquals(listOf(newer, older), ranked)
    }

    @Test
    fun `blank query keeps DAO order untouched`() {
        val a = summary(1, "Alpha")
        val b = summary(2, "Beta")
        val input = listOf(b, a)
        assertEquals(input, rankSearchResults(input, "   "))
    }
}
