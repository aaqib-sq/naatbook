package com.aistudio.mynaatnotebook.ui.reader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import com.aistudio.mynaatnotebook.viewmodel.clampedRestoreOffset
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.aistudio.mynaatnotebook.R
import com.aistudio.mynaatnotebook.data.NaatEntity
import com.aistudio.mynaatnotebook.ui.components.formatTime
import com.aistudio.mynaatnotebook.ui.components.usesArabicScript
import com.aistudio.mynaatnotebook.ui.theme.secondaryTextColor
import com.aistudio.mynaatnotebook.ui.theme.HighContrastRed
import com.aistudio.mynaatnotebook.ui.theme.FontChoices
import com.aistudio.mynaatnotebook.viewmodel.NaatViewModel
import com.aistudio.mynaatnotebook.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

private val ReaderBottomBreathingRoom = 24.dp

@Composable
fun LyricsReaderScreen(
    naat: NaatEntity,
    viewModel: NaatViewModel,
    settingsViewModel: SettingsViewModel,
    onClose: () -> Unit,
    onEdit: (NaatEntity) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scrollState = rememberScrollState()

    // Audit TOP 2: reader scroll memory — reopen where the user stopped.
    // Saved when the reader closes (one write, not per-pixel); restored after
    // the first layout pass, clamped so a shortened entry never overshoots.
    DisposableEffect(naat.id) {
        onDispose {
            settingsViewModel.saveReaderScrollOffset(naat.id, scrollState.value)
        }
    }
    // Restore must ALWAYS settle at this entry's own offset (0 = top):
    // skipping the scroll when nothing was saved used to leave an in-place
    // entry switch (same reader instance, new naat) sitting at the previous
    // entry's leftover position, mid-text (reader audit H1).
    var restoreSettled by remember(naat.id) { mutableStateOf(false) }
    LaunchedEffect(naat.id) {
        val saved = settingsViewModel.getReaderScrollOffset(naat.id)
        withFrameNanos { }
        val target = clampedRestoreOffset(saved, scrollState.maxValue)
        if (scrollState.value != target) scrollState.scrollTo(target)
        restoreSettled = true
    }
    // The dispose-save above loses the position when the process dies
    // mid-reading (reader audit M1): also save while reading — collectLatest
    // restarts the delay on every change, so a write lands about a second
    // after scrolling pauses, never per pixel. The restore gate keeps a
    // stale pre-restore position from being written under this entry's id.
    LaunchedEffect(naat.id) {
        snapshotFlow { scrollState.value }.collectLatest { offset ->
            delay(1_000)
            if (restoreSettled) {
                settingsViewModel.saveReaderScrollOffset(naat.id, offset)
            }
        }
    }

    // Keep the screen on while reciting — the reader must never dim mid-performance.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // The full-screen state is intentionally local to this reader entry. It survives
    // a rotation but never leaks into a different entry.
    var immersive by rememberSaveable(naat.id) { mutableStateOf(false) }
    var immersiveChromeVisible by rememberSaveable(naat.id) { mutableStateOf(false) }
    var showImmersiveHint by rememberSaveable(naat.id) { mutableStateOf(false) }
    var topChromeHeightPx by remember { mutableIntStateOf(0) }
    var hasMeasuredTopChrome by remember { mutableStateOf(false) }
    var bottomChromeHeightPx by remember { mutableIntStateOf(0) }

    // Auto-scroll engine configuration. Keyed per entry like the immersive
    // state above: an unkeyed rememberSaveable leaked "auto-scroll ON at
    // A's speed" into the next entry on an in-place switch (reader audit H2).
    var autoScrollActive by rememberSaveable(naat.id) { mutableStateOf(false) }
    var scrollSpeed by rememberSaveable(naat.id) { mutableFloatStateOf(1f) }

    // Sizing controls.
    val defaultFontSize by settingsViewModel.globalFontSize.collectAsStateWithLifecycle()
    var localFontSize by remember { mutableStateOf(defaultFontSize) }
    LaunchedEffect(defaultFontSize) { localFontSize = defaultFontSize }

    // Font choices (Settings → Typography): reader lyrics + header title.
    val readerFontChoice by settingsViewModel.readerFont.collectAsStateWithLifecycle()
    /** Per-entry override wins; null follows the global Settings reader font. */
    val effectiveReaderChoice = FontChoices.effectiveReaderChoice(naat.readerFontOverride, readerFontChoice)
    val titleFontChoice by settingsViewModel.titleFont.collectAsStateWithLifecycle()
    val customFamilies by settingsViewModel.customFamilies.collectAsStateWithLifecycle()
    // Reader top-bar title: the entry's own font override wins, else the
    // global title font; AUTO is script-aware on this title's script.
    val perEntryTitleFamily = FontChoices.resolveTitle(
        FontChoices.effectiveTitleChoice(naat.titleFontOverride, titleFontChoice),
        usesArabicScript(naat.title),
        customFamilies
    )

    val readerChromeVisible = !immersive || immersiveChromeVisible

    /*
     * The lyric viewport always fills the entire reader window; chrome is an
     * overlay and only the scroll-content padding changes. Padding changes
     * are animated (see the padding block below) and a follower shifts
     * ScrollState by the same per-frame delta, so the line being read stays
     * glued to its screen position. The old instant compensation also aimed
     * at 0 instead of the real hidden-state padding, overshooting by the
     * whole safe-top gap on every flip — the visible jerk on toggles.
     */

    fun enterImmersive() {
        if (immersive) return
        immersive = true
        immersiveChromeVisible = false
        showImmersiveHint = true
    }

    fun exitImmersive() {
        if (!immersive) return
        immersive = false
        immersiveChromeVisible = false
        showImmersiveHint = false
    }

    // Double-tap on the lyrics toggles the whole immersive mode, so the
    // reader never has to reach for the top-bar button (owner request
    // 2026-10-17). Single taps keep their existing meaning.
    fun toggleImmersiveMode() {
        if (immersive) exitImmersive() else enterImmersive()
    }

    fun toggleImmersiveChrome() {
        if (!immersive) return
        // Inside immersive mode the content padding never changes (the
        // chrome floats over a constant gap), so this flip moves no text.
        immersiveChromeVisible = !immersiveChromeVisible
        showImmersiveHint = !immersiveChromeVisible
    }

    fun updateTopChromeHeight(heightPx: Int) {
        if (heightPx == topChromeHeightPx) return
        topChromeHeightPx = heightPx
        // Padding follows this measurement through the animated target
        // below; the anchor follower keeps the read line steady meanwhile.
        hasMeasuredTopChrome = true
    }

    // A full-screen back press returns to normal Reader first; it never closes
    // the entry or stops the service-owned audio session by accident.
    BackHandler(enabled = immersive) { exitImmersive() }
    ApplyImmersiveSystemBars(enabled = immersive)

    // Auto-scroll loop. It continues across chrome visibility changes because the
    // same ScrollState instance owns the lyric viewport throughout.
    LaunchedEffect(autoScrollActive, scrollSpeed) {
        if (autoScrollActive) {
            while (isActive) {
                val stepPx = with(density) { (1.5f * scrollSpeed).dp.toPx() }
                scrollState.scrollBy(stepPx)
                delay(30)
                if (scrollState.value >= scrollState.maxValue) autoScrollActive = false
            }
        }
    }

    LaunchedEffect(showImmersiveHint) {
        if (showImmersiveHint) {
            delay(2_200)
            showImmersiveHint = false
        }
    }

    // In full screen the chrome is hidden, so lyrics would start at the very
    // top edge — under the camera notch/punch-hole. Start them below the
    // cutout (or status bar, whichever reaches further) plus a small margin.
    val safeTopPx = maxOf(
        WindowInsets.displayCutout.getTop(density),
        WindowInsets.statusBars.getTop(density)
    ) + with(density) { 12.dp.toPx() }.toInt()
    // Full screen keeps ONE constant top gap whether the chrome is summoned
    // or not — the chrome floats over it, so showing/hiding controls inside
    // immersive mode never moves the text at all. The gap itself starts the
    // lyrics a little lower than before (owner request 2026-10-18: +24dp
    // under the safe top). Only entering/leaving immersive changes the
    // target, and that change is animated; the follower below shifts the
    // scroll by the same per-frame delta so the read line glides instead
    // of teleporting.
    val immersiveTopPx = safeTopPx + with(density) { 24.dp.toPx() }.toInt()
    val targetTopPaddingPx = if (immersive) immersiveTopPx else topChromeHeightPx
    val targetBottomPaddingPx = if (immersive) 0 else bottomChromeHeightPx
    val animatedTopPaddingPx by animateIntAsState(
        targetValue = targetTopPaddingPx,
        animationSpec = tween(durationMillis = 300),
        label = "readerTopPadding"
    )
    val animatedBottomPaddingPx by animateIntAsState(
        targetValue = targetBottomPaddingPx,
        animationSpec = tween(durationMillis = 300),
        label = "readerBottomPadding"
    )
    var lastAnimatedTopPx by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { animatedTopPaddingPx }.collect { current ->
            val last = lastAnimatedTopPx
            lastAnimatedTopPx = current
            if (last != null && restoreSettled) {
                scrollState.dispatchRawDelta(
                    ReaderImmersivePolicy.scrollCompensationPx(last, current)
                )
            }
        }
    }
    // Until the top chrome has been measured once, snap to the target so
    // the first composition never glides in from a zero padding.
    val topContentPaddingPx = if (hasMeasuredTopChrome) animatedTopPaddingPx else targetTopPaddingPx
    val bottomContentPaddingPx = animatedBottomPaddingPx

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("reader_root")
    ) {
        ReaderLyricsViewport(
            naat = naat,
            scrollState = scrollState,
            fontSize = localFontSize,
            topChromePaddingPx = topContentPaddingPx,
            bottomChromePaddingPx = bottomContentPaddingPx,
            immersive = immersive,
            onIntentionalTap = ::toggleImmersiveChrome,
            onDoubleTap = ::toggleImmersiveMode,
            readerFontChoice = effectiveReaderChoice,
            customFamilies = customFamilies
        )

        if (readerChromeVisible) {
            // The top chrome is the bar alone now — the permanent font
            // strip was removed; its controls live in the bar's "Aa" panel.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .onSizeChanged { updateTopChromeHeight(it.height) }
                    .testTag("reader_chrome_top")
            ) {
                ReaderTopBar(
                    naat = naat,
                    immersive = immersive,
                    onClose = onClose,
                    onToggleFavorite = { viewModel.toggleFavorite(naat) },
                    onEdit = { onEdit(naat) },
                    onEnterImmersive = ::enterImmersive,
                    onExitImmersive = ::exitImmersive,
                    titleFontFamily = perEntryTitleFamily,
                    fontSize = localFontSize,
                    onFontSizeChange = { localFontSize = it },
                    autoScrollActive = autoScrollActive,
                    onAutoScrollToggle = { autoScrollActive = !autoScrollActive },
                    scrollSpeed = scrollSpeed,
                    onScrollSpeedChange = { scrollSpeed = it }
                )
            }

            ReaderAudioControls(
                naat = naat,
                viewModel = viewModel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { bottomChromeHeightPx = it.height }
                    .testTag("reader_chrome_bottom")
            )
        }

        AnimatedVisibility(
            visible = immersive && !immersiveChromeVisible && showImmersiveHint,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = MaterialTheme.shapes.large,
                shadowElevation = 4.dp,
                modifier = Modifier.testTag("immersive_reader_hint")
            ) {
                Text(
                    text = stringResource(R.string.reader_tap_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    naat: NaatEntity,
    immersive: Boolean,
    onClose: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: () -> Unit,
    onEnterImmersive: () -> Unit,
    onExitImmersive: () -> Unit,
    titleFontFamily: FontFamily,
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    autoScrollActive: Boolean,
    onAutoScrollToggle: () -> Unit,
    scrollSpeed: Float,
    onScrollSpeedChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose, modifier = Modifier.testTag("close_reader")) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.reader_close_cd),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Arabic-script titles get the same care as the lyrics surface:
            // Nastaliq has no Bold cut (synthetic bold muddies the joins),
            // its tall glyphs need a roomier line height than the Latin
            // default, and a long title must ellipsize — the old default
            // Clip cut it mid-glyph inside a joined word (reader audit).
            val titleIsArabic = usesArabicScript(naat.title)
            Text(
                text = naat.title,
                style =
                    if (titleIsArabic) {
                        MaterialTheme.typography.titleMedium.copy(lineHeight = 30.sp)
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                fontWeight = if (titleIsArabic) FontWeight.Normal else FontWeight.Bold,
                fontFamily = titleFontFamily,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // The poet line exists only when the entry has a poet — a blank
            // poet no longer renders a noisy "Unknown" placeholder.
            if (!naat.poet.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.reader_poet, naat.poet),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryTextColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // The bar's only job now: back, the title, and two compact controls.
        // Everything the old crowded bar carried (favorite, copy, share,
        // share-audio, edit) lives in the overflow menu, one labeled tap away.
        val readerContext = LocalContext.current
        val hasLyrics = !naat.lyrics.isNullOrBlank()
        val clipboard = LocalClipboardManager.current
        val shareText = if (hasLyrics) buildString {
            append(naat.title)
            if (!naat.poet.isNullOrBlank()) append("\n— ").append(naat.poet)
            append("\n\n").append(naat.lyrics)
        } else ""
        val audioShareFile = shareableAudioFile(naat)

        if (!immersive) {
            // "Aa" text-size panel: the permanent font strip is gone. Text
            // size, auto-scroll and its speed live here, one tap away.
            var textSizePanelOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { textSizePanelOpen = true },
                    modifier = Modifier.testTag("reader_text_size_btn")
                ) {
                    Text(
                        text = "Aa",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                DropdownMenu(
                    expanded = textSizePanelOpen,
                    onDismissRequest = { textSizePanelOpen = false }
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .widthIn(min = 240.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.reader_text_size_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = secondaryTextColor()
                        )
                        ThinSeekBar(
                            fraction = (fontSize - 12f) / 24f,
                            onFractionChange = { f ->
                                val raw = 12f + f * 24f
                                onFontSizeChange((raw * 2f).roundToInt() / 2f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.reader_autoscroll_label),
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = autoScrollActive,
                                onCheckedChange = { onAutoScrollToggle() },
                                modifier = Modifier.testTag("auto_scroll_toggle")
                            )
                        }
                        if (autoScrollActive) {
                            Text(
                                text = stringResource(R.string.reader_scroll_speed_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = secondaryTextColor()
                            )
                            ThinSeekBar(
                                fraction = (scrollSpeed - 1f) / 1f,
                                onFractionChange = { f ->
                                    onScrollSpeedChange(((1f + f * 1f) * 2f).roundToInt() / 2f)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Overflow menu: favorite, copy, share, share-audio, edit.
            var overflowOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { overflowOpen = true },
                    modifier = Modifier.testTag("reader_overflow_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.player_more_cd),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                DropdownMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_favorite_cd)) },
                        leadingIcon = {
                            Icon(
                                if (naat.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                tint = if (naat.isFavorite) HighContrastRed else MaterialTheme.colorScheme.onBackground
                            )
                        },
                        onClick = { overflowOpen = false; onToggleFavorite() },
                        modifier = Modifier.testTag("reader_favorite_toggle")
                    )
                    if (hasLyrics) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reader_copy_cd)) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = {
                                overflowOpen = false
                                clipboard.setText(AnnotatedString(shareText))
                                Toast.makeText(
                                    readerContext, readerContext.getString(R.string.reader_copied), Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.testTag("reader_copy_btn")
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reader_share_cd)) },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            onClick = {
                                overflowOpen = false
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                }
                                readerContext.startActivity(
                                    Intent.createChooser(send, readerContext.getString(R.string.reader_share_cd))
                                )
                            },
                            modifier = Modifier.testTag("reader_share_btn")
                        )
                        // Share-audio keeps PR #63's exact condition (primary
                        // file first, else secondary) — only the placement
                        // changed, from a confusing top-bar icon to a label.
                        if (audioShareFile != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reader_share_audio_cd)) },
                                leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                                onClick = { overflowOpen = false; shareAudioFile(readerContext, audioShareFile) },
                                modifier = Modifier.testTag("reader_share_audio_btn")
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_edit_entry)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { overflowOpen = false; onEdit() },
                        modifier = Modifier.testTag("reader_edit_btn")
                    )
                }
            }
        }

        IconButton(
            onClick = if (immersive) onExitImmersive else onEnterImmersive,
            modifier = Modifier.testTag(
                if (immersive) "reader_exit_fullscreen" else "reader_enter_fullscreen"
            )
        ) {
            Icon(
                imageVector = if (immersive) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = if (immersive) stringResource(R.string.reader_immersive_exit_cd) else stringResource(R.string.reader_immersive_enter_cd),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

/**
 * Slim seek bar in the app's monochrome identity: a 3dp line (played part in
 * onBackground, remainder at 25% alpha) with a small 12dp round thumb. The
 * stock Material slider's thick track and tall stick thumb looked bulky in
 * both the player and the text-size panel, so both use this instead. The
 * touch height is a full 48dp so the thin line stays easy to grab.
 */
@Composable
fun ThinSeekBar(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableStateOf(0) }
    val clamped = fraction.coerceIn(0f, 1f)
    val active = MaterialTheme.colorScheme.onBackground
    Box(
        modifier = modifier
            .height(48.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (widthPx > 0) {
                        onFractionChange((down.position.x / widthPx).coerceIn(0f, 1f))
                        drag(down.id) { change ->
                            onFractionChange((change.position.x / widthPx).coerceIn(0f, 1f))
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(active.copy(alpha = 0.25f), RoundedCornerShape(2.dp))
        )
        if (clamped > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(clamped)
                    .height(3.dp)
                    .background(active, RoundedCornerShape(2.dp))
            )
        }
        Box(
            Modifier
                .offset { IntOffset((widthPx * clamped).toInt() - (6.dp.toPx()).toInt(), 0) }
                .size(12.dp)
                .background(active, CircleShape)
        )
    }
}

@Composable
private fun ReaderLyricsViewport(
    naat: NaatEntity,
    scrollState: androidx.compose.foundation.ScrollState,
    fontSize: Float,
    topChromePaddingPx: Int,
    bottomChromePaddingPx: Int,
    immersive: Boolean,
    onIntentionalTap: () -> Unit,
    onDoubleTap: () -> Unit,
    readerFontChoice: String,
    customFamilies: Map<String, FontFamily>
) {
    val density = LocalDensity.current
    val topPadding = with(density) { topChromePaddingPx.toDp() }
    val bottomPadding = with(density) { bottomChromePaddingPx.toDp() } + ReaderBottomBreathingRoom
    val horizontalPadding = if (immersive) 24.dp else 20.dp
    val tapLabel = if (immersive) stringResource(R.string.reader_tap_label) else ""
    val tapModifier = Modifier.toggleReaderImmersiveOnTap(
        onTap = onIntentionalTap,
        onDoubleTap = onDoubleTap
    )

    if (naat.lyrics.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(tapModifier)
                .semantics {
                    if (immersive) {
                        onClick(label = tapLabel) {
                            onIntentionalTap()
                            true
                        }
                    }
                }
                .padding(
                    start = horizontalPadding,
                    top = topPadding,
                    end = horizontalPadding,
                    bottom = bottomPadding
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.reader_no_lyrics),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryTextColor()
            )
        }
        return
    }

    val urduLyrics = usesArabicScript(naat.lyrics)
    CompositionLocalProvider(
        LocalLayoutDirection provides if (urduLyrics) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .then(tapModifier)
                .semantics {
                    if (immersive) {
                        onClick(label = tapLabel) {
                            onIntentionalTap()
                            true
                        }
                    }
                }
                .padding(
                    start = horizontalPadding,
                    top = topPadding,
                    end = horizontalPadding,
                    bottom = bottomPadding
                )
                .testTag("reader_lyrics_surface")
        ) {
            Text(
                text = naat.lyrics,
                fontSize = fontSize.sp,
                // Per-entry override (or the global reader font); AUTO
                // resolves to the original Nastaliq-for-Urdu / Serif rule.
                fontFamily = FontChoices.resolveReader(readerFontChoice, urduLyrics, customFamilies),
                // Tall scripts (Nastaliq, and imported fonts which are
                // usually Nastaliq-style) need the roomier line height.
                lineHeight = (fontSize * if (
                    urduLyrics || readerFontChoice == FontChoices.NASTALIQ ||
                    FontChoices.customId(readerFontChoice) != null
                ) 2.0f else 1.6f).sp,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reader_lyrics_text")
            )
        }
    }
}

@Composable
private fun ReaderAudioControls(
    naat: NaatEntity,
    viewModel: NaatViewModel,
    modifier: Modifier = Modifier
) {
    val controller = viewModel.playbackController
    val nowPlaying by controller.nowPlaying.collectAsStateWithLifecycle()
    val hasSession by controller.hasActiveSession.collectAsStateWithLifecycle()
    val playerIsPlaying by controller.isPlaying.collectAsStateWithLifecycle()
    val playerPosition by controller.currentPosition.collectAsStateWithLifecycle()
    val playerDuration by controller.duration.collectAsStateWithLifecycle()
    val attachments = remember(naat.id, naat.audioPath, naat.secondaryAudioPath) {
        listOfNotNull(
            naat.audioPath?.let { naat.audioType to it },
            naat.secondaryAudioPath?.let { naat.secondaryAudioType to it }
        ).filter { it.first != "none" }
    }
    if (attachments.isEmpty()) return

    var selectedPath by remember(naat.id) { mutableStateOf(attachments.first().second) }
    LaunchedEffect(attachments, nowPlaying?.audioPath) {
        if (nowPlaying?.naatId == naat.id) selectedPath = nowPlaying?.audioPath ?: selectedPath
        else if (attachments.none { it.second == selectedPath }) selectedPath = attachments.first().second
    }
    val ownsSelected = nowPlaying?.naatId == naat.id && nowPlaying?.audioPath == selectedPath && hasSession
    val playing = ownsSelected && playerIsPlaying
    val position = if (ownsSelected) playerPosition else 0
    val duration = if (ownsSelected) playerDuration else 0
    val selectedType = attachments.firstOrNull { it.second == selectedPath }?.first ?: attachments.first().first

    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        if (attachments.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                attachments.forEach { (type, path) ->
                    TextButton(
                        onClick = {
                            selectedPath = path
                            viewModel.startEntryPlayback(naat, path)
                        },
                        modifier = Modifier.testTag("reader_select_$type")
                    ) {
                        Icon(
                            if (type == "recorded") Icons.Default.Mic else Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (type == "recorded") stringResource(R.string.reader_voice_note) else stringResource(R.string.reader_linked_audio),
                            fontWeight = if (selectedPath == path) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
        // Slim single-row player (owner request 2026-10-18): the old stack —
        // a centered type label, a full-width seek row and a separate
        // transport row — ate ~140dp under the lyrics. Everything now lives
        // in one 48dp row: type icon, skip back, play/pause, skip forward,
        // the thin seek line, the time, and the overflow menu (speed +
        // sleep timer). The attachment switcher above only appears when
        // the entry carries two audios.
        val speed by controller.playbackSpeed.collectAsStateWithLifecycle()
        val sleepRemaining by controller.sleepTimerRemainingMs.collectAsStateWithLifecycle()
        // Audit #11: while Media3 prepares a file the play tap used to
        // look ignored for a second or two — spinner in the button instead.
        val preparing by controller.isPreparing.collectAsStateWithLifecycle()
        var showSleepDialog by remember { mutableStateOf(false) }
        var menuExpanded by remember { mutableStateOf(false) }
        val speedLabel = "${if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()}x"
        val sleepLabel = sleepRemaining?.let { ms ->
            val mins = (ms / 60_000L).toInt()
            val secs = ((ms % 60_000L) / 1000L).toInt()
            "%d:%02d".format(mins, secs)
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (selectedType == "recorded") Icons.Default.Mic else Icons.Default.MusicNote,
                contentDescription = null,
                tint = secondaryTextColor(),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(2.dp))
            // Skip backward 10s. Gated on session ownership: the seekbar
            // shows 0:00 when the live session belongs to another entry,
            // but the skip buttons used to seek THAT entry's audio anyway
            // (reader audit M3).
            IconButton(
                onClick = { controller.skipBackward() },
                enabled = ownsSelected,
                modifier = Modifier.size(40.dp).testTag("reader_skip_back")
            ) {
                Icon(
                    Icons.Default.Replay10,
                    contentDescription = stringResource(R.string.player_skip_back_cd),
                    modifier = Modifier.size(24.dp)
                )
            }
            // Filled play/pause
            IconButton(
                onClick = {
                    if (playing) controller.pause()
                    else if (ownsSelected) controller.resume()
                    else viewModel.startEntryPlayback(naat, selectedPath)
                },
                modifier = Modifier
                    .size(46.dp)
                    .background(MaterialTheme.colorScheme.onBackground, CircleShape)
                    .testTag("reader_play_pause")
            ) {
                if (preparing && !playing) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.background,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) stringResource(R.string.common_pause) else stringResource(R.string.common_play),
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            // Skip forward 10s (same ownership gate as skip-back).
            IconButton(
                onClick = { controller.skipForward() },
                enabled = ownsSelected,
                modifier = Modifier.size(40.dp).testTag("reader_skip_forward")
            ) {
                Icon(
                    Icons.Default.Forward10,
                    contentDescription = stringResource(R.string.player_skip_forward_cd),
                    modifier = Modifier.size(24.dp)
                )
            }
            ThinSeekBar(
                fraction = if (duration > 0) position.toFloat() / duration.toFloat() else 0f,
                onFractionChange = { f ->
                    if (duration > 0) controller.seekTo((f * duration).toInt())
                },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("reader_audio_seekbar")
            )
            Text(
                "${formatTime(position)} / ${formatTime(duration)}",
                style = MaterialTheme.typography.labelSmall,
                color = secondaryTextColor()
            )
            if (sleepLabel != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = sleepLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryTextColor()
                )
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(40.dp).testTag("reader_more_btn")
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.player_more_cd)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.player_menu_speed))
                                Text(speedLabel, fontWeight = FontWeight.Bold)
                            }
                        },
                        onClick = { controller.cyclePlaybackSpeed() },
                        modifier = Modifier.testTag("reader_speed_btn")
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Bedtime,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.player_sleep_timer_title))
                                if (sleepLabel != null) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(sleepLabel, color = secondaryTextColor())
                                }
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            showSleepDialog = true
                        },
                        modifier = Modifier.testTag("reader_sleep_timer_btn")
                    )
                }
            }
        }
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            currentRemainingMs = sleepRemaining,
            onSelect = { minutes ->
                if (minutes <= 0) controller.cancelSleepTimer()
                else controller.setSleepTimer(minutes)
                showSleepDialog = false
            },
            onDismiss = { showSleepDialog = false }
        )
    }
}

/**
 * Sleep timer duration picker dialog.
 */
@Composable
private fun SleepTimerDialog(
    currentRemainingMs: Long?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(0, 5, 10, 15, 30, 45, 60)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_sleep_timer_title)) },
        text = {
            Column {
                options.forEach { minutes ->
                    val label = if (minutes == 0) stringResource(R.string.player_sleep_timer_off)
                                else stringResource(R.string.player_sleep_timer_minutes, minutes)
                    TextButton(
                        onClick = { onSelect(minutes) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                if (currentRemainingMs != null) {
                    Text(
                        text = stringResource(
                            R.string.player_sleep_timer_active,
                            (currentRemainingMs / 60_000L).toInt()
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor(),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = { }
    )
}

@Composable
private fun ApplyImmersiveSystemBars(enabled: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(enabled, context, view) {
        val controller = (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        }
        if (enabled) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (enabled) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * A non-consuming detector layered beside verticalScroll. It only invokes
 * the callbacks for a short, one-finger tap within touch slop; drags,
 * swipes, long presses, and multi-touch gestures continue to belong to
 * lyric scrolling. Two intentional taps released within the double-tap
 * window fire [onDoubleTap] instead of a second [onTap] — in immersive
 * mode the first tap still toggles the chrome immediately, so a
 * double-tap briefly shows the bars on its way out; outside immersive a
 * single tap stays a no-op and only the pair enters full screen.
 */
private fun Modifier.toggleReaderImmersiveOnTap(
    onTap: () -> Unit,
    onDoubleTap: () -> Unit
): Modifier = pointerInput(onTap, onDoubleTap) {
    var lastTapReleaseMs = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val origin = down.position
        val startedAt = down.uptimeMillis
        var maxTravel = 0f
        var maxPointers = 1
        var released = false
        var releasedAt = startedAt

        do {
            val event = awaitPointerEvent()
            maxPointers = maxOf(maxPointers, event.changes.count { it.pressed })
            event.changes.firstOrNull { it.id == down.id }?.let { change ->
                maxTravel = maxOf(maxTravel, (change.position - origin).getDistance())
                if (change.changedToUpIgnoreConsumed()) {
                    released = true
                    releasedAt = change.uptimeMillis
                }
            }
        } while (event.changes.any { it.pressed })

        if (released && ReaderImmersivePolicy.isIntentionalSingleTap(
                travelPx = maxTravel,
                touchSlopPx = viewConfiguration.touchSlop,
                pointerCount = maxPointers,
                durationMs = releasedAt - startedAt
            )
        ) {
            if (ReaderImmersivePolicy.isDoubleTap(lastTapReleaseMs, releasedAt)) {
                lastTapReleaseMs = 0L
                onDoubleTap()
            } else {
                lastTapReleaseMs = releasedAt
                onTap()
            }
        }
    }
}

/**
 * Stage-2 UX audit: share-audio support. Returns the entry's primary audio
 * file when it exists on disk, else the secondary one, else null.
 */
private fun shareableAudioFile(naat: NaatEntity): File? {
    naat.audioPath?.let { File(it) }?.takeIf { it.isFile }?.let { return it }
    return naat.secondaryAudioPath?.let { File(it) }?.takeIf { it.isFile }
}

/** Best-effort audio MIME from the file extension; never null. */
private fun audioMimeType(file: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        ?: "audio/*"

/**
 * Shares an entry's audio file through a FileProvider scoped to the app's
 * audio directories (see audio_file_paths.xml). A file that vanished between
 * the button showing and the tap shows a toast instead of crashing — and a
 * file outside the provider's roots is refused by getUriForFile the same way.
 */
private fun shareAudioFile(context: Context, file: File) {
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (_: IllegalArgumentException) {
        Toast.makeText(
            context, context.getString(R.string.reader_audio_missing), Toast.LENGTH_SHORT
        ).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = audioMimeType(file)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.reader_share_audio_cd))
    )
}
