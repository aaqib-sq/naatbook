package com.aistudio.mynaatnotebook.data

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Rejects absolute, malformed, and Zip-Slip destinations. */
internal fun safeBackupDestination(baseDir: File, entryName: String): File? {
    val segments = entryName.split('/')
    if (
        entryName.isBlank() || '\\' in entryName || File(entryName).isAbsolute ||
        segments.any { it.isBlank() || it == "." || it == ".." }
    ) return null
    val destination = File(baseDir, entryName)
    val basePath = baseDir.canonicalFile.path + File.separator
    return destination.takeIf { it.canonicalFile.path.startsWith(basePath) }
}

/** Result of an import: entries added, duplicates skipped (merge), old entries removed (replace). */
data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val removedExisting: Int = 0,
    val fontsAdded: Int = 0,
    val fontsFailed: Int = 0
)

/** How an import treats the current library. */
enum class ImportMode { MERGE, REPLACE }

/** User-controlled ZIP import/export for the offline notebook. */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: NaatRepository,
    private val audioFiles: AudioFileLifecycleCoordinator,
    private val customFontStore: CustomFontStore
) {
    private companion object {
        const val FORMAT_VERSION = 2
        const val APP_FOLDER_RELATIVE_PATH = "Download/NaatBook"
        const val DATA_ENTRY = "naatbook_data.json"
        const val V2_AUDIO_PREFIX = "audio/"
        const val V2_FONTS_PREFIX = "fonts/"
        val V2_FONT_NAME = Regex("^fonts/([0-9a-fA-F-]{36})\\.(ttf|otf)$")
        const val LEGACY_RECORDINGS_PREFIX = "recordings/"
        const val LEGACY_LINKED_PREFIX = "linked/"
        const val MAX_ENTRIES = 10_000
        const val MAX_JSON_BYTES = 16L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
        const val BUFFER_SIZE = 32 * 1024
        val V2_AUDIO_NAME = Regex("^audio/([0-9a-f]{64})\\.([a-z0-9]{1,8})$")
    }

    private data class PackedAudio(val relativePath: String, val source: File)
    private data class ExportAudioIndex(
        val primaryPathByNaatId: Map<Int, String>,
        val secondaryPathByNaatId: Map<Int, String>,
        val uniqueFiles: Collection<PackedAudio>
    )
    private data class StagedArchive(
        val formatVersion: Int,
        val entries: JSONArray,
        val audioByArchivePath: Map<String, File>,
        val fontsByArchivePath: Map<String, File> = emptyMap(),
        val fontMeta: List<Pair<String, String>> = emptyList(),
        // Set by entries-only exports: audio references in the manifest may
        // legitimately have no payload in the archive — import drops those
        // references instead of failing. Full backups keep the strict
        // "missing audio payload" failure.
        val entriesOnly: Boolean = false
    )
    private data class PlannedAudio(val staged: File, val destination: File, val digest: String)
    private data class PlannedEntry(
        val entity: NaatEntity,
        val signature: String,
        val primaryArchivePath: String?,
        val secondaryArchivePath: String?
    )
    private data class RestorePlan(
        val entries: List<PlannedEntry>,
        val audioByArchivePath: Map<String, PlannedAudio>
    )

    private class ExtractionBudget {
        var totalBytes: Long = 0L
        fun add(byteCount: Int) {
            totalBytes += byteCount
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw IOException("Backup archive exceeds maximum allowed size")
            }
        }
    }

    /**
     * Builds the complete, fsynced backup zip into [stagedZip] and returns
     * the entry count. Shared by the SAF export and the app-folder export
     * so both produce byte-identical archives.
     *
     * When [entriesOnly] is true, the manifest keeps the audio references
     * (so the JSON stays self-consistent) but the audio payloads are left
     * out, producing a small file; import then drops those dangling
     * references instead of failing (see [StagedArchive.entriesOnly]).
     */
    private suspend fun writeBackupZip(
        stagedZip: File,
        onProgress: (Float) -> Unit,
        entriesOnly: Boolean = false
    ): Int {
        onProgress(0.05f)
        val naats = repository.allNaats.first()
        onProgress(0.15f)
        val audioIndex = buildAudioIndex(naats) { p -> onProgress(0.15f + p * 0.15f) }
        val entries = JSONArray()
        naats.forEach { naat ->
            entries.put(JSONObject().apply {
                put("id", naat.id)
                put("title", naat.title)
                put("poet", naat.poet ?: JSONObject.NULL)
                put("category", naat.category)
                put("lyrics", naat.lyrics ?: JSONObject.NULL)
                put("audioType", naat.audioType)
                put("audioPath", audioIndex.primaryPathByNaatId[naat.id] ?: JSONObject.NULL)
                put("secondaryAudioType", naat.secondaryAudioType)
                put(
                    "secondaryAudioPath",
                    audioIndex.secondaryPathByNaatId[naat.id] ?: JSONObject.NULL
                )
                put("isFavorite", naat.isFavorite)
                put("createdAt", naat.createdAt)
                put("updatedAt", naat.updatedAt)
                // Reader-open moment travels with the backup so a restore on a
                // new phone keeps the recents strip truthful; absence = NULL.
                put("lastOpenedAt", naat.lastOpenedAt ?: JSONObject.NULL)
                put("audioDisplayName", naat.audioDisplayName ?: JSONObject.NULL)
                put("secondaryAudioDisplayName", naat.secondaryAudioDisplayName ?: JSONObject.NULL)
                put("readerFontOverride", naat.readerFontOverride ?: JSONObject.NULL)
            })
        }
        // Imported fonts travel with the backup (approved design), so a
        // restore on a new phone keeps the user's own fonts too.
        val customFonts = customFontStore.snapshot()
            .mapNotNull { font ->
                val file = customFontStore.fileFor(font)
                if (file.isFile) font to file else null
            }
        val fontsJson = JSONArray()
        customFonts.forEach { (font, _) ->
            fontsJson.put(JSONObject().apply {
                put("id", font.id)
                put("name", font.name)
                put("file", "$V2_FONTS_PREFIX${font.fileName}")
            })
        }
        val root = JSONObject().apply {
            put("formatVersion", FORMAT_VERSION)
            if (entriesOnly) put("entriesOnly", true)
            put("entries", entries)
            put("customFonts", fontsJson)
        }
        onProgress(0.35f)

        FileOutputStream(stagedZip).use { fileOutput ->
            val buffered = BufferedOutputStream(fileOutput)
            val zip = ZipOutputStream(buffered)
            try {
                zip.putNextEntry(ZipEntry(DATA_ENTRY))
                zip.write(root.toString(4).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                val audioList = if (entriesOnly) emptyList() else audioIndex.uniqueFiles.toList()
                audioList.forEachIndexed { index, packed ->
                    zip.putNextEntry(ZipEntry(packed.relativePath))
                    FileInputStream(packed.source).use { it.copyTo(zip, BUFFER_SIZE) }
                    zip.closeEntry()
                    onProgress(0.35f + (index + 1f) / audioList.size.coerceAtLeast(1) * 0.45f)
                }
                customFonts.forEach { (font, file) ->
                    zip.putNextEntry(ZipEntry("$V2_FONTS_PREFIX${font.fileName}"))
                    FileInputStream(file).use { it.copyTo(zip, BUFFER_SIZE) }
                    zip.closeEntry()
                }
                zip.finish()
                zip.flush()
                buffered.flush()
                fileOutput.fd.sync()
            } finally {
                zip.close()
            }
        }
        onProgress(0.85f)
        return naats.size
    }

    /**
     * Build and fsync the complete archive before opening the user destination.
     * A hashing/ZIP failure therefore cannot truncate a previously valid backup.
     */
    suspend fun exportBackup(
        outputUri: Uri,
        entriesOnly: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): Result<Int> = withContext(Dispatchers.IO) {
        audioFiles.exclusive {
            val stagedZip = File(context.cacheDir, "backup-export-${UUID.randomUUID()}.zip")
            // Set the moment the destination document is opened (truncated);
            // only from then on can a failure leave a partial file behind.
            var destinationTouched = false
        runCatching {
            val entryCount = writeBackupZip(stagedZip, onProgress, entriesOnly)

            val descriptor = context.contentResolver.openFileDescriptor(outputUri, "w")
                ?: throw IOException("Could not open output stream")
            destinationTouched = true
            descriptor.use { pfd ->
                FileInputStream(stagedZip).use { input ->
                    FileOutputStream(pfd.fileDescriptor).use { output ->
                        input.copyTo(output, BUFFER_SIZE)
                        output.fd.sync()
                    }
                }
            }
            onProgress(1f)
            entryCount
            }.onFailure {
                Log.e("BackupManager", "Export failed", it)
                // Audit fix: a failure DURING the final copy can leave a
                // partial zip at the destination that looks exactly like a
                // real backup — remove it so it can never be trusted by
                // mistake. A failure before the destination was opened left
                // any previous backup untouched, so delete nothing then.
                if (destinationTouched) {
                    runCatching {
                        android.provider.DocumentsContract.deleteDocument(
                            context.contentResolver, outputUri
                        )
                    }
                }
            }
                .also { stagedZip.delete() }
        }
    }

    /**
     * One-tap export into the app's own folder, `Download/NaatBook`
     * (approved design): MediaStore creates the folder automatically and no
     * permission or picker is needed on Android 10+. The file stays
     * IS_PENDING until the copy completes, and a failure deletes the pending
     * row so no partial backup is left behind. Returns (entry count, label).
     */
    suspend fun exportBackupToAppFolder(
        fileName: String,
        entriesOnly: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): Result<Pair<Int, String>> = withContext(Dispatchers.IO) {
        audioFiles.exclusive {
            val stagedZip = File(context.cacheDir, "backup-export-${UUID.randomUUID()}.zip")
            var pendingUri: Uri? = null
            runCatching {
                val entryCount = writeBackupZip(stagedZip, onProgress, entriesOnly)
                check(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    "The NaatBook folder needs Android 10 or newer — please use Choose another place"
                }
                val resolver = context.contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, APP_FOLDER_RELATIVE_PATH)
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: throw IOException("Could not create the backup file in Downloads")
                pendingUri = uri
                val written = resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(stagedZip).use { input -> input.copyTo(output, BUFFER_SIZE) }
                } ?: throw IOException("Could not write the backup file")
                check(written > 0) { "Could not write the backup file" }
                val publish = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, publish, null, null)
                pendingUri = null
                onProgress(1f)
                entryCount to "Download/NaatBook"
            }.onFailure {
                Log.e("BackupManager", "App-folder export failed", it)
                pendingUri?.let { uri ->
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }.also { stagedZip.delete() }
        }
    }

    /**
     * Build the same archive as the other exports into a plain [target]
     * file. Google Drive sync stages the backup here, then uploads the file
     * itself (and deletes it afterwards). Drive sync always uses a FULL
     * backup — the entries-only choice is manual-export only, so this keeps
     * the default (entriesOnly = false).
     */
    suspend fun exportBackupToFile(
        target: File,
        entriesOnly: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): Result<Int> = withContext(Dispatchers.IO) {
        audioFiles.exclusive {
            val stagedZip = File(context.cacheDir, "backup-export-${UUID.randomUUID()}.zip")
            runCatching {
                val entryCount = writeBackupZip(stagedZip, onProgress, entriesOnly)
                target.parentFile?.mkdirs()
                FileInputStream(stagedZip).use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output, BUFFER_SIZE)
                        output.fd.sync()
                    }
                }
                entryCount
            }.onFailure {
                Log.e("BackupManager", "File export failed", it)
            }.also { stagedZip.delete() }
        }
    }

    /**
     * Cheap fingerprint of everything a backup would contain: entry count,
     * edit times, and the imported-font registry. Drive auto-sync compares
     * it against the fingerprint captured at the last successful sync and
     * skips the upload when nothing changed.
     */
    suspend fun contentHash(): String {
        val naats = repository.allNaats.first()
        val fonts = customFontStore.snapshot()
        val raw = buildString {
            append(naats.size).append('|')
            append(naats.maxOfOrNull { it.updatedAt } ?: 0L).append('|')
            append(naats.sumOf { it.updatedAt }).append('|')
            append(fonts.joinToString(",") { it.id })
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun buildAudioIndex(
        naats: List<NaatEntity>,
        onProgress: (Float) -> Unit = {}
    ): ExportAudioIndex {
        val primary = mutableMapOf<Int, String>()
        val secondary = mutableMapOf<Int, String>()
        val relativeByCanonicalSource = mutableMapOf<String, String>()
        val packedByDigest = linkedMapOf<String, PackedAudio>()

        fun pack(path: String?): String? {
            val source = path?.let(::safeAudioSource) ?: return null
            val canonicalPath = source.canonicalPath
            return relativeByCanonicalSource[canonicalPath] ?: run {
                val digest = source.sha256()
                packedByDigest[digest]?.relativePath ?: run {
                    val extension = safeExtension(source.extension)
                    "$V2_AUDIO_PREFIX$digest.$extension".also {
                        packedByDigest[digest] = PackedAudio(it, source)
                    }
                }.also { relativeByCanonicalSource[canonicalPath] = it }
            }
        }

        naats.forEachIndexed { index, naat ->
            pack(naat.audioPath)?.let { primary[naat.id] = it }
            pack(naat.secondaryAudioPath)?.let { secondary[naat.id] = it }
            onProgress((index + 1f) / naats.size.coerceAtLeast(1))
        }
        return ExportAudioIndex(primary, secondary, packedByDigest.values)
    }

    private fun safeAudioSource(path: String): File? {
        val source = File(path)
        if (!source.isFile) return null
        val filesDir = context.filesDir.canonicalFile
        val canonical = source.canonicalFile
        val basePath = filesDir.path + File.separator
        if (!canonical.path.startsWith(basePath)) return null
        val relative = canonical.path.removePrefix(basePath).replace(File.separatorChar, '/')
        return canonical.takeIf {
            relative.startsWith(LEGACY_RECORDINGS_PREFIX) ||
                relative.startsWith(LEGACY_LINKED_PREFIX) || relative.startsWith(V2_AUDIO_PREFIX)
        }
    }

    /**
     * Import is prepare/commit: extraction and full metadata/content validation happen in a
     * private staging directory. Files are atomically renamed, then every row is inserted by
     * one Room transaction. Any pre-commit failure leaves the notebook untouched; a database
     * failure rolls back only files created by this attempt.
     */
    suspend fun importBackup(
        inputUri: Uri,
        mode: ImportMode = ImportMode.MERGE,
        onProgress: (Float) -> Unit = {}
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        audioFiles.exclusive {
            val stagingDir = File(context.cacheDir, "backup-import-${UUID.randomUUID()}")
        runCatching {
            if (!stagingDir.mkdirs()) throw IOException("Could not create import staging area")
            onProgress(0.05f)
            val staged = extractAndValidateArchive(inputUri, stagingDir) { p ->
                onProgress(0.05f + p * 0.35f)
            }
            onProgress(0.45f)
            val plan = buildRestorePlan(staged)
            onProgress(0.55f)
            // Snapshot of the current library: in MERGE it drives duplicate
            // skipping; in REPLACE its rows are removed after a successful
            // commit, so any pre-commit failure leaves the library untouched.
            val existingEntries = repository.allNaats.first()
            val newEntries: List<PlannedEntry>
            val skipped: Int
            if (mode == ImportMode.REPLACE) {
                newEntries = plan.entries
                skipped = 0
                onProgress(0.65f)
            } else {
                // Smart import: skip entries that already exist (same content).
                val existingSignatures = loadExistingSignatures(existingEntries) { p ->
                    onProgress(0.55f + p * 0.1f)
                }
                newEntries = plan.entries.filter { it.signature !in existingSignatures }
                skipped = plan.entries.size - newEntries.size
            }
            onProgress(0.68f)
            val neededArchivePaths = newEntries.flatMap {
                listOfNotNull(it.primaryArchivePath, it.secondaryArchivePath)
            }.toSet()
            val neededAudio = plan.audioByArchivePath.filterKeys { it in neededArchivePaths }
            val committed = commitRestorePlan(newEntries.map { it.entity }, neededAudio.values) { p ->
                onProgress(0.68f + p * 0.32f)
            }
            // Adopt the backup's imported fonts (new ids only, cap applies;
            // each file is re-validated by the font store before adoption).
            // Audit fix: count the outcome — fonts used to be dropped
            // silently (duplicate content, cap, invalid file) while the
            // success message only ever mentioned entries.
            var fontsAdded = 0
            var fontsFailed = 0
            staged.fontMeta.forEach { (fontId, fontName) ->
                val stagedFile = staged.fontsByArchivePath.values
                    .firstOrNull { it.name.substringBefore('.') == fontId }
                if (stagedFile == null) {
                    fontsFailed++
                } else {
                    when (runCatching {
                        customFontStore.restoreFont(fontId, fontName, stagedFile)
                    }.getOrNull()) {
                        CustomFontStore.FontRestoreResult.ADOPTED -> fontsAdded++
                        CustomFontStore.FontRestoreResult.REJECTED -> fontsFailed++
                        else -> Unit // ALREADY_PRESENT or unexpected: nothing lost
                    }
                }
            }
            var removedExisting = 0
            if (mode == ImportMode.REPLACE && existingEntries.isNotEmpty()) {
                // The backup is fully committed; only now remove the old rows
                // and any of their audio files the new library doesn't use.
                val keptPaths = newEntries.flatMap {
                    listOfNotNull(it.entity.audioPath, it.entity.secondaryAudioPath)
                }.toSet()
                existingEntries.forEach { old -> repository.deleteById(old.id) }
                removedExisting = existingEntries.size
                existingEntries.flatMap { listOfNotNull(it.audioPath, it.secondaryAudioPath) }
                    .distinct()
                    .filter { it !in keptPaths }
                    .forEach { path -> runCatching { File(path).delete() } }
            }
            onProgress(1f)
            ImportResult(committed, skipped, removedExisting, fontsAdded, fontsFailed)
            }.onFailure { Log.e("BackupManager", "Import failed", it) }
                .also { stagingDir.deleteRecursively() }
        }
    }

    /** Signature for duplicate detection: normalized content + audio fingerprints. */
    private fun entrySignature(
        title: String,
        poet: String?,
        category: String,
        lyrics: String?,
        primaryDigest: String?,
        secondaryDigest: String?
    ): String = listOf(
        title.trim().lowercase(),
        poet?.trim()?.lowercase().orEmpty(),
        category,
        lyrics?.trim().orEmpty(),
        primaryDigest.orEmpty(),
        secondaryDigest.orEmpty()
    ).joinToString("|")

    private suspend fun loadExistingSignatures(
        existing: List<NaatEntity>,
        onProgress: (Float) -> Unit = {}
    ): Set<String> {
        return existing.mapIndexed { index, entry ->
            entrySignature(
                entry.title,
                entry.poet,
                entry.category,
                entry.lyrics,
                entry.audioPath?.let { path ->
                    File(path).takeIf { it.isFile }?.sha256()
                },
                entry.secondaryAudioPath?.let { path ->
                    File(path).takeIf { it.isFile }?.sha256()
                }
            ).also {
                onProgress((index + 1f) / existing.size.coerceAtLeast(1))
            }
        }.toSet()
    }

    private fun extractAndValidateArchive(
        inputUri: Uri,
        stagingDir: File,
        onProgress: (Float) -> Unit = {}
    ): StagedArchive {
        val input = context.contentResolver.openInputStream(inputUri)
            ?: throw IOException("Could not open input stream")
        val budget = ExtractionBudget()
        val audio = linkedMapOf<String, File>()
        val fonts = linkedMapOf<String, File>()
        var jsonContent: String? = null
        var entryCount = 0

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entryCount > MAX_ENTRIES) {
                    throw IOException("Backup archive has too many entries (>$MAX_ENTRIES)")
                }
                val name = entry.name
                when {
                    entry.isDirectory -> copyEntry(zip, null, budget)
                    name == DATA_ENTRY -> {
                        if (jsonContent != null) {
                            throw IOException("Backup archive contains duplicate $DATA_ENTRY entries")
                        }
                        jsonContent = readJsonEntry(zip, budget)
                    }
                    isSupportedAudioEntry(name) -> {
                        val destination = safeBackupDestination(stagingDir, name)
                            ?: throw IOException("Unsafe backup entry: $name")
                        if (audio.putIfAbsent(name, destination) != null) {
                            throw IOException("Duplicate backup entry: $name")
                        }
                        destination.parentFile?.mkdirs()
                        try {
                            FileOutputStream(destination).use { copyEntry(zip, it, budget) }
                        } catch (error: Exception) {
                            destination.delete()
                            throw error
                        }
                    }
                    V2_FONT_NAME.matches(name) -> {
                        val destination = safeBackupDestination(stagingDir, name)
                            ?: throw IOException("Unsafe backup entry: $name")
                        if (fonts.putIfAbsent(name, destination) != null) {
                            throw IOException("Duplicate backup entry: $name")
                        }
                        destination.parentFile?.mkdirs()
                        try {
                            FileOutputStream(destination).use { copyEntry(zip, it, budget) }
                        } catch (error: Exception) {
                            destination.delete()
                            throw error
                        }
                    }
                    else -> copyEntry(zip, null, budget)
                }
                zip.closeEntry()
            }
        }

        val document = jsonContent ?: throw IOException("Invalid backup file: $DATA_ENTRY not found")
        val (version, entries) = parseBackupDocument(document)
        val entriesOnly = if (version == FORMAT_VERSION) {
            JSONObject(document).optBoolean("entriesOnly", false)
        } else false
        if (version == FORMAT_VERSION) {
            audio.forEach { (archivePath, file) ->
                val expected = V2_AUDIO_NAME.matchEntire(archivePath)?.groupValues?.get(1)
                    ?: throw IOException("Invalid v2 audio path: $archivePath")
                if (file.sha256() != expected) throw IOException("Audio checksum mismatch: $archivePath")
            }
        }
        // Imported-font registry (v2 only; older backups simply have none).
        val fontMeta: List<Pair<String, String>> = if (version == FORMAT_VERSION) {
            val root = JSONObject(document)
            val array = root.optJSONArray("customFonts")
            buildList {
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val id = obj.optString("id")
                        val name = obj.optString("name")
                        val file = obj.optString("file")
                        // Only adopt fonts whose payload is actually staged
                        // under the strict fonts/<uuid>.<ext> name.
                        if (id.isNotBlank() && name.isNotBlank() &&
                            V2_FONT_NAME.matches(file) && fonts.containsKey(file) &&
                            file.removePrefix(V2_FONTS_PREFIX).substringBefore('.') == id
                        ) {
                            add(id to name)
                        }
                    }
                }
            }
        } else emptyList()
        return StagedArchive(version, entries, audio, fonts, fontMeta, entriesOnly)
    }

    private fun buildRestorePlan(staged: StagedArchive): RestorePlan {
        val plannedAudioByArchivePath = mutableMapOf<String, PlannedAudio>()

        fun planAudio(rawPath: String?, entryNumber: Int): Pair<PlannedAudio?, String?> {
            val archivePath = when (staged.formatVersion) {
                FORMAT_VERSION -> rawPath?.also {
                    if (V2_AUDIO_NAME.matchEntire(it) == null) {
                        throw IOException("Invalid audio reference in entry $entryNumber")
                    }
                }
                1 -> legacyArchivePath(rawPath)
                else -> null
            }
            val source = archivePath?.let(staged.audioByArchivePath::get)
            // An entries-only backup keeps the audio references in the
            // manifest but carries no payload: drop the reference (the entry
            // imports with no audio) instead of failing. Full backups stay
            // strict — a missing payload there is corruption, and still
            // fails loudly. Present-but-corrupt payloads are rejected earlier
            // by checksum, regardless of the entriesOnly marker.
            if (!staged.entriesOnly && staged.formatVersion == FORMAT_VERSION &&
                archivePath != null && source == null
            ) {
                throw IOException("Missing audio payload for entry $entryNumber")
            }
            val planned = if (source == null || archivePath == null) null else {
                plannedAudioByArchivePath.getOrPut(archivePath) {
                    val digest = source.sha256()
                    PlannedAudio(
                        source,
                        File(File(context.filesDir, "audio"), "$digest.${safeExtension(source.extension)}"),
                        digest
                    )
                }
            }
            return planned to archivePath
        }

        if (staged.entries.length() > MAX_ENTRIES) {
            throw IOException("Backup manifest has too many entries (>${MAX_ENTRIES})")
        }
        val entries = buildList {
            for (index in 0 until staged.entries.length()) {
                val obj = staged.entries.getJSONObject(index)
                val title = obj.getString("title")
                if (title.isBlank()) throw IOException("Backup entry ${index + 1} has a blank title")
                val (primary, primaryArchivePath) = planAudio(obj.nullableString("audioPath"), index + 1)
                val (secondary, secondaryArchivePath) = if (staged.formatVersion == FORMAT_VERSION) {
                    planAudio(obj.nullableString("secondaryAudioPath"), index + 1)
                } else null to null
                val poet = obj.nullableString("poet")
                val category = NaatCategories.normalize(obj.nullableString("category"))
                val lyrics = obj.nullableString("lyrics")
                val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                // v2 backups predate updatedAt; creation time is their truthful fallback.
                val updatedAt = obj.optLong("updatedAt", createdAt)
                // Older backups have no lastOpenedAt; NULL keeps the recents
                // fallback (COALESCE -> updatedAt) behaving exactly as before.
                val lastOpenedAt = obj.optLong("lastOpenedAt", 0L).takeIf { it > 0L }
                val entity = NaatEntity(
                    title = title,
                    poet = poet,
                    category = category,
                    lyrics = lyrics,
                    audioType = if (primary == null) "none" else obj.optString("audioType", "none"),
                    audioPath = primary?.destination?.absolutePath,
                    isFavorite = obj.optBoolean("isFavorite", false),
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    lastOpenedAt = lastOpenedAt,
                    secondaryAudioType = if (secondary == null) "none"
                        else obj.optString("secondaryAudioType", "none"),
                    secondaryAudioPath = secondary?.destination?.absolutePath,
                    // Older backups have no display names; they stay null there.
                    audioDisplayName = obj.nullableString("audioDisplayName"),
                    secondaryAudioDisplayName = obj.nullableString("secondaryAudioDisplayName"),
                    // Same for the per-entry reader-font override.
                    readerFontOverride = obj.nullableString("readerFontOverride")
                )
                val signature = entrySignature(
                    title, poet, category, lyrics,
                    primary?.digest, secondary?.digest
                )
                add(PlannedEntry(entity, signature, primaryArchivePath, secondaryArchivePath))
            }
        }
        return RestorePlan(entries, plannedAudioByArchivePath)
    }

    private suspend fun commitRestorePlan(
        entities: List<NaatEntity>,
        audio: Collection<PlannedAudio>,
        onProgress: (Float) -> Unit = {}
    ): Int {
        val createdFiles = mutableListOf<File>()
        try {
            val audioList = audio.toList()
            audioList.forEachIndexed { index, planned ->
                val destination = planned.destination
                destination.parentFile?.mkdirs()
                if (destination.exists()) {
                    if (!destination.isFile || destination.sha256() != planned.digest) {
                        throw IOException("Existing imported audio failed integrity check")
                    }
                } else {
                    val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
                    try {
                        FileInputStream(planned.staged).use { input ->
                            FileOutputStream(temporary).use { output ->
                                input.copyTo(output, BUFFER_SIZE)
                                output.fd.sync()
                            }
                        }
                        if (!temporary.renameTo(destination)) {
                            if (!destination.isFile || destination.sha256() != planned.digest) {
                                throw IOException("Could not commit imported audio")
                            }
                        } else {
                            createdFiles += destination
                        }
                    } finally {
                        temporary.delete()
                    }
                }
                onProgress((index + 1f) / audioList.size.coerceAtLeast(1) * 0.7f)
            }
            repository.insertAll(entities)
            onProgress(1f)
            return entities.size
        } catch (error: Exception) {
            createdFiles.forEach { it.delete() }
            throw error
        }
    }

    private fun isSupportedAudioEntry(name: String): Boolean =
        name.startsWith(V2_AUDIO_PREFIX) || name.startsWith(LEGACY_RECORDINGS_PREFIX) ||
            name.startsWith(LEGACY_LINKED_PREFIX)

    private fun readJsonEntry(zip: ZipInputStream, budget: ExtractionBudget): String {
        val output = ByteArrayOutputStream()
        copyEntry(zip, output, budget) { bytesRead ->
            if (bytesRead > MAX_JSON_BYTES) throw IOException("Backup metadata exceeds maximum allowed size")
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun copyEntry(
        zip: ZipInputStream,
        output: OutputStream?,
        budget: ExtractionBudget,
        onEntryBytes: (Long) -> Unit = {}
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var entryBytes = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            budget.add(count)
            entryBytes += count
            onEntryBytes(entryBytes)
            output?.write(buffer, 0, count)
        }
    }

    private fun parseBackupDocument(json: String): Pair<Int, JSONArray> {
        val trimmed = json.trimStart()
        if (trimmed.startsWith("[")) return 1 to JSONArray(trimmed)
        val root = JSONObject(trimmed)
        val version = root.optInt("formatVersion", -1)
        if (version != FORMAT_VERSION) throw IOException("Unsupported backup format version: $version")
        return version to root.getJSONArray("entries")
    }

    private fun legacyArchivePath(rawPath: String?): String? {
        if (rawPath == null) return null
        val normalized = rawPath.replace('\\', '/')
        return when {
            normalized.startsWith(LEGACY_RECORDINGS_PREFIX) -> normalized
            normalized.startsWith(LEGACY_LINKED_PREFIX) -> normalized
            "/$LEGACY_RECORDINGS_PREFIX" in normalized ->
                LEGACY_RECORDINGS_PREFIX + normalized.substringAfterLast("/$LEGACY_RECORDINGS_PREFIX")
            "/$LEGACY_LINKED_PREFIX" in normalized ->
                LEGACY_LINKED_PREFIX + normalized.substringAfterLast("/$LEGACY_LINKED_PREFIX")
            else -> null
        }
    }

    private fun safeExtension(extension: String): String =
        extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun JSONObject.nullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }
}
