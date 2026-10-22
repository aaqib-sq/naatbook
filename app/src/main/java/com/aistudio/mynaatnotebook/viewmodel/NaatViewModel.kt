package com.aistudio.mynaatnotebook.viewmodel

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

/**
 * Undo-delete window: how long a deleted entry can be brought back.
 * Matches the Material3 long snackbar the offer is shown in.
 */
private const val UNDO_DELETE_WINDOW_MS = 10_000L

/**
 * Builds the parked filename for an audio file staged during the undo window.
 * Pure (testable): the id + index keep collisions impossible even when two
 * entries carried identically-named files; the tail is sanitized for safety.
 */
internal fun pendingDeleteFileName(entryId: Int, index: Int, originalName: String): String {
    val safe = originalName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(40)
    return "entry${entryId}_${index}_$safe"
}

/** Display label for a parked draft: its title, else its first lyrics line, else null. */
private fun keptDraftLabel(draft: EditorDraft): String? {
    val title = draft.title.trim()
    if (title.isNotEmpty()) return title
    return draft.lyrics.lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

@HiltViewModel
class NaatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val repository: NaatRepository,
    private val audioFiles: AudioFileLifecycleCoordinator,
    private val audioRecorder: AudioRecorder,
    val playbackController: PlaybackController,
    private val statusReporter: StatusReporter,
    private val settingsStore: SettingsStore
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
        .map { EditorMetadataDraft(it.editingId, it.title, it.poet, it.category, it.lyrics, it.readerFontOverride, it.titleFontOverride) }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            EditorMetadataDraft(
                initialDraft.editingId, initialDraft.title, initialDraft.poet,
                initialDraft.category, initialDraft.lyrics, initialDraft.readerFontOverride, initialDraft.titleFontOverride
            )
        )
    val editorAttachments: StateFlow<EditorAttachmentDraft> = editorDraft
        .map {
            EditorAttachmentDraft(
                it.existingAudioRemoved, it.existingAudioType, it.existingAudioPath,
                it.existingAudioDisplayName,
                it.existingSecondaryAudioRemoved, it.existingSecondaryAudioType,
                it.existingSecondaryAudioPath, it.existingSecondaryAudioDisplayName,
                it.newAttachmentPath,
                it.newAttachmentName, it.finishedRecordingPath
            )
        }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            EditorAttachmentDraft(
                initialDraft.existingAudioRemoved, initialDraft.existingAudioType,
                initialDraft.existingAudioPath, initialDraft.existingAudioDisplayName,
                initialDraft.existingSecondaryAudioRemoved,
                initialDraft.existingSecondaryAudioType, initialDraft.existingSecondaryAudioPath,
                initialDraft.existingSecondaryAudioDisplayName,
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

    private val _duplicateSavePrompt = MutableStateFlow<String?>(null)
    /** Audit #19: title of an existing entry the new draft exactly matches. */
    val duplicateSavePrompt: StateFlow<String?> = _duplicateSavePrompt.asStateFlow()

    fun dismissDuplicateSavePrompt() {
        _duplicateSavePrompt.value = null
    }

    /** Exact text-identity match (title + poet + folder + lyrics), new entries only. */
    private suspend fun findTextDuplicate(draft: EditorDraft): NaatEntity? {
        fun norm(value: String?): String = value?.trim()?.lowercase().orEmpty()
        // Audit 05 M2: compare the cheap identity fields across the whole
        // library via a light projection, and load a full row (with its
        // lyrics) only for an identity match.
        val candidates = repository.allIdentityRows().filter { row ->
            norm(row.title) == norm(draft.title) &&
                norm(row.poet) == norm(draft.poet) &&
                row.category == draft.category
        }
        for (candidate in candidates) {
            val entry = repository.getNaatById(candidate.id) ?: continue
            if (norm(entry.lyrics) == norm(draft.lyrics)) return entry
        }
        return null
    }
    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()
    private val _isAttachingFile = MutableStateFlow(false)
    val isAttachingFile: StateFlow<Boolean> = _isAttachingFile.asStateFlow()
    /** Audit #18: the in-flight attach, so it can be cancelled. */
    private var attachJob: Job? = null

    /** Audit #18: cancel an in-progress file attach (partial copy is deleted). */
    fun cancelAttach() {
        attachJob?.cancel()
    }
    private val _isOpeningNowPlaying = MutableStateFlow(false)
    val isOpeningNowPlaying: StateFlow<Boolean> = _isOpeningNowPlaying.asStateFlow()

    init {
        // Housekeeping is serialized with every create/adopt/save/delete/import path.
        // It reads both the live draft snapshot and disk draft so startup can never
        // classify an attachment created by a concurrent editor operation as orphaned.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // The undo window is session-scoped: anything parked by a
                // deletion that never got its undo (process death) completes
                // now instead of lingering as unreferenced files.
                sweepPendingDeletes()
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

    // Arena E8: a deliberately parked draft must not spring the editor open
    // on cold start — only a crash-recovery draft reopens it directly.
    private val _showAddModal = MutableStateFlow(initialDraft.active && !initialDraft.parked)
    val showAddModal: StateFlow<Boolean> = _showAddModal.asStateFlow()

    private val _selectedNaat = MutableStateFlow<NaatEntity?>(null)
    val selectedNaat: StateFlow<NaatEntity?> = _selectedNaat.asStateFlow()

    // Filter and Search States
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFolder = MutableStateFlow<String?>(null) // null means "All" / Folder Grid View
    val selectedFolder: StateFlow<String?> = _selectedFolder.asStateFlow()

    // Library scroll positions live in the ViewModel, not in remember:
    // the home grid, recents strip and the folder/search lists all leave
    // composition when a folder opens or the reader pushes on top, so a
    // composable-local state reset the scroll to the top after every
    // open-and-return (device report 2026-10-17: deleting down the list
    // meant re-scrolling from the top after each delete).
    val libraryHomeScrollState = ScrollState(0)
    val libraryRecentsListState = LazyListState()
    val librarySearchListState = LazyListState()
    val libraryFolderListState = LazyListState()

    private val _showFavoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()

    private val _searchWholeLibrary = MutableStateFlow(false)
    /** Search-scope escape: while true, an active search ignores the folder. */
    val searchWholeLibrary: StateFlow<Boolean> = _searchWholeLibrary.asStateFlow()
    fun setSearchWholeLibrary(enabled: Boolean) { _searchWholeLibrary.value = enabled }

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
        _searchWholeLibrary.value = false
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

    /** The library filter's live inputs, with the folder already resolved. */
    private data class LibraryFilter(
        val query: String,
        val folder: String?,
        val favoritesOnly: Boolean
    )

    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val filteredSummaries: StateFlow<List<NaatSummary>> = combine(
        _searchQuery.debounce(160).distinctUntilChanged(),
        _selectedFolder,
        _showFavoritesOnly,
        _searchWholeLibrary
    ) { query, folder, favoritesOnly, wholeLibrary ->
        // Audit 03 M2: fold Arabic-script variants/harakat on the query
        // side; the DAO folds the stored columns with the same rules.
        // Audit S1: the whole-library escape drops the folder scope, but
        // only while a query is actually typed — with a blank query the
        // folder view must keep its own scope.
        val normalizedQuery = normalizeSearchText(query)
        LibraryFilter(
            query = normalizedQuery,
            folder = if (wholeLibrary && normalizedQuery.isNotBlank()) null else folder,
            favoritesOnly = favoritesOnly
        )
    }
        .distinctUntilChanged()
        .flatMapLatest { filter ->
            // Room executes title/poet/lyrics matching on its query executor and returns only
            // summary columns, avoiding full-lyrics scans and allocations on the UI thread.
            // Stage-2 UX audit: rank the matches (title > poet > lyrics-only) on the way out.
            repository.filteredSummaries(filter.query, filter.folder, filter.favoritesOnly)
                .map { rankSearchResults(it, filter.query) }
        }
        // Audit 03 H-b: a Room failure used to leave the list silently
        // stale or empty with no trace anywhere.
        .catch { error ->
            Log.e("NaatViewModel", "Library filter flow failed", error)
            emit(emptyList())
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
            // Audit fix: startRecording persists the take's path up front, so a
            // process death mid-recording resurrects an UNFINISHED file here.
            // Only a file that actually parses as playable audio may come back
            // as a "finished" take; a partial file is dropped and removed.
            val rawFile = candidate?.finishedRecordingPath
                ?.let(::File)
                ?.takeIf { it.isFile }
            val activeFile = rawFile?.takeIf { isPlayableAudio(it) }
            val staleFile = rawFile?.takeUnless { it == activeFile }
            if (staleFile != null) runCatching { staleFile.delete() }
            withContext(Dispatchers.Main.immediate) {
                if (draftRevision == 0L) {
                    diskDraft?.let {
                        val cleaned = if (staleFile != null &&
                            it.finishedRecordingPath == staleFile.absolutePath
                        ) it.copy(finishedRecordingPath = null) else it
                        _editorDraft.value = cleaned
                        draftStore.save(cleaned)
                        // Audit 03 M5: rehydrate the edited row, or the
                        // discard check has no original to compare with
                        // and every restored edit draft reads as "unsaved
                        // changes" — even when nothing was touched.
                        cleaned.editingId?.let { editId ->
                            _editingNaat.value = runCatching {
                                repository.getNaatById(editId)
                            }.getOrNull()
                        }
                        if (cleaned.parked) {
                            // Arena E8: a parked draft waits behind the
                            // Library banner / resume prompt instead of
                            // reopening the editor on every cold start.
                            keptDraftPending = true
                            refreshKeptDraftInfo()
                        } else {
                            _showAddModal.value = true
                        }
                    }
                    if (staleFile != null) {
                        // Audit 03 L9: the dropped take used to vanish
                        // silently; say so once.
                        statusReporter.show(R.string.editor_recording_lost)
                    }
                    if (staleFile != null &&
                        _editorDraft.value.finishedRecordingPath == staleFile.absolutePath
                    ) {
                        _editorDraft.value =
                            _editorDraft.value.copy(finishedRecordingPath = null)
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
    /** Queued status receipts (audit 03 M13) — NaatApp drains them in order. */
    val statusMessages: kotlinx.coroutines.flow.Flow<StatusMessage> = statusReporter.messages

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

    private fun clearDraft() {
        keptDraftPending = false
        _keptDraftInfo.value = null
        persistDraft(EditorDraft(), immediate = true)
    }

    fun startAddDraft(forceFresh: Boolean = false, initialCategory: String? = null) {
        if (_editorDraft.value.active && !forceFresh) return
        if (forceFresh) {
            // A parked "keep for later" draft is deliberately abandoned here.
            keptDraftPending = false
            _keptDraftInfo.value = null
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
        // Arena L1: a new entry started from inside a folder is pre-filed in
        // that folder, not silently dropped into the default category.
        persistDraft(
            EditorDraft(
                active = true,
                category = initialCategory?.let { NaatCategories.normalize(it) }
                    ?: NaatCategories.DEFAULT
            ),
            immediate = true
        )
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
        if (_isSaving.value) return
        // Audit #18: closing the editor during an attach cancels the attach
        // instead of trapping the user until the copy finishes.
        if (_isAttachingFile.value) cancelAttach()
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

    /** Label + kind of the parked draft, for the Library banner and the resume prompt. */
    data class KeptDraftInfo(val label: String?, val isEdit: Boolean)

    private val _keptDraftInfo = MutableStateFlow(
        if (initialDraft.active && initialDraft.parked) {
            KeptDraftInfo(keptDraftLabel(initialDraft), initialDraft.editingId != null)
        } else null
    )
    val keptDraftInfo: StateFlow<KeptDraftInfo?> = _keptDraftInfo.asStateFlow()

    private fun refreshKeptDraftInfo() {
        _keptDraftInfo.value = if (keptDraftPending) {
            val draft = _editorDraft.value
            KeptDraftInfo(keptDraftLabel(draft), draft.editingId != null)
        } else null
    }

    /**
     * True while a draft the user deliberately parked with "Save draft for
     * later" is dormant (editor closed, draft + files intact on disk). The
     * FAB / edit-entry open paths consult it to offer a resume instead of
     * silently destroying the parked work. Cleared by [clearDraft] (save or
     * discard), by resume, and by any open path that replaces the draft.
     * Main-thread only.
     */
    internal var keptDraftPending = initialDraft.active && initialDraft.parked

    /**
     * Audit TOP 3: close the editor WITHOUT discarding. The draft (text, font
     * choices, finished recording, new attachment) stays persisted exactly as
     * the crash-safe autosave left it; an in-progress recording is finalized
     * into the draft instead of wiped. Nothing is deleted.
     */
    fun keepEditorDraftForLater() {
        if (_isSaving.value) return
        viewModelScope.launch {
            try {
                // An in-progress attach is cancelled like the discard path —
                // a half-copied file must never be referenced by a kept draft.
                if (_isAttachingFile.value) cancelAttach()
                // Finalize an in-progress take so the draft keeps a playable
                // file (same finalize the normal stop button performs).
                if (_recordingState.value != RecordingState.IDLE) {
                    audioFiles.exclusive { finalizeRecordingLocked() }
                }
                playbackController.stopPreview()
                _activeRecordingFile.value = null
                _editingNaat.value = null
                keptDraftPending = true
                // Arena E8: mark the draft parked so a cold start restores
                // it behind the banner/prompt instead of reopening the editor.
                persistDraft(_editorDraft.value.copy(active = true, parked = true), immediate = true)
                _showAddModal.value = false
                refreshKeptDraftInfo()
                withContext(Dispatchers.Main.immediate) {
                    statusReporter.show(R.string.editor_draft_kept)
                }
            } catch (error: Exception) {
                Log.e("NaatViewModel", "Keep draft failed", error)
                keptDraftPending = false
                // Audit 03 M6: this failure was log-only — the dialog had
                // already closed and the user believed the draft was safe.
                statusReporter.show(R.string.editor_draft_keep_failed)
            }
        }
    }

    /**
     * Reopens the parked draft: the editor shows exactly the kept state
     * (startAddDraft/startEditNaat would otherwise destroy it).
     */
    fun resumeKeptDraft() {
        val draft = _editorDraft.value
        if (!draft.active) {
            keptDraftPending = false
            _keptDraftInfo.value = null
            return
        }
        keptDraftPending = false
        _keptDraftInfo.value = null
        // Un-park on resume: from here the draft is a live editor draft
        // again, so a later crash must restore it as a crash draft (editor
        // reopens), not as a parked one.
        persistDraft(draft.copy(parked = false))
        val editingId = draft.editingId
        if (editingId != null) {
            viewModelScope.launch {
                _editingNaat.value = try {
                    repository.getNaatById(editingId)
                } catch (error: Exception) {
                    Log.w("NaatViewModel", "Kept-draft original missing", error)
                    null
                }
                _showAddModal.value = true
            }
        } else {
            _showAddModal.value = true
        }
    }

    /**
     * Throws the parked draft away from the Library banner, without the
     * editor ever opening. Mirrors the discard path; no recorder can be
     * live for a parked draft, so there is nothing to finalize — only the
     * draft's staged files are cleaned up.
     */
    fun discardKeptDraft() {
        if (!_editorDraft.value.active) {
            keptDraftPending = false
            _keptDraftInfo.value = null
            return
        }
        playbackController.stopPreview()
        cleanupDraftFilesAsync(_editorDraft.value, null)
        _editingNaat.value = null
        clearDraft()
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

    /**
     * Reader play tap (UX batch 4, Arena A14): when this entry's file has a
     * remembered position, playback starts AT that point in one service
     * round-trip (Audit fix H1). The old restoreEntry (autoplay = false) +
     * synchronous resume() raced the async service start and left the
     * track loaded but paused; restoreEntry stays only for the
     * process-death path, which genuinely wants a paused session.
     */
    fun startEntryPlaybackResuming(naat: NaatEntity, path: String) {
        viewModelScope.launch {
            val saved = runCatching { settingsStore.getAudioPosition(naat.id) }.getOrNull()
            if (saved != null && saved.second == path && saved.first > 5_000L) {
                playbackController.playEntry(naat, path, startPositionMs = saved.first)
            } else {
                playbackController.playEntry(naat, path)
            }
        }
    }

    /** Per-entry audio position memory (UX batch 4): see SettingsStore. */
    fun saveAudioPosition(naatId: Int, audioPath: String, positionMs: Long) {
        viewModelScope.launch { settingsStore.setAudioPosition(naatId, audioPath, positionMs) }
    }

    fun clearAudioPosition(naatId: Int) {
        viewModelScope.launch { settingsStore.clearAudioPosition(naatId) }
    }

    /** Mini-player tap: resolve through Room; the list flow may still hold its initial empty value. */
    private var playbackRestoreAttempted = false

    /**
     * Audit #21: after a process death, re-create the last entry session
     * paused at its saved position (mini player + notification return,
     * nothing auto-plays). Runs once per process; any rot in the saved
     * point (entry deleted, file gone, barely started) just clears it.
     */
    fun restoreLastPlaybackIfNeeded() {
        if (playbackRestoreAttempted) return
        playbackRestoreAttempted = true
        viewModelScope.launch {
            val saved = settingsStore.lastPlayback.first() ?: return@launch
            if (saved.positionMs < 3_000L) return@launch
            if (playbackController.hasActiveSession()) return@launch
            val naat = repository.getNaatById(saved.naatId)
            val path = naat?.audioPath
            if (naat == null || path == null) {
                settingsStore.clearLastPlayback()
                return@launch
            }
            if (!path.startsWith("content://") && !java.io.File(path).exists()) {
                settingsStore.clearLastPlayback()
                return@launch
            }
            playbackController.restoreEntry(naat, saved.positionMs, saved.audioPath)
        }
    }

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
                statusReporter.show(R.string.status_open_playing_failed, StatusMessage.Res(R.string.status_error_database))
                onResolved(false) // preserve playback when lookup itself failed
            } finally {
                _isOpeningNowPlaying.value = false
            }
        }
    }

    fun selectFolder(folder: String?) {
        if (_selectedFolder.value == folder) return
        _selectedFolder.value = folder
        // Audit S1: changing folder ends any whole-library search escape.
        _searchWholeLibrary.value = false
        // Audit 03 M3: the folder list state is shared across folders, so
        // a new folder used to open mid-scroll wherever the last one left
        // it. (Returning from an entry keeps the position — only an
        // actual folder change resets.)
        viewModelScope.launch { libraryFolderListState.scrollToItem(0) }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        // Audit S1: clearing the search also ends the whole-library escape.
        if (query.isBlank()) _searchWholeLibrary.value = false
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
                statusReporter.show(R.string.status_recording_start_failed_detail, StatusMessage.Res(R.string.status_error_storage))
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
                existingAudioDisplayName = naat.audioDisplayName,
                existingSecondaryAudioType = naat.secondaryAudioType,
                existingSecondaryAudioPath = naat.secondaryAudioPath,
                existingSecondaryAudioDisplayName = naat.secondaryAudioDisplayName,
                existingFavorite = naat.isFavorite,
                existingCreatedAt = naat.createdAt,
                readerFontOverride = naat.readerFontOverride,
                titleFontOverride = naat.titleFontOverride
            ), immediate = true)
        }
        _editingNaat.value = naat
        _showAddModal.value = true
        // The draft is live in the editor now (resumed or freshly built) —
        // a parked-draft prompt must not fire again for it.
        keptDraftPending = false
        _keptDraftInfo.value = null
    }

    /** The picker's original file name, for display only (never a storage path). */
    private fun resolveDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.trim()?.takeIf { it.isNotBlank() }?.take(120)
            } else {
                null
            }
        }
    }.getOrNull()

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
                    // Audit #18: chunked copy with cancellation checks — a
                    // single copyTo() could not be interrupted, trapping
                    // the user (and the editor) until a huge file finished.
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = inputStream.read(buffer)
                        if (count < 0) break
                        outputStream.write(buffer, 0, count)
                    }
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
        } catch (error: CancellationException) {
            // Audit #18: a cancelled attach deletes its partial copy and
            // stays a cancellation — never an error toast or a log scare.
            runCatching { destination.delete() }
            throw error
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
        if (!attachmentGate.tryStart()) {
            statusReporter.show(R.string.status_busy_wait)
            return
        }
        _isAttachingFile.value = true
        attachJob = viewModelScope.launch {
            try {
                val success = audioFiles.exclusive {
                    val file = copyLocalFileToAppStorageLocked(uri)
                    if (file != null && _editorDraft.value.active) {
                        val displayName = withContext(Dispatchers.IO) { resolveDisplayName(uri) }
                        adoptLinkedFileLocked(file, displayName)
                        true
                    } else {
                        deleteOnIo(file?.absolutePath)
                        false
                    }
                }
                onResult(success)
            } catch (error: CancellationException) {
                // Audit #18: user cancelled (button or editor close). The
                // partial copy was already deleted by the copy routine.
                statusReporter.show(R.string.editor_attach_cancelled)
                throw error
            } finally {
                _isAttachingFile.value = false
                attachmentGate.finish()
                attachJob = null
            }
        }
    }

    /** Called only while [audioFiles] is held; replaces a draft-owned linked file atomically. */
    private suspend fun adoptLinkedFileLocked(file: File, displayName: String? = null) {
        // Audit #18: the editor may have been closed (cancelling the attach)
        // while the copy finished — never resurrect a discarded draft.
        if (!_editorDraft.value.active) {
            deleteOnIo(file.absolutePath)
            return
        }
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
                // Show the user the name of the file THEY picked, not the
                // internal storage name. Callers without a picker name keep
                // the plain file name as a fallback.
                newAttachmentName = displayName ?: file.name
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
    fun saveDraft(forceSave: Boolean = false) {
        if (!saveGate.tryStart()) {
            // Audit 03 L6: a busy gate used to swallow the tap silently.
            statusReporter.show(R.string.status_busy_wait)
            return
        }
        // Defensive: UI blocks blank titles, but never trust the UI alone.
        if (_editorDraft.value.title.isBlank()) {
            saveGate.finish()
            statusReporter.show(R.string.editor_title_required)
            return
        }
        // Audit #19: a brand-new entry whose title, poet, folder and lyrics
        // exactly match an existing one is almost certainly an accidental
        // re-add — warn before saving instead of silently duplicating. The
        // check is async; the save gate stays held until it resolves.
        val draftSnapshot = _editorDraft.value
        // Audit 03 M4: saving an untouched edit used to bump updatedAt
        // (thrusting the entry to the top of Recents via its fallback)
        // and announce "Entry Updated!" when nothing had changed. Close
        // quietly with an honest message instead — no row is written.
        val original = _editingNaat.value
        if (draftSnapshot.editingId != null && original != null &&
            original.id == draftSnapshot.editingId &&
            !draftSnapshot.hasUnsavedChanges(original)
        ) {
            saveGate.finish()
            clearDraft()
            _editingNaat.value = null
            _showAddModal.value = false
            statusReporter.show(R.string.status_entry_no_changes)
            return
        }
        if (!forceSave && draftSnapshot.editingId == null) {
            viewModelScope.launch {
                val duplicate = try {
                    findTextDuplicate(draftSnapshot)
                } catch (error: Exception) {
                    null // a failed check must never block a save
                }
                if (duplicate != null) {
                    saveGate.finish()
                    _duplicateSavePrompt.value = duplicate.title
                } else {
                    performSaveDraft()
                }
            }
            return
        }
        performSaveDraft()
    }

    private fun performSaveDraft() {
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

                    fun existingDisplayName(type: String): String? = when {
                        !draft.existingAudioRemoved && draft.existingAudioType == type ->
                            draft.existingAudioDisplayName
                        !draft.existingSecondaryAudioRemoved && draft.existingSecondaryAudioType == type ->
                            draft.existingSecondaryAudioDisplayName
                        else -> null
                    }

                    // The linked slot's display name follows the same source as
                    // its path: a fresh pick wins, otherwise the kept file's name.
                    val linkedDisplayName = if (draft.newAttachmentPath != null) {
                        draft.newAttachmentName
                    } else {
                        existingDisplayName("local_file")
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
                            secondaryAudioPath = secondaryAudioPath,
                            audioDisplayName = if (audioType == "local_file") linkedDisplayName else null,
                            secondaryAudioDisplayName =
                                if (secondaryAudioType == "local_file") linkedDisplayName else null,
                            readerFontOverride = draft.readerFontOverride,
                            titleFontOverride = draft.titleFontOverride
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
                            secondaryAudioPath = secondaryAudioPath,
                            audioDisplayName = if (audioType == "local_file") linkedDisplayName else null,
                            secondaryAudioDisplayName =
                                if (secondaryAudioType == "local_file") linkedDisplayName else null,
                            readerFontOverride = draft.readerFontOverride,
                            titleFontOverride = draft.titleFontOverride
                        )
                        // Audit 03 H-a: the update count used to go
                        // unchecked — if a restore had removed the row
                        // mid-edit, the save still announced success.
                        if (repository.update(entity) == 0) {
                            throw IOException("The entry no longer exists")
                        }
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
                statusReporter.show(R.string.status_save_failed, StatusMessage.Res(R.string.status_error_database))
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

    /**
     * Stamps lastOpenedAt when an entry is actually shown in the Reader.
     * Kept separate from [loadNaat] because the editor loads entries through
     * the same path and an editor open is not a Reader open.
     */
    fun recordEntryOpened(id: Int) {
        viewModelScope.launch {
            try {
                repository.markOpened(id)
            } catch (error: Exception) {
                Log.e("NaatViewModel", "Failed to stamp lastOpenedAt", error)
            }
        }
    }

    fun deleteNaat(id: Int, onSuccess: () -> Unit = {}) = deleteNaatInternal(id, onSuccess)

    fun deleteNaat(naat: NaatEntity, onSuccess: () -> Unit = {}) =
        deleteNaatInternal(naat.id, onSuccess)

    private fun deleteNaatInternal(id: Int, onSuccess: () -> Unit) {
        if (!deleteGate.tryStart()) {
            statusReporter.show(R.string.status_busy_wait)
            return
        }
        _isDeleting.value = true
        viewModelScope.launch {
            try {
                var supersededPendingDelete = false
                val (deleted, undoToken) = audioFiles.exclusive {
                    // Resolve the row inside the lifecycle lock so a concurrent save
                    // cannot make cleanup decisions from stale attachment paths.
                    val current = repository.getNaatById(id) ?: return@exclusive null to -1L
                    // A second delete during an open undo window supersedes the
                    // first: the earlier deletion finalizes (its files die) now.
                    // Audit 03 L20: that used to happen with no signal at
                    // all — the user never learned the first entry's undo
                    // window had closed early.
                    supersededPendingDelete = pendingDelete != null
                    finalizePendingDelete()
                    repository.delete(current)
                    withContext(Dispatchers.Main.immediate) {
                        if (playbackController.nowPlaying.value?.naatId == current.id) {
                            playbackController.stop()
                        }
                    }
                    // The row is gone instantly (the library updates at once),
                    // but the audio files are PARKED, not destroyed, until the
                    // undo window closes — undo only needs to move them back.
                    val token = ++undoTokenCounter
                    val staged = listOfNotNull(current.audioPath, current.secondaryAudioPath)
                        .mapIndexedNotNull { index, path ->
                            runCatching {
                                stageAudioForPendingDeleteLocked(path, current.id, index)
                            }.getOrNull()
                        }
                    pendingDelete = PendingDelete(current, staged, token)
                    pendingDeleteExpiryJob = viewModelScope.launch {
                        delay(UNDO_DELETE_WINDOW_MS)
                        finalizePendingDelete()
                    }
                    current to token
                }
                withContext(Dispatchers.Main.immediate) {
                    if (deleted == null) {
                        statusReporter.show(R.string.status_entry_missing)
                    } else {
                        if (_selectedNaat.value?.id == deleted.id) _selectedNaat.value = null
                        // The old "Entry deleted" toast is replaced by the undo
                        // snackbar (collected from undoDeleteOffers in NaatApp).
                        _undoDeleteOffers.value = UndoDeleteOffer(undoToken)
                        if (supersededPendingDelete) {
                            statusReporter.show(R.string.status_previous_delete_finalized)
                        }
                        onSuccess()
                    }
                }
            } catch (error: Exception) {
                Log.e("NaatViewModel", "Delete failed", error)
                statusReporter.show(R.string.status_delete_failed, StatusMessage.Res(R.string.status_error_database))
            } finally {
                _isDeleting.value = false
                deleteGate.finish()
            }
        }
    }

    /**
     * Brings back the entry deleted in this undo window: the row is re-inserted
     * with its original id (so recents/favorites/order survive) and the parked
     * audio files are moved back to their exact original paths.
     */
    fun undoDelete(token: Long) {
        val pending = pendingDelete
        if (pending == null || pending.token != token) return
        pendingDelete = null
        pendingDeleteExpiryJob?.cancel()
        pendingDeleteExpiryJob = null
        viewModelScope.launch {
            audioFiles.exclusive {
                try {
                    pending.parkedFiles.forEach { (originalPath, stagedPath) ->
                        val staged = File(stagedPath)
                        if (staged.isFile) {
                            File(originalPath).parentFile?.mkdirs()
                            if (!staged.renameTo(File(originalPath))) {
                                Log.w("NaatViewModel", "Undo: could not move audio back")
                            }
                        }
                    }
                    repository.insert(pending.entity)
                    withContext(Dispatchers.Main.immediate) {
                        statusReporter.show(R.string.status_entry_restored)
                    }
                } catch (error: Exception) {
                    Log.e("NaatViewModel", "Undo delete failed", error)
                    withContext(Dispatchers.Main.immediate) {
                        statusReporter.show(
                            R.string.status_delete_failed,
                            StatusMessage.Res(R.string.status_error_database)
                        )
                    }
                }
            }
        }
    }

    /** One-shot undo offer: the row is already gone, the files are parked. */
    data class UndoDeleteOffer(val token: Long)

    /**
     * A deletion inside its undo window: the DB row is gone, the audio files
     * are parked under [parkedFiles] (original absolute path -> parked path).
     */
    private data class PendingDelete(
        val entity: NaatEntity,
        val parkedFiles: List<Pair<String, String>>,
        val token: Long
    )

    private var pendingDelete: PendingDelete? = null
    private var pendingDeleteExpiryJob: Job? = null
    private var undoTokenCounter = 0L
    // Conflated hand-off for the undo snackbar: always carries only the
    // LATEST offer. A buffered SharedFlow used to suspend the delete itself
    // once its single buffer slot filled — during rapid one-by-one deletes
    // the third delete onwards blocked for seconds on the previous
    // snackbar's lifetime (device report 2026-10-17). Setting a StateFlow
    // value never suspends the deleter.
    private val _undoDeleteOffers = MutableStateFlow<UndoDeleteOffer?>(null)
    val undoDeleteOffers: StateFlow<UndoDeleteOffer?> = _undoDeleteOffers.asStateFlow()

    /** True while [token] still names the parked, undoable deletion. */
    fun isUndoOfferLive(token: Long): Boolean = pendingDelete?.token == token

    private fun pendingDeleteDir(): File = File(context.filesDir, "pending_delete").apply { mkdirs() }

    /**
     * Parks an otherwise-unreferenced audio file for the undo window instead of
     * deleting it. Keeps the refcount guard: a file another entry still uses is
     * left exactly where it is. Must run while [audioFiles] is held.
     */
    private suspend fun stageAudioForPendingDeleteLocked(
        path: String,
        entryId: Int,
        index: Int
    ): Pair<String, String>? {
        if (repository.countByAudioPath(path) > 0) return null
        val file = withContext(Dispatchers.IO) {
            val candidate = File(path).canonicalFile
            val basePath = context.filesDir.canonicalPath + File.separator
            if (candidate.path.startsWith(basePath) && candidate.isFile) candidate else null
        } ?: return null
        val staged = File(pendingDeleteDir(), pendingDeleteFileName(entryId, index, file.name))
        return withContext(Dispatchers.IO) {
            val parked = if (file.renameTo(staged)) {
                true
            } else {
                // Cross-filesystem fallback: copy, then remove the original.
                runCatching {
                    file.inputStream().use { input ->
                        staged.outputStream().use { output -> input.copyTo(output) }
                    }
                    file.delete()
                }.getOrDefault(false) && staged.isFile
            }
            if (parked) file.absolutePath to staged.absolutePath else null
        }
    }

    /**
     * Completes the pending deletion: parked files are destroyed for good and
     * the saved reading position (Feature 2) goes with the entry. Safe to call
     * when nothing is pending. The undo window is session-scoped by design —
     * on relaunch, [sweepPendingDeletes] completes anything still parked.
     */
    private suspend fun finalizePendingDelete() {
        val pending = pendingDelete ?: return
        pendingDelete = null
        pendingDeleteExpiryJob?.cancel()
        pendingDeleteExpiryJob = null
        withContext(Dispatchers.IO) {
            pending.parkedFiles.forEach { (_, stagedPath) ->
                runCatching { File(stagedPath).takeIf { it.isFile }?.delete() }
            }
            runCatching { settingsStore.clearReaderScrollOffset(pending.entity.id) }
            runCatching { settingsStore.clearReaderFontSize(pending.entity.id) }
            runCatching { settingsStore.clearAudioPosition(pending.entity.id) }
        }
    }

    /**
     * Session-scoped undo by design: a process death during the window must
     * never resurrect half-deleted state, so relaunch completes the deletion.
     */
    private fun sweepPendingDeletes() {
        runCatching {
            pendingDeleteDir().listFiles()?.forEach { file ->
                if (file.isFile) file.delete()
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
