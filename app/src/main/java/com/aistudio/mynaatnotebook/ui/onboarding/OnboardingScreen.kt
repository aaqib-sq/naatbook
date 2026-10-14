package com.aistudio.mynaatnotebook.ui.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.mynaatnotebook.R
import com.aistudio.mynaatnotebook.ui.reader.ThinSeekBar
import com.aistudio.mynaatnotebook.ui.theme.HighContrastGray
import com.aistudio.mynaatnotebook.ui.theme.NastaliqFamily
import kotlinx.coroutines.launch

/**
 * First-launch intro (approved design, refined from the owner's functional
 * HTML demo): four swipeable pages in the app's monochrome identity. The
 * "art" is not generic clip-art — page 1 carries the app's own dome mark
 * and a Nastaliq hero line, and pages 2–4 show miniature replicas of the
 * real editor, reader and backup surfaces. A small dome sits at the top of
 * every page as the identity thread.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val page = pagerState.currentPage
    val fg = MaterialTheme.colorScheme.onBackground

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("onboarding_root")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { p ->
                OnboardingPage(page = p)
            }

            // Dots
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(4) { index ->
                    val selected = index == page
                    val width by animateDpAsState(
                        targetValue = if (selected) 26.dp else 8.dp,
                        label = "onboarding_dot"
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(8.dp)
                            .width(width)
                            .clip(CircleShape)
                            .background(
                                if (selected) fg
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                            )
                    )
                }
            }

            Button(
                onClick = {
                    if (page < 3) {
                        scope.launch { pagerState.animateScrollToPage(page + 1) }
                    } else {
                        onDone()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = fg,
                    contentColor = MaterialTheme.colorScheme.background
                ),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 30.dp)
                    .height(56.dp)
                    .testTag(if (page < 3) "onboarding_next" else "onboarding_start")
            ) {
                Text(
                    text = stringResource(
                        if (page < 3) R.string.onboarding_next else R.string.onboarding_start
                    ),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                if (page < 3) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }

        if (page < 3) {
            TextButton(
                onClick = onDone,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 6.dp, end = 10.dp)
                    .testTag("onboarding_skip")
            ) {
                Text(
                    text = stringResource(R.string.onboarding_skip),
                    color = HighContrastGray,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
private fun OnboardingPage(page: Int) {
    val fg = MaterialTheme.colorScheme.onBackground
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(64.dp))
        // Identity thread: the app's dome mark on every page (large on page 1).
        Icon(
            painter = painterResource(R.drawable.ic_app_logo),
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(if (page == 0) 118.dp else 40.dp)
        )
        // Urdu hero in the app's bundled Nastaliq. Nastaliq needs roughly
        // double line height or Compose clips its tall glyphs.
        val urduSize = if (page == 0) 50 else 38
        Text(
            text = stringResource(
                when (page) {
                    0 -> R.string.onboarding_urdu_1
                    1 -> R.string.onboarding_urdu_2
                    2 -> R.string.onboarding_urdu_3
                    else -> R.string.onboarding_urdu_4
                }
            ),
            fontFamily = NastaliqFamily,
            fontSize = urduSize.sp,
            lineHeight = (urduSize * 1.95).sp,
            color = fg,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (page == 0) 10.dp else 4.dp)
                .testTag("onboarding_urdu_$page")
        )

        when (page) {
            0 -> {
                DiamondDivider()
                PageTitleBody(
                    title = R.string.onboarding_title_1,
                    body = R.string.onboarding_body_1
                )
            }
            1 -> {
                EditorScene()
                Spacer(modifier = Modifier.weight(1f))
                PageTitleBody(
                    title = R.string.onboarding_title_2,
                    body = R.string.onboarding_body_2
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
            2 -> {
                ReaderScene()
                Spacer(modifier = Modifier.weight(1f))
                PageTitleBody(
                    title = R.string.onboarding_title_3,
                    body = R.string.onboarding_body_3
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
            else -> {
                BackupScene()
                Spacer(modifier = Modifier.weight(1f))
                PageTitleBody(
                    title = R.string.onboarding_title_4,
                    body = R.string.onboarding_body_4
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
        if (page == 0) Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DiamondDivider() {
    val fg = MaterialTheme.colorScheme.onBackground
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .width(58.dp)
                .height(1.5.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 10.dp)
                .size(8.dp)
                .rotate(45f)
                .background(fg)
        )
        Box(
            modifier = Modifier
                .width(58.dp)
                .height(1.5.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
        )
    }
}

@Composable
private fun PageTitleBody(title: Int, body: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = stringResource(body),
        style = MaterialTheme.typography.bodyLarge,
        color = HighContrastGray,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 6.dp)
    )
}

/** Miniature of the real editor card: title, poet hint, folder chips, slim actions. */
@Composable
private fun EditorScene() {
    SceneCard {
        Text(
            text = stringResource(R.string.onboarding_scene_kalaam_title),
            fontFamily = NastaliqFamily,
            fontSize = 22.sp,
            lineHeight = 42.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.onboarding_scene_poet_hint),
            style = MaterialTheme.typography.bodySmall,
            color = HighContrastGray
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 13.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SceneChip(text = stringResource(R.string.onboarding_scene_chip_naat), selected = true)
            SceneChip(text = stringResource(R.string.onboarding_scene_chip_hamd), selected = false)
            SceneChip(text = stringResource(R.string.onboarding_scene_chip_manqabat), selected = false)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
        ) {
            SlimSceneButton(
                label = stringResource(R.string.onboarding_scene_record),
                primary = true,
                modifier = Modifier.weight(1f)
            )
            SlimSceneButton(
                label = stringResource(R.string.onboarding_scene_browse),
                primary = false,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Miniature of the real reader: Nastaliq lines, the thin seek line, transport. */
@Composable
private fun ReaderScene() {
    SceneCard {
        Text(
            text = stringResource(R.string.onboarding_scene_lyrics),
            fontFamily = NastaliqFamily,
            fontSize = 21.sp,
            lineHeight = 44.sp,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            Text(
                text = "1:24",
                style = MaterialTheme.typography.labelSmall,
                color = HighContrastGray
            )
            ThinSeekBar(
                fraction = 0.34f,
                onFractionChange = {},
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            Text(
                text = "4:10",
                style = MaterialTheme.typography.labelSmall,
                color = HighContrastGray
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            ScenePill(text = "1.0x")
            Spacer(modifier = Modifier.width(14.dp))
            Icon(
                imageVector = Icons.Default.Replay10,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.background,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Icon(
                imageVector = Icons.Default.Forward10,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .border(
                        1.5.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Bedtime,
                    contentDescription = null,
                    tint = HighContrastGray,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "15",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = HighContrastGray
                )
            }
        }
    }
}

/** Miniature of the real backup card row, straight from Settings. */
@Composable
private fun BackupScene() {
    SceneCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_export_zip),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.onboarding_scene_export_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = HighContrastGray
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = HighContrastGray
            )
        }
        Text(
            text = stringResource(R.string.onboarding_scene_last_backup),
            style = MaterialTheme.typography.bodySmall,
            color = HighContrastGray,
            modifier = Modifier.padding(start = 55.dp, top = 10.dp)
        )
        Box(
            modifier = Modifier
                .padding(start = 55.dp, top = 10.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = stringResource(R.string.onboarding_scene_path),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun SceneCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                RoundedCornerShape(18.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            content()
        }
    }
}

@Composable
private fun SceneChip(text: String, selected: Boolean) {
    val fg = MaterialTheme.colorScheme.onBackground
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) fg else MaterialTheme.colorScheme.background)
            .border(
                1.5.dp,
                if (selected) fg else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                RoundedCornerShape(50)
            )
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (selected) MaterialTheme.colorScheme.background else HighContrastGray
        )
    }
}

@Composable
private fun SlimSceneButton(label: String, primary: Boolean, modifier: Modifier = Modifier) {
    val fg = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(50))
            .border(
                1.6.dp,
                if (primary) fg else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                RoundedCornerShape(50)
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (primary) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(fg)
            )
            Spacer(modifier = Modifier.width(7.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (primary) fg else HighContrastGray
        )
    }
}

@Composable
private fun ScenePill(text: String) {
    Box(
        modifier = Modifier
            .border(
                1.5.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = HighContrastGray
        )
    }
}
