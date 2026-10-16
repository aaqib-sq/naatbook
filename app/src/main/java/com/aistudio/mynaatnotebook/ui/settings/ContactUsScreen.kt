package com.aistudio.mynaatnotebook.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.mynaatnotebook.R
import com.aistudio.mynaatnotebook.ui.theme.secondaryTextColor

/**
 * Two contact cards: the support email and the Instagram handle. The only
 * outward actions are explicit user taps — a mailto intent for their own
 * mail app, a view intent for Instagram, and clipboard copies. Nothing here
 * makes a network call from the app itself.
 */
@Composable
fun ContactUsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    fun copiedFeedback() {
        Toast.makeText(context, context.getString(R.string.settings_copied), Toast.LENGTH_SHORT)
            .show()
    }

    /**
     * Launch the intent directly. On Android 11+ (API 30), a resolveActivity()
     * pre-check is unreliable without <queries> manifest declarations — it
     * returns null even when suitable apps ARE installed, which is exactly
     * what happened on-device. So we attempt the launch and only fall back
     * to the toast when the launch fails. The manifest also carries the
     * proper <queries> declarations so any future resolve query is truthful.
     */
    fun openIntentSafely(intent: Intent) {
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(
                context,
                context.getString(R.string.settings_open_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

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
                modifier = Modifier.testTag("contact_back_btn")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.about_back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = stringResource(R.string.contact_screen_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            ContactCard(
                label = stringResource(R.string.contact_email_label),
                value = SUPPORT_EMAIL,
                buttonText = stringResource(R.string.contact_email_button),
                valueTestTag = "contact_email_value",
                buttonTestTag = "contact_email_btn",
                onValueClick = {
                    clipboard.setText(AnnotatedString(SUPPORT_EMAIL))
                    copiedFeedback()
                },
                onButtonClick = {
                    openIntentSafely(
                        Intent(
                            Intent.ACTION_SENDTO,
                            Uri.parse("mailto:$SUPPORT_EMAIL")
                        )
                    )
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
            ContactCard(
                label = stringResource(R.string.contact_instagram_label),
                value = INSTAGRAM_HANDLE,
                buttonText = stringResource(R.string.contact_instagram_button),
                valueTestTag = "contact_instagram_value",
                buttonTestTag = "contact_instagram_btn",
                onValueClick = {
                    clipboard.setText(AnnotatedString(INSTAGRAM_HANDLE))
                    copiedFeedback()
                },
                onButtonClick = {
                    openIntentSafely(Intent(Intent.ACTION_VIEW, Uri.parse(INSTAGRAM_URL)))
                }
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ContactCard(
    label: String,
    value: String,
    buttonText: String,
    valueTestTag: String,
    buttonTestTag: String,
    onValueClick: () -> Unit,
    onButtonClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = secondaryTextColor()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onValueClick)
                    .testTag(valueTestTag)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onButtonClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background
                ),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.testTag(buttonTestTag)
            ) {
                Text(text = buttonText)
            }
        }
    }
}
