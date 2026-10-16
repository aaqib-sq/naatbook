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
        // (A DAO row that matched only inside lyrics has a title+poet that
        // do not contain the query — lyrics aren't carried by the projection.)
        val lyricsOnly = summary(2, "Some kalaam", poet = "Someone")
        val titleMatch = summary(1, "Madina sharif")
        val ranked = rankSearchResults(listOf(lyricsOnly, titleMatch), "madina")
        assertEquals(listOf(titleMatch, lyricsOnly), ranked)
    }

    @Test
    fun `poet match sits between title and lyrics-only matches`() {
        val lyricsOnly = summary(3, "Evening kalaam")
        val poetMatch = summary(2, "Morning kalaam", poet = "Qari Madina")
        val titleMatch = summary(1, "Madina sharif")
        val ranked = rankSearchResults(listOf(lyricsOnly, poetMatch, titleMatch), "madina")
        assertEquals(listOf(titleMatch, poetMatch, lyricsOnly), ranked)
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
