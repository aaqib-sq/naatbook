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
import kotlinx.coroutines.launch

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
 *   Immediate re-locks at once, a grace timer re-locks only after the
 *   app stayed backgrounded past it, and a device-lock/unlock cycle
 *   re-locks regardless of the timer when that toggle is on (default).
 */
@HiltViewModel
class LockViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val encryptedBackup: EncryptedBackup,
    private val statusReporter: StatusReporter,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _lockEnabled = MutableStateFlow(false)
    val lockEnabled: StateFlow<Boolean> = _lockEnabled.asStateFlow()

    private val _pinSet = MutableStateFlow(false)
    /** True when a PIN hash exists (set at least once). */
    val pinSet: StateFlow<Boolean> = _pinSet.asStateFlow()

    private val _biometricEnabled = MutableStateFlow(false)
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    private val _unlocked = MutableStateFlow(false)
    /** In-memory session flag: true only between an unlock and the next foreground. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

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

    /** Last backup moment (Drive sync or manual export), 0 = never. */
    private val _lastBackupAtMs = MutableStateFlow(0L)
    val lastBackupAtMs: StateFlow<Long> = _lastBackupAtMs.asStateFlow()

    /** True when the last backup is older than 7 days (or never happened). */
    private val _isBackupStale = MutableStateFlow(true)
    val isBackupStale: StateFlow<Boolean> = _isBackupStale.asStateFlow()

    /** True when this device can offer biometric unlock at all. */
    val biometricAvailable: Boolean
        get() = BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS

    /** Auto-lock grace in minutes (0 = Immediate). */
    private val _autoLockMinutes = MutableStateFlow(0)
    val autoLockMinutes: StateFlow<Int> = _autoLockMinutes.asStateFlow()

    /** Re-lock after a device-lock/unlock cycle (default true). */
    private val _lockOnDeviceUnlock = MutableStateFlow(true)
    val lockOnDeviceUnlock: StateFlow<Boolean> = _lockOnDeviceUnlock.asStateFlow()

    /** When the app last went to background (0 = never backgrounded). */
    private var backgroundedAtMs: Long = 0L

    /** True when the phone was unlocked after the app went to background. */
    private var deviceUnlockedSinceBackground: Boolean = false

    init {
        viewModelScope.launch { settingsStore.lockEnabled.collect { _lockEnabled.value = it } }
        viewModelScope.launch {
            settingsStore.lockPinHash.collect { _pinSet.value = it != null }
        }
        viewModelScope.launch {
            settingsStore.lockBiometricEnabled.collect { _biometricEnabled.value = it }
        }
        viewModelScope.launch {
            settingsStore.lockAutoLockMinutes.collect { _autoLockMinutes.value = it }
        }
        viewModelScope.launch {
            settingsStore.lockOnDeviceUnlock.collect { _lockOnDeviceUnlock.value = it }
        }
        viewModelScope.launch {
            settingsStore.lastAnyBackupAt.collect { at ->
                _lastBackupAtMs.value = at
                _isBackupStale.value = at <= 0L ||
                    System.currentTimeMillis() - at > 7L * 24L * 60L * 60L * 1000L
            }
        }
    }

    /** Re-lock on foreground. No-op when the lock is off. */
    fun relock() {
        if (_lockEnabled.value) _unlocked.value = false
    }

    /** Called when the app goes to background: start the grace clock. */
    fun onBackgrounded() {
        backgroundedAtMs = System.currentTimeMillis()
        deviceUnlockedSinceBackground = false
    }

    /** Called when the phone is unlocked while the app is backgrounded. */
    fun onDeviceUnlocked() {
        deviceUnlockedSinceBackground = true
    }

    /** Called on foreground: re-lock only when AutoLockPolicy says so. */
    fun onForegrounded() {
        if (AutoLockPolicy.shouldRelock(
                lockEnabled = _lockEnabled.value,
                lockOnDeviceUnlock = _lockOnDeviceUnlock.value,
                deviceUnlockedSinceBackground = deviceUnlockedSinceBackground,
                autoLockMinutes = _autoLockMinutes.value,
                backgroundedAtMs = backgroundedAtMs,
                nowMs = System.currentTimeMillis()
            )
        ) {
            _unlocked.value = false
        }
    }

    fun setAutoLockMinutes(minutes: Int) {
        viewModelScope.launch {
            settingsStore.setLockAutoLockMinutes(minutes)
            _autoLockMinutes.value = minutes
        }
    }

    fun setLockOnDeviceUnlock(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setLockOnDeviceUnlock(enabled)
            _lockOnDeviceUnlock.value = enabled
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
            if (!_pinSet.value) {
                onResult(EnableResult.NeedsPin)
                return@launch
            }
            if (!settingsStore.hasAnyBackup()) {
                onResult(EnableResult.BlockedNoBackup)
                return@launch
            }
            settingsStore.setLockEnabled(true)
            _lockEnabled.value = true
            _unlocked.value = true
            onResult(EnableResult.Enabled)
        }
    }

    /** Called after a successful PIN setup when the enable flow is pending. */
    fun enableAfterPinSetup(onResult: (EnableResult) -> Unit) = tryEnableLock(onResult)

    fun disableLock() {
        viewModelScope.launch {
            settingsStore.setLockEnabled(false)
            _lockEnabled.value = false
            _unlocked.value = false
            _failedAttempts.value = 0
            _lockoutUntilMs.value = 0L
        }
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
            _unlocked.value = true
            true
        } else {
            val failures = _failedAttempts.value + 1
            _failedAttempts.value = failures
            val delay = LockAttemptPolicy.delayAfterFailures(failures)
            if (delay > 0L) _lockoutUntilMs.value = System.currentTimeMillis() + delay
            false
        }
    }

    /** System biometric prompt (fingerprint/face, device-credential fallback). */
    fun requestBiometric(activity: FragmentActivity) {
        val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    _failedAttempts.value = 0
                    _lockoutUntilMs.value = 0L
                    _biometricError.value = null
                    _unlocked.value = true
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // System lockout / cancel: the PIN keypad stays as the
                    // fallback. No app-level biometric counter exists.
                    _biometricError.value = errString.toString()
                    statusReporter.show(R.string.status_lock_biometric_failed)
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.lock_biometric_title))
            .setSubtitle(context.getString(R.string.lock_biometric_subtitle))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
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

    fun clearEmergencyResult() {
        _emergencyResult.value = null
    }

    fun clearBiometricError() {
        _biometricError.value = null
    }
}
