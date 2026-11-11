package com.aistudio.mynaatnotebook.viewmodel

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.mynaatnotebook.R
import com.aistudio.mynaatnotebook.data.AutoLockPolicy
import com.aistudio.mynaatnotebook.data.EncryptedBackup
import com.aistudio.mynaatnotebook.data.LockAttemptPolicy
import com.aistudio.mynaatnotebook.data.PinHash
import com.aistudio.mynaatnotebook.data.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * App-lock state machine (combined PIN + biometric, owner-confirmed 2026-10-30).
 *
 * - The lock is a UI privacy gate, not encryption of the library: it hides
 *   the library until the owner unlocks. The library itself is not encrypted.
 * - PIN is stored only as a salted SHA-256 hash, never plaintext, and never
 *   in any backup or export (backups carry entries + audio only, so a fresh
 *   install always starts unlocked and a restore never restores the lock).
 * - Biometric has NO app-level attempt limit: the system BiometricPrompt
 *   (BIOMETRIC_STRONG or DEVICE_CREDENTIAL) handles biometric lockout and
 *   falls back to the device credential itself. Biometric-only mode is
 *   forbidden — the PIN is always available.
 * - Wrong PIN attempts escalate a delay (30s, 1min, 5min, 15min cap) and
 *   NEVER wipe data.
 * - `unlocked` is in-memory only: process death re-locks, and every
 *   foreground (ON_START) re-evaluates the lock through AutoLockPolicy:
 *   Immediate re-locks at once, and a grace timer re-locks only after
 *   the app stayed backgrounded past it.
 */
@HiltViewModel
class LockViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val encryptedBackup: EncryptedBackup,
    private val statusReporter: StatusReporter,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /**
     * The lock gate is ONE atomic value: [LockState] carries both halves
     * (enabled + unlocked) in a single StateFlow, so the UI can never
     * observe the in-between (enabled=true, unlocked=false) that used to
     * flash the lock screen for a frame when two writers updated two
     * separate flows. Every transition assigns or updates this one flow
     * synchronously; the DataStore write follows fire-and-forget and is
     * never a second writer.
     */
    data class LockState(val enabled: Boolean, val unlocked: Boolean)

    private val _lockState = MutableStateFlow(LockState(enabled = false, unlocked = false))
    /** Single source of truth for the lock gate. */
    val lockState: StateFlow<LockState> = _lockState.asStateFlow()

    /** Serialises enable/disable: a second tap while one runs is dropped. */
    private val lockOpMutex = Mutex()

    private val _lockBusy = MutableStateFlow(false)
    /** True while an enable/disable operation is in flight. */
    val lockBusy: StateFlow<Boolean> = _lockBusy.asStateFlow()

    private val _lockLoaded = MutableStateFlow(false)
    /**
     * True once the persisted lock gate (and the persisted lockout state)
     * have been read. Until then the UI must not draw the library: the
     * gate starts (enabled=false) and a locked library would flash for
     * a frame on cold start while the store answers.
     */
    val lockLoaded: StateFlow<Boolean> = _lockLoaded.asStateFlow()

    private val _pinSet = MutableStateFlow(false)
    /** True when a PIN hash exists (set at least once). */
    val pinSet: StateFlow<Boolean> = _pinSet.asStateFlow()

    private val _biometricEnabled = MutableStateFlow(false)
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    private val _failedAttempts = MutableStateFlow(0)
    /** Wrong PIN attempts since the last success (this lock session). */
    val failedAttempts: StateFlow<Int> = _failedAttempts.asStateFlow()

    private val _lockoutUntilMs = MutableStateFlow(0L)
    /** Epoch ms before which the keypad stays disabled (0 = no lockout). */
    val lockoutUntilMs: StateFlow<Long> = _lockoutUntilMs.asStateFlow()

    private val _emergencyWorking = MutableStateFlow(false)
    val emergencyWorking: StateFlow<Boolean> = _emergencyWorking.asStateFlow()

    private val _emergencyResult = MutableStateFlow<String?>(null)
    /** Plain result line for the last emergency action; null = none yet. */
    val emergencyResult: StateFlow<String?> = _emergencyResult.asStateFlow()

    private val _biometricError = MutableStateFlow<String?>(null)
    /** Last system biometric error text; null = none (keypad is the fallback). */
    val biometricError: StateFlow<String?> = _biometricError.asStateFlow()

    /** True when this device can offer biometric unlock at all. */
    val biometricAvailable: Boolean
        get() = BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS

    /** Auto-lock grace in minutes (0 = Immediate). */
    private val _autoLockMinutes = MutableStateFlow(0)
    val autoLockMinutes: StateFlow<Int> = _autoLockMinutes.asStateFlow()

    /** When the app last went to background (0 = never backgrounded). */
    private var backgroundedAtMs: Long = 0L

    init {
        // One-time load of the persisted gate: unlocked always starts
        // false (process death re-locks). This is the ONLY read of the
        // stored flag — no collector, so DataStore can never overwrite
        // the in-memory state mid-session. The persisted lockout ladder
        // loads with it, so a force-stop cannot reset the countdown;
        // an already-expired lockout is dropped, never honoured stale.
        viewModelScope.launch {
            val enabled = settingsStore.lockEnabled.first()
            val attempts = settingsStore.lockFailedAttempts.first()
            val untilMs = settingsStore.lockLockoutUntilMs.first()
            _failedAttempts.value = attempts
            _lockoutUntilMs.value =
                if (untilMs > System.currentTimeMillis()) untilMs else 0L
            _lockState.value = LockState(enabled = enabled, unlocked = false)
            _lockLoaded.value = true
        }
        viewModelScope.launch {
            settingsStore.lockPinHash.collect { _pinSet.value = it != null }
        }
        viewModelScope.launch {
            settingsStore.lockBiometricEnabled.collect { _biometricEnabled.value = it }
        }
        viewModelScope.launch {
            settingsStore.lockAutoLockMinutes.collect { _autoLockMinutes.value = it }
        }
    }

    /** Called when the app goes to background: start the grace clock. */
    fun onBackgrounded() {
        backgroundedAtMs = System.currentTimeMillis()
    }

    /** Called on foreground: re-lock only when AutoLockPolicy says so. */
    fun onForegrounded() {
        if (AutoLockPolicy.shouldRelock(
                lockEnabled = _lockState.value.enabled,
                autoLockMinutes = _autoLockMinutes.value,
                backgroundedAtMs = backgroundedAtMs,
                nowMs = System.currentTimeMillis()
            )
        ) {
            _lockState.update { it.copy(unlocked = false) }
        }
    }

    fun setAutoLockMinutes(minutes: Int) {
        viewModelScope.launch {
            settingsStore.setLockAutoLockMinutes(minutes)
            _autoLockMinutes.value = minutes
        }
    }

    /** Outcome of an enable attempt (see the backup-mandatory rule). */
    sealed interface EnableResult {
        /** Lock is now on (and the session counts as unlocked). */
        data object Enabled : EnableResult

        /** No PIN exists yet — the UI must run PIN setup first. */
        data object NeedsPin : EnableResult

        /** No backup exists — the UI must show the blocked dialog. */
        data object BlockedNoBackup : EnableResult
    }

    /**
     * Try to enable the lock. Backup-mandatory rule: enabling is refused
     * while no Drive sync, manual export, or picked export document exists.
     */
    fun tryEnableLock(onResult: (EnableResult) -> Unit) {
        viewModelScope.launch {
            if (!lockOpMutex.tryLock()) return@launch
            _lockBusy.value = true
            try {
                if (!_pinSet.value) {
                    onResult(EnableResult.NeedsPin)
                    return@launch
                }
                if (!settingsStore.hasAnyBackup()) {
                    onResult(EnableResult.BlockedNoBackup)
                    return@launch
                }
                // Atomic flip first: the UI sees enabled+unlocked together.
                // The DataStore write follows fire-and-forget.
                _lockState.value = LockState(enabled = true, unlocked = true)
                launch { settingsStore.setLockEnabled(true) }
                onResult(EnableResult.Enabled)
            } finally {
                _lockBusy.value = false
                lockOpMutex.unlock()
            }
        }
    }

    /** Called after a successful PIN setup when the enable flow is pending. */
    fun enableAfterPinSetup(onResult: (EnableResult) -> Unit) = tryEnableLock(onResult)

    fun disableLock() {
        viewModelScope.launch {
            if (!lockOpMutex.tryLock()) return@launch
            _lockBusy.value = true
            try {
                _lockState.value = LockState(enabled = false, unlocked = false)
                _failedAttempts.value = 0
                _lockoutUntilMs.value = 0L
                _biometricError.value = null
                _emergencyResult.value = null
                persistLockout()
                launch { settingsStore.setLockEnabled(false) }
            } finally {
                _lockBusy.value = false
                lockOpMutex.unlock()
            }
        }
    }

    /** Persist the lockout ladder so it survives process death. */
    private fun persistLockout() {
        val attempts = _failedAttempts.value
        val untilMs = _lockoutUntilMs.value
        viewModelScope.launch { settingsStore.setLockoutState(attempts, untilMs) }
    }

    /** Store a new PIN (4 digits, confirmed by the caller). */
    fun setPin(pin: String, onDone: () -> Unit) {
        require(PinHash.isValidPin(pin)) { "PIN must be 4 digits" }
        viewModelScope.launch {
            val salt = PinHash.salt()
            settingsStore.setLockPin(PinHash.hash(pin, salt), salt)
            _pinSet.value = true
            onDone()
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setLockBiometricEnabled(enabled)
            _biometricEnabled.value = enabled
        }
    }

    /** Synchronous PIN check for the keypad: true only on a real match. */
    suspend fun checkPin(pin: String): Boolean {
        if (System.currentTimeMillis() < _lockoutUntilMs.value) return false
        val hash = settingsStore.lockPinHash.first()
        val salt = settingsStore.lockPinSalt.first()
        return if (hash != null && salt != null && PinHash.matches(pin, salt, hash)) {
            _failedAttempts.value = 0
            _lockoutUntilMs.value = 0L
            _biometricError.value = null
            // A fresh unlock starts clean: no stale error or emergency
            // result from the previous lock session carries over.
            _emergencyResult.value = null
            persistLockout()
            _lockState.update { it.copy(unlocked = true) }
            true
        } else {
            val failures = _failedAttempts.value + 1
            _failedAttempts.value = failures
            val delay = LockAttemptPolicy.delayAfterFailures(failures)
            if (delay > 0L) _lockoutUntilMs.value = System.currentTimeMillis() + delay
            persistLockout()
            false
        }
    }

    /**
     * Check a PIN without touching the lock gate: used when the owner
     * changes the PIN in Settings (the current PIN must be proven first).
     * Wrong attempts count on the same ladder as the lock screen, so
     * guessing the current PIN in Settings is no easier than on the
     * lock screen. Never unlocks.
     */
    suspend fun verifyPin(pin: String): Boolean {
        val hash = settingsStore.lockPinHash.first()
        val salt = settingsStore.lockPinSalt.first()
        return if (hash != null && salt != null && PinHash.matches(pin, salt, hash)) {
            _failedAttempts.value = 0
            _lockoutUntilMs.value = 0L
            persistLockout()
            true
        } else {
            val failures = _failedAttempts.value + 1
            _failedAttempts.value = failures
            val delay = LockAttemptPolicy.delayAfterFailures(failures)
            if (delay > 0L) _lockoutUntilMs.value = System.currentTimeMillis() + delay
            persistLockout()
            false
        }
    }

    /** System biometric prompt (fingerprint/face, device-credential fallback). */
    fun requestBiometric(activity: FragmentActivity, onAuthenticated: (() -> Unit)? = null) {
        val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    _failedAttempts.value = 0
                    _lockoutUntilMs.value = 0L
                    _biometricError.value = null
                    _emergencyResult.value = null
                    persistLockout()
                    _lockState.update { it.copy(unlocked = true) }
                    onAuthenticated?.invoke()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // A deliberate cancel (user pressed Cancel or
                    // dismissed the prompt) is not a failure: no toast,
                    // no error state — the keypad simply stays as it is.
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) return
                    // System lockout / hardware error: the PIN keypad
                    // stays as the fallback. No app-level biometric
                    // counter exists.
                    _biometricError.value = errString.toString()
                    statusReporter.show(R.string.status_lock_biometric_failed)
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.lock_biometric_title))
            .setSubtitle(context.getString(R.string.lock_biometric_subtitle))
            .setAllowedAuthenticators(
                // BIOMETRIC_STRONG = fingerprint (Class 3); BIOMETRIC_WEAK
                // also covers face on this phone (Class 2), so the dialog
                // offers fingerprint + face, like WhatsApp. DEVICE_CREDENTIAL
                // is deliberately NOT here: the device PIN must never unlock
                // the app — the app's own PIN is the only PIN, and it is the
                // fallback when the prompt is cancelled or locked out.
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            .build()
        prompt.authenticate(info)
    }

    /** Emergency export: encrypted file into Download/NaatBook. */
    fun emergencyExport() {
        if (_emergencyWorking.value) return
        _emergencyWorking.value = true
        _emergencyResult.value = null
        viewModelScope.launch {
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
                val result = encryptedBackup.exportEncryptedBackup("NaatBook_emergency_$stamp.enc")
                result.onSuccess { label ->
                    _emergencyResult.value =
                        context.getString(R.string.lock_emergency_saved, label)
                    statusReporter.show(R.string.lock_emergency_saved_toast)
                }.onFailure { error ->
                    _emergencyResult.value = context.getString(
                        R.string.lock_emergency_failed,
                        error.message ?: context.getString(R.string.status_error_unknown)
                    )
                }
            } finally {
                _emergencyWorking.value = false
            }
        }
    }

    fun clearBiometricError() {
        _biometricError.value = null
    }
}
