package com.aistudio.mynaatnotebook.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.aistudio.mynaatnotebook.viewmodel.NaatViewModel
import com.aistudio.mynaatnotebook.viewmodel.SettingsViewModel

/**
 * A deliberately non-draggable modal sheet.
 *
 * Material3 in this project's pinned version has no sheetGesturesEnabled switch.
 * This activity-hosted modal keeps the bottom-sheet presentation while making the
 * interaction contract explicit: scrim and drag never dismiss it; only the editor
 * X action or a handled system Back request can start the close/discard flow. The
 * surface fills every available pixel below the system status bar, so no Library
 * controls can peek above it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NonDismissibleEditorSheet(
    viewModel: NaatViewModel,
    settingsViewModel: SettingsViewModel,
    discardConfirmationVisible: Boolean,
    onRequestClose: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible

    // This handler intentionally takes priority over the app-level back handler.
    // First Back clears the focused text field and lets Android hide the IME; only
    // a later Back, with no IME visible, can request an explicit discard flow.
    BackHandler(enabled = !discardConfirmationVisible) {
        if (imeVisible) {
            focusManager.clearFocus(force = true)
        } else {
            onRequestClose()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("editor_sheet_overlay")
    ) {
        // This is a sibling beneath the sheet surface, so it consumes outside taps
        // without receiving events intended for editor controls. It never closes.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .clearAndSetSemantics { }
                .testTag("editor_sheet_scrim")
        )

        // Reserve only the real status-bar inset at the top. The sheet otherwise
        // fills the window, so the Library search/header cannot show above it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .testTag("editor_sheet_surface"),
                // Full-height sheet intentionally has a square top edge: it covers
                // every underlying Library control instead of exposing it at corners.
                shape = RectangleShape,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 3.dp,
                shadowElevation = 16.dp
            ) {
                AddNaatModal(
                    viewModel = viewModel,
                    settingsViewModel = settingsViewModel,
                    onClose = onRequestClose
                )
            }
        }
    }
}
