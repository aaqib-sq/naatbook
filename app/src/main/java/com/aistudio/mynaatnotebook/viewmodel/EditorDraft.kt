package com.aistudio.mynaatnotebook.viewmodel

import com.aistudio.mynaatnotebook.data.NaatCategories
import com.aistudio.mynaatnotebook.data.NaatEntity

/** Serializable editor values only; audio payloads remain in app-owned files. */
data class EditorMetadataDraft(
    val editingId: Int?,
    val title: String,
    val poet: String,
    val category: String,
    val lyrics: String,
    /** Null = follow the global Settings reader font. */
    val readerFontOverride: String? = null,
    /** Null = follow the global Settings title font. */
    val titleFontOverride: String? = null
)

data class EditorAttachmentDraft(
    val existingAudioRemoved: Boolean,
    val existingAudioType: String,
    val existingAudioPath: String?,
    val existingAudioDisplayName: String?,
    val existingSecondaryAudioRemoved: Boolean,
    val existingSecondaryAudioType: String,
    val existingSecondaryAudioPath: String?,
    val existingSecondaryAudioDisplayName: String?,
    val newAttachmentPath: String?,
    val newAttachmentName: String?,
    val finishedRecordingPath: String?
)

data class EditorDraft(
    val active: Boolean = false,
    val editingId: Int? = null,
    val title: String = "",
    val poet: String = "",
    val category: String = NaatCategories.DEFAULT,
    val lyrics: String = "",
    val existingAudioRemoved: Boolean = false,
    val existingAudioType: String = "none",
    val existingAudioPath: String? = null,
    val existingAudioDisplayName: String? = null,
    val existingSecondaryAudioRemoved: Boolean = false,
    val existingSecondaryAudioType: String = "none",
    val existingSecondaryAudioPath: String? = null,
    val existingSecondaryAudioDisplayName: String? = null,
    val existingFavorite: Boolean = false,
    val existingCreatedAt: Long = 0L,
    val readerFontOverride: String? = null,
    val titleFontOverride: String? = null,
    val newAttachmentPath: String? = null,
    val newAttachmentName: String? = null,
    val finishedRecordingPath: String? = null
)

/** True only when abandoning this draft would lose user-entered or user-attached work. */
internal fun EditorDraft.hasUnsavedChanges(original: NaatEntity?): Boolean {
    if (!active) return false

    if (editingId == null) {
        return title.isNotBlank() ||
            poet.isNotBlank() ||
            lyrics.isNotBlank() ||
            category != NaatCategories.DEFAULT ||
            readerFontOverride != null ||
            titleFontOverride != null ||
            newAttachmentPath != null ||
            finishedRecordingPath != null
    }

    // If process restoration could not rehydrate the original row, be conservative:
    // asking before discard is always safer than silently losing a recovered draft.
    if (original == null || original.id != editingId) return true

    return title != original.title ||
        poet != original.poet.orEmpty() ||
        NaatCategories.normalize(category) != NaatCategories.normalize(original.category) ||
        lyrics != original.lyrics.orEmpty() ||
        readerFontOverride != original.readerFontOverride ||
        titleFontOverride != original.titleFontOverride ||
        existingAudioRemoved ||
        existingSecondaryAudioRemoved ||
        newAttachmentPath != null ||
        finishedRecordingPath != null
}
