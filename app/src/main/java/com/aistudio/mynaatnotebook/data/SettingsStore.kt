package com.aistudio.mynaatnotebook.data

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App settings backed by Preferences DataStore.
 *
 * Replaces the legacy "naat_notebook_prefs" SharedPreferences file; existing
 * installs keep their values through a one-time SharedPreferencesMigration
 * (the old file is then deleted). Reads are cold Flows, so the ViewModel no
 * longer blocks the main thread on disk during startup.
 */

private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
private val KEY_FONT_SIZE = floatPreferencesKey("font_size")
private val KEY_FOLDERS_WITH_ENTRIES = stringSetPreferencesKey("folders_with_entries")
private val KEY_READER_FONT = stringPreferencesKey("reader_font")
private val KEY_TITLE_FONT = stringPreferencesKey("title_font")
private val KEY_CUSTOM_FONTS = stringPreferencesKey("custom_fonts")
private val KEY_LAST_BACKUP_AT = androidx.datastore.preferences.core.longPreferencesKey("last_backup_at")
private val KEY_LAST_BACKUP_LABEL = stringPreferencesKey("last_backup_label")
private val KEY_LAST_IMPORT_URI = stringPreferencesKey("last_import_uri")
private val KEY_LAST_EXPORT_URI = stringPreferencesKey("last_export_uri")
private val KEY_ONBOARDING_SEEN = androidx.datastore.preferences.core.booleanPreferencesKey("onboarding_seen")

private val Context.settingsDataStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, "naat_notebook_prefs"))
    }
)

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    val themeMode: Flow<String> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_THEME_MODE] ?: "system" }

    val fontSize: Flow<Float> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_FONT_SIZE] ?: NaatViewModelDefaults.DEFAULT_FONT_SIZE }

    suspend fun setThemeMode(mode: String) {
        context.settingsDataStore.edit { it[KEY_THEME_MODE] = mode }
    }

    suspend fun setFontSize(size: Float) {
        context.settingsDataStore.edit { it[KEY_FONT_SIZE] = size }
    }

    /** Reader lyrics font choice: a FontChoices key, or "custom:<id>". */
    val readerFont: Flow<String> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_READER_FONT] ?: "auto" }

    suspend fun setReaderFont(choice: String) {
        context.settingsDataStore.edit { it[KEY_READER_FONT] = choice }
    }

    /** Entry-title font choice: a FontChoices key, or "custom:<id>". */
    val titleFont: Flow<String> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_TITLE_FONT] ?: "sans" }

    suspend fun setTitleFont(choice: String) {
        context.settingsDataStore.edit { it[KEY_TITLE_FONT] = choice }
    }

    /** Imported-font registry as a JSON array string (see CustomFontStore). */
    val customFontsJson: Flow<String> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_CUSTOM_FONTS] ?: "[]" }

    suspend fun setCustomFontsJson(json: String) {
        context.settingsDataStore.edit { it[KEY_CUSTOM_FONTS] = json }
    }

    /**
     * Names of folders that have contained at least one entry at some point.
     * Lets the library distinguish "brand-new folder, show first-entry copy"
     * from "folder emptied by deletions, show deleted copy".
     */
    val foldersWithEntries: Flow<Set<String>> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_FOLDERS_WITH_ENTRIES] ?: emptySet() }

    suspend fun markFoldersWithEntries(names: Set<String>) {
        if (names.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val current = prefs[KEY_FOLDERS_WITH_ENTRIES] ?: emptySet()
            if (!current.containsAll(names)) {
                prefs[KEY_FOLDERS_WITH_ENTRIES] = current + names
            }
        }
    }

    // --- Backup bookkeeping (audit #12 + picker starting-point hints) ---

    /** When the last successful export happened (epoch ms; 0 = never). */
    val lastBackupAt: Flow<Long> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_LAST_BACKUP_AT] ?: 0L }

    /** Where the last successful export went (folder label or file name). */
    val lastBackupLabel: Flow<String?> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_LAST_BACKUP_LABEL] }

    suspend fun recordBackup(atMs: Long, label: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_LAST_BACKUP_AT] = atMs
            prefs[KEY_LAST_BACKUP_LABEL] = label
        }
    }

    /** Last document the user picked for import; used as the picker's start hint. */
    val lastImportUri: Flow<String?> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_LAST_IMPORT_URI] }

    suspend fun setLastImportUri(uri: String) {
        context.settingsDataStore.edit { it[KEY_LAST_IMPORT_URI] = uri }
    }

    /** Last document the user picked for export; used as the picker's start hint. */
    val lastExportUri: Flow<String?> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_LAST_EXPORT_URI] }

    suspend fun setLastExportUri(uri: String) {
        context.settingsDataStore.edit { it[KEY_LAST_EXPORT_URI] = uri }
    }

    /** First-launch intro (onboarding) completion flag. */
    val onboardingSeen: Flow<Boolean> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_ONBOARDING_SEEN] ?: false }

    suspend fun setOnboardingSeen() {
        context.settingsDataStore.edit { it[KEY_ONBOARDING_SEEN] = true }
    }
}

/** Shared preference defaults (kept here so ViewModel and store can never drift). */
object NaatViewModelDefaults {
    const val DEFAULT_THEME_MODE = "system"
    const val DEFAULT_FONT_SIZE = 18f
}
