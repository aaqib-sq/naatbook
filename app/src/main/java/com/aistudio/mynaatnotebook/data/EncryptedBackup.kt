package com.aistudio.mynaatnotebook.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Emergency encrypted backup for the app-lock lockout screen.
 *
 * Why this exists: when every unlock method fails (PIN forgotten, biometric
 * unavailable, no security question), the only recovery is clearing app data
 * and restoring — which destroys any entries never backed up. The lockout
 * screen therefore offers to save the CURRENT library first, BEFORE any wipe.
 *
 * Privacy shape (owner decisions, 2026-10-30):
 * - The file is AES/GCM-encrypted, never plaintext: a plaintext file in
 *   shared storage would let anyone holding the phone read the notebook,
 *   defeating the lock itself.
 * - The key is app+device-bound (Android Keystore, this app's UID, this
 *   device only). No other app, file manager, or phone can use it, and the
 *   key is never written into the file or any backup.
 * - The key requires user authentication, satisfied by a device unlock
 *   within the last 5 minutes — which is exactly the state the owner is in
 *   when pressing the button (the phone is already unlocked to reach the
 *   app). No extra prompt is needed for the emergency flow.
 * - Honest limits, stated here so nobody over-promises: the key survives an
 *   app data-clear (Keystore entries live outside app data) but is DELETED
 *   on uninstall and on factory reset — an emergency file is then
 *   unrecoverable forever. Drive backup remains the primary safety net.
 *   A data-clear + import on an unlocked phone is outside the threat model:
 *   the phone lock is the primary gate; the app lock stops casual browsing.
 */
@Singleton
class EncryptedBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: BackupManager
) {
    companion object {
        private const val KEY_ALIAS = "naatbook_emergency_key"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val AUTH_VALIDITY_SECONDS = 300
        const val EMERGENCY_EXTENSION = "enc"
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
            .setInvalidatedByBiometricEnrollment(false)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }
            .generateKey()
    }

    /** Encrypt [plain] as IV || ciphertext (GCM tag appended by the cipher). */
    fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return iv + cipher.doFinal(plain)
    }

    /** Decrypt an [encrypt] output back to the original bytes. */
    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "Emergency file too short" }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val cipherText = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(cipherText)
    }

    /**
     * Stage a full backup ZIP, encrypt it, and publish it as
     * Download/NaatBook/<fileName>.enc via MediaStore — the same shared
     * folder the normal export uses, so the file survives an app
     * data-clear. Returns the display label on success.
     * Android 10+ only (same gate as the normal app-folder export).
     */
    suspend fun exportEncryptedBackup(fileName: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "The emergency file needs Android 10 or newer — use Drive sync instead"
                }
                val stagedZip = File(context.cacheDir, "emergency-${UUID.randomUUID()}.zip")
                var pendingUri: Uri? = null
                try {
                    backupManager.exportBackupToFile(stagedZip).getOrThrow()
                    val encrypted = encrypt(stagedZip.readBytes())
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            backupManager.appFolderRelativePath
                        )
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    ) ?: throw IOException("Could not create the emergency file in Downloads")
                    pendingUri = uri
                    val written = context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(encrypted)
                        encrypted.size
                    } ?: throw IOException("Could not write the emergency file")
                    check(written > 0) { "Could not write the emergency file" }
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply {
                            put(MediaStore.Downloads.IS_PENDING, 0)
                        },
                        null,
                        null
                    )
                    pendingUri = null
                    "Download/NaatBook/$fileName"
                } finally {
                    stagedZip.delete()
                    pendingUri?.let { uri ->
                        runCatching { context.contentResolver.delete(uri, null, null) }
                    }
                }
            }
        }

    /**
     * Decrypt a picked .enc emergency file into a temp ZIP in cacheDir for
     * the normal import path. The caller deletes the temp file when done.
     */
    suspend fun decryptToTempZip(uri: Uri): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val blob = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IOException("Could not read the emergency file")
                val plain = decrypt(blob)
                val temp = File(context.cacheDir, "emergency-import-${UUID.randomUUID()}.zip")
                temp.writeBytes(plain)
                temp
            }
        }
}

/** Pure PIN helpers: the PIN itself is never stored, only this salted hash. */
object PinHash {
    fun salt(): String = ByteArray(16)
        .also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    fun hash(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$salt:$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun matches(pin: String, salt: String, expectedHash: String): Boolean =
        hash(pin, salt) == expectedHash

    fun isValidPin(pin: String): Boolean =
        pin.length == 4 && pin.all { it.isDigit() }
}

/**
 * Escalating delay after wrong PIN attempts. Pure function, unit-tested.
 * No auto-wipe ever: the delay only grows, data is never deleted for
 * wrong attempts (owner decision — a wipe would be catastrophic).
 */
object LockAttemptPolicy {
    /** Delay in ms before the next PIN attempt is allowed. */
    fun delayAfterFailures(failures: Int): Long = when {
        failures < 3 -> 0L
        failures == 3 -> 30_000L
        failures == 4 -> 60_000L
        failures == 5 -> 300_000L
        else -> 900_000L
    }
}

/**
 * Auto-lock decision on returning to the foreground. Pure function,
 * unit-tested. The app re-locks when any of these hold:
 * - the lock is off (never: there is nothing to re-lock — returns false),
 * - a device-lock/unlock cycle happened since backgrounding and the
 *   "lock on device unlock" toggle is on,
 * - the timer is Immediate (0 minutes),
 * - the app stayed backgrounded at least the chosen grace period.
 * Otherwise (still inside the grace period, no device-unlock cycle) the
 * session stays unlocked.
 */
object AutoLockPolicy {
    /** True when the app must show the lock screen on this foreground. */
    fun shouldRelock(
        lockEnabled: Boolean,
        lockOnDeviceUnlock: Boolean,
        deviceUnlockedSinceBackground: Boolean,
        autoLockMinutes: Int,
        backgroundedAtMs: Long,
        nowMs: Long
    ): Boolean {
        if (!lockEnabled) return false
        if (lockOnDeviceUnlock && deviceUnlockedSinceBackground) return true
        if (autoLockMinutes <= 0) return true
        if (backgroundedAtMs <= 0L) return true
        return nowMs - backgroundedAtMs >= autoLockMinutes * 60_000L
    }
}
