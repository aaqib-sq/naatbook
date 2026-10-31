package com.aistudio.mynaatnotebook.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
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
 * No scrolling, ever (owner order): the content is sized to fit, with
 * the emergency options living in the top-right three-dot menu instead
 * of a card below the keypad. The menu buttons save data BEFORE any
 * wipe: "Sync to Drive" uploads the current library (existing Drive
 * sync), "Make a local file" writes an encrypted emergency file to
 * Download/NaatBook. Neither button unlocks the app or shows entries.
 * The standard Material fingerprint icon is the biometric mark.
 *
 * Layout (owner orders, 2026-10-31): the content block is vertically
 * centered so the keypad sits at thumb reach instead of crowding the
 * top; the three-dot menu clears the status bar; and background taps
 * show NO press effect at all — the overlay still consumes every touch
 * (the library underneath stays unreachable), but with indication =
 * null, so ripples appear only on real buttons and keys.
 */
@Composable
fun LockScreen(
    lockViewModel: LockViewModel,
    driveViewModel: DriveViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lockoutUntilMs by lockViewModel.lockoutUntilMs.collectAsStateWithLifecycle()
    val biometricEnabled by lockViewModel.biometricEnabled.collectAsStateWithLifecycle()
    val biometricError by lockViewModel.biometricError.collectAsStateWithLifecycle()
    val emergencyWorking by lockViewModel.emergencyWorking.collectAsStateWithLifecycle()
    val emergencyResult by lockViewModel.emergencyResult.collectAsStateWithLifecycle()
    val driveEmail by driveViewModel.connectedEmail.collectAsStateWithLifecycle()
    val driveSyncing by driveViewModel.isSyncing.collectAsStateWithLifecycle()

    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    var pinSubmitting by remember { mutableStateOf(false) }
    var pressedKey by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
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
    val keypadEnabled = !lockedOut && !pinSubmitting

    // Back must never escape the lock screen into the library.
    BackHandler(enabled = true) { }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Consume every touch so nothing reaches the library
            // underneath — but with NO visual indication, so taps on
            // empty background show no ripple at all. Ripples live
            // only on real buttons and keys.
            .clickable(
                enabled = true,
                onClick = {},
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            )
            .testTag("lock_screen"),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Vertically centered (was top-packed): the keypad sat in
            // the upper half with a dead black bottom half. Centering
            // drops the keypad to thumb reach and balances the space.
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_app_logo),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
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
            Spacer(modifier = Modifier.height(16.dp))
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
            Spacer(modifier = Modifier.height(20.dp))
            // Keypad: 1-9, blank, 0, backspace. Monochrome outlined circles.
            // The pressed key fills briefly so every tap — including the
            // last digit, whose auto-submit used to swallow all feedback —
            // shows a visible click before anything else happens.
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<")
            keys.chunked(3).forEach { rowKeys ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    rowKeys.forEach { key ->
                        if (key.isEmpty()) {
                            Spacer(modifier = Modifier.size(68.dp))
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .border(
                                        2.dp,
                                        MaterialTheme.colorScheme.onBackground,
                                        CircleShape
                                    )
                                    .background(
                                        if (pressedKey == key) {
                                            MaterialTheme.colorScheme.onBackground
                                                .copy(alpha = 0.18f)
                                        } else {
                                            Color.Transparent
                                        },
                                        CircleShape
                                    )
                                    .clickable(enabled = keypadEnabled) {
                                        pinError = false
                                        lockViewModel.clearBiometricError()
                                        pressedKey = key
                                        if (key == "<") {
                                            pin = pin.dropLast(1)
                                            scope.launch {
                                                delay(150L)
                                                if (pressedKey == key) pressedKey = null
                                            }
                                        } else if (pin.length < 4) {
                                            pin += key
                                            if (pin.length == 4) {
                                                // Last digit: let the press
                                                // effect (and 4th dot) show
                                                // before auto-submit runs.
                                                val typed = pin
                                                pinSubmitting = true
                                                scope.launch {
                                                    delay(150L)
                                                    if (pressedKey == key) pressedKey = null
                                                    if (!lockViewModel.checkPin(typed)) {
                                                        pin = ""
                                                        pinError = true
                                                    }
                                                    pinSubmitting = false
                                                }
                                            } else {
                                                scope.launch {
                                                    delay(150L)
                                                    if (pressedKey == key) pressedKey = null
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
            Spacer(modifier = Modifier.height(16.dp))
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
            // Emergency result line (the options themselves live in the
            // three-dot menu). Shown only after an option was used.
            emergencyResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.lock_emergency_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // Emergency options: three-dot overflow menu, top-right.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopEnd
        ) {
            Column {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        // Clear of the status bar (was 8dp from the
                        // screen edge, jammed into the status zone).
                        .statusBarsPadding()
                        .padding(top = 8.dp, end = 16.dp)
                        .testTag("lock_menu_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.lock_menu_desc),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (driveEmail != null) {
                                    stringResource(R.string.lock_emergency_drive_btn)
                                } else {
                                    stringResource(R.string.lock_emergency_drive_not_connected)
                                }
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            driveViewModel.syncNow()
                        },
                        enabled = driveEmail != null && !driveSyncing,
                        modifier = Modifier.testTag("lock_menu_drive")
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.lock_emergency_local_btn)) },
                        onClick = {
                            menuExpanded = false
                            lockViewModel.emergencyExport()
                        },
                        enabled = !emergencyWorking,
                        modifier = Modifier.testTag("lock_menu_local")
                    )
                }
            }
        }
    }
}
