package com.aistudio.mynaatnotebook.viewmodel

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Debounced process-death snapshot; all filesystem work is called from Dispatchers.IO. */
internal class EditorDraftDiskStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "editor-draft.json")

    fun read(): EditorDraft? {
        if (!file.isFile) return null
        val json = JSONObject(file.readText())
        fun optional(key: String) = if (json.isNull(key)) null else json.optString(key)
        return EditorDraft(
            active = json.optBoolean("active", false),
            parked = json.optBoolean("parked", false),
            editingId = if (json.isNull("editingId")) null else json.optInt("editingId"),
            title = json.optString("title"),
            poet = json.optString("poet"),
            category = json.optString("category", com.aistudio.mynaatnotebook.data.NaatCategories.DEFAULT),
            lyrics = json.optString("lyrics"),
            existingAudioRemoved = json.optBoolean("existingAudioRemoved"),
            existingAudioType = json.optString("existingAudioType", "none"),
            existingAudioPath = optional("existingAudioPath"),
            existingAudioDisplayName = optional("existingAudioDisplayName"),
            existingSecondaryAudioRemoved = json.optBoolean("existingSecondaryAudioRemoved"),
            existingSecondaryAudioType = json.optString("existingSecondaryAudioType", "none"),
            existingSecondaryAudioPath = optional("existingSecondaryAudioPath"),
            existingSecondaryAudioDisplayName = optional("existingSecondaryAudioDisplayName"),
            existingFavorite = json.optBoolean("existingFavorite"),
            existingCreatedAt = json.optLong("existingCreatedAt"),
            readerFontOverride = optional("readerFontOverride"),
            titleFontOverride = optional("titleFontOverride"),
            newAttachmentPath = optional("newAttachmentPath"),
            newAttachmentName = optional("newAttachmentName"),
            finishedRecordingPath = optional("finishedRecordingPath")
        )
    }

    fun write(draft: EditorDraft) {
        val json = JSONObject().apply {
            put("active", draft.active)
            put("parked", draft.parked)
            put("editingId", draft.editingId ?: JSONObject.NULL)
            put("title", draft.title)
            put("poet", draft.poet)
            put("category", draft.category)
            put("lyrics", draft.lyrics)
            put("existingAudioRemoved", draft.existingAudioRemoved)
            put("existingAudioType", draft.existingAudioType)
            put("existingAudioPath", draft.existingAudioPath ?: JSONObject.NULL)
            put("existingAudioDisplayName", draft.existingAudioDisplayName ?: JSONObject.NULL)
            put("existingSecondaryAudioRemoved", draft.existingSecondaryAudioRemoved)
            put("existingSecondaryAudioType", draft.existingSecondaryAudioType)
            put("existingSecondaryAudioPath", draft.existingSecondaryAudioPath ?: JSONObject.NULL)
            put("existingSecondaryAudioDisplayName", draft.existingSecondaryAudioDisplayName ?: JSONObject.NULL)
            put("existingFavorite", draft.existingFavorite)
            put("existingCreatedAt", draft.existingCreatedAt)
            put("readerFontOverride", draft.readerFontOverride ?: JSONObject.NULL)
            put("titleFontOverride", draft.titleFontOverride ?: JSONObject.NULL)
            put("newAttachmentPath", draft.newAttachmentPath ?: JSONObject.NULL)
            put("newAttachmentName", draft.newAttachmentName ?: JSONObject.NULL)
            put("finishedRecordingPath", draft.finishedRecordingPath ?: JSONObject.NULL)
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IllegalStateException("Could not commit editor draft snapshot")
        }
    }

    fun delete() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }
}
