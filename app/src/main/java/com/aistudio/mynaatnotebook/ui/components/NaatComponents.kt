package com.aistudio.mynaatnotebook.ui.components

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
import com.aistudio.mynaatnotebook.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.mynaatnotebook.audio.RecordingState
import com.aistudio.mynaatnotebook.data.NaatCategories
import com.aistudio.mynaatnotebook.data.NaatEntity
import com.aistudio.mynaatnotebook.ui.theme.MyApplicationTheme
import com.aistudio.mynaatnotebook.ui.theme.NastaliqFamily
import com.aistudio.mynaatnotebook.ui.theme.HighContrastRed
import com.aistudio.mynaatnotebook.ui.theme.HighContrastGray
import com.aistudio.mynaatnotebook.viewmodel.NaatViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

// --- Bottom app bar with a real docked Material FAB ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NaatBottomNavigation(
    currentTab: Int,
    onTabSelected: (Int) -> Unit,
    onAddRequested: () -> Unit
) {
    // Child composables must NEVER re-read the system dark flag: the app's
    // explicit White/Black/System mode is already resolved into the colorScheme.
    val background = MaterialTheme.colorScheme.background
    val foreground = MaterialTheme.colorScheme.onBackground

    BottomAppBar(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bottom_app_bar"),
        containerColor = background,
        contentColor = foreground,
        tonalElevation = 4.dp,
        contentPadding = PaddingValues(horizontal = 12.dp),
        actions = {
            BottomBarTab(
                label = stringResource(R.string.nav_library),
                icon = Icons.Default.Home,
                selected = currentTab == 0,
                onClick = { onTabSelected(0) },
                modifier = Modifier.weight(1f),
                testTag = "nav_tab_library"
            )

            // Add is an app action, not a third navigation destination. Using the
            // Material FAB gives it true elevation, touch feedback, and semantics.
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                FloatingActionButton(
                    onClick = onAddRequested,
                    modifier = Modifier.testTag("nav_tab_add"),
                    containerColor = foreground,
                    contentColor = background,
                    elevation = FloatingActionButtonDefaults.elevation()
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.nav_add_entry_cd),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            BottomBarTab(
                label = stringResource(R.string.nav_settings),
                icon = Icons.Default.Settings,
                selected = currentTab == 2,
                onClick = { onTabSelected(2) },
                modifier = Modifier.weight(1f),
                testTag = "nav_tab_settings"
            )
        }
    )
}

@Composable
private fun BottomBarTab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String
) {
    val foreground = MaterialTheme.colorScheme.onBackground
    Column(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                // Re-selecting an active top-level tab must remain inert; otherwise
                // it can restart UI work and cause the visual flash P4e removed.
                onClick = { if (!selected) onClick() }
            )
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(100))
                .background(if (selected) foreground.copy(alpha = 0.10f) else Color.Transparent)
                .padding(horizontal = 20.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label.lowercase().replaceFirstChar { it.titlecase() },
                tint = if (selected) foreground else foreground.copy(alpha = 0.55f),
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) foreground else foreground.copy(alpha = 0.55f),
            letterSpacing = 0.5.sp
        )
    }
}

// --- Global Mini-Player: floating transport strip above the bottom nav ---
@Composable
fun GlobalMiniPlayer(
    viewModel: NaatViewModel,
    onOpen: () -> Unit,
    titleFontFamily: FontFamily = FontFamily.SansSerif
) {
    val controller = viewModel.playbackController
    val nowPlaying by controller.nowPlaying.collectAsStateWithLifecycle()
    val hasSession by controller.hasActiveSession.collectAsStateWithLifecycle()
    val isPlaying by controller.isPlaying.collectAsStateWithLifecycle()
    // Audit #10/#11: the strip used to give no position cue and no loading
    // feedback — both come from flows the controller already exposes.
    val position by controller.currentPosition.collectAsStateWithLifecycle()
    val durationMs by controller.duration.collectAsStateWithLifecycle()
    val preparing by controller.isPreparing.collectAsStateWithLifecycle()

    val current = nowPlaying
    if (current == null || !hasSession) return

    val fg = MaterialTheme.colorScheme.onBackground
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mini_player")
    ) {
        // Hairline matching the nav bar's top divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("mini_player_row"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_app_logo_small),
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = titleFontFamily,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("mini_player_title")
                )
                if (!current.poet.isNullOrBlank()) {
                    Text(
                        text = current.poet,
                        style = MaterialTheme.typography.bodySmall,
                        color = HighContrastGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = { controller.togglePlayPause() },
                modifier = Modifier
                    .size(40.dp)
                    .testTag("mini_player_play_pause")
            ) {
                if (preparing && !isPlaying) {
                    CircularProgressIndicator(
                        color = fg,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) stringResource(R.string.common_pause) else stringResource(R.string.common_play),
                        tint = fg,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            // Dismiss (approved): full stop via the same controller.stop()
            // path used on entry delete/switch — the session ends, the
            // now-playing state clears, and this strip disappears. Pause
            // alone could never remove the bar before.
            IconButton(
                onClick = { controller.stop() },
                modifier = Modifier
                    .size(40.dp)
                    .testTag("mini_player_close")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.mini_player_close),
                    tint = fg,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        // Progress hairline (audit #10): same thin-line language as the
        // reader's seek bar — played part in foreground, rest at low alpha.
        if (durationMs > 0) {
            val fraction = (position.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .background(fg.copy(alpha = 0.18f))
                    .testTag("mini_player_progress")
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(2.5.dp)
                        .background(fg)
                )
            }
        }
    }
}

// --- Tab A: My Library Screen ---

// --- Live VU meter for the recorder: bars light up with the mic amplitude ---
@Composable
fun RecordingVuMeter(
    amplitude: Int,
    modifier: Modifier = Modifier,
    barCount: Int = 14
) {
    val normalized = (amplitude / 32767f).coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = normalized, label = "vu_meter")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        repeat(barCount) { index ->
            val frac = (index + 1f) / barCount
            val isActive = animated >= frac * 0.9f
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((6f + 16f * frac).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (isActive) HighContrastRed
                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.22f)
                    )
            )
        }
    }
}

// --- Inline audio preview (play/pause + position) for the add/edit modal ---
@Composable
fun AudioAttachmentPreview(
    path: String,
    viewModel: NaatViewModel
) {
    val controller = viewModel.playbackController
    val activePreviewPath by controller.previewPath.collectAsStateWithLifecycle()
    val playerIsPlaying by controller.isPlaying.collectAsStateWithLifecycle()
    val currentPos by controller.currentPosition.collectAsStateWithLifecycle()
    val duration by controller.duration.collectAsStateWithLifecycle()
    val ownsPreview = activePreviewPath == path && controller.ownsPreview(path)
    val isPlaying = ownsPreview && playerIsPlaying

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                when {
                    isPlaying -> controller.pause()
                    ownsPreview -> controller.resume()
                    else -> controller.playPreview(path)
                }
            },
            modifier = Modifier.testTag("audio_preview_play")
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) stringResource(R.string.player_preview_pause_cd) else stringResource(R.string.player_preview_play_cd),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        Text(
            text = if (ownsPreview) {
                "${formatTime(currentPos)} / ${formatTime(duration)}"
            } else {
                stringResource(R.string.player_preview_label)
            },
            style = MaterialTheme.typography.bodySmall,
            color = HighContrastGray
        )
    }
}

// Formatting seconds duration
fun formatTime(ms: Int): String {
    val totalSecs = ms / 1000
    val hours = totalSecs / 3600
    val mins = (totalSecs % 3600) / 60
    val secs = totalSecs % 60
    // Audit #16: long recordings (bayans) used to render as "90:00".
    return if (hours > 0) String.format("%d:%02d:%02d", hours, mins, secs)
    else String.format("%02d:%02d", mins, secs)
}

// Arabic-script detection (Urdu/Persian/Punjabi/Arabic): drives the Nastaliq
// font selection and RTL layout direction. Anything Latin stays Serif + LTR.
private val arabicScriptRegex =
    Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")

fun usesArabicScript(text: String?): Boolean =
    !text.isNullOrEmpty() && arabicScriptRegex.containsMatchIn(text)
