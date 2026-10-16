package com.aistudio.mynaatnotebook.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.font.FontFamily
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One user-imported font: registry entry + its private file. */
data class CustomFont(val id: String, val name: String, val fileName: String)

/** A validated font file waiting in cache for its name-and-save step. */
data class PendingFontImport(
    val tempFile: File,
    val sourceName: String,
    val suggestedName: String,
    val family: FontFamily
)

/**
 * User-imported fonts (approved design): the user picks font files from
 * storage; each is validated (extension + magic bytes + a real Typeface
 * parse) and copied into app-private storage before it can be used.
 * Registry lives in SettingsStore as JSON; files live in filesDir/fonts.
 * Limits: at most [MAX_FONTS] fonts, each at most [MAX_FONT_BYTES].
 * A font file is only ever read as text-rendering data inside this app.
 */
@Singleton
class CustomFontStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore
) {
    companion object {
        const val MAX_FONTS = 10
        const val MAX_FONT_BYTES = 15L * 1024 * 1024
        /**
         * Audit fix (stage 1): a backup-declared font `name` goes straight
         * into the registry and the picker UI — a hostile JSON could store a
         * 100KB name or control characters. Cap and clean restore-path names.
         * (The picker-import naming dialog is user-typed, so it stays as-is.)
         */
        const val MAX_FONT_NAME_LENGTH = 60

        /** Restores only: control chars out, length capped, never blank. */
        internal fun sanitizeRestoredFontName(raw: String): String =
            raw.filterNot { it.isISOControl() }.trim().take(MAX_FONT_NAME_LENGTH)
                .ifBlank { "My Font" }

        // sfnt magic: TrueType (0x00010000), legacy 'true', OpenType 'OTTO'
        private val FONT_MAGICS = listOf(
            byteArrayOf(0x00, 0x01, 0x00, 0x00),
            byteArrayOf(0x74, 0x72, 0x75, 0x65),
            byteArrayOf(0x4F, 0x54, 0x54, 0x4F)
        )
    }

    private val fontsDir: File get() = File(context.filesDir, "fonts").apply { mkdirs() }

    val fonts: Flow<List<CustomFont>> = settingsStore.customFontsJson.map { parseRegistry(it) }

    fun fileFor(font: CustomFont): File = File(fontsDir, font.fileName)

    /** Loads (and thereby validates) a font file as a Compose family. */
    fun loadFamily(file: File): FontFamily? {
        if (!file.isFile) return null
        return try {
            val typeface = Typeface.createFromFile(file) ?: return null
            FontFamily(typeface)
        } catch (e: RuntimeException) {
            null
        }
    }

    fun familyFor(font: CustomFont): FontFamily? = loadFamily(fileFor(font))

    private fun parseRegistry(json: String): List<CustomFont> = try {
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(
                    CustomFont(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        fileName = obj.getString("file")
                    )
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Audit #15: font names must stay distinguishable in the picker — two
     * rows with the same name are indistinguishable. A taken name gains a
     * " (2)", " (3)"… suffix instead of silently duplicating.
     */
    private fun uniqueName(desired: String, existing: List<CustomFont>): String {
        val base = desired.trim().ifBlank { "My Font" }
        val taken = existing.map { it.name.trim().lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var suffix = 2
        while ("$base ($suffix)".lowercase() in taken) suffix++
        return "$base ($suffix)"
    }

    private fun registryJson(fonts: List<CustomFont>): String {
        val array = JSONArray()
        fonts.forEach { font ->
            array.put(JSONObject().apply {
                put("id", font.id)
                put("name", font.name)
                put("file", font.fileName)
            })
        }
        return array.toString()
    }

    private suspend fun writeRegistry(fonts: List<CustomFont>) {
        settingsStore.setCustomFontsJson(registryJson(fonts))
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /** True when a byte-identical font is already imported (same content, any name). */
    private fun isDuplicate(candidate: File, existing: List<CustomFont>): Boolean {
        val candidateHash = sha256(candidate)
        return existing.any { font ->
            val file = fileFor(font)
            file.isFile && sha256(file) == candidateHash
        }
    }

    /** Pure byte-level validation: extension, size, magic, real parse. */
    fun validateFontFile(file: File): Boolean {        val ext = file.extension.lowercase()
        if (ext != "ttf" && ext != "otf") return false
        if (file.length() !in 1..MAX_FONT_BYTES) return false
        val header = ByteArray(4)
        val read = try {
            file.inputStream().use { it.read(header) }
        } catch (e: IOException) {
            return false
        }
        if (read < 4) return false
        if (FONT_MAGICS.none { magic -> magic.indices.all { header[it] == magic[it] } }) return false
        return loadFamily(file) != null
    }

    /**
     * Phase 1 of import: copy the picked file into cache (size-capped),
     * validate it, and hand back a pending import for the naming step.
     * Throws IOException with a user-presentable reason on rejection.
     */
    suspend fun beginImport(uri: Uri): PendingFontImport = withContext(Dispatchers.IO) {
        val current = fonts.first()
        if (current.size >= MAX_FONTS) {
            throw IOException("Font limit reached ($MAX_FONTS). Delete one first.")
        }
        val sourceName = queryDisplayName(uri) ?: "font"
        val ext = sourceName.substringAfterLast('.', "").lowercase()
        if (ext != "ttf" && ext != "otf") {
            throw IOException("Only .ttf or .otf font files can be imported.")
        }
        val temp = File(context.cacheDir, "font-import-${UUID.randomUUID()}.$ext")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open the selected file.")
            input.use { stream ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_FONT_BYTES) {
                            throw IOException("Font file is too large (max 15 MB).")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (!validateFontFile(temp)) {
                throw IOException("That file is not a valid font.")
            }
            if (isDuplicate(temp, current)) {
                throw IOException("This font is already added.")
            }
            val family = loadFamily(temp)
                ?: throw IOException("That file is not a valid font.")
            PendingFontImport(
                tempFile = temp,
                sourceName = sourceName,
                suggestedName = sourceName.substringBeforeLast('.')
                    .replace(Regex("[-_]+"), " ").trim().ifBlank { "My Font" },
                family = family
            )
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    /** Phase 2: move a validated pending import into private storage. */
    suspend fun commitImport(pending: PendingFontImport, name: String): CustomFont =
        withContext(Dispatchers.IO) {
            val current = fonts.first()
            if (current.size >= MAX_FONTS) {
                throw IOException("Font limit reached ($MAX_FONTS). Delete one first.")
            }
            if (isDuplicate(pending.tempFile, current)) {
                pending.tempFile.delete()
                throw IOException("This font is already added.")
            }
            val id = UUID.randomUUID().toString()
            val ext = pending.tempFile.extension.lowercase()
            val fileName = "$id.$ext"
            val destination = File(fontsDir, fileName)
            if (!pending.tempFile.renameTo(destination)) {
                pending.tempFile.copyTo(destination, overwrite = true)
                pending.tempFile.delete()
            }
            val font = CustomFont(
                id,
                uniqueName(name.trim().ifBlank { pending.suggestedName }, current),
                fileName
            )
            writeRegistry(current + font)
            font
        }

    fun discardImport(pending: PendingFontImport) {
        pending.tempFile.delete()
    }

    /** Removes a font; returns the deleted entry so callers can reset choices pointing at it. */
    suspend fun deleteFont(id: String): CustomFont? = withContext(Dispatchers.IO) {
        val current = fonts.first()
        val target = current.firstOrNull { it.id == id } ?: return@withContext null
        fileFor(target).delete()
        writeRegistry(current - target)
        target
    }

    /** Outcome of a backup font restore, so the caller can report honestly. */
    enum class FontRestoreResult { ADOPTED, ALREADY_PRESENT, REJECTED }

    /**
     * Backup restore hook: adopt a staged font file (already extracted by
     * BackupManager) if its id is new and the cap allows. Re-validates, and
     * applies the same content-duplicate guard as a manual import (audit
     * fix: the restore path used to skip it, so a byte-identical font under
     * a different id — e.g. a backup from another phone — duplicated).
     */
    suspend fun restoreFont(id: String, name: String, stagedFile: File): FontRestoreResult =
        withContext(Dispatchers.IO) {
            if (!stagedFile.name.startsWith(id)) return@withContext FontRestoreResult.REJECTED
            val current = fonts.first()
            if (current.any { it.id == id }) return@withContext FontRestoreResult.ALREADY_PRESENT
            if (!validateFontFile(stagedFile)) return@withContext FontRestoreResult.REJECTED
            if (isDuplicate(stagedFile, current)) {
                return@withContext FontRestoreResult.ALREADY_PRESENT
            }
            if (current.size >= MAX_FONTS) return@withContext FontRestoreResult.REJECTED
            val fileName = stagedFile.name
            val destination = File(fontsDir, fileName)
            if (!stagedFile.renameTo(destination)) {
                stagedFile.copyTo(destination, overwrite = true)
            }
            writeRegistry(current + CustomFont(id, uniqueName(sanitizeRestoredFontName(name), current), fileName))
            FontRestoreResult.ADOPTED
        }

    /**
     * Audit #20: a registry entry whose file is missing or no longer parses
     * used to render silently as the default font forever. Prune such
     * entries and report them so the caller can tell the user once.
     */
    suspend fun pruneBrokenEntries(): List<CustomFont> = withContext(Dispatchers.IO) {
        val current = fonts.first()
        val broken = current.filter { font ->
            val file = fileFor(font)
            !file.isFile || loadFamily(file) == null
        }
        if (broken.isNotEmpty()) writeRegistry(current - broken.toSet())
        broken
    }

    /**
     * Audit #23: abandoned phase-1 import temps (app killed between pick
     * and naming) accumulated in cache forever. Drop any older than a day;
     * a live pending import is minutes old at most.
     */
    suspend fun pruneStaleImportTemps() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("font-import-") && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    /** Current registry snapshot for backup export. */
    suspend fun snapshot(): List<CustomFont> = fonts.first()

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    } catch (e: Exception) {
        null
    }
}
