package com.aistudio.mynaatnotebook.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import android.content.Context
import com.aistudio.mynaatnotebook.viewmodel.StatusMessage
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
import com.aistudio.mynaatnotebook.viewmodel.BackupViewModel
import com.aistudio.mynaatnotebook.viewmodel.DriveViewModel
import com.aistudio.mynaatnotebook.viewmodel.NaatViewModel
import com.aistudio.mynaatnotebook.viewmodel.SettingsViewModel

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
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val isAttaching by viewModel.isAttachingFile.collectAsStateWithLifecycle()

    var showDiscardConfirmation by rememberSaveable { mutableStateOf(false) }
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

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            Toast.makeText(context, it.resolve(context), Toast.LENGTH_LONG).show()
            viewModel.clearStatusMessage()
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
        viewModel.startEditNaat(naat)
    }

    fun openEditorById(id: Int) {
        if (showAddModal || currentRoute != NaatRoutes.HOME) return
        if (!navigationDebouncePassed()) return
        viewModel.loadNaat(
            id = id,
            onLoaded = { naat -> viewModel.startEditNaat(naat) },
            onFailure = { /* debounce expires on its own; nothing to reset */ }
        )
    }

    fun openAdd() {
        if (showAddModal || currentRoute != NaatRoutes.HOME) return
        if (!navigationDebouncePassed()) return
        // A FAB press always represents a deliberately fresh new entry, never a
        // dormant edit draft. startAddDraft handles recorder/file cleanup off-main.
        viewModel.startAddDraft(forceFresh = true)
    }

    fun requestEditorClose() {
        if (isSaving) return
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
                // The non-draggable editor sheet owns the full available height below
                // the status bar, while its LazyColumn remains the only inner scroller.
                val atHome = currentRoute == null || currentRoute == NaatRoutes.HOME

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    bottomBar = {
                        // Mini-player is home-only. The reader has its own audio controls,
                        // and the anti-bleed rule stops playback when switching entries,
                        // so a global mini-player on the reader is unnecessary.
                        if (atHome) {
                            Column {
                                GlobalMiniPlayer(
                                    viewModel = viewModel,
                                    titleFontFamily = titleFontFamily,
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
                        text = {
                            Text(
                                stringResource(R.string.discard_message)
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showDiscardConfirmation = false
                                    viewModel.setShowAddModal(false)
                                }
                            ) { Text(stringResource(R.string.common_discard)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDiscardConfirmation = false }) {
                                Text(stringResource(R.string.discard_keep_editing))
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
    viewModel: NaatViewModel,
    onReaderBack: () -> Unit
) {
    val selectedFolder by viewModel.selectedFolder.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.showFavoritesOnly.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val canHandleAppBack = !editorModalVisible && (
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

/**
 * Resolves a [StatusMessage] to a display string using [Context.getString].
 * Nested [StatusMessage] arguments (e.g. error fallbacks) are resolved recursively.
 */
private fun StatusMessage.resolve(context: Context): String = when (this) {
    is StatusMessage.Text -> text
    is StatusMessage.Res -> context.getString(
        resId,
        *args.map { if (it is StatusMessage) it.resolve(context) else it }.toTypedArray()
    )
}
