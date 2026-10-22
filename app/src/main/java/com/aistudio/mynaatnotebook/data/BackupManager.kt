package com.aistudio.mynaatnotebook.data

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.io.InputStream
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
    val fontsFailed: Int = 0,
    // Audit fix L4: ids of the rows REPLACE removed, so the caller can
    // drop their per-entry DataStore keys immediately.
    val removedIds: List<Int> = emptyList()
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
            currentCoroutineContext().ensureActive()
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
                put("titleFontOverride", naat.titleFontOverride ?: JSONObject.NULL)
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
                // Audit 05 M3: write the manifest through a Writer in one
                // pass instead of holding the whole JSON text AND its
                // UTF-8 byte copy at once (the old toString(4).toByteArray
                // doubled the peak). Full token streaming is not available:
                // Android's org.json JSONObject has no write(Writer).
                val manifestWriter = java.io.OutputStreamWriter(zip, Charsets.UTF_8)
                manifestWriter.write(root.toString(4))
                manifestWriter.flush()
                zip.closeEntry()
                val audioList = if (entriesOnly) emptyList() else audioIndex.uniqueFiles.toList()
                audioList.forEachIndexed { index, packed ->
                    currentCoroutineContext().ensureActive()
                    zip.putNextEntry(ZipEntry(packed.relativePath))
                    FileInputStream(packed.source).use { it.copyToCancellable(zip) }
                    zip.closeEntry()
                    onProgress(0.35f + (index + 1f) / audioList.size.coerceAtLeast(1) * 0.45f)
                }
                customFonts.forEach { (font, file) ->
                    currentCoroutineContext().ensureActive()
                    zip.putNextEntry(ZipEntry("$V2_FONTS_PREFIX${font.fileName}"))
                    FileInputStream(file).use { it.copyToCancellable(zip) }
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
                        input.copyToCancellable(output)
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
                // Audit fix M2: the Android 10 gate must run BEFORE
                // building the ZIP — on Android 7-9 the old order hashed
                // and compressed the whole library (up to 85% progress)
                // and only then failed the check.
                check(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    "The NaatBook folder needs Android 10 or newer — please use Choose another place"
                }
                val entryCount = writeBackupZip(stagedZip, onProgress, entriesOnly)
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
                    FileInputStream(stagedZip).use { input -> input.copyToCancellable(output) }
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
                        input.copyToCancellable(output)
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
            // Audit 02 L1: the plain sum let an edit and a delete cancel
            // each other out. A weighted mix plus the lyrics footprint and
            // the font files' sizes makes a silent collision practically
            // impossible for a change signal (it is not a security hash).
            append(naats.sumOf { it.updatedAt * 31L + it.id }).append('|')
            append(naats.sumOf { (it.lyrics?.length ?: 0).toLong() }).append('|')
            // Audit fix M5: starring/unstarring never touched updatedAt,
            // and the hash never saw isFavorite — so favorite-only
            // changes never triggered Drive auto-sync. (A changed
            // formula simply triggers one fresh sync; it is a change
            // signal, not a stored format.)
            append(naats.filter { it.isFavorite }.sumOf { it.id * 17L + 1L }).append('|')
            append(fonts.joinToString(",") { font ->
                val file = customFontStore.fileFor(font)
                "${font.id}:${file.length()}:${file.lastModified()}"
            })
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    /**
     * Entry count of a backup file, for the import confirmation dialog
     * (audit 02 M1): the REPLACE warning used to be static, so an empty
     * backup looked exactly like a full one. Reads only the manifest
     * entry; nothing is extracted.
     */
    suspend fun peekEntryCount(inputUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val input = context.contentResolver.openInputStream(inputUri)
                ?: throw IOException("Could not open input stream")
            val budget = ExtractionBudget()
            var count: Int? = null
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == DATA_ENTRY) {
                        count = parseBackupDocument(readJsonEntry(zip, budget)).second.length()
                        break
                    }
                    zip.closeEntry()
                }
            }
            count ?: throw IOException("Invalid backup file: $DATA_ENTRY not found")
        }
    }

    private suspend fun buildAudioIndex(
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
            currentCoroutineContext().ensureActive()
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
     * one Room transaction (in REPLACE, the inserts and the old-row deletes share one
     * transaction). Any pre-commit failure leaves the notebook untouched; a database
     * failure rolls back only files created by this attempt.
     */
    /**
     * Audit fix H3: rewrite a "custom:<backupId>" font override to the
     * local UUID an identical font already has. Non-custom values and
     * unmapped ids pass through untouched.
     */
    private fun remapFontOverride(value: String?, remap: Map<String, String>): String? {
        if (value == null || !value.startsWith("custom:")) return value
        val mapped = remap[value.removePrefix("custom:")] ?: return value
        return "custom:$mapped"
    }

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
            // Audit fix (stage 1): dedupe within the archive itself — two
            // entries with the same duplicate signature must not both land
            // (first occurrence wins). The exporter never produces this;
            // only a hand-made file can. In-archive duplicates stay counted
            // in `skipped` for MERGE so the result stays truthful.
            val dedupedEntries = plan.entries.distinctBy { it.signature }
            // Audit fix H3: restore the backup's fonts BEFORE the entities
            // land, so a byte-identical font that already exists locally
            // under another UUID can be remapped (custom:<backupId> ->
            // custom:<existingId>) on every restored entry. MERGE
            // adoptions inherit the remap too — they are derived from
            // these planned entities below. (Caveat: fonts adopted here
            // stay adopted even if the later commit fails — same as any
            // standalone font import, and a retry finds them already
            // present instead of duplicating them.)
            var fontsAdded = 0
            var fontsFailed = 0
            val fontIdRemap = mutableMapOf<String, String>()
            staged.fontMeta.forEach { (fontId, fontName) ->
                val stagedFile = staged.fontsByArchivePath.values
                    .firstOrNull { it.name.substringBefore('.') == fontId }
                if (stagedFile == null) {
                    fontsFailed++
                } else {
                    when (val result = runCatching {
                        customFontStore.restoreFont(fontId, fontName, stagedFile)
                    }.getOrNull()) {
                        CustomFontStore.FontRestoreResult.Adopted -> fontsAdded++
                        is CustomFontStore.FontRestoreResult.Duplicate ->
                            fontIdRemap[fontId] = result.existingId
                        CustomFontStore.FontRestoreResult.Rejected -> fontsFailed++
                        else -> Unit // AlreadyPresent or unexpected: nothing lost
                    }
                }
            }
            val planEntries = if (fontIdRemap.isEmpty()) dedupedEntries else dedupedEntries.map { planned ->
                planned.copy(
                    entity = planned.entity.copy(
                        readerFontOverride = remapFontOverride(planned.entity.readerFontOverride, fontIdRemap),
                        titleFontOverride = remapFontOverride(planned.entity.titleFontOverride, fontIdRemap)
                    )
                )
            }
            // Snapshot of the current library: in MERGE it drives duplicate
            // skipping; in REPLACE its rows are removed together with the
            // commit, so any pre-commit failure leaves the library untouched.
            val existingEntries = repository.allNaats.first()
            val newEntries: List<PlannedEntry>
            val skipped: Int
            // Audit 02 M3: rows adopted in place during a MERGE (metadata
            // refresh / audio re-attach) are applied after the main commit.
            var metadataUpdates: List<NaatEntity> = emptyList()
            val reattachArchivePaths = mutableListOf<String?>()
            if (mode == ImportMode.REPLACE) {
                newEntries = planEntries
                skipped = 0
                onProgress(0.65f)
            } else {
                // Smart import: skip entries that already exist (same content).
                val existingMatches = loadExistingMatches(existingEntries) { p ->
                    onProgress(0.55f + p * 0.1f)
                }
                val exactBySignature = existingMatches.associateBy { it.exactSignature }
                val byTextSignature = existingMatches.groupBy { it.textSignature }
                val usedExistingIds = mutableSetOf<Int>()
                val updates = mutableListOf<NaatEntity>()
                newEntries = planEntries.filter { planned ->
                    val plannedPrimary = planned.primaryArchivePath
                        ?.let { plan.audioByArchivePath[it]?.digest }
                    val plannedSecondary = planned.secondaryArchivePath
                        ?.let { plan.audioByArchivePath[it]?.digest }
                    val exact = exactBySignature[planned.signature]
                    if (exact != null && usedExistingIds.add(exact.entry.id)) {
                        // Same content already in the library: keep the row,
                        // but adopt the backup's favorite flag, font
                        // overrides and audio names — they used to be
                        // dropped silently on a skip (audit 02 M3).
                        mergeMetadata(exact.entry, planned.entity)?.let { updates += it }
                        false
                    } else {
                        // Re-attach case (audit 02 M3): same text, but the
                        // existing row's audio file is missing from disk,
                        // so its digest could never match and the entry was
                        // imported as a duplicate row. When the backup
                        // carries that audio, point the existing row at the
                        // restored file instead.
                        val plannedText = entrySignature(
                            planned.entity.title, planned.entity.poet,
                            planned.entity.category, planned.entity.lyrics, null, null
                        )
                        val candidate = byTextSignature[plannedText]?.firstOrNull { match ->
                            match.entry.id !in usedExistingIds &&
                                slotRematchable(match.primaryDigest, match.entry.audioPath, plannedPrimary) &&
                                slotRematchable(match.secondaryDigest, match.entry.secondaryAudioPath, plannedSecondary) &&
                                (slotMissing(match.primaryDigest, match.entry.audioPath) ||
                                    slotMissing(match.secondaryDigest, match.entry.secondaryAudioPath))
                        }
                        if (candidate == null) {
                            true
                        } else {
                            usedExistingIds += candidate.entry.id
                            var merged = candidate.entry
                            if (slotMissing(candidate.primaryDigest, candidate.entry.audioPath)) {
                                merged = merged.copy(
                                    audioPath = planned.entity.audioPath,
                                    audioType = planned.entity.audioType
                                )
                                reattachArchivePaths += planned.primaryArchivePath
                            }
                            if (slotMissing(candidate.secondaryDigest, candidate.entry.secondaryAudioPath)) {
                                merged = merged.copy(
                                    secondaryAudioPath = planned.entity.secondaryAudioPath,
                                    secondaryAudioType = planned.entity.secondaryAudioType
                                )
                                reattachArchivePaths += planned.secondaryArchivePath
                            }
                            updates += mergeMetadata(merged, planned.entity) ?: merged
                            false
                        }
                    }
                }
                metadataUpdates = updates
                skipped = plan.entries.size - newEntries.size
            }
            onProgress(0.68f)
            val neededArchivePaths = (newEntries.flatMap {
                listOfNotNull(it.primaryArchivePath, it.secondaryArchivePath)
            } + reattachArchivePaths.filterNotNull()).toSet()
            val neededAudio = plan.audioByArchivePath.filterKeys { it in neededArchivePaths }
            // Audit fix (stage 1): files commit first (rollback unchanged);
            // the database insert and — in REPLACE — the old-row deletes then
            // share one Room transaction, so a failure mid-delete can no
            // longer leave a mixed old+new library in the DB.
            commitAudioFiles(neededAudio.values) { p ->
                onProgress(0.68f + p * 0.24f)
            }
            onProgress(0.92f)
            val entities = newEntries.map { it.entity }
            val committed: Int
            var removedExisting = 0
            var removedIds: List<Int> = emptyList()
            if (mode == ImportMode.REPLACE && existingEntries.isNotEmpty()) {
                val keptPaths = entities.flatMap {
                    listOfNotNull(it.audioPath, it.secondaryAudioPath)
                }.toSet()
                committed = repository.replaceLibrary(
                    entities, existingEntries.map { it.id }
                ).size
                removedExisting = existingEntries.size
                removedIds = existingEntries.map { it.id }
                // Any of the old rows' audio files the new library doesn't
                // use are now orphans; delete them (best effort, as before).
                existingEntries.flatMap { listOfNotNull(it.audioPath, it.secondaryAudioPath) }
                    .distinct()
                    .filter { it !in keptPaths }
                    .forEach { path -> runCatching { File(path).delete() } }
            } else {
                committed = repository.insertAll(entities).size
            }
            // MERGE adoptions (audit 02 M3): favorite/override refresh and
            // audio re-attaches land only after the main commit succeeded;
            // each is best-effort so one bad row cannot fail the import.
            metadataUpdates.forEach { updated ->
                runCatching { repository.update(updated) }
            }
            onProgress(0.96f)
            // Audit fix H3: fonts were already restored (and overrides
            // remapped) before the entities landed — see above. Nothing
            // left to do here; the counters below carry that outcome.
            onProgress(1f)
            sweepOrphanImportedAudio()
            ImportResult(
                committed, skipped, removedExisting, fontsAdded, fontsFailed,
                removedIds = removedIds
            )
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

    private data class ExistingEntryMatch(
        val entry: NaatEntity,
        val exactSignature: String,
        val textSignature: String,
        val primaryDigest: String?,
        val secondaryDigest: String?
    )

    /** A slot whose row records a path but whose file is gone from disk. */
    private fun slotMissing(existingDigest: String?, existingPath: String?): Boolean =
        existingPath != null && existingDigest == null

    /**
     * A slot can take part in a re-attach match when both sides agree, or
     * when the existing file is missing and the backup brings a digest.
     */
    private fun slotRematchable(
        existingDigest: String?,
        existingPath: String?,
        plannedDigest: String?
    ): Boolean =
        existingDigest == plannedDigest ||
            (slotMissing(existingDigest, existingPath) && plannedDigest != null)

    /**
     * Metadata a MERGE skip adopts from the backup (audit 02 M3): the
     * favorite flag comes from the backup; font overrides and audio names
     * only fill/replace when the backup actually carries them. Returns
     * null when nothing would change.
     */
    private fun mergeMetadata(existing: NaatEntity, incoming: NaatEntity): NaatEntity? {
        val merged = existing.copy(
            isFavorite = incoming.isFavorite,
            readerFontOverride = incoming.readerFontOverride ?: existing.readerFontOverride,
            titleFontOverride = incoming.titleFontOverride ?: existing.titleFontOverride,
            audioDisplayName = incoming.audioDisplayName?.takeIf { it.isNotBlank() }
                ?: existing.audioDisplayName,
            secondaryAudioDisplayName = incoming.secondaryAudioDisplayName
                ?.takeIf { it.isNotBlank() } ?: existing.secondaryAudioDisplayName
        )
        return merged.takeIf { it != existing }
    }

    private suspend fun loadExistingMatches(
        existing: List<NaatEntity>,
        onProgress: (Float) -> Unit = {}
    ): List<ExistingEntryMatch> {
        return existing.mapIndexed { index, entry ->
            currentCoroutineContext().ensureActive()
            val primaryDigest = entry.audioPath?.let { path ->
                File(path).takeIf { it.isFile }?.sha256()
            }
            val secondaryDigest = entry.secondaryAudioPath?.let { path ->
                File(path).takeIf { it.isFile }?.sha256()
            }
            ExistingEntryMatch(
                entry = entry,
                exactSignature = entrySignature(
                    entry.title, entry.poet, entry.category, entry.lyrics,
                    primaryDigest, secondaryDigest
                ),
                textSignature = entrySignature(
                    entry.title, entry.poet, entry.category, entry.lyrics, null, null
                ),
                primaryDigest = primaryDigest,
                secondaryDigest = secondaryDigest
            ).also {
                onProgress((index + 1f) / existing.size.coerceAtLeast(1))
            }
        }
    }

    /**
     * Audit 02 L4: REPLACE's per-row file cleanup is post-commit and
     * best-effort, so a crash in between used to strand orphan files
     * forever. The imported-audio store is content-addressed and only
     * import commits and library rows ever reference it — anything in it
     * that no row points at is an orphan and can go. (Legacy recordings/
     * and linked/ folders are NOT swept: an unsaved editor draft's take
     * can legitimately sit there unreferenced.)
     */
    private suspend fun sweepOrphanImportedAudio() {
        runCatching {
            val referenced = repository.allNaats.first()
                .flatMap { listOfNotNull(it.audioPath, it.secondaryAudioPath) }
                .map { File(it).canonicalPath }
                .toSet()
            File(context.filesDir, "audio").listFiles()?.forEach { file ->
                if (file.isFile && file.canonicalPath !in referenced) {
                    runCatching { file.delete() }
                }
            }
        }
    }

    private suspend fun extractAndValidateArchive(
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
                currentCoroutineContext().ensureActive()
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
                    readerFontOverride = obj.nullableString("readerFontOverride"),
                    // Newer backups carry the per-entry title-font override too.
                    titleFontOverride = obj.nullableString("titleFontOverride")
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

    /**
     * Commits the staged audio files: tmp+rename per file, rolling back only
     * files created by this attempt on failure. Rows are inserted by the
     * caller afterwards (in REPLACE, inserts and old-row deletes share one
     * Room transaction — see [NaatDao.replaceLibrary]).
     */
    private suspend fun commitAudioFiles(
        audio: Collection<PlannedAudio>,
        onProgress: (Float) -> Unit = {}
    ) {
        val createdFiles = mutableListOf<File>()
        try {
            val audioList = audio.toList()
            audioList.forEachIndexed { index, planned ->
                currentCoroutineContext().ensureActive()
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
                                input.copyToCancellable(output)
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
            onProgress(1f)
        } catch (error: Exception) {
            createdFiles.forEach { it.delete() }
            throw error
        }
    }

    private fun isSupportedAudioEntry(name: String): Boolean =
        name.startsWith(V2_AUDIO_PREFIX) || name.startsWith(LEGACY_RECORDINGS_PREFIX) ||
            name.startsWith(LEGACY_LINKED_PREFIX)

    private suspend fun readJsonEntry(zip: ZipInputStream, budget: ExtractionBudget): String {
        val output = ByteArrayOutputStream()
        copyEntry(zip, output, budget) { bytesRead ->
            if (bytesRead > MAX_JSON_BYTES) throw IOException("Backup metadata exceeds maximum allowed size")
        }
        return output.toString(Charsets.UTF_8.name())
    }

    /**
     * Chunked copy that observes coroutine cancellation (audit TOP 4): every
     * long file movement in export/import goes through here, checking in per
     * chunk, so the Cancel button actually stops the work instead of waiting
     * for a multi-hundred-megabyte copy to finish.
     */
    private suspend fun InputStream.copyToCancellable(out: OutputStream): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            copied += count
        }
        return copied
    }

    private suspend fun copyEntry(
        zip: ZipInputStream,
        output: OutputStream?,
        budget: ExtractionBudget,
        onEntryBytes: (Long) -> Unit = {}
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var entryBytes = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
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

    /**
     * Audit 05 M4: import/export hash the same audio files repeatedly
     * (dedupe, signature, verify, plan) — each pass used to re-read the
     * whole file from disk. Digests are memoized by file identity
     * (path + length + mtime); a changed file invalidates itself.
     */
    private val shaCache = java.util.concurrent.ConcurrentHashMap<String, Triple<Long, Long, String>>()

    private fun File.sha256(): String {
        if (isFile) {
            val key = absolutePath
            val length = length()
            val modified = lastModified()
            shaCache[key]?.let { (len, mod, hash) ->
                if (len == length && mod == modified) return hash
            }
            val computed = computeSha256()
            shaCache[key] = Triple(length, modified, computed)
            return computed
        }
        return computeSha256()
    }

    private fun File.computeSha256(): String {
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
