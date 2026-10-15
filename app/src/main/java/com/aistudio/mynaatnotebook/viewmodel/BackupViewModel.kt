package com.aistudio.mynaatnotebook.viewmodel

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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private val settingsStore: SettingsStore
) : ViewModel() {

    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    fun backupNotebook(uri: Uri, entriesOnly: Boolean = false) {
        if (_isWorking.value) return
        _isWorking.value = true
        _progress.value = 0f
        viewModelScope.launch {
            try {
                statusReporter.show(R.string.status_exporting)
                val result = backupManager.exportBackup(uri, entriesOnly) { p ->
                    _progress.value = p.coerceIn(0f, 1f)
                }
                result.onSuccess { count ->
                    statusReporter.show(R.string.status_export_success, count)
                    settingsStore.recordBackup(
                        System.currentTimeMillis(),
                        uri.lastPathSegment?.substringAfterLast('/') ?: "Chosen place"
                    )
                    settingsStore.setLastExportUri(uri.toString())
                }.onFailure {
                    statusReporter.show(R.string.status_export_failed, it.localizedMessage ?: StatusMessage.Res(R.string.status_error_unknown))
                }
            } finally {
                _progress.value = null
                _isWorking.value = false
            }
        }
    }

    /** One-tap export into Download/NaatBook (no picker). */
    fun backupNotebookToAppFolder(fileName: String, entriesOnly: Boolean = false) {
        if (_isWorking.value) return
        _isWorking.value = true
        _progress.value = 0f
        viewModelScope.launch {
            try {
                statusReporter.show(R.string.status_exporting)
                val result = backupManager.exportBackupToAppFolder(fileName, entriesOnly) { p ->
                    _progress.value = p.coerceIn(0f, 1f)
                }
                result.onSuccess { (count, label) ->
                    statusReporter.show(R.string.status_export_success, count)
                    settingsStore.recordBackup(System.currentTimeMillis(), label)
                }.onFailure {
                    statusReporter.show(R.string.status_export_failed, it.localizedMessage ?: StatusMessage.Res(R.string.status_error_unknown))
                }
            } finally {
                _progress.value = null
                _isWorking.value = false
            }
        }
    }

    fun restoreNotebook(uri: Uri, mode: ImportMode = ImportMode.MERGE) {
        if (_isWorking.value) return
        _isWorking.value = true
        _progress.value = 0f
        // Audit fix: a REPLACE restore deletes the old library (and its audio
        // files) — if an entry was playing, its mini player used to linger as
        // a ghost over the new library. Stop playback up front instead.
        if (mode == ImportMode.REPLACE) playbackController.stop()
        viewModelScope.launch {
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
                        statusReporter.show(
                            R.string.status_import_success_skipped,
                            importResult.imported, importResult.skipped
                        )
                    } else {
                        statusReporter.show(R.string.status_import_success, importResult.imported)
                    }
                    // Font outcome was previously invisible: if the backup
                    // carried fonts that could not be restored (or new ones
                    // that were), say so after the main message has been read.
                    if (importResult.fontsFailed > 0 || importResult.fontsAdded > 0) {
                        delay(2_800)
                        if (importResult.fontsFailed > 0) {
                            statusReporter.show(
                                R.string.status_import_fonts_failed, importResult.fontsFailed
                            )
                        } else {
                            statusReporter.show(
                                R.string.status_import_fonts_restored, importResult.fontsAdded
                            )
                        }
                    }
                }.onFailure {
                    statusReporter.show(R.string.status_import_failed, it.localizedMessage ?: StatusMessage.Res(R.string.status_error_unknown))
                }
            } finally {
                _progress.value = null
                _isWorking.value = false
            }
        }
    }
}
