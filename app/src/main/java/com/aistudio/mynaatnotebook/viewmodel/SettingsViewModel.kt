package com.aistudio.mynaatnotebook.viewmodel

import android.net.Uri
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.mynaatnotebook.R
import com.aistudio.mynaatnotebook.data.CustomFont
import com.aistudio.mynaatnotebook.data.CustomFontStore
import com.aistudio.mynaatnotebook.data.FontImportException
import com.aistudio.mynaatnotebook.data.NaatViewModelDefaults
import com.aistudio.mynaatnotebook.data.PendingFontImport
import com.aistudio.mynaatnotebook.data.LocaleHelper
import com.aistudio.mynaatnotebook.data.SettingsStore
import com.aistudio.mynaatnotebook.ui.theme.FontChoices
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App preferences (theme mode, global font size), backed by Preferences DataStore.
 *
 * Extracted from NaatViewModel: settings are independent of library/editor state.
 * Defaults render instantly; stored values land on first DataStore emit.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val customFontStore: CustomFontStore,
    private val statusReporter: StatusReporter
) : ViewModel() {

    private val _themeMode = MutableStateFlow(NaatViewModelDefaults.DEFAULT_THEME_MODE)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _appLanguage = MutableStateFlow(LocaleHelper.SYSTEM)
    /** App language override (LocaleHelper choice); UI applies it via recreate. */
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private val _globalFontSize = MutableStateFlow(NaatViewModelDefaults.DEFAULT_FONT_SIZE)
    val globalFontSize: StateFlow<Float> = _globalFontSize.asStateFlow()

    private val _foldersWithEntries = MutableStateFlow<Set<String>>(emptySet())
    /** Folders that have held entries before (drives empty-folder copy choice). */
    val foldersWithEntries: StateFlow<Set<String>> = _foldersWithEntries.asStateFlow()

    private val _readerFont = MutableStateFlow(FontChoices.AUTO)
    /** Reader lyrics font choice key (FontChoices key or "custom:<id>"). */
    val readerFont: StateFlow<String> = _readerFont.asStateFlow()

    private val _titleFont = MutableStateFlow(FontChoices.SANS)
    /** Entry-title font choice key (FontChoices key or "custom:<id>"). */
    val titleFont: StateFlow<String> = _titleFont.asStateFlow()

    private val _customFonts = MutableStateFlow<List<CustomFont>>(emptyList())
    /** User-imported fonts (registry order). */
    val customFonts: StateFlow<List<CustomFont>> = _customFonts.asStateFlow()

    private val _customFamilies = MutableStateFlow<Map<String, FontFamily>>(emptyMap())
    /** Loaded families for imported fonts, by id; rebuilt on registry change. */
    val customFamilies: StateFlow<Map<String, FontFamily>> = _customFamilies.asStateFlow()

    private val _pendingFontImport = MutableStateFlow<PendingFontImport?>(null)
    /** A validated font file awaiting its name-and-save step, if any. */
    val pendingFontImport: StateFlow<PendingFontImport?> = _pendingFontImport.asStateFlow()

    private val _fontError = MutableStateFlow<StatusMessage?>(null)
    /** Last font import/delete error to show the user, if any. */
    val fontError: StateFlow<StatusMessage?> = _fontError.asStateFlow()

    private val _lastBackupText = MutableStateFlow<String?>(null)
    /** "14 Oct 2026, 9:52 AM • Download/NaatBook" — null until the first backup. */
    val lastBackupText: StateFlow<String?> = _lastBackupText.asStateFlow()

    private val _lastImportUri = MutableStateFlow<String?>(null)
    /** Last picked import document (picker starting-point hint). */
    val lastImportUri: StateFlow<String?> = _lastImportUri.asStateFlow()

    private val _lastExportUri = MutableStateFlow<String?>(null)
    /** Last picked export document (picker starting-point hint). */
    val lastExportUri: StateFlow<String?> = _lastExportUri.asStateFlow()

    private val _onboardingSeen = MutableStateFlow<Boolean?>(null)
    /** Null until the store answers, so the app can splash instead of flashing. */
    val onboardingSeen: StateFlow<Boolean?> = _onboardingSeen.asStateFlow()

    fun setOnboardingSeen() {
        _onboardingSeen.value = true
        viewModelScope.launch { settingsStore.setOnboardingSeen() }
    }

    init {
        // DataStore emits current settings once, then on every change
        viewModelScope.launch { settingsStore.themeMode.collect { _themeMode.value = it } }
        viewModelScope.launch { settingsStore.appLanguage.collect { _appLanguage.value = it } }
        viewModelScope.launch { settingsStore.fontSize.collect { _globalFontSize.value = it } }
        viewModelScope.launch { settingsStore.foldersWithEntries.collect { _foldersWithEntries.value = it } }
        viewModelScope.launch { settingsStore.readerFont.collect { _readerFont.value = it } }
        viewModelScope.launch { settingsStore.titleFont.collect { _titleFont.value = it } }
        viewModelScope.launch {
            customFontStore.fonts.collect { fonts ->
                _customFonts.value = fonts
                _customFamilies.value = fonts.mapNotNull { font ->
                    customFontStore.familyFor(font)?.let { font.id to it }
                }.toMap()
            }
        }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                settingsStore.lastBackupAt, settingsStore.lastBackupLabel
            ) { at, label -> at to label }.collect { (at, label) ->
                _lastBackupText.value = if (at > 0L) {
                    val date = java.text.SimpleDateFormat(
                        "d MMM yyyy, h:mm a", java.util.Locale.getDefault()
                    ).format(java.util.Date(at))
                    if (label.isNullOrBlank()) date else "$date • $label"
                } else null
            }
        }
        viewModelScope.launch { settingsStore.lastImportUri.collect { _lastImportUri.value = it } }
        viewModelScope.launch { settingsStore.lastExportUri.collect { _lastExportUri.value = it } }
        viewModelScope.launch { settingsStore.onboardingSeen.collect { _onboardingSeen.value = it } }
        // Font-registry housekeeping (audit #20 + #23): drop registry
        // entries whose files vanished (they used to render silently as
        // the default font) and clear stale import temps. Runs once per
        // process; a removal is announced, never silent.
        viewModelScope.launch {
            val removed = customFontStore.pruneBrokenEntries()
            if (removed.isNotEmpty()) {
                statusReporter.show(R.string.status_fonts_pruned, removed.size)
            }
            customFontStore.pruneStaleImportTemps()
        }
    }

    fun markFoldersWithEntries(names: Set<String>) {
        if (names.isEmpty() || _foldersWithEntries.value.containsAll(names)) return
        _foldersWithEntries.value = _foldersWithEntries.value + names
        viewModelScope.launch { settingsStore.markFoldersWithEntries(names) }
    }

    /** Suspend writes -> exactly one DataStore transaction per committed user action. */
    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        viewModelScope.launch { settingsStore.setThemeMode(mode) }
    }

    fun setAppLanguage(choice: String) {
        _appLanguage.value = choice
        viewModelScope.launch { settingsStore.setAppLanguage(choice) }
    }

    fun setGlobalFontSize(size: Float) {
        _globalFontSize.value = size
        viewModelScope.launch { settingsStore.setFontSize(size) }
    }

    fun setReaderFont(choice: String) {
        _readerFont.value = choice
        viewModelScope.launch { settingsStore.setReaderFont(choice) }
    }

    fun setTitleFont(choice: String) {
        _titleFont.value = choice
        viewModelScope.launch { settingsStore.setTitleFont(choice) }
    }

    /**
     * Reader scroll memory (audit TOP 2): where the user stopped reading each
     * entry, in pixels. Saved when the reader closes, restored on open.
     */
    fun readerScrollOffset(naatId: Int): Flow<Int> = settingsStore.readerScrollOffset(naatId)

    suspend fun getReaderScrollOffset(naatId: Int): Int =
        settingsStore.getReaderScrollOffset(naatId)

    fun saveReaderScrollOffset(naatId: Int, offsetPx: Int) {
        viewModelScope.launch { settingsStore.setReaderScrollOffset(naatId, offsetPx) }
    }

    /** Phase 1 of font import: validate the picked file, then await a name. */
    fun beginFontImport(uri: Uri) {
        viewModelScope.launch {
            runCatching { customFontStore.beginImport(uri) }
                .onSuccess { _pendingFontImport.value = it }
                .onFailure { _fontError.value = fontImportMessage(it) }
        }
    }

    fun confirmFontImport(name: String) {
        val pending = _pendingFontImport.value ?: return
        _pendingFontImport.value = null
        viewModelScope.launch {
            runCatching { customFontStore.commitImport(pending, name) }
                .onFailure { _fontError.value = fontImportMessage(it) }
        }
    }

    /** Typed import failures render in the user's language; anything else
     * falls back to a generic (also localized) import-failed message. */
    private fun fontImportMessage(error: Throwable): StatusMessage =
        (error as? FontImportException)?.let { StatusMessage.Res(it.resId, it.args) }
            ?: StatusMessage.Res(R.string.settings_font_error_unknown)

    fun cancelFontImport() {
        val pending = _pendingFontImport.value ?: return
        _pendingFontImport.value = null
        customFontStore.discardImport(pending)
    }

    fun deleteCustomFont(id: String) {
        viewModelScope.launch {
            val deleted = customFontStore.deleteFont(id) ?: return@launch
            // A choice pointing at the deleted font falls back to its default.
            if (_readerFont.value == FontChoices.customKey(deleted.id)) {
                setReaderFont(FontChoices.AUTO)
            }
            if (_titleFont.value == FontChoices.customKey(deleted.id)) {
                setTitleFont(FontChoices.SANS)
            }
        }
    }

    fun clearFontError() {
        _fontError.value = null
    }
}
