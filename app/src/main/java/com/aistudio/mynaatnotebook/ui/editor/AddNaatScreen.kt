package com.aistudio.mynaatnotebook.ui.editor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.aistudio.mynaatnotebook.R
import com.aistudio.mynaatnotebook.audio.RecordingState
import com.aistudio.mynaatnotebook.data.NaatCategories
import com.aistudio.mynaatnotebook.ui.components.CategoryDisplay
import com.aistudio.mynaatnotebook.ui.components.AudioAttachmentPreview
import com.aistudio.mynaatnotebook.ui.components.RecordingVuMeter
import com.aistudio.mynaatnotebook.ui.components.formatTime
import com.aistudio.mynaatnotebook.ui.components.usesArabicScript
import com.aistudio.mynaatnotebook.ui.theme.secondaryTextColor
import com.aistudio.mynaatnotebook.ui.theme.HighContrastRed
import com.aistudio.mynaatnotebook.ui.theme.NastaliqFamily
import com.aistudio.mynaatnotebook.viewmodel.EditorAttachmentDraft
import com.aistudio.mynaatnotebook.viewmodel.NaatViewModel
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.aistudio.mynaatnotebook.ui.components.FONT_FOLLOW_SETTINGS
import com.aistudio.mynaatnotebook.ui.components.FontPickerDialog
import com.aistudio.mynaatnotebook.ui.components.FontPickerLeadChoice
import com.aistudio.mynaatnotebook.ui.components.fontChoiceLabel
import com.aistudio.mynaatnotebook.ui.theme.FontChoices
import com.aistudio.mynaatnotebook.viewmodel.SettingsViewModel

/** Keyed lazy editor: only visible sections are composed and high-frequency media state is local. */
@Composable
fun AddNaatModal(
    viewModel: NaatViewModel,
    settingsViewModel: SettingsViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The form scrolls in the weighted list; the Save bar is pinned below it
    // (approved design) so saving never needs a scroll to the form's end.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "header") { EditorHeader(viewModel, onClose) }
            item(key = "metadata") { EditorMetadataSection(viewModel, settingsViewModel) }
            item(key = "audio") { EditorAudioSection(viewModel) }
            item(key = "bottom-space") { Spacer(Modifier.height(8.dp)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            EditorSaveSection(viewModel)
        }
    }
}

@Composable
private fun EditorHeader(viewModel: NaatViewModel, onClose: () -> Unit) {
    val editingId by viewModel.editorEntryId.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val isAttaching by viewModel.isAttachingFile.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            if (editingId != null) stringResource(R.string.editor_title_edit) else stringResource(R.string.editor_title_add),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        IconButton(
            onClick = onClose,
            enabled = !isSaving && !isAttaching,
            modifier = Modifier.testTag("close_add_modal")
        ) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.editor_close_cd))
        }
    }
}

@Composable
private fun EditorMetadataSection(viewModel: NaatViewModel, settingsViewModel: SettingsViewModel) {
    val context = LocalContext.current
    val metadata by viewModel.editorMetadata.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val lyricsDictationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) {
                viewModel.updateDraft {
                    it.copy(lyrics = if (it.lyrics.isBlank()) spoken else it.lyrics.trimEnd() + "\n" + spoken)
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Details card (approved design): borderless fields grouped in one
        // card, divided by a hairline — the grouped-form look of pro apps.
        EditorSectionCard {
            EditorCardLabel(stringResource(R.string.editor_details_title))
            TextField(
                value = metadata.title,
                onValueChange = { value -> viewModel.updateDraft { it.copy(title = value) } },
                label = { Text(stringResource(R.string.editor_title_label)) },
                modifier = Modifier.fillMaxWidth().testTag("add_naat_title"),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                singleLine = true,
                enabled = !isSaving,
                colors = borderlessFieldColors()
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            TextField(
                value = metadata.poet,
                onValueChange = { value -> viewModel.updateDraft { it.copy(poet = value) } },
                label = { Text(stringResource(R.string.editor_poet_label)) },
                modifier = Modifier.fillMaxWidth().testTag("add_naat_poet"),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                singleLine = true,
                enabled = !isSaving,
                colors = borderlessFieldColors()
            )
        }

        // Folder chips (approved design): one tap picks the folder; the old
        // read-only field + dropdown menu is gone.
        Column {
            Text(
                text = stringResource(R.string.editor_folder_label),
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryTextColor(),
                modifier = Modifier.padding(start = 4.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("folder_location_dropdown")
            ) {
                items(NaatCategories.ALL) { category ->
                    FolderChip(
                        label = stringResource(CategoryDisplay.displayNameRes(category)),
                        selected = metadata.category == category,
                        enabled = !isSaving,
                        onClick = { viewModel.updateDraft { it.copy(category = category) } }
                    )
                }
            }
        }

        // Per-entry fonts (approved design): two independent rows — lyrics
        // font and title font; null on either follows the corresponding
        // global Settings font.
        EntryFontSection(viewModel, settingsViewModel)

        // Lyrics in their own card (approved design).
        EditorSectionCard {
            EditorCardLabel(stringResource(R.string.editor_lyrics_label))
            TextField(
                value = metadata.lyrics,
                onValueChange = { value -> viewModel.updateDraft { it.copy(lyrics = value) } },
                placeholder = { Text(stringResource(R.string.editor_lyrics_hint)) },
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = if (usesArabicScript(metadata.lyrics)) NastaliqFamily else FontFamily.Default,
                    lineHeight = if (usesArabicScript(metadata.lyrics)) 32.sp else LocalTextStyle.current.lineHeight
                ),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            try {
                                lyricsDictationLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                    )
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.editor_dictate_prompt))
                                })
                            } catch (_: Exception) {
                                Toast.makeText(context, context.getString(R.string.editor_stt_unsupported), Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.testTag("lyrics_dictate_mic")
                    ) { Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.editor_dictate_cd), tint = secondaryTextColor()) }
                },
                modifier = Modifier.fillMaxWidth().height(180.dp).testTag("add_naat_lyrics"),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                enabled = !isSaving,
                colors = borderlessFieldColors()
            )
        }
    }
}

/**
 * Per-entry font picker block: two independent rows — one for the lyrics,
 * one for the title (the title is a display element and can carry a different
 * font than the body text). A null override on either means "follow the
 * corresponding global Settings font"; any other choice applies to this
 * entry's respective surface only (built-in or imported font).
 */
private enum class EntryFontRow { LYRICS, TITLE }

@Composable
private fun EntryFontSection(viewModel: NaatViewModel, settingsViewModel: SettingsViewModel) {
    EntryFontRowView(viewModel, settingsViewModel, EntryFontRow.LYRICS)
    Spacer(modifier = Modifier.height(12.dp))
    EntryFontRowView(viewModel, settingsViewModel, EntryFontRow.TITLE)
}

@Composable
private fun EntryFontRowView(
    viewModel: NaatViewModel,
    settingsViewModel: SettingsViewModel,
    row: EntryFontRow
) {
    val metadata by viewModel.editorMetadata.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val globalReaderFont by settingsViewModel.readerFont.collectAsStateWithLifecycle()
    val globalTitleFont by settingsViewModel.titleFont.collectAsStateWithLifecycle()
    val customFonts by settingsViewModel.customFonts.collectAsStateWithLifecycle()
    val customFamilies by settingsViewModel.customFamilies.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }

    val isLyrics = row == EntryFontRow.LYRICS
    val override = if (isLyrics) metadata.readerFontOverride else metadata.titleFontOverride
    val globalFont = if (isLyrics) globalReaderFont else globalTitleFont
    val builtinKeys = if (isLyrics) FontChoices.READER_KEYS else FontChoices.TITLE_KEYS
    val labelRes = if (isLyrics) R.string.editor_lyrics_font_label
                   else R.string.editor_title_font_label
    val testTag = if (isLyrics) "entry_lyrics_font_btn" else "entry_title_font_btn"

    val currentLabel = override?.let { fontChoiceLabel(it, customFonts) }
        ?: stringResource(R.string.editor_font_follow_settings)

    Column {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = secondaryTextColor(),
            modifier = Modifier.padding(start = 4.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !isSaving) { showPicker = true }
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
                Text(
                    text = "Aa",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = secondaryTextColor()
            )
        }
    }

    if (showPicker) {
        FontPickerDialog(
            title = stringResource(labelRes),
            builtinKeys = builtinKeys,
            currentChoice = override ?: FONT_FOLLOW_SETTINGS,
            customFonts = customFonts,
            customFamilies = customFamilies,
            onPick = { choice ->
                val chosen = if (choice == FONT_FOLLOW_SETTINGS) null else choice
                viewModel.updateDraft {
                    if (isLyrics) it.copy(readerFontOverride = chosen)
                    else it.copy(titleFontOverride = chosen)
                }
                showPicker = false
            },
            onDismiss = { showPicker = false },
            leadChoices = listOf(
                FontPickerLeadChoice(
                    choiceKey = FONT_FOLLOW_SETTINGS,
                    label = stringResource(R.string.editor_font_follow_settings),
                    sample = fontChoiceLabel(globalFont, customFonts),
                    testTag = "font_picker_row_follow_settings"
                )
            )
        )
    }
}

/** The editor's grouped-section card, matching the audio card's styling. */
@Composable
private fun EditorSectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            content = content
        )
    }
}

/** Small label at the top of an editor section card. Shown as-is: the
 * labels localize into Urdu, where uppercasing is meaningless and
 * letter-tracking breaks joining. */
@Composable
private fun EditorCardLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold
        ),
        color = secondaryTextColor(),
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
    )
}

/** Borderless field colors so fields sit directly on their section card. */
@Composable
private fun borderlessFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    errorContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent
)

/** One folder choice in the editor's chip row. */
@Composable
private fun FolderChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.onBackground, shape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .testTag("folder_chip_$label"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.background
                    else MaterialTheme.colorScheme.onBackground,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Confirmation shown before an action that would replace an audio already
 * attached to this entry (the old audio is removed when the entry is saved).
 */
@Composable
private fun ReplaceAudioConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_replace_audio_title)) },
        text = { Text(stringResource(R.string.editor_replace_audio_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("replace_audio_confirm")
            ) { Text(stringResource(R.string.action_replace)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        modifier = Modifier.testTag("replace_audio_dialog")
    )
}

@Composable
private fun EditorAudioSection(viewModel: NaatViewModel) {
    val context = LocalContext.current
    val attachments by viewModel.editorAttachments.collectAsStateWithLifecycle()
    val activeRecordingFile by viewModel.activeRecordingFile.collectAsStateWithLifecycle()
    val isAttaching by viewModel.isAttachingFile.collectAsStateWithLifecycle()

    fun doAttach(uri: Uri) {
        viewModel.attachLocalFile(uri) { success ->
            Toast.makeText(
                context,
                if (success) context.getString(R.string.editor_attach_success) else context.getString(R.string.editor_attach_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Replacing an already-attached linked audio loses the old one on save,
    // so that path asks first; a first-time attach never prompts.
    val hasExistingLinked = (attachments.existingAudioType == "local_file" && !attachments.existingAudioRemoved) ||
        (attachments.existingSecondaryAudioType == "local_file" && !attachments.existingSecondaryAudioRemoved)
    var pendingReplaceUri by remember { mutableStateOf<Uri?>(null) }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !isAttaching) {
            if (hasExistingLinked) pendingReplaceUri = uri else doAttach(uri)
        }
    }

    pendingReplaceUri?.let { uri ->
        ReplaceAudioConfirmDialog(
            onConfirm = {
                pendingReplaceUri = null
                doAttach(uri)
            },
            onDismiss = { pendingReplaceUri = null }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.editor_dual_audio_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Card(
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExistingAttachments(attachments, activeRecordingFile != null, viewModel)
                HorizontalDivider()
                RecorderControls(
                    viewModel = viewModel,
                    onBrowseClick = { audioPicker.launch("audio/*") },
                    // Disable while attaching or when a new file is already picked
                    // (prevents silently replacing the pending attachment).
                    browseEnabled = !isAttaching && attachments.newAttachmentPath == null,
                    isAttaching = isAttaching
                )
                attachments.newAttachmentPath?.let { path ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            attachments.newAttachmentName?.let {
                                stringResource(R.string.editor_attached_file, it)
                            } ?: path.substringAfterLast('/'),
                            color = secondaryTextColor(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        AudioAttachmentPreview(path, viewModel)
                        IconButton(onClick = {
                            viewModel.deleteOrphanFile(path)
                            viewModel.updateDraft {
                                it.copy(newAttachmentPath = null, newAttachmentName = null)
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.editor_remove_linked_cd), tint = HighContrastRed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExistingAttachments(
    draft: EditorAttachmentDraft,
    recordingReplaced: Boolean,
    viewModel: NaatViewModel
) {
    listOf(
        Triple(draft.existingAudioType, draft.existingAudioPath, false),
        Triple(draft.existingSecondaryAudioType, draft.existingSecondaryAudioPath, true)
    ).forEach { (type, path, secondary) ->
        val removed = if (secondary) draft.existingSecondaryAudioRemoved else draft.existingAudioRemoved
        val replaced = (type == "recorded" && recordingReplaced) ||
            (type == "local_file" && draft.newAttachmentPath != null)
        if (type != "none" && path != null && !removed && !replaced) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (type == "recorded") Icons.Default.Mic else Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = secondaryTextColor()
                )
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (type == "recorded") stringResource(R.string.editor_current_voice_note) else stringResource(R.string.editor_current_linked_audio),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    // The linked file's original name, so the user can see
                    // WHICH file is attached (recordings need no name).
                    val displayName = if (secondary) draft.existingSecondaryAudioDisplayName
                                      else draft.existingAudioDisplayName
                    if (type == "local_file" && !displayName.isNullOrBlank()) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryTextColor(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                AudioAttachmentPreview(path, viewModel)
                IconButton(onClick = {
                    viewModel.updateDraft {
                        if (secondary) it.copy(existingSecondaryAudioRemoved = true)
                        else it.copy(existingAudioRemoved = true)
                    }
                }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.editor_remove_attachment_cd), tint = HighContrastRed)
                }
            }
        }
    }
}

/** The Browse button shared by the recorder's idle layouts. */
@Composable
private fun BrowseAudioButton(
    onClick: () -> Unit,
    enabled: Boolean,
    isAttaching: Boolean,
    onCancelAttach: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Slim outline style (approved design): the chunky filled pills are
    // gone; short single-line label, hairline border, 44dp tall.
    OutlinedButton(
        // Audit #18: while attaching, the button stays live and acts as
        // the cancel control — a huge copy no longer traps the user.
        onClick = { if (isAttaching) onCancelAttach() else onClick() },
        enabled = enabled || isAttaching,
        modifier = modifier.height(48.dp).testTag("link_external_file_btn"),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onBackground
        )
    ) {
        if (isAttaching) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.editor_attaching))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.common_cancel),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("editor_attach_cancel")
            )
        } else {
            Icon(Icons.Default.MusicNote, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.editor_browse_short),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RecorderControls(
    viewModel: NaatViewModel,
    onBrowseClick: () -> Unit,
    browseEnabled: Boolean,
    isAttaching: Boolean
) {
    val context = LocalContext.current
    val state by viewModel.recordingState.collectAsStateWithLifecycle()
    val activeFile by viewModel.activeRecordingFile.collectAsStateWithLifecycle()
    val attachments by viewModel.editorAttachments.collectAsStateWithLifecycle()
    // A new recording replaces an already-attached recorded audio on save —
    // ask before starting when one exists (unless it was already removed).
    val hasExistingRecorded = (attachments.existingAudioType == "recorded" && !attachments.existingAudioRemoved) ||
        (attachments.existingSecondaryAudioType == "recorded" && !attachments.existingSecondaryAudioRemoved)
    var confirmRecord by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startRecording()
        else Toast.makeText(context, context.getString(R.string.editor_mic_permission), Toast.LENGTH_LONG).show()
    }

    if (confirmRecord) {
        ReplaceAudioConfirmDialog(
            onConfirm = {
                confirmRecord = false
                permission.launch(android.Manifest.permission.RECORD_AUDIO)
            },
            onDismiss = { confirmRecord = false }
        )
    }

    when (state) {
        RecordingState.RECORDING, RecordingState.PAUSED -> {
            val elapsed by viewModel.recordingElapsedMs.collectAsStateWithLifecycle()
            val amplitude by viewModel.recordingAmplitude.collectAsStateWithLifecycle()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (state == RecordingState.RECORDING) viewModel.pauseRecording()
                        else viewModel.resumeRecording()
                    },
                    modifier = Modifier.testTag(
                        if (state == RecordingState.RECORDING) "pause_recording_btn" else "resume_recording_btn"
                    )
                ) { Text(if (state == RecordingState.RECORDING) stringResource(R.string.common_pause) else stringResource(R.string.editor_resume)) }
                Button(
                    onClick = viewModel::stopRecording,
                    colors = ButtonDefaults.buttonColors(containerColor = HighContrastRed),
                    modifier = Modifier.testTag("stop_recording_btn")
                ) { Text(stringResource(R.string.editor_finish)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (state == RecordingState.RECORDING) Icons.Default.FiberManualRecord else Icons.Default.Pause,
                    contentDescription = null,
                    tint = if (state == RecordingState.RECORDING) HighContrastRed else secondaryTextColor(),
                    modifier = Modifier.size(14.dp)
                )
                Text(formatTime(elapsed.toInt()), fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("recording_timer"))
                RecordingVuMeter(amplitude)
            }
        }
        RecordingState.IDLE -> {
            val finishedFile = activeFile
            if (finishedFile == null) {
                // Record and Browse share one equal row (approved design),
                // both slim outline buttons with single-line labels.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (hasExistingRecorded) confirmRecord = true
                            else permission.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                        modifier = Modifier.weight(1f).height(48.dp).testTag("start_recording_btn"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onBackground
                        )
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.editor_record_short), maxLines = 1)
                    }
                    BrowseAudioButton(
                        onClick = onBrowseClick,
                        enabled = browseEnabled,
                        isAttaching = isAttaching,
                        onCancelAttach = viewModel::cancelAttach,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        context.getString(R.string.editor_recording_ready, finishedFile.name),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AudioAttachmentPreview(finishedFile.absolutePath, viewModel)
                    TextButton(
                        onClick = viewModel::discardRecording,
                        modifier = Modifier.testTag("discard_recording_btn")
                    ) { Text(stringResource(R.string.common_discard), color = HighContrastRed) }
                    IconButton(
                        // Audit fix: re-record used to call startRecording
                        // directly, silently deleting the finished take. It now
                        // goes through the same replace confirmation as the
                        // main Record button.
                        onClick = { confirmRecord = true },
                        modifier = Modifier.testTag("rerecord_btn")
                    ) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.editor_rerecord_cd)) }
                }
                Spacer(Modifier.height(8.dp))
                BrowseAudioButton(
                    onClick = onBrowseClick,
                    enabled = browseEnabled,
                    isAttaching = isAttaching,
                    onCancelAttach = viewModel::cancelAttach,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun EditorSaveSection(viewModel: NaatViewModel) {
    val metadata by viewModel.editorMetadata.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val isAttaching by viewModel.isAttachingFile.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Button(
        onClick = {
            if (metadata.title.isBlank()) {
                Toast.makeText(context, context.getString(R.string.editor_title_required), Toast.LENGTH_SHORT).show()
            } else viewModel.saveDraft()
        },
        enabled = !isSaving && !isAttaching,
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("save_notebook_btn")
    ) {
        if (isSaving) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.editor_saving), fontWeight = FontWeight.Bold)
        } else {
            Text(
                if (metadata.editingId != null) stringResource(R.string.editor_save_changes) else stringResource(R.string.editor_save_entry),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}
