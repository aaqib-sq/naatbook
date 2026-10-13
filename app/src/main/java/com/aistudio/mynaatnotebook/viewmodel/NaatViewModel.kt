package com.aistudio.mynaatnotebook.viewmodel

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.mynaatnotebook.audio.*
import com.aistudio.mynaatnotebook.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aistudio.mynaatnotebook.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject

private const val DRAFT_PERSIST_DEBOUNCE_MS = 150L

@HiltViewModel
class NaatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val repository: NaatRepository,
    private val audioFiles: AudioFileLifecycleCoordinator,
    private val audioRecorder: AudioRecorder,
    val playbackController: PlaybackController,
    private val statusReporter: StatusReporter
) : ViewModel() {

    private val draftStore = EditorDraftStore(savedStateHandle)
    private val draftDiskStore = EditorDraftDiskStore(context)
    // SavedStateHandle carries only lightweight draft metadata; disk owns lyrics.
    private val initialDraft = draftStore.restore()
    private val _editorDraft = MutableStateFlow(initialDraft)
    private var draftPersistenceJob: Job? = null
    private val draftPersistenceMutex = Mutex()
    private var draftRevision = 0L

    companion object {
        /** Max audio attachment size: 100 MB. */
        private const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024
    }
    val editorDraft: StateFlow<EditorDraft> = _editorDraft.asStateFlow()
    val editorEntryId: StateFlow<Int?> = editorDraft
        .map { it.editingId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialDraft.editingId)
    val editorMetadata: StateFlow<EditorMetadataDraft> = editorDraft
        .map { EditorMetadataDraft(it.editingId, it.title, it.poet, it.category, it.lyrics) }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            EditorMetadataDraft(
                initialDraft.editingId, initialDraft.title, initialDraft.poet,
                initialDraft.category, initialDraft.lyrics
            )
        )
    val editorAttachments: StateFlow<EditorAttachmentDraft> = editorDraft
        .map {
            EditorAttachmentDraft(
                it.existingAudioRemoved, it.existingAudioType, it.existingAudioPath,
                it.existingSecondaryAudioRemoved, it.existingSecondaryAudioType,
                it.existingSecondaryAudioPath, it.newAttachmentPath,
                it.newAttachmentName, it.finishedRecordingPath
            )
        }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            EditorAttachmentDraft(
                initialDraft.existingAudioRemoved, initialDraft.existingAudioType,
                initialDraft.existingAudioPath, initialDraft.existingSecondaryAudioRemoved,
                initialDraft.existingSecondaryAudioType, initialDraft.existingSecondaryAudioPath,
                initialDraft.newAttachmentPath, initialDraft.newAttachmentName,
                initialDraft.finishedRecordingPath
            )
        )

    private val saveGate = OperationGate()
    private val deleteGate = OperationGate()
    private val attachmentGate = OperationGate()
    private val recordingGate = OperationGate()
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()
    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()
    private val _isAttachingFile = MutableStateFlow(false)
    val isAttachingFile: StateFlow<Boolean> = _isAttachingFile.asStateFlow()
    private val _isOpeningNowPlaying = MutableStateFlow(false)
    val isOpeningNowPlaying: StateFlow<Boolean> = _isOpeningNowPlaying.asStateFlow()

    init {
        // Housekeeping is serialized with every create/adopt/save/delete/import path.
        // It reads both the live draft snapshot and disk draft so startup can never
        // classify an attachment created by a concurrent editor operation as orphaned.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                audioFiles.exclusive {
                    val referenced = buildSet {
                        repository.allNaats.first().forEach { naat ->
                            naat.audioPath?.let(::add)
                            naat.secondaryAudioPath?.let(::add)
                        }
                        _editorDraft.value.newAttachmentPath?.let(::add)
                        _editorDraft.value.finishedRecordingPath?.let(::add)
                        draftDiskStore.read()?.takeIf { it.active }?.let { diskDraft ->
                            diskDraft.newAttachmentPath?.let(::add)
                            diskDraft.finishedRecordingPath?.let(::add)
                        }
                    }
                    listOf(
                        recordingsDirectory(),
                        linkedDirectory(),
                        importedAudioDirectory()
                    ).forEach { dir ->
                        dir.listFiles()?.forEach { file ->
                            if (file.isFile && file.absolutePath !in referenced && file.delete()) {
                                Log.d("NaatViewModel", "Cleaned orphaned audio file: ${file.name}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("NaatViewModel", "Orphaned audio cleanup failed", e)
            }
        }
    }

    // Preferences & Settings are DataStore-backed; legacy values migrate in once.

    // UI Navigation Screen State. Add is a transient FAB action, not a tab.
    private val _currentTab = MutableStateFlow(0) // 0: Library, 2: Settings
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

    private val _showAddModal = MutableStateFlow(initialDraft.active)
    val showAddModal: StateFlow<Boolean> = _showAddModal.asStateFlow()

    private val _selectedNaat = MutableStateFlow<NaatEntity?>(null)
    val selectedNaat: StateFlow<NaatEntity?> = _selectedNaat.asStateFlow()

    // Filter and Search States
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFolder = MutableStateFlow<String?>(null) // null means "All" / Folder Grid View
    val selectedFolder: StateFlow<String?> = _selectedFolder.asStateFlow()

    private val _showFavoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()

    fun toggleFavoritesOnly() {
        _showFavoritesOnly.value = !_showFavoritesOnly.value
    }

    fun setFavoritesOnly(enabled: Boolean) {
        _showFavoritesOnly.value = enabled
    }

    /** Returns the Library tab to its root view: no folder, no filter, no search text. */
    fun resetLibraryToHome() {
        _selectedFolder.value = null
        _showFavoritesOnly.value = false
        _searchQuery.value = ""
    }

    // Lightweight Library state: lyrics are never materialized for cards/folder counts.
    val allSummaries: StateFlow<List<NaatSummary>> = repository.allSummaries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Room-backed strict LIMIT 10 feed, ordered by newest create/edit timestamp. */
    val recentSummaries: StateFlow<List<NaatSummary>> = repository.recentSummaries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categoryCounts: StateFlow<Map<String, Int>> = repository.categoryCounts
        .map { rows ->
            rows.groupBy { NaatCategories.normalize(it.category) }
                .mapValues { (_, group) -> group.sumOf { it.count } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val filteredSummaries: StateFlow<List<NaatSummary>> = combine(
        _searchQuery.debounce(160).distinctUntilChanged(),
        _selectedFolder,
        _showFavoritesOnly
    ) { query, folder, favoritesOnly -> Triple(query.trim(), folder, favoritesOnly) }
        .distinctUntilChanged()
        .flatMapLatest { (query, folder, favoritesOnly) ->
            // Room executes title/poet/lyrics matching on its query executor and returns only
            // summary columns, avoiding full-lyrics scans and allocations on the UI thread.
            repository.filteredSummaries(query, folder, favoritesOnly)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active recording file state (for adding a new Naat). File validation happens
    // on IO during disk hydration, never while constructing the ViewModel on Main.
    private val _activeRecordingFile = MutableStateFlow<File?>(null)
    val activeRecordingFile: StateFlow<File?> = _activeRecordingFile.asStateFlow()

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    // Live recording meter (elapsed time + amplitude for the VU meter)
    private val _recordingElapsedMs = MutableStateFlow(0L)
    val recordingElapsedMs: StateFlow<Long> = _recordingElapsedMs.asStateFlow()

    private val _recordingAmplitude = MutableStateFlow(0)
    val recordingAmplitude: StateFlow<Int> = _recordingAmplitude.asStateFlow()

    init {
        // Disk is authoritative for heavy draft text. Hydrate it even when a
        // lightweight SavedStateHandle snapshot exists so process restoration never
        // depends on a potentially Binder-sized lyrics bundle.
        viewModelScope.launch(Dispatchers.IO) {
            val diskDraft = runCatching { draftDiskStore.read() }.getOrNull()
                ?.takeIf { it.active }
            val candidate = diskDraft ?: _editorDraft.value.takeIf { it.active }
            val activeFile = candidate?.finishedRecordingPath
                ?.let(::File)
                ?.takeIf { it.isFile }
            withContext(Dispatchers.Main.immediate) {
                if (draftRevision == 0L) {
                    diskDraft?.let {
                        _editorDraft.value = it
                        draftStore.save(it)
                        _showAddModal.value = true
                    }
                    _activeRecordingFile.value = activeFile
                }
            }
        }
    }

    private var recordingMeterJob: Job? = null

    // Entry currently being edited in the modal (null = "add new" mode)
    private val _editingNaat = MutableStateFlow<NaatEntity?>(null)
    val editingNaat: StateFlow<NaatEntity?> = _editingNaat.asStateFlow()

    private fun startRecordingMeter() {
        recordingMeterJob?.cancel()
        _recordingElapsedMs.value = 0L
        recordingMeterJob = viewModelScope.launch {
            while (isActive) {
                _recordingElapsedMs.value = audioRecorder.getElapsedMs()
                _recordingAmplitude.value = audioRecorder.getMaxAmplitude()
                delay(120)
            }
        }
    }

    private fun stopRecordingMeter() {
        recordingMeterJob?.cancel()
        recordingMeterJob = null
        _recordingElapsedMs.value = 0L
        _recordingAmplitude.value = 0
    }

    private fun cleanupDraftFilesAsync(draft: EditorDraft, activePath: String?) {
        val paths = listOf(activePath, draft.finishedRecordingPath, draft.newAttachmentPath)
        viewModelScope.launch {
            audioFiles.exclusive {
                withContext(Dispatchers.IO) {
                    DraftFileCleanup.discard(
                        listOf(draft.existingAudioPath, draft.existingSecondaryAudioPath),
                        paths
                    )
                }
            }
        }
    }

    // Status/toast messages are shared process-wide via StatusReporter so every
    // feature ViewModel reports through the single channel the UI already collects.
    val statusMessage: StateFlow<StatusMessage?> = statusReporter.message

    fun clearStatusMessage() = statusReporter.clear()

    private fun persistDraft(draft: EditorDraft, immediate: Boolean = false) {
        _editorDraft.value = draft
        val revision = ++draftRevision
        draftPersistenceJob?.cancel()

        fun saveHandleIfCurrent() {
            if (revision == draftRevision) draftStore.save(draft)
        }
        if (immediate) {
            // Attachment/mode boundaries are rare and must survive immediately.
            saveHandleIfCurrent()
            draftPersistenceJob = viewModelScope.launch(Dispatchers.IO) {
                draftPersistenceMutex.withLock {
                    if (revision != draftRevision) return@withLock
                    runCatching {
                        if (draft.active) draftDiskStore.write(draft) else draftDiskStore.delete()
                    }.onFailure { Log.e("NaatViewModel", "Draft snapshot failed", it) }
                }
            }
        } else {
            // Typing stays purely in-memory. One snapshot is written after the user pauses,
            // rather than rewriting every SavedStateHandle key and the full lyrics each keypress.
            draftPersistenceJob = viewModelScope.launch {
                delay(DRAFT_PERSIST_DEBOUNCE_MS)
                saveHandleIfCurrent()
                withContext(Dispatchers.IO) {
                    draftPersistenceMutex.withLock {
                        if (revision != draftRevision) return@withLock
                        runCatching { draftDiskStore.write(draft) }
                            .onFailure { Log.e("NaatViewModel", "Draft snapshot failed", it) }
                    }
                }
            }
        }
    }

    fun updateDraft(transform: (EditorDraft) -> EditorDraft) {
        persistDraft(transform(_editorDraft.value))
    }

    private fun clearDraft() = persistDraft(EditorDraft(), immediate = true)

    fun startAddDraft(forceFresh: Boolean = false) {
        if (_editorDraft.value.active && !forceFresh) return
        if (forceFresh) {
            // Detach ownership immediately for responsive navigation. Native capture
            // stops now; all file cleanup is serialized and runs on IO afterwards.
            val old = _editorDraft.value
            val activePath = if (_recordingState.value != RecordingState.IDLE) {
                stopRecorderForDiscardOnMain()
            } else {
                _activeRecordingFile.value?.absolutePath.also { _activeRecordingFile.value = null }
            }
            playbackController.stopPreview()
            cleanupDraftFilesAsync(old, activePath)
        }
        persistDraft(EditorDraft(active = true), immediate = true)
        _editingNaat.value = null
        _showAddModal.value = true
    }

    fun selectTab(index: Int) {
        // Library and Settings are persistent top-level tabs. Add is intentionally
        // opened only through startAddDraft from the docked FAB.
        if (index == 0 || index == 2) _currentTab.value = index
    }

    /** Used by the editor shell before an explicit X/Back discard request. */
    fun hasUnsavedEditorChanges(): Boolean =
        _editorDraft.value.hasUnsavedChanges(_editingNaat.value)

    /** Explicit cancellation/discard. Recorder finalization always precedes file cleanup. */
    fun setShowAddModal(show: Boolean) {
        if (!show && !_showAddModal.value) return
        if (show) {
            if (!_editorDraft.value.active) startAddDraft() else _showAddModal.value = true
            return
        }
        if (_isSaving.value || _isAttachingFile.value) return
        val draft = _editorDraft.value
        val activePath = if (_recordingState.value != RecordingState.IDLE) {
            stopRecorderForDiscardOnMain()
        } else {
            _activeRecordingFile.value?.absolutePath.also { _activeRecordingFile.value = null }
        }
        // Stop only the UI-owned preview; service-owned entry playback survives.
        playbackController.stopPreview()
        cleanupDraftFilesAsync(draft, activePath)
        _editingNaat.value = null
        clearDraft()
        _showAddModal.value = false
    }

    fun selectNaat(naat: NaatEntity?) {
        // Closing the reader (null) leaves playback running — the MediaSession
        // owns that session in the background now. Only switching directly to a
        // DIFFERENT entry (or deleting it) stops the old audio: anti-bleed rule.
        if (naat != null &&
            naat.id != _selectedNaat.value?.id &&
            naat.id != playbackController.nowPlaying.value?.naatId
        ) {
            playbackController.stop()
        }
        _selectedNaat.value = naat
    }

    /** Starts a media-session-owned listening session for an entry (from the reader). */
    fun startEntryPlayback(naat: NaatEntity) {
        naat.audioPath?.let { playbackController.playEntry(naat, it) }
    }

    fun startEntryPlayback(naat: NaatEntity, path: String) {
        playbackController.playEntry(naat, path)
    }

    /** Mini-player tap: resolve through Room; the list flow may still hold its initial empty value. */
    fun openNowPlayingEntry(onResolved: (Boolean) -> Unit = {}) {
        if (_isOpeningNowPlaying.value) return
        val requested = playbackController.nowPlaying.value ?: return
        _isOpeningNowPlaying.value = true
        viewModelScope.launch {
            try {
                val entry = repository.getNaatById(requested.naatId)
                // Ignore a result for a session replaced while Room was queried.
                if (playbackController.nowPlaying.value?.naatId != requested.naatId) return@launch
                if (entry != null) {
                    _selectedNaat.value = entry
                    onResolved(true)
                } else {
                    playbackController.stop() // database confirmed deletion
                    onResolved(false)
                }
            } catch (e: Exception) {
                Log.e("NaatViewModel", "Unable to open now-playing entry", e)
                statusReporter.show(R.string.status_open_playing_failed, e.localizedMessage ?: StatusMessage.Res(R.string.status_error_database))
                onResolved(false) // preserve playback when lookup itself failed
            } finally {
                _isOpeningNowPlaying.value = false
            }
        }
    }

    fun selectFolder(folder: String?) {
        _selectedFolder.value = folder
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // --- Audio recording and app-owned file lifecycle ---
    private fun recordingsDirectory(): File = File(context.filesDir, "recordings")
    private fun linkedDirectory(): File = File(context.filesDir, "linked")
    private fun importedAudioDirectory(): File = File(context.filesDir, "audio")

    private suspend fun ensureDirectoryOnIo(directory: File): File = withContext(Dispatchers.IO) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create ${directory.name} directory")
        }
        if (!directory.isDirectory) throw IOException("Invalid ${directory.name} directory")
        directory
    }

    private suspend fun deleteOnIo(path: String?) = withContext(Dispatchers.IO) {
        path?.let { runCatching { File(it).delete() } }
    }

    private suspend fun isUsableAudioFileOnIo(file: File?): Boolean = withContext(Dispatchers.IO) {
        file?.isFile == true && file.length() > 0L
    }

    /** Called only while [audioFiles] is held. Native recorder calls stay on Main; file IO stays off it. */
    private suspend fun finalizeRecordingLocked(expectedPath: String? = null): File? {
        val file = _activeRecordingFile.value
        // A queued Finish action must never finalize or mutate a newer take after
        // the editor was closed/reopened while it waited for the lifecycle lock.
        if (expectedPath != null && file?.absolutePath != expectedPath) return null
        val stoppedCleanly = withContext(Dispatchers.Main.immediate) {
            val stopped = audioRecorder.stop()
            _recordingState.value = RecordingState.IDLE
            stopRecordingMeter()
            stopped
        }
        val valid = stoppedCleanly && isUsableAudioFileOnIo(file)
        if (!valid) deleteOnIo(file?.absolutePath)
        return withContext(Dispatchers.Main.immediate) {
            if (valid && file != null) {
                persistDraft(_editorDraft.value.copy(finishedRecordingPath = file.absolutePath))
                file
            } else {
                _activeRecordingFile.value = null
                persistDraft(_editorDraft.value.copy(finishedRecordingPath = null))
                null
            }
        }
    }

    /** Stops native capture immediately for a discard/edit transition; file cleanup stays async on IO. */
    private fun stopRecorderForDiscardOnMain(): String? {
        val activePath = _activeRecordingFile.value?.absolutePath
        audioRecorder.stop()
        _recordingState.value = RecordingState.IDLE
        stopRecordingMeter()
        _activeRecordingFile.value = null
        return activePath
    }

    fun startRecording() {
        if (!recordingGate.tryStart()) return
        viewModelScope.launch {
            try {
                audioFiles.exclusive {
                    val draft = _editorDraft.value
                    val requestedRevision = draftRevision
                    if (!draft.active || !_showAddModal.value) return@exclusive
                    val obsoletePaths = setOfNotNull(
                        _activeRecordingFile.value?.absolutePath,
                        draft.finishedRecordingPath
                    ).filter {
                        it != draft.existingAudioPath && it != draft.existingSecondaryAudioPath
                    }
                    val file = withContext(Dispatchers.IO) {
                        obsoletePaths.forEach { path -> runCatching { File(path).delete() } }
                        File(
                            ensureDirectoryOnIo(recordingsDirectory()),
                            "record_${System.currentTimeMillis()}.m4a"
                        )
                    }
                    val started = withContext(Dispatchers.Main.immediate) {
                        if (!_editorDraft.value.active || !_showAddModal.value ||
                            draftRevision != requestedRevision
                        ) {
                            false
                        } else {
                            playbackController.stop() // prevent playback feedback while capturing a fresh take
                            _activeRecordingFile.value = file
                            persistDraft(draft.copy(finishedRecordingPath = file.absolutePath))
                            val startedCleanly = audioRecorder.start(file)
                            _recordingState.value = audioRecorder.getRecordingState()
                            if (_recordingState.value == RecordingState.RECORDING) startRecordingMeter()
                            startedCleanly && _recordingState.value == RecordingState.RECORDING
                        }
                    }
                    if (!started) {
                        deleteOnIo(file.absolutePath)
                        withContext(Dispatchers.Main.immediate) {
                            _activeRecordingFile.value = null
                            persistDraft(_editorDraft.value.copy(finishedRecordingPath = null))
                            stopRecordingMeter()
                        }
                        statusReporter.show(R.string.status_recording_start_failed)
                    }
                }
            } catch (error: Exception) {
                Log.e("NaatViewModel", "Unable to start recording", error)
                statusReporter.show(R.string.status_recording_start_failed_detail, error.localizedMessage ?: StatusMessage.Res(R.string.status_error_storage))
            } finally {
                recordingGate.finish()
            }
        }
    }

    fun pauseRecording() {
        audioRecorder.pause()
        _recordingState.value = audioRecorder.getRecordingState()
        _recordingAmplitude.value = 0 // VU meter flattens while paused
    }

    fun resumeRecording() {
        audioRecorder.resume()
        _recordingState.value = audioRecorder.getRecordingState()
    }

    fun stopRecording() {
        if (!recordingGate.tryStart()) return
        val requestedPath = _activeRecordingFile.value?.absolutePath
        viewModelScope.launch {
            try {
                audioFiles.exclusive { finalizeRecordingLocked(requestedPath) }
            } finally {
                recordingGate.finish()
            }
        }
    }

    /** Discard the current take. File deletion is serialized and always off Main. */
    fun discardRecording() {
        if (!recordingGate.tryStart()) return
        val requestedPath = _activeRecordingFile.value?.absolutePath
        viewModelScope.launch {
            try {
                audioFiles.exclusive {
                    val file = _activeRecordingFile.value
                    if (requestedPath == null || file?.absolutePath != requestedPath) return@exclusive
                    withContext(Dispatchers.Main.immediate) {
                        audioRecorder.stop()
                        playbackController.stopPreview()
                        _recordingState.value = RecordingState.IDLE
                        stopRecordingMeter()
                        _activeRecordingFile.value = null
                        persistDraft(_editorDraft.value.copy(finishedRecordingPath = null))
                    }
                    deleteOnIo(file.absolutePath)
                }
            } finally {
                recordingGate.finish()
            }
        }
    }

    /** Open the add/edit modal pre-filled with an existing entry. */
    fun startEditNaat(naat: NaatEntity) {
        // Editing the entry invalidates its playing session (attachment may change).
        playbackController.stop()
        val current = _editorDraft.value
        if (!current.active || current.editingId != naat.id) {
            if (current.active) {
                val activePath = if (_recordingState.value != RecordingState.IDLE) {
                    stopRecorderForDiscardOnMain()
                } else {
                    _activeRecordingFile.value?.absolutePath.also { _activeRecordingFile.value = null }
                }
                cleanupDraftFilesAsync(current, activePath)
            }
            persistDraft(EditorDraft(
                active = true,
                editingId = naat.id,
                title = naat.title,
                poet = naat.poet.orEmpty(),
                category = NaatCategories.normalize(naat.category),
                lyrics = naat.lyrics.orEmpty(),
                existingAudioType = naat.audioType,
                existingAudioPath = naat.audioPath,
                existingSecondaryAudioType = naat.secondaryAudioType,
                existingSecondaryAudioPath = naat.secondaryAudioPath,
                existingFavorite = naat.isFavorite,
                existingCreatedAt = naat.createdAt
            ), immediate = true)
        }
        _editingNaat.value = naat
        _showAddModal.value = true
    }

    // --- Local device file attachment ---
    private fun resolveAudioExtension(uri: Uri): String {
        // 1. MIME type from ContentResolver -> extension
        val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val fromMime = mimeType?.let {
            android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
        }
        if (!fromMime.isNullOrBlank()) return fromMime.lowercase()
        // 2. Extension from URI path / display name
        val fromPath = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        if (!fromPath.isNullOrBlank() && fromPath.length <= 5) return fromPath
        // 3. Fallback
        return "mp3"
    }

    private suspend fun copyLocalFileToAppStorageLocked(uri: Uri): File? = withContext(Dispatchers.IO) {
        // Reject files over 100 MB to avoid filling app-private storage silently.
        val fileSize = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        if (fileSize > MAX_ATTACHMENT_BYTES) {
            withContext(Dispatchers.Main.immediate) {
                statusReporter.show(R.string.editor_file_too_large)
            }
            return@withContext null
        }
        // Preserve the source file's extension instead of hardcoding .mp3 so the
        // player/sniffer sees the real format (wav, ogg, m4a, ...).
        val extension = resolveAudioExtension(uri)
        val destination = File(
            ensureDirectoryOnIo(linkedDirectory()),
            "linked_${System.currentTimeMillis()}_file.$extension"
        )
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
            input.use { inputStream ->
                FileOutputStream(destination).use { outputStream ->
                    inputStream.copyTo(outputStream)
                    outputStream.fd.sync()
                }
            }
            // Validate that the copied file is actually playable audio.
            // A corrupt or non-audio file would otherwise fail silently at playback time.
            if (!isPlayableAudio(destination)) {
                Log.w("NaatViewModel", "Attached file is not valid audio, rejecting")
                withContext(Dispatchers.Main.immediate) {
                    statusReporter.show(R.string.editor_invalid_audio_file)
                }
                runCatching { destination.delete() }
                return@withContext null
            }
            destination
        } catch (error: Exception) {
            Log.e("NaatViewModel", "Failed to copy local file", error)
            runCatching { destination.delete() }
            null
        }
    }

    /** Returns true if the file has a readable audio track. */
    private fun isPlayableAudio(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            // Has audio if duration metadata exists and is positive.
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            duration > 0
        } catch (error: Exception) {
            Log.w("NaatViewModel", "Audio validation failed", error)
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun attachLocalFile(uri: Uri, onResult: (Boolean) -> Unit = {}) {
        if (!attachmentGate.tryStart()) return
        _isAttachingFile.value = true
        viewModelScope.launch {
            try {
                val success = audioFiles.exclusive {
                    val file = copyLocalFileToAppStorageLocked(uri)
                    if (file != null && _editorDraft.value.active) {
                        adoptLinkedFileLocked(file)
                        true
                    } else {
                        deleteOnIo(file?.absolutePath)
                        false
                    }
                }
                onResult(success)
            } finally {
                _isAttachingFile.value = false
                attachmentGate.finish()
            }
        }
    }

    /** Called only while [audioFiles] is held; replaces a draft-owned linked file atomically. */
    private suspend fun adoptLinkedFileLocked(file: File) {
        if (_recordingState.value != RecordingState.IDLE) finalizeRecordingLocked()
        withContext(Dispatchers.Main.immediate) { playbackController.stopPreview() }
        val draft = _editorDraft.value
        val superseded = draft.newAttachmentPath?.takeIf {
            it != draft.existingAudioPath && it != draft.existingSecondaryAudioPath &&
                it != file.absolutePath
        }
        deleteOnIo(superseded)
        withContext(Dispatchers.Main.immediate) {
            persistDraft(draft.copy(
                newAttachmentPath = file.absolutePath,
                newAttachmentName = file.name
            ))
        }
    }

    /** Public helper retained for callers that already own a copied app-private file. */
    fun adoptLinkedFile(file: File) {
        viewModelScope.launch {
            audioFiles.exclusive { adoptLinkedFileLocked(file) }
        }
    }

    /** Deletes an app-owned file only when no database entry still references it. */
    fun deleteOrphanFile(path: String) {
        viewModelScope.launch {
            audioFiles.exclusive { deleteAudioIfUnreferencedLocked(path) }
        }
    }

    /** Called only while [audioFiles] is held, closing the Room-count/delete TOCTOU window. */
    private suspend fun deleteAudioIfUnreferencedLocked(path: String) {
        if (repository.countByAudioPath(path) > 0) return
        withContext(Dispatchers.IO) {
            val file = File(path).canonicalFile
            val basePath = context.filesDir.canonicalPath + File.separator
            if (file.path.startsWith(basePath) && file.isFile) file.delete()
        }
    }

    // --- CRUD DB Operations ---
    fun saveDraft() {
        if (!saveGate.tryStart()) return
        // Defensive: UI blocks blank titles, but never trust the UI alone.
        if (_editorDraft.value.title.isBlank()) {
            saveGate.finish()
            statusReporter.show(R.string.editor_title_required)
            return
        }
        _isSaving.value = true

        viewModelScope.launch {
            try {
                audioFiles.exclusive {
                    if (_recordingState.value != RecordingState.IDLE) {
                        finalizeRecordingLocked()
                    } else {
                        withContext(Dispatchers.Main.immediate) {
                            audioRecorder.stop()
                            stopRecordingMeter()
                        }
                    }

                    var draft = _editorDraft.value
                    val finishedRecording = draft.finishedRecordingPath
                        ?.let(::File)
                        ?.takeIf { isUsableAudioFileOnIo(it) }
                    if (draft.finishedRecordingPath != null && finishedRecording == null) {
                        deleteOnIo(draft.finishedRecordingPath)
                        draft = draft.copy(finishedRecordingPath = null)
                        withContext(Dispatchers.Main.immediate) { persistDraft(draft) }
                    }

                    fun existingPath(type: String): String? = when {
                        !draft.existingAudioRemoved && draft.existingAudioType == type -> draft.existingAudioPath
                        !draft.existingSecondaryAudioRemoved && draft.existingSecondaryAudioType == type ->
                            draft.existingSecondaryAudioPath
                        else -> null
                    }

                    val recordingPath = finishedRecording?.absolutePath ?: existingPath("recorded")
                    val linkedPath = draft.newAttachmentPath ?: existingPath("local_file")
                    val audioType = when {
                        recordingPath != null -> "recorded"
                        linkedPath != null -> "local_file"
                        else -> "none"
                    }
                    val audioPath = recordingPath ?: linkedPath
                    val secondaryAudioType = if (recordingPath != null && linkedPath != null) {
                        "local_file"
                    } else {
                        "none"
                    }
                    val secondaryAudioPath = if (secondaryAudioType == "local_file") linkedPath else null
                    val persistedAt = System.currentTimeMillis()

                    val saved = if (draft.editingId == null) {
                        val entity = NaatEntity(
                            title = draft.title,
                            poet = draft.poet.takeIf { it.isNotBlank() },
                            category = draft.category,
                            lyrics = draft.lyrics.takeIf { it.isNotBlank() },
                            audioType = audioType,
                            audioPath = audioPath,
                            isFavorite = false,
                            createdAt = persistedAt,
                            updatedAt = persistedAt,
                            secondaryAudioType = secondaryAudioType,
                            secondaryAudioPath = secondaryAudioPath
                        )
                        val id = repository.insert(entity).toInt()
                        entity.copy(id = id)
                    } else {
                        val entity = NaatEntity(
                            id = draft.editingId,
                            title = draft.title,
                            poet = draft.poet.takeIf { it.isNotBlank() },
                            category = draft.category,
                            lyrics = draft.lyrics.takeIf { it.isNotBlank() },
                            audioType = audioType,
                            audioPath = audioPath,
                            isFavorite = draft.existingFavorite,
                            createdAt = draft.existingCreatedAt,
                            updatedAt = persistedAt,
                            secondaryAudioType = secondaryAudioType,
                            secondaryAudioPath = secondaryAudioPath
                        )
                        repository.update(entity)
                        entity
                    }

                    // The database row now owns retained files. Keep reference checks and
                    // old-file cleanup inside the same lifecycle lock as this save.
                    val retainedPaths = setOfNotNull(audioPath, secondaryAudioPath)
                    setOfNotNull(draft.existingAudioPath, draft.existingSecondaryAudioPath)
                        .filter { it !in retainedPaths }
                        .forEach { oldPath ->
                            try {
                                deleteAudioIfUnreferencedLocked(oldPath)
                            } catch (error: Exception) {
                                Log.w("NaatViewModel", "Saved, but old audio cleanup failed", error)
                            }
                        }

                    withContext(Dispatchers.Main.immediate) {
                        _activeRecordingFile.value = null
                        clearDraft()
                        _editingNaat.value = null
                        _showAddModal.value = false
                        _recordingState.value = RecordingState.IDLE
                        if (_selectedNaat.value?.id == saved.id) _selectedNaat.value = saved
                        statusReporter.show(
                            if (draft.editingId == null) {
                                R.string.status_entry_saved
                            } else {
                                R.string.status_entry_updated
                            }
                        )
                    }
                }
            } catch (error: Exception) {
                Log.e("NaatViewModel", "Save failed", error)
                statusReporter.show(R.string.status_save_failed, error.localizedMessage ?: StatusMessage.Res(R.string.status_error_database))
                // Keep the modal and draft files intact so the user can retry.
            } finally {
                _isSaving.value = false
                saveGate.finish()
            }
        }
    }

    fun toggleFavorite(naat: NaatEntity) = toggleFavorite(naat.id)

    fun toggleFavorite(id: Int) {
        viewModelScope.launch {
            try {
                val updated = repository.toggleFavorite(id) ?: return@launch
                if (_selectedNaat.value?.id == id) _selectedNaat.value = updated
                if (_editorDraft.value.editingId == id) {
                    persistDraft(_editorDraft.value.copy(existingFavorite = updated.isFavorite))
                }
            } catch (e: Exception) {
                Log.e("NaatViewModel", "Favorite toggle failed", e)
                statusReporter.show(R.string.status_favorite_failed)
            }
        }
    }

    fun loadNaat(
        id: Int,
        onLoaded: (NaatEntity) -> Unit,
        onFailure: () -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                repository.getNaatById(id)?.let(onLoaded) ?: run {
                    statusReporter.show(R.string.status_entry_missing)
                    onFailure()
                }
            } catch (error: Exception) {
                Log.e("NaatViewModel", "Entry lookup failed", error)
                statusReporter.show(R.string.status_entry_lookup_failed)
                onFailure()
            }
        }
    }

    fun deleteNaat(id: Int, onSuccess: () -> Unit = {}) = deleteNaatInternal(id, onSuccess)

    fun deleteNaat(naat: NaatEntity, onSuccess: () -> Unit = {}) =
        deleteNaatInternal(naat.id, onSuccess)

    private fun deleteNaatInternal(id: Int, onSuccess: () -> Unit) {
        if (!deleteGate.tryStart()) return
        _isDeleting.value = true
        viewModelScope.launch {
            try {
                val deleted = audioFiles.exclusive {
                    // Resolve the row inside the lifecycle lock so a concurrent save
                    // cannot make cleanup decisions from stale attachment paths.
                    val current = repository.getNaatById(id) ?: return@exclusive null
                    repository.delete(current)
                    withContext(Dispatchers.Main.immediate) {
                        if (playbackController.nowPlaying.value?.naatId == current.id) {
                            playbackController.stop()
                        }
                    }
                    setOfNotNull(current.audioPath, current.secondaryAudioPath)
                        .forEach { path ->
                            try {
                                deleteAudioIfUnreferencedLocked(path)
                            } catch (error: Exception) {
                                Log.w("NaatViewModel", "Deleted row, but audio cleanup failed", error)
                            }
                        }
                    current
                }
                withContext(Dispatchers.Main.immediate) {
                    if (deleted == null) {
                        statusReporter.show(R.string.status_entry_missing)
                    } else {
                        if (_selectedNaat.value?.id == deleted.id) _selectedNaat.value = null
                        statusReporter.show(R.string.status_entry_deleted)
                        onSuccess()
                    }
                }
            } catch (error: Exception) {
                Log.e("NaatViewModel", "Delete failed", error)
                statusReporter.show(R.string.status_delete_failed, error.localizedMessage ?: StatusMessage.Res(R.string.status_error_database))
            } finally {
                _isDeleting.value = false
                deleteGate.finish()
            }
        }
    }

    /**
     * Called when the app goes to the background: stop playback and finalize any
     * in-progress take so the recording stays complete and previewable on return.
     */
    fun onAppBackgrounded() {
        // Entry sessions survive; UI-owned previews do not.
        playbackController.stopPreview()
        if (_recordingState.value != RecordingState.IDLE) stopRecording()
        if (_editorDraft.value.active) persistDraft(_editorDraft.value, immediate = true)
    }

    override fun onCleared() {
        // PlaybackController is process-scoped; ViewModel teardown must not end a
        // service-owned listening session. Do not synchronously delete files here:
        // onCleared runs on Main and the next process startup's serialized orphan
        // sweep owns abandoned draft cleanup safely.
        audioRecorder.stop()
        playbackController.stopPreview()
        stopRecordingMeter()
        super.onCleared()
    }
}
