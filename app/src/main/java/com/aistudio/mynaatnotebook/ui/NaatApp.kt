package com.aistudio.mynaatnotebook.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.aistudio.mynaatnotebook.viewmodel.StatusMessage
import com.aistudio.mynaatnotebook.viewmodel.resolve
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.aistudio.mynaatnotebook.R
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aistudio.mynaatnotebook.data.NaatEntity
import com.aistudio.mynaatnotebook.ui.components.GlobalMiniPlayer
import com.aistudio.mynaatnotebook.ui.components.NaatBottomNavigation
import com.aistudio.mynaatnotebook.ui.editor.NonDismissibleEditorSheet
import com.aistudio.mynaatnotebook.ui.library.LibraryScreen
import com.aistudio.mynaatnotebook.ui.onboarding.OnboardingScreen
import com.aistudio.mynaatnotebook.ui.reader.LyricsReaderScreen
import com.aistudio.mynaatnotebook.ui.settings.ContactUsScreen
import com.aistudio.mynaatnotebook.ui.settings.PrivacyPolicyScreen
import com.aistudio.mynaatnotebook.ui.settings.SettingsScreen
import com.aistudio.mynaatnotebook.ui.theme.FontChoices
import com.aistudio.mynaatnotebook.ui.theme.MyApplicationTheme
import com.aistudio.mynaatnotebook.ui.theme.HighContrastRed
import kotlinx.coroutines.flow.collectLatest
import com.aistudio.mynaatnotebook.viewmodel.BackupViewModel
import com.aistudio.mynaatnotebook.viewmodel.DriveViewModel
import com.aistudio.mynaatnotebook.viewmodel.NaatViewModel
import com.aistudio.mynaatnotebook.viewmodel.SettingsViewModel

/**
 * Audit TOP 3: which editor-open request is waiting on the parked-draft
 * resume question. A transient question, never data.
 */
sealed interface KeptDraftPrompt {
    /** The + button was tapped while a draft was parked for later. */
    data object NewEntry : KeptDraftPrompt

    /** Editing [naat] was requested while a draft for another entry was parked. */
    data class EditEntry(val naat: NaatEntity) : KeptDraftPrompt
}

private object NaatRoutes {
    const val HOME = "home"
    const val READER = "reader"
    const val PRIVACY = "privacy_policy"
    const val CONTACT = "contact_us"
}

@Composable
fun NaatApp(
    viewModel: NaatViewModel,
    settingsViewModel: SettingsViewModel,
    backupViewModel: BackupViewModel,
    driveViewModel: DriveViewModel
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val tabStateHolder = rememberSaveableStateHolder()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val showAddModal by viewModel.showAddModal.collectAsStateWithLifecycle()
    val selectedNaat by viewModel.selectedNaat.collectAsStateWithLifecycle()
    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
    // First-launch intro gate: null = store has not answered yet (splash),
    // false = show the intro, true = straight into the app. introRequested
    // is the Settings "App intro" replay.
    val onboardingSeen by settingsViewModel.onboardingSeen.collectAsStateWithLifecycle()
    var introRequested by rememberSaveable { mutableStateOf(false) }
    val foldersWithEntries by settingsViewModel.foldersWithEntries.collectAsStateWithLifecycle()
    // Title font resolved once here and handed to the surfaces that render
    // entry titles with the GLOBAL font (library rows, mini player).
    // The recents strip and the Reader top bar resolve per-entry from the
    // same choice, so they take the raw choice + custom families as well.
    val titleFontChoice by settingsViewModel.titleFont.collectAsStateWithLifecycle()
    val customFamilies by settingsViewModel.customFamilies.collectAsStateWithLifecycle()
    val titleFontFamily = FontChoices.resolveTitle(titleFontChoice, urduScript = false, customFamilies)
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val isAttaching by viewModel.isAttachingFile.collectAsStateWithLifecycle()
    // Arena E1: the parked-draft prompt names the draft it asks about.
    val keptDraftInfo by viewModel.keptDraftInfo.collectAsStateWithLifecycle()

    var showDiscardConfirmation by rememberSaveable { mutableStateOf(false) }
    // Audit TOP 3: which parked-draft resume prompt is showing, if any. Not
    // saveable on purpose — it is a transient question, never data.
    var keptDraftPrompt by remember { mutableStateOf<KeptDraftPrompt?>(null) }
    // Navigation debounce: time-based, can never get stuck. The old boolean
    // guard (detailNavigationPending) could stick at true and silently kill all
    // taps. A timestamp simply expires — no reset logic to fail.
    var lastNavigationAttempt by remember { mutableStateOf(0L) }
    fun navigationDebouncePassed(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastNavigationAttempt < 500) return false
        lastNavigationAttempt = now
        return true
    }
    LaunchedEffect(currentRoute, showAddModal) {
        if (!showAddModal) showDiscardConfirmation = false
    }

    val darkThemeEnabled = when (themeMode) {
        "white" -> false
        "black" -> true
        else -> isSystemInDarkTheme()
    }

    // Audit 03 M13: status messages arrive on a queue — every receipt is
    // shown, even when two land back to back.
    LaunchedEffect(Unit) {
        viewModel.statusMessages.collect { message ->
            Toast.makeText(context, message.resolve(context), Toast.LENGTH_LONG).show()
        }
    }

    // Reader closure remains state-driven. Editor closure is sheet state-driven, so
    // there is no second NavHost pop authority for add/edit requests.
    LaunchedEffect(currentRoute, selectedNaat) {
        if (currentRoute == NaatRoutes.READER && selectedNaat == null) navController.popBackStack()
    }

    // Audit #21: one-shot resume-point restore after process death.
    LaunchedEffect(Unit) { viewModel.restoreLastPlaybackIfNeeded() }

    AppBackHandler(
        currentRoute,
        currentTab,
        showAddModal,
        // Audit 03 L2: while the intro overlay is up (first run or a
        // Settings replay), Back belongs to the intro — the handler used
        // to reset the library state underneath it.
        overlayVisible = onboardingSeen == false || introRequested,
        viewModel,
        onReaderBack = {
            viewModel.selectNaat(null)
            navController.popBackStack()
        }
    )

    fun openReader(id: Int) {
        if (showAddModal) return
        if (!navigationDebouncePassed()) return
        // Do NOT require currentRoute == HOME. The back-stack pop when leaving
        // the reader can fail silently, leaving currentRoute stuck at READER
        // while the UI shows the list. In that state the old route check
        // silently killed every tap. Navigating with launchSingleTop is safe
        // from any route: it never duplicates the reader, and selectNaat
        // updates the displayed entry.
        viewModel.loadNaat(
            id = id,
            onLoaded = { naat ->
                viewModel.recordEntryOpened(naat.id)
                viewModel.selectNaat(naat)
                navController.navigate(NaatRoutes.READER) { launchSingleTop = true }
            },
            onFailure = { /* debounce expires on its own; nothing to reset */ }
        )
    }

    fun openEditorEntry(naat: NaatEntity) {
        if (showAddModal) return
        if (!navigationDebouncePassed()) return
        // Audit TOP 3: a parked "keep for later" draft for a DIFFERENT entry
        // would be destroyed by startEditNaat — offer the resume instead.
        // (The same entry resumes silently inside startEditNaat already.)
        if (viewModel.keptDraftPending && viewModel.editorDraft.value.editingId != naat.id) {
            keptDraftPrompt = KeptDraftPrompt.EditEntry(naat)
            return
        }
        viewModel.startEditNaat(naat)
    }

    fun openEditorById(id: Int) {
        if (showAddModal || currentRoute != NaatRoutes.HOME) return
        if (!navigationDebouncePassed()) return
        viewModel.loadNaat(
            id = id,
            // Audit fix H2: the same parked-draft guard as openEditorEntry.
            // Without it, tapping Edit on a Library row silently destroyed
            // a parked "Save draft for later" draft and its audio.
            onLoaded = { naat ->
                if (viewModel.keptDraftPending && viewModel.editorDraft.value.editingId != naat.id) {
                    keptDraftPrompt = KeptDraftPrompt.EditEntry(naat)
                } else {
                    viewModel.startEditNaat(naat)
                }
            },
            onFailure = { /* debounce expires on its own; nothing to reset */ }
        )
    }

    fun openAdd() {
        if (showAddModal || currentRoute != NaatRoutes.HOME) return
        if (!navigationDebouncePassed()) return
        // Audit TOP 3: the FAB always meant "deliberately fresh", but a
        // parked "keep for later" draft must not die silently — ask first.
        if (viewModel.keptDraftPending) {
            keptDraftPrompt = KeptDraftPrompt.NewEntry
            return
        }
        // A FAB press always represents a deliberately fresh new entry, never a
        // dormant edit draft. startAddDraft handles recorder/file cleanup off-main.
        // Arena L1: started from inside a folder, the entry is pre-filed there.
        viewModel.startAddDraft(
            forceFresh = true,
            initialCategory = viewModel.selectedFolder.value
        )
    }

    fun requestEditorClose() {
        if (isSaving) {
            // Audit 03 L7: a back press during save used to be swallowed
            // without a trace, reading as a frozen app.
            Toast.makeText(context, R.string.status_busy_wait, Toast.LENGTH_SHORT).show()
            return
        }
        // Audit #18: an in-progress attach no longer traps the editor —
        // closing cancels the attach and proceeds as usual.
        if (isAttaching) viewModel.cancelAttach()
        if (viewModel.hasUnsavedEditorChanges()) {
            showDiscardConfirmation = true
        } else {
            viewModel.setShowAddModal(false)
        }
    }

    MyApplicationTheme(darkTheme = darkThemeEnabled) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                // Audit TOP 1: the undo-delete snackbar host. The toast bus
                // (StatusReporter) cannot carry actions, so delete undo offers
                // travel on their own one-shot flow and are shown here.
                val snackbarHostState = remember { SnackbarHostState() }
                val undoDeletedMessage = stringResource(R.string.status_entry_deleted)
                val undoActionLabel = stringResource(R.string.common_undo)
                // A stale token (window already expired) is a no-op inside
                // the viewmodel, so a late tap can never resurrect a
                // finalized deletion.
                LaunchedEffect(Unit) {
                    // collectLatest: a newer delete replaces the visible
                    // snackbar instead of queueing behind it (only the
                    // newest deletion is undoable anyway — a fresh delete
                    // finalizes the previous one inside the viewmodel).
                    viewModel.undoDeleteOffers.collectLatest { offer ->
                        if (offer == null || !viewModel.isUndoOfferLive(offer.token)) {
                            return@collectLatest
                        }
                        val result = snackbarHostState.showSnackbar(
                            message = undoDeletedMessage,
                            actionLabel = undoActionLabel,
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.undoDelete(offer.token)
                        }
                    }
                }
                // The non-draggable editor sheet owns the full available height below
                // the status bar, while its LazyColumn remains the only inner scroller.
                val atHome = currentRoute == null || currentRoute == NaatRoutes.HOME

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                    bottomBar = {
                        // The mini player shows on Home and on the Privacy /
                        // Contact screens (Arena X5): opening those used to
                        // make the bar vanish while the audio kept playing.
                        // The bottom navigation stays home-only, and the
                        // reader is still excluded — it has its own audio
                        // controls, and the anti-bleed rule stops playback
                        // when switching entries.
                        val showMiniPlayer = atHome ||
                            currentRoute == NaatRoutes.PRIVACY ||
                            currentRoute == NaatRoutes.CONTACT
                        if (showMiniPlayer) {
                            Column {
                                GlobalMiniPlayer(
                                    viewModel = viewModel,
                                    titleFontChoice = titleFontChoice,
                                    customFamilies = customFamilies,
                                    onOpen = {
                                        viewModel.openNowPlayingEntry { found ->
                                            if (found && !showAddModal && navigationDebouncePassed()) {
                                                navController.navigate(NaatRoutes.READER) {
                                                    launchSingleTop = true
                                                }
                                            }
                                        }
                                    }
                                )
                                if (atHome) {
                                    NaatBottomNavigation(
                                        currentTab = currentTab,
                                        onTabSelected = { tab ->
                                            if (tab != currentTab) viewModel.selectTab(tab)
                                        },
                                        onAddRequested = ::openAdd
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = NaatRoutes.HOME,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(MaterialTheme.colorScheme.background),
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None }
                    ) {
                        composable(NaatRoutes.HOME) {
                            // Top-level tabs switch in-place: no back-stack transaction,
                            // fade, or simultaneous full-screen composition.
                            tabStateHolder.SaveableStateProvider(currentTab) {
                                when (currentTab) {
                                    2 -> SettingsScreen(
                                        settingsViewModel,
                                        backupViewModel,
                                        driveViewModel,
                                        onShowIntro = { introRequested = true },
                                        onOpenPrivacyPolicy = {
                                            navController.navigate(NaatRoutes.PRIVACY) {
                                                launchSingleTop = true
                                            }
                                        },
                                        onOpenContactUs = {
                                            navController.navigate(NaatRoutes.CONTACT) {
                                                launchSingleTop = true
                                            }
                                        }
                                    )
                                    else -> LibraryScreen(
                                        viewModel = viewModel,
                                        onOpenReader = ::openReader,
                                        onEdit = ::openEditorById,
                                        onAddEntry = ::openAdd,
                                        foldersWithEntries = foldersWithEntries,
                                        onFoldersSeen = settingsViewModel::markFoldersWithEntries,
                                        titleFontFamily = titleFontFamily,
                                        titleFontChoice = titleFontChoice,
                                        customFamilies = customFamilies
                                    )
                                }
                            }
                        }
                        composable(NaatRoutes.PRIVACY) {
                            PrivacyPolicyScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(NaatRoutes.CONTACT) {
                            ContactUsScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(NaatRoutes.READER) {
                            selectedNaat?.let { naat ->
                                LyricsReaderScreen(
                                    naat = naat,
                                    viewModel = viewModel,
                                    settingsViewModel = settingsViewModel,
                                    onClose = {
                                        viewModel.selectNaat(null)
                                        navController.popBackStack()
                                    },
                                    onEdit = ::openEditorEntry
                                )
                            }
                        }
                    }
                }

                if (showAddModal) {
                    NonDismissibleEditorSheet(
                        viewModel = viewModel,
                        settingsViewModel = settingsViewModel,
                        discardConfirmationVisible = showDiscardConfirmation,
                        onRequestClose = ::requestEditorClose
                    )
                }

                if (showDiscardConfirmation) {
                    AlertDialog(
                        onDismissRequest = { showDiscardConfirmation = false },
                        title = { Text(stringResource(R.string.discard_title)) },
                        // Audit 04 L3: the message slot holds the message
                        // only — "Save draft for later" used to be embedded
                        // in it as a full-width button, breaking the
                        // anatomy every other dialog follows. It is the
                        // dialog's primary (confirm) action now.
                        text = { Text(stringResource(R.string.discard_message)) },
                        confirmButton = {
                            // Audit TOP 3: park the draft instead of
                            // destroying it — text, fonts, recording and
                            // attachment stay on disk for later.
                            TextButton(
                                onClick = {
                                    showDiscardConfirmation = false
                                    viewModel.keepEditorDraftForLater()
                                }
                            ) { Text(stringResource(R.string.editor_keep_draft)) }
                        },
                        dismissButton = {
                            Row {
                                TextButton(
                                    onClick = {
                                        showDiscardConfirmation = false
                                        viewModel.setShowAddModal(false)
                                    }
                                ) {
                                    Text(
                                        stringResource(R.string.common_discard),
                                        color = HighContrastRed
                                    )
                                }
                                TextButton(onClick = { showDiscardConfirmation = false }) {
                                    Text(stringResource(R.string.discard_keep_editing))
                                }
                            }
                        }
                    )
                }

                // Audit TOP 3: a parked "keep for later" draft must not die
                // silently when the user taps + or edits a different entry.
                keptDraftPrompt?.let { prompt ->
                    AlertDialog(
                        onDismissRequest = { keptDraftPrompt = null },
                        title = { Text(stringResource(R.string.editor_kept_draft_title)) },
                        text = {
                            val label = keptDraftInfo?.label
                            Text(
                                if (label != null) {
                                    stringResource(R.string.editor_kept_draft_message, label)
                                } else {
                                    stringResource(R.string.editor_kept_draft_message_unnamed)
                                }
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    keptDraftPrompt = null
                                    viewModel.resumeKeptDraft()
                                }
                            ) { Text(stringResource(R.string.editor_kept_draft_continue)) }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    keptDraftPrompt = null
                                    when (prompt) {
                                        is KeptDraftPrompt.NewEntry ->
                                            viewModel.startAddDraft(
                                                forceFresh = true,
                                                initialCategory = viewModel.selectedFolder.value
                                            )
                                        is KeptDraftPrompt.EditEntry ->
                                            viewModel.startEditNaat(prompt.naat)
                                    }
                                }
                            ) {
                                Text(
                                    stringResource(
                                        if (prompt is KeptDraftPrompt.NewEntry)
                                            R.string.editor_kept_draft_fresh
                                        else
                                            R.string.editor_kept_draft_edit_anyway
                                    )
                                )
                            }
                        }
                    )
                }

                // Audit #19: exact-duplicate warning for a brand-new entry.
                val duplicateSavePrompt by viewModel.duplicateSavePrompt
                    .collectAsStateWithLifecycle()
                duplicateSavePrompt?.let { existingTitle ->
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissDuplicateSavePrompt() },
                        title = { Text(stringResource(R.string.editor_duplicate_title)) },
                        text = {
                            Text(stringResource(R.string.editor_duplicate_message, existingTitle))
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    viewModel.dismissDuplicateSavePrompt()
                                    viewModel.saveDraft(forceSave = true)
                                }
                            ) { Text(stringResource(R.string.editor_duplicate_save_anyway)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.dismissDuplicateSavePrompt() }) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                    )
                }

                // First-launch intro overlay (covers everything until done
                // or skipped). While the store has not answered, a plain
                // splash with the app mark avoids any flash of either UI.
                if (onboardingSeen == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_app_logo),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(84.dp)
                        )
                    }
                } else if (onboardingSeen == false || introRequested) {
                    OnboardingScreen(
                        onDone = {
                            introRequested = false
                            settingsViewModel.setOnboardingSeen()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppBackHandler(
    currentRoute: String?,
    currentTab: Int,
    editorModalVisible: Boolean,
    overlayVisible: Boolean,
    viewModel: NaatViewModel,
    onReaderBack: () -> Unit
) {
    val selectedFolder by viewModel.selectedFolder.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.showFavoritesOnly.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val canHandleAppBack = !editorModalVisible && !overlayVisible && (
        currentRoute == NaatRoutes.READER ||
            (currentRoute == NaatRoutes.HOME && currentTab == 2) ||
            (currentRoute == NaatRoutes.HOME && currentTab == 0 &&
                (selectedFolder != null || favoritesOnly || searchQuery.isNotBlank()))
    )
    BackHandler(enabled = canHandleAppBack) {
        when {
            // Pop explicitly AND clear selection atomically. Relying on the
            // LaunchedEffect to pop after selectNaat(null) was unreliable: the
            // pop sometimes never happened, leaving currentRoute stuck at
            // READER while the list was shown, which silently blocked all
            // subsequent entry taps.
            currentRoute == NaatRoutes.READER -> onReaderBack()
            currentRoute == NaatRoutes.HOME && currentTab == 2 -> {
                viewModel.selectTab(0)
                viewModel.resetLibraryToHome()
            }
            selectedFolder != null -> viewModel.selectFolder(null)
            favoritesOnly -> viewModel.setFavoritesOnly(false)
            else -> viewModel.setSearchQuery("")
        }
    }
}

