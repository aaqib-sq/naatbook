package com.aistudio.mynaatnotebook.ui.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import com.aistudio.mynaatnotebook.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.mynaatnotebook.audio.RecordingState
import com.aistudio.mynaatnotebook.data.NaatCategories
import com.aistudio.mynaatnotebook.ui.components.CategoryDisplay
import com.aistudio.mynaatnotebook.ui.components.usesArabicScript
import com.aistudio.mynaatnotebook.ui.theme.FontChoices
import com.aistudio.mynaatnotebook.data.NaatSummary
import com.aistudio.mynaatnotebook.ui.theme.MyApplicationTheme
import com.aistudio.mynaatnotebook.ui.theme.NastaliqFamily
import com.aistudio.mynaatnotebook.ui.theme.HighContrastRed
import com.aistudio.mynaatnotebook.ui.theme.secondaryTextColor
import com.aistudio.mynaatnotebook.viewmodel.NaatViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun LibraryScreen(
    viewModel: NaatViewModel,
    onOpenReader: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onAddEntry: () -> Unit,
    foldersWithEntries: Set<String>,
    onFoldersSeen: (Set<String>) -> Unit,
    titleFontFamily: FontFamily,
    // Raw title choice + custom families: only the recents strip resolves
    // titles per-entry; list rows keep the global titleFontFamily above.
    titleFontChoice: String,
    customFamilies: Map<String, FontFamily>
) {
    val context = LocalContext.current
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedFolder by viewModel.selectedFolder.collectAsStateWithLifecycle()
    val filteredNaats by viewModel.filteredSummaries.collectAsStateWithLifecycle()
    val recentNaats by viewModel.recentSummaries.collectAsStateWithLifecycle()
    val categoryCounts by viewModel.categoryCounts.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.showFavoritesOnly.collectAsStateWithLifecycle()
    val isDeleting by viewModel.isDeleting.collectAsStateWithLifecycle()
    // Arena E8: a deliberately parked draft waits here as a banner instead
    // of reopening the editor on every cold start.
    val keptDraftInfo by viewModel.keptDraftInfo.collectAsStateWithLifecycle()
    // Now-playing highlight: the entry whose audio is currently loaded.
    val nowPlaying by viewModel.playbackController.nowPlaying.collectAsStateWithLifecycle()
    val hasPlaybackSession by viewModel.playbackController.hasActiveSession.collectAsStateWithLifecycle()

    // Remember every folder that currently holds entries, so an emptied
    // folder can later show "deleted" copy instead of "first entry" copy.
    LaunchedEffect(categoryCounts) {
        val nonEmpty = categoryCounts.filterValues { it > 0 }.keys
        if (nonEmpty.isNotEmpty()) onFoldersSeen(nonEmpty)
    }

    // Entry pending deletion - deletions also remove attached audio, so confirm first
    var deleteCandidate by remember { mutableStateOf<NaatSummary?>(null) }

    // Speech-to-Text Voice input launcher
    val speechRecognizerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val spokenText = results?.firstOrNull() ?: ""
                viewModel.setSearchQuery(spokenText)
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        val searchBg = MaterialTheme.colorScheme.surfaceVariant
        val searchBorder = MaterialTheme.colorScheme.outline

        // Core Top Search Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, searchBorder, RoundedCornerShape(24.dp))
                .background(searchBg)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(R.string.library_search_cd),
                tint = secondaryTextColor(),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            // BasicTextField is used instead of OutlinedTextField: the M3 text field
            // enforces a 56.dp minimum height, which clipped the text in this 48.dp
            // pill. Here we control padding, line height and vertical centering
            // ourselves so the placeholder/input can never be cut off.
            BasicTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                    lineHeight = 20.sp,
                    // The field's own font: Arabic-script input resolves to
                    // the app's Nastaliq family like everywhere else; the
                    // shared body style's tracking would break its joining.
                    fontFamily = if (usesArabicScript(searchQuery)) NastaliqFamily else FontFamily.Default,
                    letterSpacing = 0.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .wrapContentHeight(Alignment.CenterVertically)
                    .testTag("global_search_input"),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = stringResource(R.string.library_search_hint),
                                color = secondaryTextColor(),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    lineHeight = 20.sp,
                                    // The hint is always Urdu — always
                                    // Nastaliq, and never letter-tracked.
                                    fontFamily = NastaliqFamily,
                                    letterSpacing = 0.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (searchQuery.isNotEmpty()) {
                IconButton(
                    onClick = { viewModel.setSearchQuery("") },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("search_clear_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.library_clear_search),
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(
                onClick = {
                    try {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.library_voice_prompt))
                        }
                        speechRecognizerLauncher.launch(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.library_stt_unsupported), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("speech_to_text_mic")
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = stringResource(R.string.library_voice_search_cd),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (selectedFolder == null) {
            if (searchQuery.isBlank() && !favoritesOnly) {
            // Main Folder System Grid View with Recent entries
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(viewModel.libraryHomeScrollState)
            ) {
                // Parked-draft banner: the draft the user deliberately saved
                // for later stays reachable (and discardable) from Home.
                keptDraftInfo?.let { info ->
                    val draftLabel = info.label
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp, bottom = 16.dp)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(12.dp)
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = if (draftLabel != null) {
                                    stringResource(R.string.library_draft_banner, draftLabel)
                                } else {
                                    stringResource(R.string.library_draft_banner_untitled)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row {
                                TextButton(onClick = { viewModel.resumeKeptDraft() }) {
                                    Text(stringResource(R.string.editor_kept_draft_continue))
                                }
                                TextButton(onClick = { viewModel.discardKeptDraft() }) {
                                    Text(
                                        stringResource(R.string.common_discard),
                                        color = HighContrastRed
                                    )
                                }
                            }
                        }
                    }
                }

                // Recents first (approved design): the entries a user returns
                // to most sit at the top as swipeable cards; the full lists
                // stay inside the folder cards below.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp, start = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.library_recent_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = secondaryTextColor()
                    )
                }

                if (recentNaats.isEmpty()) {
                    // Audit 03 L4: a fresh install's home used to be a
                    // wall of zero-count folder cards under a bare "no
                    // entries" line, with no next action. The guided
                    // empty state is the root's call to action.
                    EmptyStateGuide(
                        titleText = stringResource(R.string.empty_guide_title),
                        bodyText = stringResource(R.string.library_empty_folder),
                        buttonText = stringResource(R.string.empty_guide_add),
                        onAddEntry = onAddEntry,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    // Room already applies updatedAt DESC and strict LIMIT 10,
                    // so this strip stays bounded however large the library gets.
                    LazyRow(
                        state = viewModel.libraryRecentsListState,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        items(recentNaats, key = { it.id }) { naat ->
                            RecentNaatCard(
                                naat = naat,
                                isPlaying = hasPlaybackSession && nowPlaying?.naatId == naat.id,
                                onClick = { onOpenReader(naat.id) },
                                // Per-entry title: the entry's own override wins;
                                // AUTO is script-aware on this title's script.
                                titleFontFamily = FontChoices.resolveTitle(
                                    FontChoices.effectiveTitleChoice(
                                        naat.titleFontOverride, titleFontChoice
                                    ),
                                    usesArabicScript(naat.title),
                                    customFamilies
                                )
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp, bottom = 12.dp, start = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.library_folders_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = secondaryTextColor()
                    )
                    // Quick filter: show only starred/favorite entries (across all folders)
                    FilterChip(
                        selected = favoritesOnly,
                        onClick = { viewModel.toggleFavoritesOnly() },
                        label = { Text(stringResource(R.string.library_favorites_label), fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (favoritesOnly) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        modifier = Modifier.testTag("favorites_filter_chip")
                    )
                }

                // Taxonomy is defined once in NaatCategories: content types only,
                // never media formats (the old mixed list had an "Audio Only"
                // folder; audio attachments now live inside any category).
                // Folder glyphs: six hand-crafted vector drawables carrying the
                // correct symbolism (Kaaba/Gumbad/masjid-skyline/Zulfiqar/ode
                // scroll/voice waveform); My Kalam & Others keep their approved
                // stock icons. All painters are tinted onBackground by the card.
                val kaabaPainter = painterResource(R.drawable.ic_cat_kaaba)
                val domePainter = painterResource(R.drawable.ic_cat_dome)
                val skylinePainter = painterResource(R.drawable.ic_cat_skyline)
                val zulfiqarPainter = painterResource(R.drawable.ic_cat_zulfiqar)
                val scrollPainter = painterResource(R.drawable.ic_cat_scroll)
                val wavesPainter = painterResource(R.drawable.ic_cat_waves)
                val editPainter = rememberVectorPainter(Icons.Default.Edit)
                val folderStockPainter = rememberVectorPainter(Icons.Default.Folder)
                // Audit 05 L3: the icon map is stable — don't rebuild
                // it on every recomposition of the home list.
                val folderIcons: Map<String, Painter> = remember {
                    mapOf(
                    NaatCategories.NAAT to domePainter,
                    NaatCategories.HAMD to kaabaPainter,
                    NaatCategories.MANQABAT to zulfiqarPainter,
                    NaatCategories.SALAM to skylinePainter,
                    NaatCategories.QASIDA to scrollPainter,
                    NaatCategories.NASHEED to wavesPainter,
                    NaatCategories.MY_KALAM to editPainter,
                    NaatCategories.OTHERS to folderStockPainter
                    )
                }

                // Grid layout (using nested Rows to avoid nested scrolling parent errors)
                val folderRows = NaatCategories.ALL.chunked(2)
                folderRows.forEachIndexed { rowIndex, rowFolders ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        rowFolders.forEachIndexed { index, folder ->
                            FolderSleekCard(
                                folder = folder,
                                iconPainter = folderIcons[folder] ?: folderStockPainter,
                                count = categoryCounts[folder] ?: 0,
                                onClick = { viewModel.selectFolder(folder) },
                                modifier = Modifier.weight(1f)
                            )
                            if (index < rowFolders.lastIndex) {
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                        }
                        // Keep the grid rectangular if the taxonomy ever gains an odd count
                        if (rowFolders.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    if (rowIndex < folderRows.lastIndex) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // Content spacing buffer
                Spacer(modifier = Modifier.height(80.dp))
            }
            } else {
                // Results list: global search across folders, and/or the favorites filter
                Text(
                    text = when {
                        // The query is user-typed text and can be Urdu — it is
                        // shown as typed, never uppercased or letter-tracked.
                        favoritesOnly && searchQuery.isNotBlank() -> stringResource(R.string.library_favorites_query_title, searchQuery.trim())
                        favoritesOnly -> stringResource(R.string.library_favorites_title)
                        else -> stringResource(R.string.library_results_title, searchQuery.trim())
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = secondaryTextColor(),
                    modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
                )
                // Audit 03 M1: at the root, the favorites filter hid its
                // own off-switch (the chip lives in the home branch that
                // this view replaces). Same removable indicator the
                // folder view already had.
                if (favoritesOnly) {
                    Row(
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { viewModel.setFavoritesOnly(false) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("root_favorites_chip"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = HighContrastRed,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.library_favorites_title),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.library_favorites_filter_off_cd),
                            tint = secondaryTextColor(),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                if (filteredNaats.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyStateGuide(
                            titleText = stringResource(R.string.empty_guide_title),
                            bodyText = if (favoritesOnly && searchQuery.isBlank()) {
                                stringResource(R.string.library_empty_favorites)
                            } else {
                                stringResource(R.string.library_empty_search)
                            },
                            // Audit #7: an empty result caused by a search
                            // query used to be a dead end — offer clearing it.
                            buttonText = if (searchQuery.isNotBlank()) {
                                stringResource(R.string.library_clear_search)
                            } else null,
                            onAddEntry = onAddEntry,
                            onButtonClick = if (searchQuery.isNotBlank()) {
                                { viewModel.setSearchQuery("") }
                            } else null
                        )
                    }
                } else {
                    LazyColumn(
                        state = viewModel.librarySearchListState,
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredNaats, key = { it.id }) { naat ->
                        NaatRowItem(
                            naat = naat,
                            isPlaying = hasPlaybackSession && nowPlaying?.naatId == naat.id,
                            onItemClick = { onOpenReader(naat.id) },
                            onDeleteClick = { deleteCandidate = naat },
                            onFavoriteClick = { viewModel.toggleFavorite(naat.id) },
                            onEditClick = { onEdit(naat.id) },
                            titleFontFamily = titleFontFamily
                        )
                        }
                    }
                }
            }
        } else {
            // Inside Folder List-View
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { viewModel.selectFolder(null) },
                    modifier = Modifier.testTag("back_to_folders")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.library_back_cd),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    // Audit T1: the folder heading follows the app language,
                    // like the folder cards on Home already do.
                    text = stringResource(CategoryDisplay.displayNameRes(selectedFolder ?: "")),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
            // Show when search is active inside a folder, so the user knows
            // the results are scoped and not global.
            if (searchQuery.isNotBlank()) {
                Text(
                    text = stringResource(
                        R.string.library_search_in_folder,
                        stringResource(CategoryDisplay.displayNameRes(selectedFolder ?: ""))
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryTextColor(),
                    modifier = Modifier.padding(start = 48.dp, bottom = 8.dp)
                )
            }

            // Audit #8: the favorites filter used to stay silently active
            // inside folders (its chip only exists on Home). Show a removable
            // indicator here so a partial list is never a mystery.
            if (favoritesOnly) {
                Row(
                    modifier = Modifier
                        .padding(start = 48.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { viewModel.setFavoritesOnly(false) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag("folder_favorites_chip"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = HighContrastRed,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = stringResource(R.string.library_favorites_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.library_favorites_filter_off_cd),
                        tint = secondaryTextColor(),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            if (filteredNaats.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    // Copy depends on the folder's history: a folder that once
                    // held entries gets "deleted" copy; a brand-new folder
                    // gets the first-entry copy.
                    val folderHadEntries = selectedFolder != null && selectedFolder in foldersWithEntries &&
                        searchQuery.isBlank()
                    if (searchQuery.isNotBlank()) {
                        // Audit #7: the emptiness is caused by the search
                        // filter, not by an empty folder — the honest action
                        // is clearing the search (an entry added here would
                        // stay invisible under the stale query).
                        EmptyStateGuide(
                            titleText = stringResource(R.string.empty_guide_title),
                            bodyText = stringResource(R.string.library_empty_search),
                            buttonText = stringResource(R.string.library_clear_search),
                            onAddEntry = onAddEntry,
                            onButtonClick = { viewModel.setSearchQuery("") }
                        )
                    } else {
                        EmptyStateGuide(
                            titleText = stringResource(
                                if (folderHadEntries) R.string.library_empty_folder_deleted_title
                                else R.string.empty_guide_title
                            ),
                            bodyText = stringResource(
                                if (folderHadEntries) R.string.library_empty_folder_deleted_body
                                else R.string.library_empty_folder
                            ),
                            buttonText = stringResource(
                                if (folderHadEntries) R.string.empty_guide_add_new
                                else R.string.empty_guide_add
                            ),
                            onAddEntry = onAddEntry
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = viewModel.libraryFolderListState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredNaats, key = { it.id }) { naat ->
                        NaatRowItem(
                            naat = naat,
                            isPlaying = hasPlaybackSession && nowPlaying?.naatId == naat.id,
                            onItemClick = { onOpenReader(naat.id) },
                            onDeleteClick = { deleteCandidate = naat },
                            onFavoriteClick = { viewModel.toggleFavorite(naat.id) },
                            onEditClick = { onEdit(naat.id) },
                            titleFontFamily = titleFontFamily
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation — deletions also remove attached audio files permanently.
    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { if (!isDeleting) deleteCandidate = null },
            title = { Text(stringResource(R.string.library_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.library_delete_message,
                        candidate.title,
                        if (candidate.audioType != "none" || candidate.secondaryAudioType != "none")
                            stringResource(R.string.library_delete_with_audio)
                        else "."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteNaat(candidate.id) { deleteCandidate = null }
                    },
                    enabled = !isDeleting,
                    modifier = Modifier.testTag("confirm_delete_btn")
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.library_deleting))
                    } else {
                        Text(stringResource(R.string.common_delete), color = HighContrastRed)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }, enabled = !isDeleting) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

// --- Folder Sleek Card ---
@Composable
fun FolderSleekCard(
    folder: String,
    iconPainter: Painter,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardBg = MaterialTheme.colorScheme.surfaceVariant
    val cardBorder = MaterialTheme.colorScheme.outline
    // Icon chips use the inverse surface so the glyph wells read against the card
    val iconBg = MaterialTheme.colorScheme.background

    Card(
        modifier = modifier
            .height(130.dp)
            .border(1.dp, cardBorder, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .testTag("folder_card_${folder.replace(" ", "_")}"),
        colors = CardDefaults.cardColors(
            containerColor = cardBg
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = iconPainter,
                    contentDescription = stringResource(CategoryDisplay.displayNameRes(folder)),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(26.dp)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(CategoryDisplay.displayNameRes(folder)),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 20.sp,
                        lineHeight = 36.sp
                    ),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.library_entries_count, count),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = secondaryTextColor()
                    ),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

// --- Recent entry card (home strip) ---
/**
 * Compact card for the Recents strip at the top of the home screen. A tap
 * opens the reader; favorite/edit/delete stay in the folder lists, where
 * the full rows live. Deliberately a different material from the matte
 * folder cards (approved design): a glass look — a soft vertical sheen
 * gradient and a brighter hairline border — so the strip reads as its own
 * species at a glance. Playing state keeps the solid white border.
 *
 * The title is the hero (big, bold, centered): the card's job is to let the
 * user spot the naat and tap it. The poet line was removed by user order.
 */
@Composable
fun RecentNaatCard(
    naat: NaatSummary,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    titleFontFamily: FontFamily = FontFamily.SansSerif
) {
    val cardBorder = if (isPlaying) MaterialTheme.colorScheme.onBackground
                     else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
    // Glass sheen: light catching the top of the card, fading downward.
    // onBackground-based alphas keep it monochrome in both themes.
    val glassBrush = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.13f),
            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.03f)
        )
    )
    Card(
        modifier = modifier
            .width(210.dp)
            .height(124.dp)
            .border(if (isPlaying) 1.5.dp else 1.dp, cardBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("recent_card_${naat.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(glassBrush)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val audioTypes = setOf(naat.audioType, naat.secondaryAudioType)
                Icon(
                    imageVector = if ("recorded" in audioTypes) Icons.Default.Mic else Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = secondaryTextColor(),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                val categoryName = stringResource(CategoryDisplay.displayNameRes(naat.category))
                Text(
                    // Category names localize into Urdu — shown as-is, never
                    // uppercased or letter-tracked (both break Arabic joining).
                    text = categoryName,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        // Audit 06 L4: synthetic Bold muddies Arabic-script
                        // joins; the chip is Bold only for Latin names.
                        fontWeight = if (usesArabicScript(categoryName)) {
                            FontWeight.Normal
                        } else {
                            FontWeight.Bold
                        }
                    ),
                    color = secondaryTextColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isPlaying) {
                    Spacer(modifier = Modifier.weight(1f))
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.testTag("now_playing_indicator_${naat.id}")
                    ) {
                        listOf(6.dp, 10.dp, 5.dp).forEach { barHeight ->
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(barHeight)
                                    .background(
                                        MaterialTheme.colorScheme.onBackground,
                                        RoundedCornerShape(1.dp)
                                    )
                            )
                        }
                    }
                }
            }
            // The recognition block: centered in the remaining space so the
            // card stays composed whether the title runs one line or two and
            // whether or not there is a lyrics preview below it.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = naat.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    fontWeight = FontWeight.Bold,
                    fontFamily = titleFontFamily,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    // Never letter-space the title: it breaks Nastaliq joining.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                remember(naat.id, naat.lyrics) { firstLyricsLine(naat.lyrics) }?.let { preview ->
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * First displayable line of the lyrics for the recent-card preview.
 * Returns null when there is nothing worth showing (null, blank, or
 * whitespace-only lyrics). CRLF input is handled by trimming each line.
 */
internal fun firstLyricsLine(lyrics: String?): String? =
    lyrics?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() }

// --- Row Item representation inside a folder list ---
/**
 * Guided empty state (approved preview): circular icon badge, a title, the
 * explanatory body text, and — where adding makes sense — a filled
 * "add first entry" button plus an import hint. Monochrome, matching the
 * app's identity.
 */
@Composable
private fun EmptyStateGuide(
    titleText: String,
    bodyText: String,
    buttonText: String?,
    onAddEntry: () -> Unit,
    modifier: Modifier = Modifier,
    onButtonClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.LibraryMusic,
                contentDescription = null,
                tint = secondaryTextColor(),
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = titleText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = bodyText,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = secondaryTextColor()
        )
        if (buttonText != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onButtonClick ?: onAddEntry,
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background
                ),
                modifier = Modifier.testTag("empty_add_first_btn")
            ) {
                Text(
                    text = if (onButtonClick == null) "+ " + buttonText else buttonText,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.empty_guide_import_hint),
                style = MaterialTheme.typography.bodySmall,
                color = secondaryTextColor()
            )
        }
    }
}

@Composable
fun NaatRowItem(
    naat: NaatSummary,
    isPlaying: Boolean = false,
    onItemClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onEditClick: () -> Unit,
    titleFontFamily: FontFamily = FontFamily.SansSerif
) {
    val cardBg = MaterialTheme.colorScheme.surfaceVariant
    // Playing entry: white (onBackground) border in the app's monochrome
    // identity — no accent colors, per the approved preview.
    val cardBorder = if (isPlaying) MaterialTheme.colorScheme.onBackground
                     else MaterialTheme.colorScheme.outline

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(if (isPlaying) 1.5.dp else 1.dp, cardBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onItemClick)
            .testTag("naat_row_item_${naat.id}"),
        colors = CardDefaults.cardColors(
            containerColor = cardBg
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = naat.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = titleFontFamily,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isPlaying) {
                        Spacer(modifier = Modifier.width(8.dp))
                        // Equalizer bars (static trio) + "Playing" label
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.testTag("now_playing_indicator_${naat.id}")
                        ) {
                            listOf(6.dp, 10.dp, 5.dp).forEach { barHeight ->
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(barHeight)
                                        .background(
                                            MaterialTheme.colorScheme.onBackground,
                                            RoundedCornerShape(1.dp)
                                        )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.library_now_playing),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                if (!naat.poet.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.library_by_poet, naat.poet),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Independent voice-note and linked-file indicators.
                val audioTypes = setOf(naat.audioType, naat.secondaryAudioType)
                if ("recorded" in audioTypes) {
                    Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.library_recorded_cd),
                        tint = secondaryTextColor(), modifier = Modifier.padding(end = 4.dp).size(20.dp))
                }
                if ("local_file" in audioTypes) {
                    Icon(Icons.Default.MusicNote, contentDescription = stringResource(R.string.library_linked_cd),
                        tint = secondaryTextColor(), modifier = Modifier.padding(end = 4.dp).size(20.dp))
                }

                // Edit entry
                IconButton(onClick = onEditClick, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.common_edit_entry),
                        tint = secondaryTextColor(),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Favorite Quick Heart Tag
                IconButton(onClick = onFavoriteClick, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = if (naat.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = stringResource(R.string.library_favorite_toggle_cd),
                        tint = if (naat.isFavorite) HighContrastRed else secondaryTextColor(),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Delete Entry
                IconButton(onClick = onDeleteClick, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.library_delete_cd),
                        tint = secondaryTextColor().copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// --- Tab B: Add New Modal Window (Full Screen Overlay) ---
