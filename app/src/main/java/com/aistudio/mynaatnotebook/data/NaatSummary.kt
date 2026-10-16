package com.aistudio.mynaatnotebook.data

/** Lightweight Library projection; large lyrics stay in Room until Reader/Editor opens.
 *  The recent feed is the one exception: it carries lyrics for the one-line preview. */
data class NaatSummary(
    val id: Int,
    val title: String,
    val poet: String?,
    val category: String,
    val audioType: String,
    val audioPath: String?,
    val isFavorite: Boolean,
    val createdAt: Long,
    val secondaryAudioType: String,
    val secondaryAudioPath: String?,
    val lyrics: String? = null,
    /** Per-entry title-font override (null = follow the global Settings
     *  title font); carried here only where the feed needs per-entry title
     *  resolution (recents). */
    val titleFontOverride: String? = null
)

data class CategoryCount(
    val category: String,
    val count: Int
)
