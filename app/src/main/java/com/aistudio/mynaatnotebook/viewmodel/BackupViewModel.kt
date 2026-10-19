package com.aistudio.mynaatnotebook.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.aistudio.mynaatnotebook.R
import com.aistudio.mynaatnotebook.audio.PlaybackController
import com.aistudio.mynaatnotebook.data.BackupManager
import com.aistudio.mynaatnotebook.data.ImportMode
import com.aistudio.mynaatnotebook.data.SettingsStore
import com.aistudio.mynaatnotebook.data.drive.DriveSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
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
                        statusReporter.show(R.string.status_export_failed, it.localizedMessage ?: StatusMessage.Res(R.string.status_error_unknown))
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
                        statusReporter.show(R.string.status_export_failed, it.localizedMessage ?: StatusMessage.Res(R.string.status_error_unknown))
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
            try {
                statusReporter.show(R.string.status_importing)
                val result = backupManager.importBackup(uri, mode) { p ->
                    _progress.value = p.coerceIn(0f, 1f)
                }
                result.onSuccess { importResult ->
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
                        statusReporter.show(R.string.status_import_failed, it.localizedMessage ?: StatusMessage.Res(R.string.status_error_unknown))
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
            }
        }
    }

    /**
     * Deletes the Drive-restore staging zip from the private cache when the
     * import was fed by it. Anything else (a user-picked backup) is left
     * strictly alone.
     */
    private fun deleteDriveRestoreCopy(uri: Uri) {
        if (uri.scheme != "file") return
        runCatching {
            val file = File(uri.path ?: return)
            val restoreZip = File(context.cacheDir, DriveSyncManager.RESTORE_ZIP_NAME)
            if (file.canonicalFile == restoreZip.canonicalFile) file.delete()
        }
    }
}
