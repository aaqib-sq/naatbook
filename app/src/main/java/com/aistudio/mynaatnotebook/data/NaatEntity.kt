package com.aistudio.mynaatnotebook.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "naats", indices = [Index(value = ["updatedAt"])])
data class NaatEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val poet: String?,
    val category: String,
    val lyrics: String?,
    val audioType: String, // "recorded", "local_file", or "none"
    val audioPath: String?,
    val isFavorite: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
    /** Changes on every persisted edit; powers the Room-backed Recent Notebooks list. */
    val updatedAt: Long = System.currentTimeMillis(),
    /** Epoch millis of the last time the entry was opened in the Reader (null = never). */
    val lastOpenedAt: Long? = null,
    val secondaryAudioType: String = "none",
    val secondaryAudioPath: String? = null,
    // Original file name of a linked audio (from the picker), kept only for
    // display in the editor — internal storage names are meaningless
    // ("linked_<timestamp>_file.mp3"). Null for recordings and for entries
    // linked before this field existed.
    val audioDisplayName: String? = null,
    val secondaryAudioDisplayName: String? = null,
    /**
     * Per-entry reader-font override, stored in the same encoding as
     * SettingsStore.readerFont (a FontChoices key, or "custom:<id>").
     * Null means "follow the global Settings reader font".
     */
    val readerFontOverride: String? = null
)
