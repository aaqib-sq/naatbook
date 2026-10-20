package com.aistudio.mynaatnotebook.data

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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
private val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
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
private val KEY_LAST_PLAYBACK_NAAT_ID = androidx.datastore.preferences.core.intPreferencesKey("last_playback_naat_id")
private val KEY_PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
private val KEY_DRIVE_EMAIL = stringPreferencesKey("drive_email")
private val KEY_DRIVE_LAST_SYNC_AT = androidx.datastore.preferences.core.longPreferencesKey("drive_last_sync_at")
private val KEY_DRIVE_AUTO_SYNC = androidx.datastore.preferences.core.booleanPreferencesKey("drive_auto_sync")
private val KEY_DRIVE_SYNC_INTERVAL = stringPreferencesKey("drive_sync_interval")
private val KEY_DRIVE_LAST_HASH = stringPreferencesKey("drive_last_hash")
private val KEY_LAST_PLAYBACK_POSITION_MS = androidx.datastore.preferences.core.longPreferencesKey("last_playback_position_ms")
private val KEY_LAST_PLAYBACK_AUDIO_PATH = androidx.datastore.preferences.core.stringPreferencesKey("last_playback_audio_path")

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

    /** App language override: a LocaleHelper choice ("system" / "en" / "ur"). */
    val appLanguage: Flow<String> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_APP_LANGUAGE] ?: LocaleHelper.SYSTEM }

    suspend fun setAppLanguage(choice: String) {
        // Mirror synchronously-readable copy first: attachBaseContext depends on it.
        LocaleHelper.writeChoice(context, choice)
        context.settingsDataStore.edit { it[KEY_APP_LANGUAGE] = choice }
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

    /** Last entry playback point, for resume-after-process-death (audit #21). */
    // Audit 03 M7: the saved point also remembers WHICH of the entry's
    // files was playing — restoring always used to rebuild the session
    // on the primary file, silently dropping a secondary selection.
    data class LastPlayback(val naatId: Int, val positionMs: Long, val audioPath: String? = null)

    val lastPlayback: Flow<LastPlayback?> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val id = prefs[KEY_LAST_PLAYBACK_NAAT_ID] ?: 0
            if (id > 0) LastPlayback(
                id,
                prefs[KEY_LAST_PLAYBACK_POSITION_MS] ?: 0L,
                prefs[KEY_LAST_PLAYBACK_AUDIO_PATH]
            ) else null
        }

    suspend fun setLastPlayback(naatId: Int, positionMs: Long, audioPath: String? = null) {
        context.settingsDataStore.edit {
            it[KEY_LAST_PLAYBACK_NAAT_ID] = naatId
            it[KEY_LAST_PLAYBACK_POSITION_MS] = positionMs
            if (audioPath != null) it[KEY_LAST_PLAYBACK_AUDIO_PATH] = audioPath
            else it.remove(KEY_LAST_PLAYBACK_AUDIO_PATH)
        }
    }

    suspend fun clearLastPlayback() {
        context.settingsDataStore.edit {
            it.remove(KEY_LAST_PLAYBACK_NAAT_ID)
            it.remove(KEY_LAST_PLAYBACK_POSITION_MS)
            it.remove(KEY_LAST_PLAYBACK_AUDIO_PATH)
        }
    }

    /** Persisted playback speed: the reader's speed choice survives app restarts. */
    val playbackSpeed: Flow<Float> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_PLAYBACK_SPEED] ?: 1.0f }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.settingsDataStore.edit { it[KEY_PLAYBACK_SPEED] = speed.coerceIn(0.5f, 2.0f) }
    }

    /**
     * Per-entry reader scroll memory (audit TOP 2): where the user stopped
     * reading, in pixels. Stored as one key per entry id — never in the DB,
     * so it can't disturb updatedAt/lastOpenedAt semantics, and old backups
     * simply don't carry it. Deleted entries' keys are dropped when the
     * delete finalizes (see NaatViewModel.finalizePendingDelete).
     */
    private fun readerScrollKey(naatId: Int) = intPreferencesKey("reader_scroll_$naatId")

    fun readerScrollOffset(naatId: Int): Flow<Int> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[readerScrollKey(naatId)] ?: 0 }

    suspend fun getReaderScrollOffset(naatId: Int): Int =
        context.settingsDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { it[readerScrollKey(naatId)] ?: 0 }
            .first()

    suspend fun setReaderScrollOffset(naatId: Int, offsetPx: Int) {
        context.settingsDataStore.edit { it[readerScrollKey(naatId)] = offsetPx.coerceAtLeast(0) }
    }

    suspend fun clearReaderScrollOffset(naatId: Int) {
        context.settingsDataStore.edit { it.remove(readerScrollKey(naatId)) }
    }

    /**
     * Per-entry reader text-size memory (audit R1): the Aa size the user last
     * chose for this entry, or null when they never sized it — the Settings
     * default then applies. Same per-entry DataStore pattern as the scroll
     * memory above: never in the DB, and deleted entries' keys are dropped
     * when the delete finalizes.
     */
    private fun readerFontSizeKey(naatId: Int) = floatPreferencesKey("reader_font_$naatId")

    fun readerFontSize(naatId: Int): Flow<Float?> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[readerFontSizeKey(naatId)] }

    suspend fun setReaderFontSize(naatId: Int, size: Float) {
        context.settingsDataStore.edit { it[readerFontSizeKey(naatId)] = size.coerceIn(12f, 36f) }
    }

    suspend fun clearReaderFontSize(naatId: Int) {
        context.settingsDataStore.edit { it.remove(readerFontSizeKey(naatId)) }
    }

    // --- Google Drive sync state ---

    /** Connected Google account email for Drive sync; null = not connected. */
    val driveEmail: Flow<String?> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_DRIVE_EMAIL] }

    suspend fun setDriveEmail(email: String) {
        context.settingsDataStore.edit { it[KEY_DRIVE_EMAIL] = email }
    }

    /** When the last successful Drive sync finished (epoch ms; 0 = never). */
    val driveLastSyncAt: Flow<Long> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_DRIVE_LAST_SYNC_AT] ?: 0L }

    /** Content fingerprint captured at the last successful Drive sync. */
    val driveLastHash: Flow<String?> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_DRIVE_LAST_HASH] }

    suspend fun recordDriveSync(atMs: Long, hash: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_DRIVE_LAST_SYNC_AT] = atMs
            prefs[KEY_DRIVE_LAST_HASH] = hash
        }
    }

    /** Whether Drive auto-sync (on app open, when content changed) is on. */
    val driveAutoSync: Flow<Boolean> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_DRIVE_AUTO_SYNC] ?: false }

    suspend fun setDriveAutoSync(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_DRIVE_AUTO_SYNC] = enabled }
    }

    /** How often auto-sync may fire; persisted by key, default WEEKLY. */
    val driveSyncInterval: Flow<DriveSyncInterval> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { DriveSyncInterval.fromKey(it[KEY_DRIVE_SYNC_INTERVAL]) }

    suspend fun setDriveSyncInterval(interval: DriveSyncInterval) {
        context.settingsDataStore.edit { it[KEY_DRIVE_SYNC_INTERVAL] = interval.key }
    }

    /** Forget the Drive connection entirely (local state only). */
    suspend fun clearDriveState() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(KEY_DRIVE_EMAIL)
            prefs.remove(KEY_DRIVE_LAST_SYNC_AT)
            prefs.remove(KEY_DRIVE_AUTO_SYNC)
            prefs.remove(KEY_DRIVE_SYNC_INTERVAL)
            prefs.remove(KEY_DRIVE_LAST_HASH)
        }
    }
}

/** Shared preference defaults (kept here so ViewModel and store can never drift). */
object NaatViewModelDefaults {
    const val DEFAULT_THEME_MODE = "system"
    const val DEFAULT_FONT_SIZE = 18f
}
