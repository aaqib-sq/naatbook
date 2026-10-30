package com.aistudio.mynaatnotebook.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistudio.mynaatnotebook.R
import com.aistudio.mynaatnotebook.viewmodel.DriveViewModel
import com.aistudio.mynaatnotebook.viewmodel.LockViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Combined PIN + biometric lock screen (owner-confirmed variant, 2026-10-30).
 *
 * Covers the whole app while locked: library content is never composed
 * underneath in a readable way (this overlay is opaque and consumes all
 * input), and Back is swallowed. The keypad is ALWAYS the fallback —
 * biometric-only mode is forbidden by design.
 *
 * Emergency section: hidden until the user taps "Can't unlock?" or any
 * unlock attempt fails. Its buttons save data BEFORE any wipe:
 * "Drive sync karo" uploads the current library (existing Drive sync),
 * "Local file banao" writes an encrypted emergency file to
 * Download/NaatBook. Neither button unlocks the app or shows entries.
 */
@Composable
fun LockScreen(
    lockViewModel: LockViewModel,
    driveViewModel: DriveViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val failedAttempts by lockViewModel.failedAttempts.collectAsStateWithLifecycle()
    val lockoutUntilMs by lockViewModel.lockoutUntilMs.collectAsStateWithLifecycle()
    val biometricEnabled by lockViewModel.biometricEnabled.collectAsStateWithLifecycle()
    val biometricError by lockViewModel.biometricError.collectAsStateWithLifecycle()
    val emergencyWorking by lockViewModel.emergencyWorking.collectAsStateWithLifecycle()
    val emergencyResult by lockViewModel.emergencyResult.collectAsStateWithLifecycle()
    val driveEmail by driveViewModel.connectedEmail.collectAsStateWithLifecycle()
    val driveSyncing by driveViewModel.isSyncing.collectAsStateWithLifecycle()
    val driveLastSyncText by driveViewModel.lastSyncText.collectAsStateWithLifecycle()

    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    var showEmergency by remember { mutableStateOf(false) }
    // 1s ticker so the lockout countdown moves without recomposing the world.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lockoutUntilMs) {
        while (System.currentTimeMillis() < lockoutUntilMs) {
            delay(1000L)
            nowMs = System.currentTimeMillis()
        }
        nowMs = System.currentTimeMillis()
    }
    val lockedOut = nowMs < lockoutUntilMs
    val remainingSec = ((lockoutUntilMs - nowMs) / 1000L).coerceAtLeast(0L)

    // The emergency section opens itself after any failed attempt.
    LaunchedEffect(failedAttempts) {
        if (failedAttempts > 0) showEmergency = true
    }

    // Back must never escape the lock screen into the library.
    BackHandler(enabled = true) { }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(enabled = true, onClick = {})
            .testTag("lock_screen"),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_app_logo),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.lock_app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when {
                    lockedOut -> stringResource(R.string.lock_try_again_in, remainingSec)
                    pinError -> stringResource(R.string.lock_wrong_pin)
                    biometricError != null -> stringResource(R.string.lock_use_pin_instead)
                    else -> stringResource(R.string.lock_enter_pin)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            // PIN dots.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(4) { index ->
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .border(
                                2.dp,
                                MaterialTheme.colorScheme.onBackground,
                                CircleShape
                            )
                            .background(
                                if (index < pin.length) MaterialTheme.colorScheme.onBackground
                                else MaterialTheme.colorScheme.background
                            )
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            // Keypad: 1-9, blank, 0, backspace. Monochrome outlined circles.
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<")
            keys.chunked(3).forEach { rowKeys ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    rowKeys.forEach { key ->
                        if (key.isEmpty()) {
                            Spacer(modifier = Modifier.size(72.dp))
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .border(
                                        2.dp,
                                        MaterialTheme.colorScheme.onBackground,
                                        CircleShape
                                    )
                                    .clickable(enabled = !lockedOut) {
                                        pinError = false
                                        lockViewModel.clearBiometricError()
                                        if (key == "<") {
                                            pin = pin.dropLast(1)
                                        } else if (pin.length < 4) {
                                            pin += key
                                            if (pin.length == 4) {
                                                val typed = pin
                                                scope.launch {
                                                    if (!lockViewModel.checkPin(typed)) {
                                                        pin = ""
                                                        pinError = true
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .testTag("lock_key_$key"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            if (biometricEnabled && lockViewModel.biometricAvailable && !lockedOut) {
                OutlinedButton(
                    onClick = {
                        (context as? FragmentActivity)?.let {
                            lockViewModel.requestBiometric(it)
                        }
                    },
                    modifier = Modifier.testTag("lock_biometric_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.lock_biometric_btn))
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            TextButton(
                onClick = { showEmergency = !showEmergency },
                modifier = Modifier.testTag("lock_cant_unlock_btn")
            ) {
                Text(text = stringResource(R.string.lock_cant_unlock))
            }
            if (showEmergency) {
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.lock_emergency_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { driveViewModel.syncNow() },
                        enabled = driveEmail != null && !driveSyncing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("lock_emergency_drive_btn")
                    ) {
                        if (driveSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (driveEmail != null) {
                                stringResource(R.string.lock_emergency_drive_btn)
                            } else {
                                stringResource(R.string.lock_emergency_drive_not_connected)
                            }
                        )
                    }
                    driveLastSyncText?.let { text ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { lockViewModel.emergencyExport() },
                        enabled = !emergencyWorking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("lock_emergency_local_btn")
                    ) {
                        if (emergencyWorking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(text = stringResource(R.string.lock_emergency_local_btn))
                    }
                    emergencyResult?.let { result ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.lock_emergency_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
