package com.aistudio.mynaatnotebook.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.aistudio.mynaatnotebook.R
import com.aistudio.mynaatnotebook.audio.PlaybackController
import com.aistudio.mynaatnotebook.data.BackupManager
import com.aistudio.mynaatnotebook.data.EncryptedBackup
import com.aistudio.mynaatnotebook.data.ImportMode
import com.aistudio.mynaatnotebook.data.SettingsStore
import com.aistudio.mynaatnotebook.data.drive.DriveSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * User-controlled backup export / import.
 *
 * Extracted from NaatViewModel: backup is independent of library/editor state.
 * Progress and result messages go through the shared [StatusReporter] so the UI
 * keeps collecting a single status flow.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: BackupManager,
    private val encryptedBackup: EncryptedBackup,
    private val statusReporter: StatusReporter,
    private val playbackController: PlaybackController,
    private val settingsStore: SettingsStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    /** The in-flight operation, so the progress row names it (not just %). */
    enum class BackupOp { EXPORT, IMPORT }

    private val _operation = MutableStateFlow<BackupOp?>(null)
    val operation: StateFlow<BackupOp?> = _operation.asStateFlow()

    /** The in-flight backup/restore coroutine, so the UI can cancel it. */
    private var backupJob: Job? = null

    /** Audit TOP 4: stop an in-progress export/import. Cancellation is
     * cooperative — the backup manager checks in per chunk/entry, cleans
     * staging, and commits nothing; the failure branch below reports it as
     * "Cancelled", not an error. */
    fun cancelBackupWork() {
        backupJob?.cancel()
    }

    private fun isCancelled(error: Throwable) = error is CancellationException

    /**
     * UX batch 1 (audit S3): map a backup failure to a human reason. Raw
     * exception text — class names, file paths, stack fragments — never
     * reaches the user; only what happened and what to do about it.
     */
    private fun friendlyError(error: Throwable): StatusMessage {
        val message = error.message.orEmpty()
        return when {
            error is java.util.zip.ZipException ||
                message.contains("Invalid backup", ignoreCase = true) ->
                StatusMessage.Res(R.string.status_error_bad_backup)
            message.contains("No space", ignoreCase = true) ||
                message.contains("ENOSPC", ignoreCase = true) ->
                StatusMessage.Res(R.string.status_error_no_space)
            message.contains("Android 10", ignoreCase = true) ->
                StatusMessage.Res(R.string.status_error_no_folder)
            else -> StatusMessage.Res(R.string.status_error_unknown)
        }
    }

    fun backupNotebook(uri: Uri, entriesOnly: Boolean = false) {
        if (_isWorking.value) return
        _isWorking.value = true
        _operation.value = BackupOp.EXPORT
        _progress.value = 0f
        backupJob = viewModelScope.launch {
            try {
                statusReporter.show(R.string.status_exporting)
                val result = backupManager.exportBackup(uri, entriesOnly) { p ->
                    _progress.value = p.coerceIn(0f, 1f)
                }
                result.onSuccess { count ->
                    statusReporter.showPlural(R.plurals.status_export_success, count, count)
                    settingsStore.recordBackup(
                        System.currentTimeMillis(),
                        uri.lastPathSegment?.substringAfterLast('/') ?: "Chosen place"
                    )
                    settingsStore.setLastExportUri(uri.toString())
                }.onFailure {
                    if (isCancelled(it)) {
                        // The partial destination document is already removed
                        // by the manager's destinationTouched cleanup.
                        statusReporter.show(R.string.status_cancelled)
                    } else {
                        statusReporter.show(R.string.status_export_failed, friendlyError(it))
                    }
                }
            } finally {
                _operation.value = null
                _progress.value = null
                _isWorking.value = false
                backupJob = null
            }
        }
    }

    /** One-tap export into Download/NaatBook (no picker). */
    fun backupNotebookToAppFolder(fileName: String, entriesOnly: Boolean = false) {
        if (_isWorking.value) return
        _isWorking.value = true
        _operation.value = BackupOp.EXPORT
        _progress.value = 0f
        backupJob = viewModelScope.launch {
            try {
                statusReporter.show(R.string.status_exporting)
                val result = backupManager.exportBackupToAppFolder(fileName, entriesOnly) { p ->
                    _progress.value = p.coerceIn(0f, 1f)
                }
                result.onSuccess { (count, label) ->
                    statusReporter.showPlural(R.plurals.status_export_success, count, count)
                    settingsStore.recordBackup(System.currentTimeMillis(), label)
                }.onFailure {
                    if (isCancelled(it)) {
                        // The pending MediaStore row is already deleted by the
                        // manager's pendingUri cleanup.
                        statusReporter.show(R.string.status_cancelled)
                    } else {
                        statusReporter.show(R.string.status_export_failed, friendlyError(it))
                    }
                }
            } finally {
                _operation.value = null
                _progress.value = null
                _isWorking.value = false
                backupJob = null
            }
        }
    }

    /** Manifest entry count for the import confirmation dialog (audit 02 M1). */
    suspend fun peekEntryCount(uri: Uri): Int? =
        backupManager.peekEntryCount(uri).getOrNull()

    fun restoreNotebook(uri: Uri, mode: ImportMode = ImportMode.MERGE) {
        if (_isWorking.value) return
        _isWorking.value = true
        _operation.value = BackupOp.IMPORT
        _progress.value = 0f
        // Audit fix: a REPLACE restore deletes the old library (and its audio
        // files) — if an entry was playing, its mini player used to linger as
        // a ghost over the new library. Stop playback up front instead.
        if (mode == ImportMode.REPLACE) playbackController.stop()
        backupJob = viewModelScope.launch {
            settingsStore.setLastImportUri(uri.toString())
            // App lock: temp ZIP for a decrypted emergency file. Declared
            // OUTSIDE try so the finally block below can delete it.
            var decryptedTemp: File? = null
            try {
                // UX batch 1 (Arena S4): a REPLACE wipes the current library
                // with no undo, so a safety copy of it is saved FIRST. If the
                // copy cannot be made, the replace does not run at all.
                var safetyPlace: StatusMessage.Res? = null
                if (mode == ImportMode.REPLACE) {
                    statusReporter.show(R.string.status_safety_copy_making)
                    val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
                    val safetyName = "NaatBook_safety-copy_$stamp.zip"
                    val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    val safety: Result<Int> = if (modern) {
                        backupManager.exportBackupToAppFolder(safetyName)
                            .map { (count, _) -> count }
                    } else {
                        // The Downloads folder needs Android 10+; older
                        // phones keep the copy in the app's private folder.
                        val dir = File(context.filesDir, "safety-copies").apply { mkdirs() }
                        dir.listFiles()
                            ?.sortedByDescending { it.lastModified() }
                            ?.drop(1)
                            ?.forEach { old -> runCatching { old.delete() } }
                        backupManager.exportBackupToFile(File(dir, safetyName))
                    }
                    val safetyCount = safety.getOrElse { error ->
                        if (isCancelled(error)) {
                            statusReporter.show(R.string.status_cancelled)
                        } else {
                            statusReporter.show(R.string.status_safety_copy_failed)
                        }
                        return@launch
                    }
                    if (safetyCount > 0) {
                        safetyPlace = StatusMessage.Res(
                            if (modern) R.string.safety_place_downloads
                            else R.string.safety_place_app
                        )
                    }
                    _progress.value = 0f
                }
                statusReporter.show(R.string.status_importing)
                // App lock: an encrypted emergency file (.enc) decrypts to a
                // temp ZIP first; the temp file is deleted in the finally
                // block below. The original .enc file is never touched.
                var importUri = uri
                if (isEmergencyFile(uri)) {
                    val decrypted = encryptedBackup.decryptToTempZip(uri)
                    val temp = decrypted.getOrElse { error ->
                        statusReporter.show(
                            R.string.status_import_failed,
                            friendlyError(error)
                        )
                        return@launch
                    }
                    decryptedTemp = temp
                    importUri = Uri.fromFile(temp)
                }
                val result = backupManager.importBackup(importUri, mode) { p ->
                    _progress.value = p.coerceIn(0f, 1f)
                }
                result.onSuccess { importResult ->
                    // Audit fix L4: REPLACE removed these rows — drop
                    // their per-entry DataStore keys now, so they never
                    // orphan (the startup prune covers any gap).
                    if (importResult.removedIds.isNotEmpty()) {
                        importResult.removedIds.forEach { id ->
                            runCatching { settingsStore.clearPerEntryKeys(id) }
                        }
                    }
                    if (importResult.removedExisting > 0) {
                        statusReporter.show(
                            R.string.status_import_success_replaced,
                            importResult.imported, importResult.removedExisting
                        )
                    } else if (importResult.skipped > 0) {
                        statusReporter.showPlural(
                            R.plurals.status_import_success_skipped,
                            importResult.imported, importResult.imported, importResult.skipped
                        )
                    } else {
                        statusReporter.showPlural(R.plurals.status_import_success, importResult.imported, importResult.imported)
                    }
                    // Where the safety copy went — after the main receipt,
                    // like the font note below.
                    if (safetyPlace != null) {
                        delay(2_800)
                        statusReporter.show(R.string.status_safety_copy_saved, safetyPlace)
                    }
                    // Font outcome was previously invisible: if the backup
                    // carried fonts that could not be restored (or new ones
                    // that were), say so after the main message has been read.
                    if (importResult.fontsFailed > 0 || importResult.fontsAdded > 0) {
                        delay(2_800)
                        if (importResult.fontsFailed > 0) {
                            statusReporter.showPlural(
                                R.plurals.status_import_fonts_failed,
                                importResult.fontsFailed, importResult.fontsFailed
                            )
                        } else {
                            statusReporter.showPlural(
                                R.plurals.status_import_fonts_restored,
                                importResult.fontsAdded, importResult.fontsAdded
                            )
                        }
                    }
                }.onFailure {
                    if (isCancelled(it)) {
                        // Staging is deleted by the manager; nothing was
                        // committed (cancel during staging never reaches the
                        // commit phase), so plainly report the cancellation.
                        statusReporter.show(R.string.status_cancelled)
                    } else {
                        statusReporter.show(R.string.status_import_failed, friendlyError(it))
                    }
                }
            } finally {
                _operation.value = null
                _progress.value = null
                _isWorking.value = false
                backupJob = null
                // Audit fix (stage 1): a Drive restore staged the backup as
                // drive-restore.zip in the private cache. importBackup has
                // already consumed it into its own staging area (or failed
                // before needing it), so remove the copy — success and
                // failure alike. User-picked files are never touched (the
                // canonical-path identity check fails for anything else).
                deleteDriveRestoreCopy(uri)
                // App lock: the decrypted temp ZIP is staging only.
                decryptedTemp?.let { temp -> runCatching { temp.delete() } }
            }
        }
    }

    /**
     * True when [uri] names an encrypted emergency file (.enc). The display
     * name is queried for SAF documents; file URIs use the path directly.
     */
    private fun isEmergencyFile(uri: Uri): Boolean {
        if (uri.scheme == "file") return uri.path?.endsWith(".enc") == true
        val displayName = runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return displayName?.endsWith(".enc") == true ||
            uri.lastPathSegment?.endsWith(".enc") == true
    }

    /**
     * Deletes the Drive-restore staging zip from the private cache when the
     * import was fed by it. Anything else (a user-picked backup) is left
     * strictly alone.
     */
    /**
     * Audit fix M4: deletes the downloaded drive-restore.zip, but only
     * when [uri] actually IS that cache file (other schemes/paths are a
     * no-op). Besides the restore flow's finally block, the import
     * dialog's dismiss/cancel paths call this — cancelling the
     * Merge/Replace prompt never reached the finally block, so the file
     * used to strand in cache.
     */
    fun deleteDriveRestoreCopy(uri: Uri) {
        if (uri.scheme != "file") return
        runCatching {
            val file = File(uri.path ?: return)
            val restoreZip = File(context.cacheDir, DriveSyncManager.RESTORE_ZIP_NAME)
            if (file.canonicalFile == restoreZip.canonicalFile) file.delete()
        }
    }
}
