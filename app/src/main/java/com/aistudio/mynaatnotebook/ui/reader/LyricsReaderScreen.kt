package com.aistudio.mynaatnotebook.ui.reader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.sp
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
import com.aistudio.mynaatnotebook.ui.theme.HighContrastGray
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

    // Auto-scroll engine configuration.
    var autoScrollActive by rememberSaveable { mutableStateOf(false) }
    var scrollSpeed by rememberSaveable { mutableFloatStateOf(1f) }

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
     * The lyric viewport always fills the entire reader window. Chrome is rendered
     * as an overlay and only changes scroll-content padding, never the viewport's
     * origin or height. Before any top chrome change we move ScrollState by the
     * exact padding delta. Consequently the currently read line stays in the same
     * screen position instead of jumping when controls appear/disappear.
     */
    fun compensateForTopChromeChange(fromPx: Int, toPx: Int) {
        scrollState.dispatchRawDelta(
            ReaderImmersivePolicy.scrollCompensationPx(fromPx, toPx)
        )
    }

    fun enterImmersive() {
        if (immersive) return
        compensateForTopChromeChange(topChromeHeightPx, 0)
        immersive = true
        immersiveChromeVisible = false
        showImmersiveHint = true
    }

    fun exitImmersive() {
        if (!immersive) return
        val fromTop = if (immersiveChromeVisible) topChromeHeightPx else 0
        compensateForTopChromeChange(fromTop, topChromeHeightPx)
        immersive = false
        immersiveChromeVisible = false
        showImmersiveHint = false
    }

    fun toggleImmersiveChrome() {
        if (!immersive) return
        val fromTop = if (immersiveChromeVisible) topChromeHeightPx else 0
        val toTop = if (immersiveChromeVisible) 0 else topChromeHeightPx
        compensateForTopChromeChange(fromTop, toTop)
        immersiveChromeVisible = !immersiveChromeVisible
        showImmersiveHint = !immersiveChromeVisible
    }

    fun updateTopChromeHeight(heightPx: Int) {
        if (heightPx == topChromeHeightPx) return
        val oldHeight = topChromeHeightPx
        topChromeHeightPx = heightPx
        // On initial Reader composition there is no previously visible lyric line
        // to anchor: leave ScrollState at zero so the first verse begins below the
        // freshly measured chrome. Subsequent changes preserve the active line.
        if (readerChromeVisible && hasMeasuredTopChrome) {
            compensateForTopChromeChange(oldHeight, heightPx)
        }
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
    val topContentPaddingPx = if (readerChromeVisible) topChromeHeightPx else safeTopPx
    val bottomContentPaddingPx = if (readerChromeVisible) bottomChromeHeightPx else 0

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
            readerFontChoice = effectiveReaderChoice,
            customFamilies = customFamilies
        )

        if (readerChromeVisible) {
            Column(
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
                    titleFontFamily = perEntryTitleFamily
                )
                ReaderControls(
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
    titleFontFamily: FontFamily
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
            Text(
                text = naat.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = titleFontFamily,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1
            )
            Text(
                text = if (!naat.poet.isNullOrBlank()) stringResource(R.string.reader_poet, naat.poet) else stringResource(R.string.reader_unknown_poet),
                style = MaterialTheme.typography.bodySmall,
                color = HighContrastGray,
                maxLines = 1
            )
        }

        if (!immersive) {
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.testTag("reader_favorite_toggle")
            ) {
                Icon(
                    imageVector = if (naat.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = stringResource(R.string.reader_favorite_cd),
                    tint = if (naat.isFavorite) HighContrastRed else MaterialTheme.colorScheme.onBackground
                )
            }
            // Audit #9: the kalaam text used to be trapped in the app — no
            // copy, no share. Both live here now, next to Edit.
            val readerContext = LocalContext.current
            if (!naat.lyrics.isNullOrBlank()) {
                val clipboard = LocalClipboardManager.current
                val shareText = buildString {
                    append(naat.title)
                    if (!naat.poet.isNullOrBlank()) append("\n— ").append(naat.poet)
                    append("\n\n").append(naat.lyrics)
                }
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(shareText))
                        Toast.makeText(
                            readerContext, readerContext.getString(R.string.reader_copied), Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.testTag("reader_copy_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.reader_copy_cd),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                IconButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        readerContext.startActivity(
                            Intent.createChooser(send, readerContext.getString(R.string.reader_share_cd))
                        )
                    },
                    modifier = Modifier.testTag("reader_share_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringResource(R.string.reader_share_cd),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            // Stage-2 UX audit: the recorded/linked audio was trapped too.
            // The button sits next to the text share and only appears when a
            // real audio file exists on disk (primary first, else secondary).
            val audioShareFile = shareableAudioFile(naat)
            if (audioShareFile != null) {
                IconButton(
                    onClick = { shareAudioFile(readerContext, audioShareFile) },
                    modifier = Modifier.testTag("reader_share_audio_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = stringResource(R.string.reader_share_audio_cd),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.testTag("reader_edit_btn")) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.common_edit_entry),
                    tint = MaterialTheme.colorScheme.onBackground
                )
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
 * both the player and the font row, so both use this instead. The touch
 * height stays 24dp so the thin line remains easy to grab.
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
            .height(24.dp)
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
private fun ReaderControls(
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
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f), MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("reader_controls"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1.2f)
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = stringResource(R.string.reader_font_size_cd),
                modifier = Modifier.size(16.dp),
                tint = HighContrastGray
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.reader_font_label), style = MaterialTheme.typography.bodySmall, color = HighContrastGray)
            // Same thin line as the player seek, but only ~60% of the slot
            // width so the font row takes visibly less room (user request).
            Box(modifier = Modifier.weight(1f)) {
                ThinSeekBar(
                    fraction = (fontSize - 12f) / 24f,
                    onFractionChange = { f ->
                        val raw = 12f + f * 24f
                        onFontSizeChange((raw * 2f).roundToInt() / 2f)
                    },
                    modifier = Modifier.fillMaxWidth(0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onAutoScrollToggle,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (autoScrollActive) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        HighContrastGray
                    }
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(32.dp)
                    .testTag("auto_scroll_toggle")
            ) {
                Text(
                    text = if (autoScrollActive) stringResource(R.string.reader_autoscroll_on) else stringResource(R.string.reader_autoscroll_off),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (autoScrollActive) {
                Spacer(modifier = Modifier.width(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(1f, 1.5f, 2f).forEach { speed ->
                        TextButton(
                            onClick = { onScrollSpeedChange(speed) },
                            contentPadding = PaddingValues(horizontal = 2.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                text = (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "x",
                                fontSize = 11.sp,
                                fontWeight = if (scrollSpeed == speed) FontWeight.Bold else FontWeight.Normal,
                                color = if (scrollSpeed == speed) {
                                    MaterialTheme.colorScheme.onBackground
                                } else {
                                    HighContrastGray
                                }
                            )
                        }
                    }
                }
            }
        }
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
    readerFontChoice: String,
    customFamilies: Map<String, FontFamily>
) {
    val density = LocalDensity.current
    val topPadding = with(density) { topChromePaddingPx.toDp() }
    val bottomPadding = with(density) { bottomChromePaddingPx.toDp() } + ReaderBottomBreathingRoom
    val horizontalPadding = if (immersive) 24.dp else 20.dp
    val tapLabel = if (immersive) stringResource(R.string.reader_tap_label) else ""
    val tapModifier = Modifier.toggleReaderChromeOnIntentionalTap(
        enabled = immersive,
        onTap = onIntentionalTap
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
                color = HighContrastGray
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
        Text(
            if (selectedType == "recorded") stringResource(R.string.reader_voice_note) else stringResource(R.string.reader_linked_audio),
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = HighContrastGray
        )
        // Line 1: seek — current time, full-width thin line, total time
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatTime(position),
                style = MaterialTheme.typography.bodySmall,
                color = HighContrastGray
            )
            ThinSeekBar(
                fraction = if (duration > 0) position.toFloat() / duration.toFloat() else 0f,
                onFractionChange = { f ->
                    if (duration > 0) controller.seekTo((f * duration).toInt())
                },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("reader_audio_seekbar")
            )
            Text(
                formatTime(duration),
                style = MaterialTheme.typography.bodySmall,
                color = HighContrastGray
            )
        }
        // Line 2: transport — speed chip, skip back, big play/pause, skip
        // forward, sleep timer chip (same layout as the approved preview).
        PlayerTransportControls(
            controller = controller,
            playing = playing,
            onPlayPause = {
                if (playing) controller.pause()
                else if (ownsSelected) controller.resume()
                else viewModel.startEntryPlayback(naat, selectedPath)
            }
        )
    }
}

/**
 * Transport row for the reader player: skip-back, a large filled play/pause
 * button and skip-forward centered, with a three-dot overflow menu at the
 * right edge holding playback speed and the sleep timer (both used rarely,
 * so they no longer take permanent space in the player). When a sleep timer
 * is running, its remaining time stays visible next to the dots.
 */
@Composable
private fun PlayerTransportControls(
    controller: com.aistudio.mynaatnotebook.audio.PlaybackController,
    playing: Boolean,
    onPlayPause: () -> Unit
) {
    val speed by controller.playbackSpeed.collectAsStateWithLifecycle()
    val sleepRemaining by controller.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    // Audit #11: while Media3 prepares a file there used to be no feedback
    // at all — the play tap looked ignored for a second or two.
    val preparing by controller.isPreparing.collectAsStateWithLifecycle()
    var showSleepDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var menuExpanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val speedLabel = "${if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()}x"
    val sleepLabel = sleepRemaining?.let { ms ->
        val mins = (ms / 60_000L).toInt()
        val secs = ((ms % 60_000L) / 1000L).toInt()
        "%d:%02d".format(mins, secs)
    }

    Box(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Skip backward 10s
            IconButton(
                onClick = { controller.skipBackward() },
                modifier = Modifier.testTag("reader_skip_back")
            ) {
                Icon(
                    Icons.Default.Replay10,
                    contentDescription = stringResource(R.string.player_skip_back_cd),
                    modifier = Modifier.size(28.dp)
                )
            }
            // Big filled play/pause
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.onBackground, CircleShape)
                    .testTag("reader_play_pause")
            ) {
                if (preparing && !playing) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.background,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(26.dp)
                    )
                } else {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) stringResource(R.string.common_pause) else stringResource(R.string.common_play),
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            // Skip forward 10s
            IconButton(
                onClick = { controller.skipForward() },
                modifier = Modifier.testTag("reader_skip_forward")
            ) {
                Icon(
                    Icons.Default.Forward10,
                    contentDescription = stringResource(R.string.player_skip_forward_cd),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        // Right edge: remaining sleep time (when a timer runs) + overflow menu
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (sleepLabel != null) {
                Text(
                    text = sleepLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = HighContrastGray
                )
                Spacer(Modifier.width(2.dp))
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.testTag("reader_more_btn")
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
                                    Text(sleepLabel, color = HighContrastGray)
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
                                else "$minutes min"
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
                        text = "Active: ${currentRemainingMs / 60_000L}m remaining",
                        style = MaterialTheme.typography.bodySmall,
                        color = HighContrastGray,
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
 * A non-consuming detector layered beside verticalScroll. It only invokes the
 * toggle for a short, one-finger tap within touch slop; drags, swipes, long
 * presses, and multi-touch gestures continue to belong to lyric scrolling.
 */
private fun Modifier.toggleReaderChromeOnIntentionalTap(
    enabled: Boolean,
    onTap: () -> Unit
): Modifier = if (!enabled) {
    this
} else {
    pointerInput(onTap) {
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
