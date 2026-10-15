package com.aistudio.mynaatnotebook.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import com.aistudio.mynaatnotebook.BuildConfig
import com.aistudio.mynaatnotebook.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.mynaatnotebook.audio.RecordingState
import com.aistudio.mynaatnotebook.data.CustomFont
import com.aistudio.mynaatnotebook.data.DriveSyncInterval
import com.aistudio.mynaatnotebook.data.ImportMode
import com.aistudio.mynaatnotebook.data.NaatCategories
import com.aistudio.mynaatnotebook.data.NaatEntity
import com.aistudio.mynaatnotebook.data.PendingFontImport
import com.aistudio.mynaatnotebook.ui.theme.FontChoices
import com.aistudio.mynaatnotebook.ui.theme.MyApplicationTheme
import com.aistudio.mynaatnotebook.ui.reader.ThinSeekBar
import com.aistudio.mynaatnotebook.ui.theme.NastaliqFamily
import com.aistudio.mynaatnotebook.ui.theme.HighContrastRed
import com.aistudio.mynaatnotebook.ui.theme.HighContrastGray
import com.aistudio.mynaatnotebook.viewmodel.BackupViewModel
import com.aistudio.mynaatnotebook.viewmodel.DriveViewModel
import com.aistudio.mynaatnotebook.viewmodel.SettingsViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.io.File

/** Which manual export destination opened the backup-type chooser dialog. */
private enum class ExportTarget { APP_FOLDER, CHOOSE_PLACE }

@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    backupViewModel: BackupViewModel,
    driveViewModel: DriveViewModel,
    onShowIntro: () -> Unit = {},
    onOpenPrivacyPolicy: () -> Unit = {},
    onOpenContactUs: () -> Unit = {}
) {
    val context = LocalContext.current
    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
    val appLanguage by settingsViewModel.appLanguage.collectAsStateWithLifecycle()
    val globalFontSize by settingsViewModel.globalFontSize.collectAsStateWithLifecycle()
    val readerFont by settingsViewModel.readerFont.collectAsStateWithLifecycle()
    val titleFont by settingsViewModel.titleFont.collectAsStateWithLifecycle()
    val customFonts by settingsViewModel.customFonts.collectAsStateWithLifecycle()
    val customFamilies by settingsViewModel.customFamilies.collectAsStateWithLifecycle()
    val pendingFontImport by settingsViewModel.pendingFontImport.collectAsStateWithLifecycle()
    val fontError by settingsViewModel.fontError.collectAsStateWithLifecycle()
    val backupProgress by backupViewModel.progress.collectAsStateWithLifecycle()
    val backupWorking by backupViewModel.isWorking.collectAsStateWithLifecycle()
    val lastBackupText by settingsViewModel.lastBackupText.collectAsStateWithLifecycle()
    val lastImportUri by settingsViewModel.lastImportUri.collectAsStateWithLifecycle()
    val lastExportUri by settingsViewModel.lastExportUri.collectAsStateWithLifecycle()
    val driveEmail by driveViewModel.connectedEmail.collectAsStateWithLifecycle()
    val driveLastSyncText by driveViewModel.lastSyncText.collectAsStateWithLifecycle()
    val driveAutoSync by driveViewModel.autoSync.collectAsStateWithLifecycle()
    val driveSyncInterval by driveViewModel.syncInterval.collectAsStateWithLifecycle()
    val driveSyncing by driveViewModel.isSyncing.collectAsStateWithLifecycle()
    val driveProgress by driveViewModel.progress.collectAsStateWithLifecycle()
    val drivePendingRestoreUri by driveViewModel.pendingRestoreUri.collectAsStateWithLifecycle()
    val driveConsentIntent by driveViewModel.consentIntent.collectAsStateWithLifecycle()
    var driveDisconnectConfirm by remember { mutableStateOf(false) }

    // Which font picker is open: "reader", "title", or null.
    var fontPickerTarget by remember { mutableStateOf<String?>(null) }
    // Picker to return to after the font-delete dialog closes (audit #14:
    // deleting several fonts used to mean reopening the picker every time).
    var pickerTargetBeforeDelete by remember { mutableStateOf<String?>(null) }
    // Custom font awaiting delete confirmation.
    var fontToDelete by remember { mutableStateOf<CustomFont?>(null) }

    // Font file picker — any file is offered; CustomFontStore validates
    // strictly (extension + magic + real parse) before anything is kept.
    val importFontLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) settingsViewModel.beginFontImport(uri)
        }
    )

    // Export to a user-chosen place (SAF). The intent carries a starting-
    // point hint: Android can't be forced to open a folder, but DocumentsUI
    // honors EXTRA_INITIAL_URI on most devices (incl. Samsung).
    // The backup type (full vs entries-only) is chosen in the export chooser
    // dialog and remembered here for the SAF result callback.
    var pendingExportEntriesOnly by remember { mutableStateOf(false) }
    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            val uri = result.data?.data
            if (result.resultCode == Activity.RESULT_OK && uri != null) {
                backupViewModel.backupNotebook(uri, pendingExportEntriesOnly)
            }
        }
    )

    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    // Backup type chooser state: which export row opened it (one-tap into
    // Download/NaatBook, or the choose-another-place icon).
    var exportChoiceTarget by remember { mutableStateOf<ExportTarget?>(null) }

    // Open zip backup launcher (same starting-point hint as export).
    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            val uri = result.data?.data
            if (result.resultCode == Activity.RESULT_OK && uri != null) {
                // Show confirmation before importing (prevents accidental duplicates)
                pendingImportUri = uri
            }
        }
    )

    // Google Drive sign-in: the system account picker; the ViewModel reads
    // the chosen account (or the failure) from the result intent.
    val driveSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result -> driveViewModel.onSignInResult(result.data) }
    )

    // Google permission screen shown when a sync/download needs fresh
    // consent; on success the interrupted action retries itself.
    val driveConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            driveViewModel.onConsentResult(result.resultCode == Activity.RESULT_OK)
        }
    )
    LaunchedEffect(driveConsentIntent) {
        driveConsentIntent?.let { intent ->
            driveViewModel.consumeConsentIntent()
            driveConsentLauncher.launch(intent)
        }
    }

    // A finished Drive download joins the exact same merge/replace import
    // dialog flow as a locally picked backup file.
    LaunchedEffect(drivePendingRestoreUri) {
        drivePendingRestoreUri?.let { uri ->
            driveViewModel.consumeRestoreUri()
            pendingImportUri = uri
        }
    }

    // Import dialog: choose merge vs replace first. Replace is destructive,
    // so it needs a second, explicit warning confirmation before anything runs.
    // --- Font dialogs (picker / naming / delete / error) ---
    fontPickerTarget?.let { target ->
        val isReader = target == "reader"
        FontPickerDialog(
            title = stringResource(
                if (isReader) R.string.settings_reader_font_picker else R.string.settings_title_font_picker
            ),
            builtinKeys = if (isReader) FontChoices.READER_KEYS else FontChoices.TITLE_KEYS,
            currentChoice = if (isReader) readerFont else titleFont,
            customFonts = customFonts,
            customFamilies = customFamilies,
            onPick = { choice ->
                if (isReader) settingsViewModel.setReaderFont(choice)
                else settingsViewModel.setTitleFont(choice)
                fontPickerTarget = null
            },
            onDeleteRequest = { font ->
                pickerTargetBeforeDelete = fontPickerTarget
                fontPickerTarget = null
                fontToDelete = font
            },
            onImport = {
                fontPickerTarget = null
                importFontLauncher.launch(arrayOf("*/*"))
            },
            onDismiss = { fontPickerTarget = null }
        )
    }

    pendingFontImport?.let { pending ->
        FontNameDialog(
            pending = pending,
            onSave = { name -> settingsViewModel.confirmFontImport(name) },
            onCancel = { settingsViewModel.cancelFontImport() }
        )
    }

    fontToDelete?.let { font ->
        val inUse = readerFont == FontChoices.customKey(font.id) ||
            titleFont == FontChoices.customKey(font.id)
        // Closing this dialog (either way) returns to the picker it came
        // from, so removing several fonts doesn't need repeated reopening.
        val closeDelete: () -> Unit = {
            fontToDelete = null
            fontPickerTarget = pickerTargetBeforeDelete
            pickerTargetBeforeDelete = null
        }
        AlertDialog(
            onDismissRequest = closeDelete,
            title = { Text(stringResource(R.string.settings_font_delete_title)) },
            text = {
                Text(
                    stringResource(
                        if (inUse) R.string.settings_font_delete_in_use else R.string.settings_font_delete_message,
                        font.name
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    settingsViewModel.deleteCustomFont(font.id)
                    closeDelete()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = closeDelete) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    fontError?.let { message ->
        AlertDialog(
            onDismissRequest = { settingsViewModel.clearFontError() },
            title = { Text(stringResource(R.string.settings_font_error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { settingsViewModel.clearFontError() }) {
                    Text(stringResource(R.string.common_ok))
                }
            }
        )
    }

    pendingImportUri?.let { uri ->
        var importMode by remember(uri) { mutableStateOf(ImportMode.MERGE) }
        var confirmReplace by remember(uri) { mutableStateOf(false) }
        if (!confirmReplace) {
            AlertDialog(
                onDismissRequest = { pendingImportUri = null },
                title = { Text(stringResource(R.string.settings_import_confirm_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.settings_import_confirm_message))
                        Spacer(modifier = Modifier.height(12.dp))
                        listOf(
                            ImportMode.MERGE to (R.string.settings_import_mode_merge to R.string.settings_import_mode_merge_desc),
                            ImportMode.REPLACE to (R.string.settings_import_mode_replace to R.string.settings_import_mode_replace_desc)
                        ).forEach { (mode, texts) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { importMode = mode }
                                    .padding(vertical = 4.dp)
                                    .testTag("import_mode_${mode.name.lowercase()}")
                            ) {
                                RadioButton(
                                    selected = importMode == mode,
                                    onClick = { importMode = mode }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(texts.first),
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (mode == ImportMode.REPLACE) HighContrastRed
                                                else MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        stringResource(texts.second),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (importMode == ImportMode.REPLACE) {
                                confirmReplace = true
                            } else {
                                pendingImportUri = null
                                backupViewModel.restoreNotebook(uri, ImportMode.MERGE)
                            }
                        },
                        modifier = Modifier.testTag("import_confirm_btn")
                    ) {
                        Text(
                            stringResource(
                                if (importMode == ImportMode.REPLACE) R.string.settings_import_mode_replace
                                else R.string.settings_import_confirm_import
                            )
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingImportUri = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { pendingImportUri = null },
                title = { Text(stringResource(R.string.settings_import_replace_title)) },
                text = { Text(stringResource(R.string.settings_import_replace_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingImportUri = null
                            backupViewModel.restoreNotebook(uri, ImportMode.REPLACE)
                        },
                        modifier = Modifier.testTag("import_replace_confirm_btn")
                    ) {
                        Text(
                            stringResource(R.string.settings_import_replace_confirm),
                            color = HighContrastRed
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingImportUri = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
                modifier = Modifier.testTag("import_replace_dialog")
            )
        }
    }

    // Backup type chooser: shown when a manual export is triggered — either
    // the one-tap row (Download/NaatBook) or the choose-another-place icon.
    // The choice travels into the ZIP itself, so the file name is only a
    // label; "-entries" keeps entries-only files recognizable.
    exportChoiceTarget?.let { target ->
        var entriesOnly by remember(target) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { exportChoiceTarget = null },
            title = { Text(stringResource(R.string.settings_export_choice_title)) },
            text = {
                Column {
                    listOf(
                        false to (
                            R.string.settings_export_choice_full to
                                R.string.settings_export_choice_full_desc
                            ),
                        true to (
                            R.string.settings_export_choice_entries to
                                R.string.settings_export_choice_entries_desc
                            )
                    ).forEach { (value, texts) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { entriesOnly = value }
                                .padding(vertical = 4.dp)
                                .testTag("export_choice_${if (value) "entries_only" else "full"}")
                        ) {
                            RadioButton(
                                selected = entriesOnly == value,
                                onClick = { entriesOnly = value }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(texts.first),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    stringResource(texts.second),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        exportChoiceTarget = null
                        val fileName = "NaatNotebook_backup" +
                            (if (entriesOnly) "_entries" else "") +
                            "_${System.currentTimeMillis()}.zip"
                        when (target) {
                            ExportTarget.APP_FOLDER ->
                                backupViewModel.backupNotebookToAppFolder(fileName, entriesOnly)
                            ExportTarget.CHOOSE_PLACE -> {
                                pendingExportEntriesOnly = entriesOnly
                                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_TITLE, fileName)
                                    runCatching {
                                        putExtra(
                                            android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                                            backupInitialUri(lastExportUri)
                                        )
                                    }
                                }
                                exportBackupLauncher.launch(intent)
                            }
                        }
                    },
                    modifier = Modifier.testTag("export_choice_confirm_btn")
                ) { Text(stringResource(R.string.settings_export_cd)) }
            },
            dismissButton = {
                TextButton(onClick = { exportChoiceTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            modifier = Modifier.testTag("export_choice_dialog")
        )
    }

    if (driveDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { driveDisconnectConfirm = false },
            title = { Text(stringResource(R.string.settings_drive_disconnect_title)) },
            text = { Text(stringResource(R.string.settings_drive_disconnect_message)) },
            confirmButton = {
                TextButton(onClick = {
                    driveDisconnectConfirm = false
                    driveViewModel.disconnect()
                }) { Text(stringResource(R.string.settings_drive_disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { driveDisconnectConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        // 1. Theme Configuration
        SettingsGroupHeading(stringResource(R.string.settings_theme_title))
        Spacer(modifier = Modifier.height(8.dp))

        SettingsGroupCard {
            // Theme choices as three rows in one card (approved design):
            // a color dot shows the theme, a subtitle says what it means,
            // and the selected row carries a check. (The visual swatch
            // cards were rejected as cheap-looking; rows match the
            // settings page's own grouped-row language.)
            val themeChoices = listOf(
                Triple(
                    "system",
                    stringResource(R.string.settings_theme_system),
                    stringResource(R.string.settings_theme_system_sub)
                ),
                Triple(
                    "white",
                    stringResource(R.string.settings_theme_white),
                    stringResource(R.string.settings_theme_white_sub)
                ),
                Triple(
                    "black",
                    stringResource(R.string.settings_theme_black),
                    stringResource(R.string.settings_theme_black_sub)
                )
            )
            themeChoices.forEachIndexed { index, (mode, title, subtitle) ->
                val selected = themeMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { settingsViewModel.setThemeMode(mode) }
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                        .testTag("settings_theme_$mode"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemeDot(mode)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selected) {
                        Icon(Icons.Default.Check, contentDescription = null)
                    }
                }
                if (index < themeChoices.lastIndex) {
                    SettingsRowDivider(58.dp)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))


        // 2. App Language
        SettingsGroupHeading(stringResource(R.string.settings_language_title))
        Spacer(modifier = Modifier.height(8.dp))

        SettingsGroupCard {
            // Same grouped-row language as the theme card: title, subtitle,
            // check on the selected row. Picking a row stores the choice and
            // recreates the activity so every string re-resolves at once.
            // Layout direction stays LTR in every language (user decision
            // 2026-10-15): choosing Urdu swaps strings only, never mirrors.
            val languageChoices = listOf(
                Triple(
                    "system",
                    stringResource(R.string.settings_language_system),
                    stringResource(R.string.settings_language_system_sub)
                ),
                Triple(
                    "en",
                    stringResource(R.string.settings_language_english),
                    stringResource(R.string.settings_language_english_sub)
                ),
                Triple(
                    "ur",
                    stringResource(R.string.settings_language_urdu),
                    stringResource(R.string.settings_language_urdu_sub)
                )
            )
            languageChoices.forEachIndexed { index, (mode, title, subtitle) ->
                val selected = appLanguage == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!selected) {
                                settingsViewModel.setAppLanguage(mode)
                                (context as? Activity)?.recreate()
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                        .testTag("settings_language_$mode"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selected) {
                        Icon(Icons.Default.Check, contentDescription = null)
                    }
                }
                if (index < languageChoices.lastIndex) {
                    SettingsRowDivider(16.dp)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 3. Default Font Sizing adjustment
        SettingsGroupHeading(stringResource(R.string.settings_typography_title))
        Spacer(modifier = Modifier.height(8.dp))

        SettingsGroupCard {
            Column(modifier = Modifier.padding(16.dp)) {
                // Drag updates a LOCAL pending value (label & preview follow
                // live); the DataStore write fires exactly once, on release.
                // Committing on every tick was a disk write-storm per drag.
                var pendingFontSize by remember { mutableFloatStateOf(globalFontSize) }
                LaunchedEffect(globalFontSize) { pendingFontSize = globalFontSize }
                Text(
                    text = stringResource(R.string.settings_font_size_label, pendingFontSize.toInt()),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Same thin seek line as the reader (approved design); the
                // stock slider's thick track and stick thumb are gone. The
                // drag moves the local pending value in exact 1-sp steps and
                // the DataStore commit is debounced, so a drag still writes
                // once — never a write-storm.
                LaunchedEffect(pendingFontSize) {
                    if (pendingFontSize != globalFontSize) {
                        delay(350)
                        settingsViewModel.setGlobalFontSize(pendingFontSize)
                    }
                }
                ThinSeekBar(
                    fraction = (pendingFontSize - 12f) / 24f,
                    onFractionChange = { fraction ->
                        pendingFontSize = (12f + fraction * 24f).roundToInt().toFloat()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("global_font_slider")
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Sample preview — real salam text in authentic Nastaliq + RTL
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Text(
                        text = "اللَّهُمَّ صَلِّ عَلَىٰ مُحَمَّدٍ وَعَلَىٰ آلِ مُحَمَّدٍ",
                        fontSize = pendingFontSize.sp,
                        fontFamily = NastaliqFamily,
                        lineHeight = (pendingFontSize * 2.0f).sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                SettingsRowDivider(0.dp)
                // Font choice rows (approved design): current choice as the
                // subtitle; tapping opens the picker with live samples.
                FontChoiceRow(
                    title = stringResource(R.string.settings_reader_font),
                    choiceLabel = fontChoiceLabel(readerFont, customFonts),
                    testTag = "reader_font_btn",
                    onClick = { fontPickerTarget = "reader" }
                )
                SettingsRowDivider(72.dp)
                FontChoiceRow(
                    title = stringResource(R.string.settings_title_font),
                    choiceLabel = fontChoiceLabel(titleFont, customFonts),
                    testTag = "title_font_btn",
                    onClick = { fontPickerTarget = "title" }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 3. Local Backup & Restore (offline)
        SettingsGroupHeading(stringResource(R.string.settings_backup_title))
        Spacer(modifier = Modifier.height(8.dp))

        // Grouped rows in one card (approved design) instead of two loose
        // buttons: icon well, title + subtitle, chevron.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column {
                SettingsActionRow(
                    icon = Icons.Default.Download,
                    title = stringResource(R.string.settings_export_zip),
                    subtitle = stringResource(R.string.settings_export_subtitle),
                    contentDescription = stringResource(R.string.settings_export_cd),
                    enabled = !backupWorking,
                    testTag = "export_backup_btn",
                    onClick = {
                        // One-tap export into Download/NaatBook (approved
                        // design) — no picker for the common case. The type
                        // chooser dialog decides full vs entries-only.
                        exportChoiceTarget = ExportTarget.APP_FOLDER
                    },
                    trailing = {
                        IconButton(
                            onClick = { exportChoiceTarget = ExportTarget.CHOOSE_PLACE },
                            enabled = !backupWorking,
                            modifier = Modifier.testTag("export_choose_place_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = stringResource(R.string.settings_export_choose_place_cd),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                )
                SettingsRowDivider()
                SettingsActionRow(
                    icon = Icons.Default.Upload,
                    title = stringResource(R.string.settings_import_zip),
                    contentDescription = stringResource(R.string.settings_import_cd),
                    enabled = !backupWorking,
                    testTag = "import_backup_btn",
                    onClick = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(
                                Intent.EXTRA_MIME_TYPES,
                                arrayOf("application/zip", "application/octet-stream")
                            )
                            runCatching {
                                putExtra(
                                    android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                                    backupInitialUri(lastImportUri)
                                )
                            }
                        }
                        importBackupLauncher.launch(intent)
                    }
                )
                if (lastBackupText != null) {
                    SettingsRowDivider()
                    Text(
                        text = stringResource(R.string.settings_last_backup, lastBackupText!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 68.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)
                            .testTag("last_backup_text")
                    )
                }
            }
        }

        // Backup progress indicator
        backupProgress?.let { progress ->
            Spacer(modifier = Modifier.height(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .testTag("backup_progress_bar"),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 4. Google Drive Backup (online, opt-in)
        SettingsGroupHeading(stringResource(R.string.settings_drive_title))
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column {
                if (driveEmail == null) {
                    SettingsActionRow(
                        icon = Icons.Default.CloudSync,
                        title = stringResource(R.string.settings_drive_connect),
                        subtitle = stringResource(R.string.settings_drive_connect_subtitle),
                        contentDescription = stringResource(R.string.settings_drive_connect),
                        enabled = !driveSyncing && !backupWorking,
                        testTag = "drive_connect_btn",
                        onClick = {
                            driveSignInLauncher.launch(driveViewModel.signInIntent())
                        }
                    )
                } else {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = driveEmail!!,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = if (driveLastSyncText != null) {
                                stringResource(
                                    R.string.settings_drive_last_sync, driveLastSyncText!!
                                )
                            } else {
                                stringResource(R.string.settings_drive_never_synced)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("drive_last_sync_text")
                        )
                    }
                    SettingsRowDivider(14.dp)
                    SettingsActionRow(
                        icon = Icons.Default.CloudUpload,
                        title = stringResource(R.string.settings_drive_sync_now),
                        contentDescription = stringResource(R.string.settings_drive_sync_now),
                        enabled = !driveSyncing && !backupWorking,
                        testTag = "drive_sync_btn",
                        onClick = { driveViewModel.syncNow() }
                    )
                    SettingsRowDivider()
                    SettingsActionRow(
                        icon = Icons.Default.CloudDownload,
                        title = stringResource(R.string.settings_drive_restore),
                        contentDescription = stringResource(R.string.settings_drive_restore),
                        enabled = !driveSyncing && !backupWorking,
                        testTag = "drive_restore_btn",
                        onClick = { driveViewModel.restoreLatest() }
                    )
                    SettingsRowDivider()
                    SettingsActionRow(
                        icon = Icons.Default.CloudSync,
                        title = stringResource(R.string.settings_drive_auto),
                        subtitle = stringResource(R.string.settings_drive_auto_subtitle),
                        contentDescription = stringResource(R.string.settings_drive_auto),
                        enabled = !driveSyncing,
                        testTag = "drive_auto_row",
                        onClick = { driveViewModel.setAutoSync(!driveAutoSync) },
                        trailing = {
                            Switch(
                                checked = driveAutoSync,
                                onCheckedChange = { driveViewModel.setAutoSync(it) },
                                modifier = Modifier.testTag("drive_auto_switch")
                            )
                        }
                    )
                    SettingsRowDivider()
                    // Auto-sync schedule: shown only while auto-sync is on.
                    // Same grouped-row language as the rest of the card (title
                    // row + a check on the selected interval); monochrome.
                    if (driveAutoSync) {
                        Column(
                            modifier = Modifier.padding(
                                start = 68.dp,
                                end = 14.dp,
                                top = 10.dp,
                                bottom = 4.dp
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.settings_drive_interval_title),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            val intervalChoices = listOf(
                                DriveSyncInterval.OPEN to
                                    stringResource(R.string.settings_drive_interval_open),
                                DriveSyncInterval.DAILY to
                                    stringResource(R.string.settings_drive_interval_daily),
                                DriveSyncInterval.WEEKLY to
                                    stringResource(R.string.settings_drive_interval_weekly)
                            )
                            intervalChoices.forEach { (interval, label) ->
                                val selected = driveSyncInterval == interval
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !driveSyncing) {
                                            if (!selected) {
                                                driveViewModel.setSyncInterval(interval)
                                            }
                                        }
                                        .padding(vertical = 9.dp)
                                        .testTag("drive_interval_${interval.key}"),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (selected) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                }
                            }
                        }
                        SettingsRowDivider()
                    }
                    SettingsActionRow(
                        icon = Icons.Default.CloudOff,
                        title = stringResource(R.string.settings_drive_disconnect),
                        subtitle = stringResource(R.string.settings_drive_disconnect_subtitle),
                        contentDescription = stringResource(R.string.settings_drive_disconnect),
                        enabled = !driveSyncing,
                        testTag = "drive_disconnect_btn",
                        onClick = { driveDisconnectConfirm = true }
                    )
                }
            }
        }

        // Drive sync/download progress indicator
        driveProgress?.let { progress ->
            Spacer(modifier = Modifier.height(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .testTag("drive_progress_bar"),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // App intro replay (the first-launch pages, on demand).
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
            shape = RoundedCornerShape(12.dp)
        ) {
            SettingsActionRow(
                icon = Icons.Default.MenuBook,
                title = stringResource(R.string.settings_intro_title),
                subtitle = stringResource(R.string.settings_intro_subtitle),
                contentDescription = stringResource(R.string.settings_intro_cd),
                enabled = true,
                testTag = "settings_intro_btn",
                onClick = onShowIntro
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // About group: privacy policy + contact info. The app identity
        // line below stays its single footer (per the approved design).
        SettingsGroupHeading(stringResource(R.string.settings_about_group))
        Spacer(modifier = Modifier.height(8.dp))

        SettingsGroupCard {
            SettingsActionRow(
                icon = Icons.Default.Description,
                title = stringResource(R.string.settings_privacy_row),
                subtitle = stringResource(R.string.settings_privacy_row_sub),
                contentDescription = stringResource(R.string.settings_privacy_row),
                enabled = true,
                testTag = "about_privacy_row",
                onClick = onOpenPrivacyPolicy
            )
            SettingsRowDivider()
            SettingsActionRow(
                icon = Icons.Default.MailOutline,
                title = stringResource(R.string.settings_contact_row),
                subtitle = stringResource(R.string.settings_contact_row_sub),
                contentDescription = stringResource(R.string.settings_contact_row),
                enabled = true,
                testTag = "about_contact_row",
                onClick = onOpenContactUs
            )
        }

        Spacer(modifier = Modifier.height(40.dp))
        Text(
            text = stringResource(R.string.settings_about, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(20.dp))
    }
}

/** Uniform bordered card shared by every settings group (matches the
 * Drive/Export card treatment: visible border, 12dp corners, transparent
 * body so the theme background shows through). */
@Composable
private fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(content = content)
    }
}

/** Uniform small all-caps grey heading above every settings group. The
 * group's EXISTING title string is uppercased by style; no copy is renamed. */
@Composable
private fun SettingsGroupHeading(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.5.sp
    )
}

/** Uniform hairline divider between rows inside a group card. */
@Composable
private fun SettingsRowDivider(start: Dp = 68.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = start),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    )
}

/** Small color dot previewing a theme mode: half black / half white for system. */
@Composable
private fun ThemeDot(mode: String) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
    ) {
        when (mode) {
            "white" -> Box(Modifier.fillMaxSize().background(Color(0xFFF7F7F7)))
            "black" -> Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A)))
            else -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF0A0A0A)))
                Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFF7F7F7)))
            }
        }
    }
}

/** Display label for a stored font choice (built-in name or the custom font's own name). */
@Composable
private fun fontChoiceLabel(choice: String, customFonts: List<CustomFont>): String {
    FontChoices.customId(choice)?.let { id ->
        return customFonts.firstOrNull { it.id == id }?.name ?: stringResource(R.string.settings_font_custom_missing)
    }
    return stringResource(
        when (choice) {
            FontChoices.AUTO -> R.string.settings_font_auto
            FontChoices.SERIF -> R.string.settings_font_serif
            FontChoices.NASTALIQ -> R.string.settings_font_nastaliq
            else -> R.string.settings_font_sans
        }
    )
}

/** One tappable font-choice row inside the typography card (name + current choice + chevron). */
@Composable
private fun FontChoiceRow(
    title: String,
    choiceLabel: String,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text("Aa", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                choiceLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Font picker — the single home for fonts (approved design): built-in
 * choices first, then the user's imported fonts (each with a trash action),
 * and an import button at the bottom. There is deliberately no separate
 * "My Fonts" card on the main settings screen anymore.
 */
@Composable
private fun FontPickerDialog(
    title: String,
    builtinKeys: List<String>,
    currentChoice: String,
    customFonts: List<CustomFont>,
    customFamilies: Map<String, FontFamily>,
    onPick: (String) -> Unit,
    onDeleteRequest: (CustomFont) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                builtinKeys.forEach { key ->
                    FontPickerRow(
                        label = fontChoiceLabel(key, customFonts),
                        sample = when (key) {
                            FontChoices.AUTO -> stringResource(R.string.settings_font_auto_desc)
                            FontChoices.NASTALIQ -> "مدینہ کا سفر"
                            else -> "Madina ka Safar"
                        },
                        family = FontChoices.builtinFamily(key),
                        selected = currentChoice == key,
                        testTag = "font_picker_row_$key",
                        onClick = { onPick(key) }
                    )
                }
                customFonts.forEach { font ->
                    val key = FontChoices.customKey(font.id)
                    FontPickerRow(
                        label = font.name,
                        sample = "مدینہ کا سفر",
                        family = customFamilies[font.id] ?: FontFamily.Default,
                        selected = currentChoice == key,
                        testTag = "font_picker_row_$key",
                        onClick = { onPick(key) },
                        onDelete = { onDeleteRequest(font) },
                        deleteTestTag = "font_picker_delete_${font.id}"
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("import_font_btn"),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onBackground
                    )
                ) {
                    Text(stringResource(R.string.settings_import_font), maxLines = 1)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun FontPickerRow(
    label: String,
    sample: String,
    family: FontFamily,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    deleteTestTag: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                sample,
                fontFamily = family,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Icon(imageVector = Icons.Default.Check, contentDescription = null)
        }
        if (onDelete != null) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag(deleteTestTag ?: "font_picker_delete")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.settings_font_delete_cd, label)
                )
            }
        }
    }
}

/** Naming step after a font file validates: shows the real file, an editable name, and a live sample. */
@Composable
private fun FontNameDialog(
    pending: PendingFontImport,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember(pending) { mutableStateOf(pending.suggestedName) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.settings_font_new_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_font_file_label, pending.sourceName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_font_name_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("font_name_field")
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "مدینہ کا سفر",
                    fontFamily = pending.family,
                    fontSize = 26.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.settings_font_sample_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("font_save_btn")
            ) { Text(stringResource(R.string.settings_font_save)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** One tappable row inside the Settings data card: icon well, title + subtitle, chevron. */
@Composable
private fun SettingsActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    contentDescription: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) MaterialTheme.colorScheme.onBackground else HighContrastGray,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onBackground else HighContrastGray
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Starting-point hint for the system document picker: the document the user
 * picked last time, else the app's own Download/NaatBook folder. Android
 * treats EXTRA_INITIAL_URI as a hint — most DocumentsUI builds (incl.
 * Samsung's) open there, but nothing can force it.
 */
private fun backupInitialUri(persisted: String?): Uri =
    persisted?.let { runCatching { Uri.parse(it) }.getOrNull() }
        ?: android.provider.DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Download/NaatBook"
        )
