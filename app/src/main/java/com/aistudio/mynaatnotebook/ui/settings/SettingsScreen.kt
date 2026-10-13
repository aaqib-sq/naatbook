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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.mynaatnotebook.audio.RecordingState
import com.aistudio.mynaatnotebook.data.ImportMode
import com.aistudio.mynaatnotebook.data.NaatCategories
import com.aistudio.mynaatnotebook.data.NaatEntity
import com.aistudio.mynaatnotebook.ui.theme.MyApplicationTheme
import com.aistudio.mynaatnotebook.ui.reader.ThinSeekBar
import com.aistudio.mynaatnotebook.ui.theme.NastaliqFamily
import com.aistudio.mynaatnotebook.ui.theme.HighContrastRed
import com.aistudio.mynaatnotebook.ui.theme.HighContrastGray
import com.aistudio.mynaatnotebook.viewmodel.BackupViewModel
import com.aistudio.mynaatnotebook.viewmodel.SettingsViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
            shape = RoundedCornerShape(8.dp)
        ) {
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
                        .padding(horizontal = 16.dp, vertical = 12.dp)
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
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (selected) {
                        Icon(Icons.Default.Check, contentDescription = null)
                    }
                }
                if (index < themeChoices.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 58.dp, end = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    )
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
                        val timestamp = System.currentTimeMillis()
                        val fileName = "NaatNotebook_backup_$timestamp.zip"
                        exportBackupLauncher.launch(fileName)
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 68.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
                SettingsActionRow(
                    icon = Icons.Default.Upload,
                    title = stringResource(R.string.settings_import_zip),
                    subtitle = stringResource(R.string.settings_import_subtitle),
                    contentDescription = stringResource(R.string.settings_import_cd),
                    enabled = !backupWorking,
                    testTag = "import_backup_btn",
                    onClick = {
                        importBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }
                )
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

/** One tappable row inside the Settings data card: icon well, title + subtitle, chevron. */
@Composable
private fun SettingsActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    contentDescription: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
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
                color = if (enabled) MaterialTheme.colorScheme.onBackground else HighContrastGray
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = HighContrastGray
            )
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = HighContrastGray,
            modifier = Modifier.size(20.dp)
        )
    }
}

// --- View D: Realtime Lyrics Reader & Distraction-free Performance Screen ---
