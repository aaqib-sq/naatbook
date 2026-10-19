package com.aistudio.mynaatnotebook.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
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
import com.aistudio.mynaatnotebook.R
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One user-imported font: registry entry + its private file. */
data class CustomFont(val id: String, val name: String, val fileName: String)

/**
 * A font-import failure the UI can render in the user's language. Carries a
 * string resource id (plus format args) instead of a hardcoded English
 * message, so the error dialog shows localized copy.
 */
class FontImportException(
    @StringRes val resId: Int,
    val args: List<Any> = emptyList()
) : IOException()

/** A validated font file waiting in cache for its name-and-save step. */
data class PendingFontImport(
    val tempFile: File,
    val sourceName: String,
    val suggestedName: String,
    val family: FontFamily
)

/** Outcome of a ZIP batch font import, for the summary messages. */
data class ZipFontImportResult(
    val imported: Int,
    val duplicates: Int,
    val invalid: Int,
    val tooLarge: Int,
    val limitSkipped: Int,
    val fontsFound: Int
)

/** What a picked file turned out to be, so the caller can route itself. */
sealed interface FontPickOutcome {
    data class Single(val pending: PendingFontImport) : FontPickOutcome
    data class Batch(val result: ZipFontImportResult) : FontPickOutcome
}

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
        const val MAX_FONTS = 100
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

    /**
     * Audit 05 H1: parsed families are cached by file identity (path +
     * length + mtime). The settings collector used to re-run
     * Typeface.createFromFile for EVERY registered font on EVERY registry
     * emission — with a full registry that is a disk read + parse per font on the main
     * thread per settings change, a visible stall. A replaced file (same
     * path, new bytes) invalidates itself through length/mtime.
     */
    private data class CachedFamily(val length: Long, val modified: Long, val family: FontFamily?)

    private val familyCache = java.util.concurrent.ConcurrentHashMap<String, CachedFamily>()

    /** Loads (and thereby validates) a font file as a Compose family. */
    fun loadFamily(file: File): FontFamily? {
        if (!file.isFile) return null
        val key = file.absolutePath
        val length = file.length()
        val modified = file.lastModified()
        familyCache[key]?.let { cached ->
            if (cached.length == length && cached.modified == modified) return cached.family
        }
        val loaded = try {
            Typeface.createFromFile(file)?.let { FontFamily(it) }
        } catch (e: RuntimeException) {
            null
        }
        familyCache[key] = CachedFamily(length, modified, loaded)
        return loaded
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
            throw FontImportException(R.string.settings_font_error_limit, listOf(MAX_FONTS))
        }
        val sourceName = queryDisplayName(uri) ?: "font"
        val ext = sourceName.substringAfterLast('.', "").lowercase()
        if (ext != "ttf" && ext != "otf") {
            // Audit 03 M11: this used to be a plain IOException, so the UI
            // fell back to the generic "could not import" message even
            // though the specific "not a valid font" string existed.
            throw FontImportException(R.string.settings_font_error_invalid)
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
                            throw FontImportException(R.string.settings_font_error_too_large)
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (!validateFontFile(temp)) {
                throw FontImportException(R.string.settings_font_error_invalid)
            }
            if (isDuplicate(temp, current)) {
                throw FontImportException(R.string.settings_font_error_duplicate)
            }
            val family = loadFamily(temp)
                ?: throw FontImportException(R.string.settings_font_error_invalid)
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
                throw FontImportException(R.string.settings_font_error_limit, listOf(MAX_FONTS))
            }
            if (isDuplicate(pending.tempFile, current)) {
                pending.tempFile.delete()
                throw FontImportException(R.string.settings_font_error_duplicate)
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

    /**
     * Entry point for the font picker: a ZIP pick becomes a batch import
     * (every font inside, named from its file name — no per-font naming
     * step); anything else keeps the single-file validate-then-name flow.
     *
     * Detection must not trust the display name alone: some pickers hand
     * back a name without its ".zip" suffix, and the file was then fed to
     * the single-font flow and rejected as "not a valid font" (device
     * report, 2026-10-19). A ZIP is recognized by its PK magic bytes —
     * the name is only a second signal.
     */
    suspend fun importPicked(uri: Uri): FontPickOutcome {
        val sourceName = queryDisplayName(uri) ?: "font"
        return if (sourceName.lowercase().endsWith(".zip") || hasZipMagic(uri)) {
            FontPickOutcome.Batch(importFontsFromZip(uri))
        } else {
            FontPickOutcome.Single(beginImport(uri))
        }
    }

    /** True when the picked content starts with the ZIP local-header magic. */
    private fun hasZipMagic(uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val header = ByteArray(4)
            var read = 0
            while (read < header.size) {
                val n = stream.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            read == header.size &&
                header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                (header[2] == 0x03.toByte() || header[2] == 0x05.toByte()) &&
                (header[3] == 0x04.toByte() || header[3] == 0x06.toByte())
        } ?: false
    } catch (e: Exception) {
        false
    }

    /**
     * Batch-imports every font file (.ttf/.otf, at any folder depth)
     * inside a picked ZIP. Each entry passes the same gates as a single
     * import — per-file size cap, real-parse validation, SHA-256
     * duplicate guard, the registry limit — and is named from its file
     * name (uniquified like a hand-named import). The registry is
     * written once, at the end.
     *
     * The archive is staged to a local file and read through ZipFile
     * (central directory). The first device attempt walked the picked
     * stream with ZipInputStream and died midway with the generic
     * "could not import" error and nothing imported: provider streams
     * and streaming-ZIP quirks are a fragile combination. A failure
     * after some fonts landed keeps those fonts, and the failure itself
     * is surfaced with its real reason instead of the generic message.
     */
    suspend fun importFontsFromZip(uri: Uri): ZipFontImportResult =
        withContext(Dispatchers.IO) {
            val current = fonts.first().toMutableList()
            var imported = 0
            var duplicates = 0
            var invalid = 0
            var tooLarge = 0
            var limitSkipped = 0
            var fontsFound = 0
            val stagedZip = File(context.cacheDir, "font-zip-${UUID.randomUUID()}.zip")
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Could not open the selected file.")
                input.use { stream ->
                    FileOutputStream(stagedZip).use { output -> stream.copyTo(output) }
                }
                java.util.zip.ZipFile(stagedZip).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val baseName = entry.name.replace('\\', '/').substringAfterLast('/')
                        val ext = baseName.substringAfterLast('.', "").lowercase()
                        if (ext != "ttf" && ext != "otf") continue
                        fontsFound++
                        if (current.size >= MAX_FONTS) {
                            limitSkipped++
                            continue
                        }
                        if (entry.size > MAX_FONT_BYTES) {
                            tooLarge++
                            continue
                        }
                        val temp = File(
                            context.cacheDir,
                            "font-zip-entry-${UUID.randomUUID()}.$ext"
                        )
                        try {
                            var overflow = false
                            zip.getInputStream(entry).use { stream ->
                                FileOutputStream(temp).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    var total = 0L
                                    while (true) {
                                        val count = stream.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        if (total > MAX_FONT_BYTES) {
                                            overflow = true
                                            break
                                        }
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                            when {
                                overflow -> {
                                    tooLarge++
                                    temp.delete()
                                }
                                !validateFontFile(temp) -> {
                                    invalid++
                                    temp.delete()
                                }
                                isDuplicate(temp, current) -> {
                                    duplicates++
                                    temp.delete()
                                }
                                else -> {
                                    val id = UUID.randomUUID().toString()
                                    val fileName = "$id.$ext"
                                    val destination = File(fontsDir, fileName)
                                    if (!temp.renameTo(destination)) {
                                        temp.copyTo(destination, overwrite = true)
                                        temp.delete()
                                    }
                                    val display = baseName.substringBeforeLast('.')
                                        .replace(Regex("[-_]+"), " ").trim()
                                        .ifBlank { "My Font" }
                                    current += CustomFont(
                                        id,
                                        uniqueName(display, current),
                                        fileName
                                    )
                                    imported++
                                }
                            }
                        } catch (e: Exception) {
                            temp.delete()
                            invalid++
                        }
                    }
                }
            } catch (e: FontImportException) {
                throw e
            } catch (e: Exception) {
                // Fonts that already landed stay landed — a dead archive
                // must not take the successful imports down with it.
                if (imported > 0) writeRegistry(current)
                throw FontImportException(
                    R.string.settings_font_zip_failed,
                    listOf(e.message ?: e.javaClass.simpleName)
                )
            } finally {
                stagedZip.delete()
            }
            if (imported > 0) writeRegistry(current)
            ZipFontImportResult(
                imported, duplicates, invalid, tooLarge, limitSkipped, fontsFound
            )
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
