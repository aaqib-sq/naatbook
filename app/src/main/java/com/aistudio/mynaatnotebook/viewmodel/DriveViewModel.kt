package com.aistudio.mynaatnotebook.viewmodel

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.mynaatnotebook.R
import com.aistudio.mynaatnotebook.data.DriveSyncInterval
import com.aistudio.mynaatnotebook.data.SettingsStore
import com.aistudio.mynaatnotebook.data.shouldAutoSync
import com.aistudio.mynaatnotebook.data.drive.DriveBackupInfo
import com.aistudio.mynaatnotebook.data.drive.DriveException
import com.aistudio.mynaatnotebook.data.drive.DriveSyncManager
import com.aistudio.mynaatnotebook.data.drive.SyncOutcome
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Google Drive backup state for Settings: connection, manual sync,
 * restore (download, then the shared merge/replace dialog takes over),
 * disconnect, and the opt-in auto-sync check on app open.
 *
 * Result messages go through the shared [StatusReporter], like backup.
 */
@HiltViewModel
class DriveViewModel @Inject constructor(
    private val driveManager: DriveSyncManager,
    private val settingsStore: SettingsStore,
    private val statusReporter: StatusReporter
) : ViewModel() {

    private val _connectedEmail = MutableStateFlow<String?>(null)
    val connectedEmail: StateFlow<String?> = _connectedEmail.asStateFlow()

    private val _lastSyncText = MutableStateFlow<String?>(null)
    /** Formatted last-sync time; null = connected but never synced. */
    val lastSyncText: StateFlow<String?> = _lastSyncText.asStateFlow()

    private val _autoSync = MutableStateFlow(false)
    val autoSync: StateFlow<Boolean> = _autoSync.asStateFlow()

    private val _syncInterval = MutableStateFlow(DriveSyncInterval.WEEKLY)
    val syncInterval: StateFlow<DriveSyncInterval> = _syncInterval.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    /** The in-flight operation, so the progress row names it (not just %). */
    enum class DriveOp { SYNC, DOWNLOAD }

    private val _operation = MutableStateFlow<DriveOp?>(null)
    val operation: StateFlow<DriveOp?> = _operation.asStateFlow()

    private val _pendingRestoreUri = MutableStateFlow<Uri?>(null)
    /** Downloaded Drive backup awaiting the merge/replace choice. */
    val pendingRestoreUri: StateFlow<Uri?> = _pendingRestoreUri.asStateFlow()

    private val _restoreChoices = MutableStateFlow<List<DriveBackupInfo>?>(null)
    /**
     * UX batch 1 (audit B1): when Drive holds several backups, restore
     * shows them (newest first, with dates and entry counts) instead of
     * silently downloading the newest one.
     */
    val restoreChoices: StateFlow<List<DriveBackupInfo>?> = _restoreChoices.asStateFlow()

    private val _consentIntent = MutableStateFlow<Intent?>(null)
    /** Google permission screen to show so an interrupted action can retry. */
    val consentIntent: StateFlow<Intent?> = _consentIntent.asStateFlow()

    private enum class PendingAction { NONE, SYNC, DOWNLOAD }
    private var pendingAction = PendingAction.NONE

    /** The in-flight Drive sync/download coroutine, so the UI can cancel it. */
    private var driveJob: Job? = null

    /** Audit TOP 4: stop an in-progress Drive sync or restore download. The
     * manager checks in per 1 MiB chunk; a partially uploaded backup simply
     * never completes (the resumable session is abandoned). */
    fun cancelDriveWork() {
        driveJob?.cancel()
    }

    init {
        viewModelScope.launch { settingsStore.driveEmail.collect { _connectedEmail.value = it } }
        viewModelScope.launch { settingsStore.driveAutoSync.collect { _autoSync.value = it } }
        viewModelScope.launch { settingsStore.driveSyncInterval.collect { _syncInterval.value = it } }
        viewModelScope.launch {
            settingsStore.driveLastSyncAt.collect { at ->
                _lastSyncText.value = if (at > 0L) {
                    SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(at))
                } else null
            }
        }
        // The Google sign-in cache is the source of truth; mirror it into
        // the store so the UI survives process death either way.
        viewModelScope.launch {
            val accountEmail = driveManager.signedInAccount()?.email
            val stored = settingsStore.driveEmail.first()
            if (accountEmail != null && stored != accountEmail) {
                settingsStore.setDriveEmail(accountEmail)
            } else if (accountEmail == null && stored != null) {
                settingsStore.clearDriveState()
            }
        }
    }

    fun signInIntent(): Intent = driveManager.signInClient().signInIntent

    fun onSignInResult(data: Intent?) {
        val account = runCatching {
            GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
        }.getOrNull()
        val email = account?.email
        if (email == null) {
            statusReporter.show(R.string.status_drive_sign_in_failed)
            return
        }
        viewModelScope.launch { settingsStore.setDriveEmail(email) }
        statusReporter.show(R.string.status_drive_connected, email)
        // Audit round 2 (S1): never upload blindly on sign-in. If Drive
        // already holds backups, this device may be a fresh install whose
        // (possibly empty) library must not become the newest backup —
        // point at Restore instead. Only a genuinely empty Drive folder
        // gets the immediate first backup. If the check itself fails,
        // upload nothing; "Sync now" stays one tap away and is guarded.
        viewModelScope.launch {
            driveManager.remoteBackups()
                .onSuccess { existing ->
                    if (existing.isEmpty()) {
                        runSync(manual = true)
                    } else {
                        statusReporter.show(R.string.status_drive_restore_available)
                    }
                }
                .onFailure { error ->
                    // Audit 03 H3: the check failing used to be silent —
                    // the user saw "connected" and assumed a backup ran.
                    if (error !is CancellationException) {
                        statusReporter.show(R.string.status_drive_check_failed)
                    }
                }
        }
    }

    fun syncNow() = runSync(manual = true)

    private val _emptySyncConfirm = MutableStateFlow(false)
    /** Manual sync was skipped by the empty-library guard; ask first. */
    val emptySyncConfirm: StateFlow<Boolean> = _emptySyncConfirm.asStateFlow()

    fun confirmEmptySync() {
        _emptySyncConfirm.value = false
        runSync(manual = true, forced = true)
    }

    fun dismissEmptySync() {
        _emptySyncConfirm.value = false
    }

    private fun runSync(manual: Boolean, forced: Boolean = false) {
        if (_isSyncing.value) return
        _isSyncing.value = true
        _operation.value = DriveOp.SYNC
        _progress.value = 0f
        driveJob = viewModelScope.launch {
            try {
                if (manual) statusReporter.show(R.string.status_drive_syncing)
                driveManager.syncNow(
                    onProgress = { p -> _progress.value = p.coerceIn(0f, 1f) },
                    forced = forced
                )
                    .onSuccess { outcome ->
                        when (outcome) {
                            is SyncOutcome.Uploaded -> statusReporter.showPlural(
                                R.plurals.status_drive_sync_success, outcome.count, outcome.count
                            )
                            SyncOutcome.SkippedEmpty -> if (manual) {
                                _emptySyncConfirm.value = true
                            } else {
                                statusReporter.show(R.string.status_drive_empty_skipped)
                            }
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) {
                            statusReporter.show(R.string.status_cancelled)
                        } else {
                            handleDriveError(error, PendingAction.SYNC)
                        }
                    }
            } finally {
                _operation.value = null
                _progress.value = null
                _isSyncing.value = false
                driveJob = null
            }
        }
    }

    /**
     * Restore entry point (UX batch 1, audit B1): list the Drive backups
     * first. Exactly one backup downloads straight away; several open the
     * chooser — restore never silently means "the newest".
     */
    fun restoreLatest() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        _operation.value = DriveOp.DOWNLOAD
        driveJob = viewModelScope.launch {
            try {
                driveManager.remoteBackups()
                    .onSuccess { backups ->
                        when {
                            backups.isEmpty() ->
                                statusReporter.show(R.string.status_drive_no_backup)
                            backups.size == 1 -> downloadById(backups.first().fileId)
                            else -> _restoreChoices.value = backups
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) {
                            statusReporter.show(R.string.status_cancelled)
                        } else {
                            handleDriveError(error, PendingAction.DOWNLOAD)
                        }
                    }
            } finally {
                _operation.value = null
                _progress.value = null
                _isSyncing.value = false
                driveJob = null
            }
        }
    }

    /** The user picked one backup in the restore chooser: download it. */
    fun chooseRestoreBackup(info: DriveBackupInfo) {
        _restoreChoices.value = null
        if (_isSyncing.value) return
        _isSyncing.value = true
        _operation.value = DriveOp.DOWNLOAD
        _progress.value = 0f
        driveJob = viewModelScope.launch {
            try {
                downloadById(info.fileId)
            } finally {
                _operation.value = null
                _progress.value = null
                _isSyncing.value = false
                driveJob = null
            }
        }
    }

    fun dismissRestoreChoices() {
        _restoreChoices.value = null
    }

    /** Download one backup and hand it to the shared import dialog. */
    private suspend fun downloadById(fileId: String) {
        _progress.value = 0f
        statusReporter.show(R.string.status_drive_downloading)
        driveManager.downloadBackup(fileId) { p -> _progress.value = p.coerceIn(0f, 1f) }
            .onSuccess { file -> _pendingRestoreUri.value = Uri.fromFile(file) }
            .onFailure { error ->
                if (error is CancellationException) {
                    statusReporter.show(R.string.status_cancelled)
                } else {
                    handleDriveError(error, PendingAction.DOWNLOAD)
                }
            }
    }

    fun consumeRestoreUri() {
        _pendingRestoreUri.value = null
    }

    fun consumeConsentIntent() {
        _consentIntent.value = null
    }

    fun onConsentResult(granted: Boolean) {
        val action = pendingAction
        pendingAction = PendingAction.NONE
        if (!granted) {
            statusReporter.show(R.string.status_drive_consent_declined)
            return
        }
        when (action) {
            PendingAction.SYNC -> runSync(manual = true)
            PendingAction.DOWNLOAD -> restoreLatest()
            PendingAction.NONE -> Unit
        }
    }

    fun setAutoSync(enabled: Boolean) {
        _autoSync.value = enabled
        viewModelScope.launch { settingsStore.setDriveAutoSync(enabled) }
    }

    fun setSyncInterval(interval: DriveSyncInterval) {
        _syncInterval.value = interval
        viewModelScope.launch { settingsStore.setDriveSyncInterval(interval) }
    }

    /**
     * Auto-sync check, called when the app comes to the foreground: if the
     * feature is on and the library changed since the last sync, upload a
     * fresh backup quietly (only failures and the success receipt surface).
     * The pure [shouldAutoSync] rule decides whether the chosen interval
     * has elapsed; manual "Sync now" never passes through it.
     */
    fun maybeAutoSync() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            if (settingsStore.driveEmail.first() == null) return@launch
            if (!settingsStore.driveAutoSync.first()) return@launch
            val current = runCatching { driveManager.contentHash() }.getOrNull() ?: return@launch
            if (shouldAutoSync(
                    interval = settingsStore.driveSyncInterval.first(),
                    connected = true,
                    autoSyncOn = true,
                    hashChanged = current != settingsStore.driveLastHash.first(),
                    lastSyncAtMs = settingsStore.driveLastSyncAt.first(),
                    nowMs = System.currentTimeMillis()
                )
            ) runSync(manual = false)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            driveManager.signOut()
            settingsStore.clearDriveState()
            statusReporter.show(R.string.status_drive_disconnected)
        }
    }

    private fun handleDriveError(error: Throwable, action: PendingAction) {
        val driveError = error as? DriveException
        when {
            driveError?.consentIntent != null -> {
                pendingAction = action
                _consentIntent.value = driveError.consentIntent
                statusReporter.show(R.string.status_drive_consent_needed)
            }
            driveError?.noBackup == true ->
                statusReporter.show(R.string.status_drive_no_backup)
            driveError?.needsReconnect == true -> {
                viewModelScope.launch { settingsStore.clearDriveState() }
                statusReporter.show(R.string.status_drive_reconnect_needed)
            }
            // UX batch 1 (audit S3): only DriveException messages — written
            // in this codebase, in human words — may reach the user. Any
            // other exception's raw text stays in the logs, not the UI.
            action == PendingAction.DOWNLOAD -> statusReporter.show(
                R.string.status_drive_download_failed,
                if (error is DriveException) {
                    error.message ?: StatusMessage.Res(R.string.status_error_unknown)
                } else {
                    StatusMessage.Res(R.string.status_error_unknown)
                }
            )
            else -> statusReporter.show(
                R.string.status_drive_sync_failed,
                if (error is DriveException) {
                    error.message ?: StatusMessage.Res(R.string.status_error_unknown)
                } else {
                    StatusMessage.Res(R.string.status_error_unknown)
                }
            )
        }
    }
}
