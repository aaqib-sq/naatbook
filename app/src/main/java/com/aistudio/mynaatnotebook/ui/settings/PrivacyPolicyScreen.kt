package com.aistudio.mynaatnotebook.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.mynaatnotebook.R
import com.aistudio.mynaatnotebook.ui.theme.secondaryTextColor

/**
 * A block of the privacy policy document. The full text mirrors
 * PRIVACY.md at the repository root (same promises, same contact
 * address) and ships inside the app so the screen works fully offline.
 */
private sealed interface PolicyBlock {
    data class Heading(@StringRes val res: Int) : PolicyBlock
    data class Para(@StringRes val res: Int, val args: Array<Any> = emptyArray()) : PolicyBlock
    data class Bullet(@StringRes val res: Int) : PolicyBlock
}

private val POLICY_BLOCKS: List<PolicyBlock> = listOf(
    PolicyBlock.Para(R.string.privacy_body_updated),
    PolicyBlock.Para(R.string.privacy_body_intro),
    PolicyBlock.Heading(R.string.privacy_h_data),
    PolicyBlock.Para(R.string.privacy_p_data_1),
    PolicyBlock.Para(R.string.privacy_p_data_2),
    PolicyBlock.Para(R.string.privacy_p_data_3),
    PolicyBlock.Heading(R.string.privacy_h_drive),
    PolicyBlock.Para(R.string.privacy_p_drive_intro),
    PolicyBlock.Bullet(R.string.privacy_b_drive_1),
    PolicyBlock.Bullet(R.string.privacy_b_drive_2),
    PolicyBlock.Bullet(R.string.privacy_b_drive_3),
    PolicyBlock.Bullet(R.string.privacy_b_drive_4),
    PolicyBlock.Bullet(R.string.privacy_b_drive_5),
    PolicyBlock.Heading(R.string.privacy_h_perms),
    PolicyBlock.Bullet(R.string.privacy_b_perm_net),
    PolicyBlock.Bullet(R.string.privacy_b_perm_mic),
    PolicyBlock.Heading(R.string.privacy_h_contact),
    PolicyBlock.Para(R.string.privacy_p_contact, arrayOf<Any>(SUPPORT_EMAIL)),
)

@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    // Explicit opaque theme background. During a HELD predictive-back
    // gesture, Navigation Compose keeps the previous destination (Settings)
    // composed behind this screen while the gesture drives the animation;
    // with no background painted here, that behind content ghosts through
    // the policy text. (The NavHost already uses non-fading None transitions
    // everywhere — NaatApp.kt — so no transition change is needed.) This
    // matches the pattern every other full screen in the app uses
    // (LyricsReaderScreen, AddNaatScreen, OnboardingScreen, ContactUsScreen)
    // and holds in both light and dark themes because it is a theme color,
    // not a literal.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("privacy_back_btn")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.about_back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = stringResource(R.string.privacy_screen_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .testTag("privacy_body_list")
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            items(POLICY_BLOCKS, key = { it.hashCode() }) { block ->
                when (block) {
                    is PolicyBlock.Heading -> {
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = stringResource(block.res),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    is PolicyBlock.Para -> {
                        Text(
                            text = stringResource(block.res, *block.args),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    is PolicyBlock.Bullet -> {
                        Row(modifier = Modifier.padding(bottom = 8.dp)) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(block.res),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}
