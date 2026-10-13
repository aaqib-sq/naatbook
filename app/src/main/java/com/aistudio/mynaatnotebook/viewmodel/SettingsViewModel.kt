package com.aistudio.mynaatnotebook.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.mynaatnotebook.data.NaatViewModelDefaults
import com.aistudio.mynaatnotebook.data.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val settingsStore: SettingsStore
) : ViewModel() {

    private val _themeMode = MutableStateFlow(NaatViewModelDefaults.DEFAULT_THEME_MODE)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _globalFontSize = MutableStateFlow(NaatViewModelDefaults.DEFAULT_FONT_SIZE)
    val globalFontSize: StateFlow<Float> = _globalFontSize.asStateFlow()

    private val _foldersWithEntries = MutableStateFlow<Set<String>>(emptySet())
    /** Folders that have held entries before (drives empty-folder copy choice). */
    val foldersWithEntries: StateFlow<Set<String>> = _foldersWithEntries.asStateFlow()

    init {
        // DataStore emits current settings once, then on every change
        viewModelScope.launch { settingsStore.themeMode.collect { _themeMode.value = it } }
        viewModelScope.launch { settingsStore.fontSize.collect { _globalFontSize.value = it } }
        viewModelScope.launch { settingsStore.foldersWithEntries.collect { _foldersWithEntries.value = it } }
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

    fun setGlobalFontSize(size: Float) {
        _globalFontSize.value = size
        viewModelScope.launch { settingsStore.setFontSize(size) }
    }
}
