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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.mynaatnotebook.audio.RecordingState
import com.aistudio.mynaatnotebook.data.ImportMode
import com.aistudio.mynaatnotebook.data.NaatCategories
import com.aistudio.mynaatnotebook.data.NaatEntity
import com.aistudio.mynaatnotebook.ui.theme.MyApplicationTheme
import com.aistudio.mynaatnotebook.ui.theme.NastaliqFamily
import com.aistudio.mynaatnotebook.ui.theme.HighContrastRed
import com.aistudio.mynaatnotebook.ui.theme.HighContrastGray
import com.aistudio.mynaatnotebook.viewmodel.BackupViewModel
import com.aistudio.mynaatnotebook.viewmodel.SettingsViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    backupViewModel: BackupViewModel
) {
    val context = LocalContext.current
    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
    val globalFontSize by settingsViewModel.globalFontSize.collectAsStateWithLifecycle()
    val backupProgress by backupViewModel.progress.collectAsStateWithLifecycle()
    val backupWorking by backupViewModel.isWorking.collectAsStateWithLifecycle()

    // Create zip launcher
    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = { uri ->
            if (uri != null) {
                backupViewModel.backupNotebook(uri)
            }
        }
    )

    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    // Open zip backup launcher
    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                // Show confirmation before importing (prevents accidental duplicates)
                pendingImportUri = uri
            }
        }
    )

    // Import dialog: choose merge vs replace first. Replace is destructive,
    // so it needs a second, explicit warning confirmation before anything runs.
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
                                        color = HighContrastGray
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
        Text(
            text = stringResource(R.string.settings_theme_title),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                listOf(
                    "system" to stringResource(R.string.settings_theme_system),
                    "white" to stringResource(R.string.settings_theme_white),
                    "black" to stringResource(R.string.settings_theme_black)
                ).forEach { (mode, title) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { settingsViewModel.setThemeMode(mode) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = themeMode == mode,
                            onClick = { settingsViewModel.setThemeMode(mode) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = title, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 2. Default Font Sizing adjustment
        Text(
            text = stringResource(R.string.settings_typography_title),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
            shape = RoundedCornerShape(8.dp)
        ) {
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
                Slider(
                    value = pendingFontSize,
                    onValueChange = { pendingFontSize = it },
                    onValueChangeFinished = { settingsViewModel.setGlobalFontSize(pendingFontSize) },
                    valueRange = 12f..36f,
                    steps = 23, // 24 steps total = 12..36 sp in exact 1-sp increments
                    modifier = Modifier.testTag("global_font_slider")
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
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 3. Local Backup & Restore Offline
        Text(
            text = stringResource(R.string.settings_backup_title),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_backup_description),
            style = MaterialTheme.typography.bodySmall,
            color = HighContrastGray
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    val timestamp = System.currentTimeMillis()
                    val fileName = "NaatNotebook_backup_$timestamp.zip"
                    exportBackupLauncher.launch(fileName)
                },
                enabled = !backupWorking,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("export_backup_btn"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = stringResource(R.string.settings_export_cd))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.settings_export_zip), fontSize = 12.sp)
            }

            Button(
                onClick = {
                    importBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
                enabled = !backupWorking,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("import_backup_btn"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Upload, contentDescription = stringResource(R.string.settings_import_cd))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.settings_import_zip), fontSize = 12.sp)
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
                    color = HighContrastGray,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }

        Spacer(modifier = Modifier.height(40.dp))
        Text(
            text = stringResource(R.string.settings_about, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = HighContrastGray,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(20.dp))
    }
}

// --- View D: Realtime Lyrics Reader & Distraction-free Performance Screen ---
