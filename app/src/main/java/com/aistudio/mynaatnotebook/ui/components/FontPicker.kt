package com.aistudio.mynaatnotebook.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aistudio.mynaatnotebook.R
import com.aistudio.mynaatnotebook.data.CustomFont
import com.aistudio.mynaatnotebook.ui.theme.FontChoices

/** Sentinel choice key meaning "follow the global Settings font" (per-entry overrides). */
const val FONT_FOLLOW_SETTINGS = "follow_settings"

/**
 * An extra row pinned above the built-in choices in [FontPickerDialog]
 * (e.g. "Follow Settings" for per-entry overrides).
 */
data class FontPickerLeadChoice(
    val choiceKey: String,
    val label: String,
    val sample: String = "",
    val family: FontFamily = FontFamily.Default,
    val testTag: String = "font_picker_row_$choiceKey"
)


/** Display label for a stored font choice (built-in name or the custom font's own name). */
@Composable
fun fontChoiceLabel(choice: String, customFonts: List<CustomFont>): String {
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

/**
 * Font picker — the single home for fonts (approved design): built-in
 * choices first, then the user's imported fonts (each with a trash action),
 * and an import button at the bottom. There is deliberately no separate
 * "My Fonts" card on the main settings screen anymore.
 */
@Composable
fun FontPickerDialog(
    title: String,
    builtinKeys: List<String>,
    currentChoice: String,
    customFonts: List<CustomFont>,
    customFamilies: Map<String, FontFamily>,
    onPick: (String) -> Unit,
    onDeleteRequest: ((CustomFont) -> Unit)? = null,
    onImport: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    leadChoices: List<FontPickerLeadChoice> = emptyList()
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Audit F1: the old body was one eagerly-composed scrolling
            // Column — with up to 100 imported fonts, picking one meant a
            // long scroll-hunt through near-identical samples. The list is
            // lazy now, and 10+ custom fonts get a search field (which
            // targets the imported fonts, so built-ins step aside while
            // a query is typed).
            var fontQuery by remember { mutableStateOf("") }
            val searching = fontQuery.isNotBlank()
            val visibleCustomFonts = if (!searching) {
                customFonts
            } else {
                customFonts.filter { it.name.contains(fontQuery.trim(), ignoreCase = true) }
            }
            val importAction = onImport
            Column {
                if (customFonts.size >= 10) {
                    OutlinedTextField(
                        value = fontQuery,
                        onValueChange = { fontQuery = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_font_search_hint)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                    if (!searching) {
                        items(leadChoices, key = { "lead_${it.choiceKey}" }) { lead ->
                            FontPickerRow(
                                label = lead.label,
                                sample = lead.sample,
                                family = lead.family,
                                selected = currentChoice == lead.choiceKey,
                                testTag = lead.testTag,
                                onClick = { onPick(lead.choiceKey) }
                            )
                        }
                        items(builtinKeys, key = { "builtin_$it" }) { key ->
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
                        if (visibleCustomFonts.isNotEmpty()) {
                            item(key = "imported_header") {
                                Text(
                                    text = stringResource(
                                        R.string.settings_font_imported_count, customFonts.size
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                                )
                            }
                        }
                    }
                    items(visibleCustomFonts, key = { it.id }) { font ->
                        val key = FontChoices.customKey(font.id)
                        FontPickerRow(
                            label = font.name,
                            sample = "مدینہ کا سفر",
                            family = customFamilies[font.id] ?: FontFamily.Default,
                            selected = currentChoice == key,
                            testTag = "font_picker_row_$key",
                            onClick = { onPick(key) },
                            onDelete = onDeleteRequest?.let { req -> { req(font) } },
                            deleteTestTag = "font_picker_delete_${font.id}"
                        )
                    }
                    if (searching && visibleCustomFonts.isEmpty()) {
                        item(key = "search_none") {
                            Text(
                                text = stringResource(R.string.settings_font_search_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                    if (importAction != null) {
                        item(key = "import_button") {
                            OutlinedButton(
                                onClick = importAction,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                                    .height(48.dp)
                                    .testTag("import_font_btn"),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onBackground
                                )
                            ) {
                                Text(
                                    stringResource(R.string.settings_import_font),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
fun FontPickerRow(
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
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
