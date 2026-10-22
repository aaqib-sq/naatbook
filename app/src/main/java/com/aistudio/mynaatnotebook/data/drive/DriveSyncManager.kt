package com.aistudio.mynaatnotebook.data.drive

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import com.aistudio.mynaatnotebook.R
import com.aistudio.mynaatnotebook.data.BackupManager
import com.aistudio.mynaatnotebook.data.SettingsStore
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * A Drive sync failure with enough structure for the UI to react:
 * reconnect the account, ask for consent, or report "no backup yet".
 */
class DriveException(
    message: String,
    val needsReconnect: Boolean = false,
    val noBackup: Boolean = false,
    val consentIntent: Intent? = null,
    /**
     * Audit fix M3: the LOCALIZED string for the UI. The raw [message] is
     * English (sometimes raw API/IO text) and must never reach a toast —
     * DriveViewModel renders only this resId now, so Urdu mode never
     * shows raw English errors.
     */
    @StringRes val resId: Int = R.string.status_error_unknown
) : IOException(message)

/** One backup file stored in the Drive folder. */
data class DriveBackupInfo(val fileId: String, val name: String, val createdAtMs: Long?)

/**
 * Entry count encoded in a Drive backup file name ("...-n42.zip", written
 * by sync since the count-in-name change); null for older names.
 */
fun driveBackupEntryCount(fileName: String): Int? =
    Regex("""-n(\d+)\.zip$""").find(fileName)?.groupValues?.get(1)?.toIntOrNull()

/** Outcome of a sync attempt (audit round 2, S1/S2). */
sealed interface SyncOutcome {
    /** A backup was uploaded; [count] entries went up. */
    data class Uploaded(val count: Int) : SyncOutcome

    /**
     * Nothing was uploaded: the local library is empty and Drive already
     * holds backups, so an empty upload would have endangered them.
     */
    data object SkippedEmpty : SyncOutcome
}

private class DriveHttpException(val code: Int, message: String) : IOException(message)

/**
 * Google Drive sync for the same backup ZIP the local export produces.
 *
 * Sign-in is the system Google account picker (play-services-auth); file
 * access uses the Drive REST API directly with an OAuth token for the
 * drive.file scope, so backups land in a normal, user-visible folder the
 * user can inspect in their own Drive app. Only the newest [KEEP_BACKUPS]
 * backups are kept; older ones are deleted after each successful upload.
 */
@Singleton
class DriveSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
    private val settingsStore: SettingsStore
) {
    companion object {
        const val FOLDER_NAME = "NaatBook Backup"
        /**
         * Audit fix (stage 1): a Drive restore downloads the newest backup
         * here in app-private cache; [com.aistudio.mynaatnotebook.viewmodel.BackupViewModel]
         * deletes it once the import no longer needs it (success or failure).
         */
        const val RESTORE_ZIP_NAME = "drive-restore.zip"
        const val BACKUP_FILE_PREFIX = "naatbook-backup-"
        private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val TOKEN_SCOPE = "oauth2:$DRIVE_FILE_SCOPE"
        private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val CHUNK_SIZE = 1_048_576 // 1 MiB; a multiple of 256 KiB, as resumable uploads require
        // Audit 02 M2: 2 was too thin a window — one bad newest backup
        // could evict the last good one before a restore noticed.
        private const val KEEP_BACKUPS = 3
        private const val BUFFER_SIZE = 32 * 1024
    }

    fun signInClient(): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_FILE_SCOPE))
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    fun signedInAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    /** Local sign-out only; backups already in Drive are untouched. */
    fun signOut() {
        runCatching { signInClient().signOut() }
    }

    /** Fingerprint of the current library, for change detection. */
    suspend fun contentHash(): String = backupManager.contentHash()

    /**
     * Build the backup ZIP and upload it to Drive, then prune old backups
     * and record the sync bookkeeping.
     *
     * Empty-library guard (audit round 2, S1/S2): when the local library
     * has 0 entries and the Drive folder already holds backups, the upload
     * is skipped ([SyncOutcome.SkippedEmpty]) unless [forced] — an empty
     * upload must never become the newest backup over real ones, because
     * rotation keeps only the newest [KEEP_BACKUPS] and a restore against
     * the empty file reads as "0 entries" (REPLACE would wipe the phone).
     */
    suspend fun syncNow(
        onProgress: (Float) -> Unit,
        forced: Boolean = false
    ): Result<SyncOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            withApi { token ->
                val folderId = ensureFolder(token)
                val hash = backupManager.contentHash()
                val staged = File(context.cacheDir, "drive-sync-upload.zip")
                try {
                    val count = backupManager.exportBackupToFile(staged) { p ->
                        onProgress(p * 0.3f)
                    }.getOrThrow()
                    // The entry count rides in the file name so an empty
                    // backup is recognizable at a glance in the Drive app.
                    val name = BACKUP_FILE_PREFIX +
                        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
                        "-n$count.zip"
                    val existing = listBackups(token, folderId)
                    if (DriveSyncPolicy.decideUpload(count, existing.size, forced) ==
                        UploadDecision.SKIP_EMPTY_OVER_EXISTING
                    ) {
                        SyncOutcome.SkippedEmpty
                    } else {
                        var uploadedId: String? = null
                        try {
                            uploadedId = uploadBackup(token, folderId, staged, name) { p ->
                                onProgress(0.3f + p * 0.7f)
                            }
                            // Audit 02 M2: verify the bytes Drive stored
                            // match the local zip before rotation trusts
                            // this upload as the newest good backup. A
                            // mismatch deletes the bad copy and fails the
                            // sync, so older backups stay untouched.
                            val newId = uploadedId
                            if (newId != null) {
                                val remoteMd5 = runCatching { remoteMd5(token, newId) }.getOrNull()
                                if (remoteMd5 != null &&
                                    !remoteMd5.equals(md5Hex(staged), ignoreCase = true)
                                ) {
                                    runCatching { deleteFile(token, newId) }
                                    throw DriveException(
                                        "Upload verification failed — please sync again",
                                        resId = R.string.status_drive_error_verify
                                    )
                                }
                            }
                            // Rotation: keep only the newest backups. An empty
                            // upload (only possible when forced, or as the very
                            // first backup) never evicts existing backups.
                            if (count > 0) {
                                listBackups(token, folderId).drop(KEEP_BACKUPS).forEach { old ->
                                    currentCoroutineContext().ensureActive()
                                    runCatching { deleteFile(token, old.fileId) }
                                }
                            }
                            settingsStore.recordDriveSync(System.currentTimeMillis(), hash)
                            SyncOutcome.Uploaded(count)
                        } catch (e: CancellationException) {
                        // The cancel raced a completed upload: remove the orphan so
                        // Drive keeps only acknowledged syncs, and don't record a
                        // sync the user stopped. Best-effort and non-cancellable;
                        // the cancellation itself surfaces unchanged.
                        uploadedId?.let { id ->
                            runCatching {
                                withContext(NonCancellable) { deleteFile(token, id) }
                            }
                        }
                        throw e
                        }
                    }
                } finally {
                    staged.delete()
                }
            }
        }
    }

    /**
     * Metadata of the backups currently in the Drive folder (newest first);
     * an absent folder reads as an empty list. Cheap — no file downloads.
     * Used to decide whether a sign-in should upload anything at all.
     */
    suspend fun remoteBackups(): Result<List<DriveBackupInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            withApi { token ->
                val folderId = findFolder(token)
                if (folderId == null) emptyList() else listBackups(token, folderId)
            }
        }
    }

    /** Download the newest Drive backup into the cache dir for restore. */
    suspend fun downloadLatest(onProgress: (Float) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                withApi { token ->
                    val folderId = findFolder(token)
                        ?: throw DriveException("No backup found in Drive", noBackup = true)
                    val newest = listBackups(token, folderId).firstOrNull()
                        ?: throw DriveException("No backup found in Drive", noBackup = true)
                    val target = File(context.cacheDir, RESTORE_ZIP_NAME)
                    downloadFile(token, newest.fileId, target, onProgress)
                    target
                }
            }
        }

    /** Download one chosen Drive backup into the cache dir for restore. */
    suspend fun downloadBackup(fileId: String, onProgress: (Float) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                withApi { token ->
                    val target = File(context.cacheDir, RESTORE_ZIP_NAME)
                    downloadFile(token, fileId, target, onProgress)
                    target
                }
            }
        }

    // --- Auth plumbing -------------------------------------------------

    private suspend fun accessToken(account: GoogleSignInAccount): String =
        withContext(Dispatchers.IO) {
            val acct = account.account
                ?: throw DriveException("Google account unavailable", needsReconnect = true)
            try {
                GoogleAuthUtil.getToken(context, acct, TOKEN_SCOPE)
            } catch (e: UserRecoverableAuthException) {
                throw DriveException("Google permission needed", consentIntent = e.intent)
            } catch (e: IOException) {
                throw DriveException(
                    e.message ?: "Network error",
                    resId = R.string.status_drive_error_network
                )
            } catch (e: Exception) {
                throw DriveException(
                    e.message ?: "Could not get Google permission", needsReconnect = true
                )
            }
        }

    /**
     * Run [block] with a valid token. A 401 gets exactly one retry with a
     * fresh token (the cached one may have been revoked or expired); a
     * second 401 means the connection itself is broken.
     */
    private suspend fun <T> withApi(block: suspend (String) -> T): T {
        val account = signedInAccount()
            ?: throw DriveException("Not connected", needsReconnect = true)
        var token = accessToken(account)
        try {
            return block(token)
        } catch (e: DriveHttpException) {
            if (e.code != 401) throw DriveException(
                e.message ?: "Drive error ${e.code}",
                resId = R.string.status_drive_error_generic
            )
        }
        runCatching { GoogleAuthUtil.invalidateToken(context, token) }
        token = accessToken(account)
        try {
            return block(token)
        } catch (e: DriveHttpException) {
            if (e.code == 401) {
                throw DriveException("Session expired — connect again", needsReconnect = true)
            }
            throw DriveException(
                e.message ?: "Drive error ${e.code}",
                resId = R.string.status_drive_error_generic
            )
        }
    }

    // --- Drive REST ----------------------------------------------------

    private fun openConnection(url: String, token: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        return conn
    }

    private fun errorMessage(conn: HttpURLConnection): String {
        val body = runCatching {
            conn.errorStream?.bufferedReader()?.use { it.readText() }
        }.getOrNull().orEmpty()
        val parsed = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return parsed?.takeIf { it.isNotBlank() } ?: "Google Drive error (${conn.responseCode})"
    }

    private fun checkedBody(conn: HttpURLConnection): String {
        val code = conn.responseCode
        if (code !in 200..299) throw DriveHttpException(code, errorMessage(conn))
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun findFolder(token: String): String? {
        val query = URLEncoder.encode(
            "name = '$FOLDER_NAME' and mimeType = '$FOLDER_MIME' and trashed = false", "UTF-8"
        )
        val conn = openConnection("$FILES_URL?q=$query&fields=files(id,name)&spaces=drive", token, "GET")
        val files = JSONObject(checkedBody(conn)).optJSONArray("files") ?: return null
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun createFolder(token: String): String {
        val conn = openConnection(FILES_URL, token, "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        val meta = JSONObject().put("name", FOLDER_NAME).put("mimeType", FOLDER_MIME)
        conn.outputStream.use { it.write(meta.toString().toByteArray(Charsets.UTF_8)) }
        return JSONObject(checkedBody(conn)).getString("id")
    }

    private fun ensureFolder(token: String): String = findFolder(token) ?: createFolder(token)

    private fun listBackups(token: String, folderId: String): List<DriveBackupInfo> {
        val query = URLEncoder.encode(
            "'$folderId' in parents and name contains '$BACKUP_FILE_PREFIX' and trashed = false",
            "UTF-8"
        )
        val conn = openConnection(
            "$FILES_URL?q=$query&fields=files(id,name,createdTime)&orderBy=createdTime%20desc",
            token, "GET"
        )
        val files = JSONObject(checkedBody(conn)).optJSONArray("files") ?: return emptyList()
        return buildList {
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                add(
                    DriveBackupInfo(
                        file.getString("id"),
                        file.getString("name"),
                        parseDriveTime(file.optString("createdTime"))
                    )
                )
            }
        }
    }

    private fun parseDriveTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val normalized = value.replace("Z", "+00:00")
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")) {
            try {
                return SimpleDateFormat(pattern, Locale.US).parse(normalized)?.time
            } catch (e: Exception) {
                // Try the next pattern.
            }
        }
        return null
    }

    /**
     * Runs a blocking [HttpURLConnection] exchange (socket writes/reads) on a
     * short-lived daemon worker thread, so coroutine cancellation tears the
     * socket down promptly: [invokeOnCancellation] calls
     * [HttpURLConnection.disconnect], the blocked worker wakes with an
     * [IOException], and the cancellation — not the socket error — is what the
     * caller sees. [HttpURLConnection] otherwise ignores coroutine
     * cancellation and thread interrupts, so a Cancel tap mid-transfer would
     * have no effect without this.
     */
    private suspend fun <T> cancellableExchange(
        conn: HttpURLConnection,
        block: (HttpURLConnection) -> T
    ): T = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { conn.disconnect() }
        thread(name = "drive-http", isDaemon = true) {
            try {
                cont.resumeWith(Result.success(block(conn)))
            } catch (t: Throwable) {
                // If the continuation was already cancelled, invokeOnCancellation
                // tore the socket down; the worker's socket error must not mask
                // the CancellationException the caller is about to receive
                // (resume/resumeWithException on a cancelled continuation drop
                // the result instead of delivering it).
                cont.resumeWith(Result.failure(t))
            }
        }
    }

    /**
     * Resumable upload in 1 MiB chunks: a dropped connection loses at most
     * one chunk, and the server-acknowledged Range header re-syncs the
     * offset if a chunk was only partly received.
     */
    private suspend fun uploadBackup(
        token: String,
        folderId: String,
        file: File,
        fileName: String,
        onProgress: (Float) -> Unit
    ): String {
        val init = openConnection("$UPLOAD_URL?uploadType=resumable", token, "POST")
        init.doOutput = true
        init.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        init.setRequestProperty("X-Upload-Content-Type", "application/zip")
        init.setRequestProperty("X-Upload-Content-Length", file.length().toString())
        val meta = JSONObject()
            .put("name", fileName)
            .put("parents", JSONArray().put(folderId))
        val initCode = cancellableExchange(init) { c ->
            c.outputStream.use { it.write(meta.toString().toByteArray(Charsets.UTF_8)) }
            c.responseCode
        }
        currentCoroutineContext().ensureActive()
        if (initCode !in 200..299) throw DriveHttpException(initCode, errorMessage(init))
        val sessionUri = init.getHeaderField("Location")
            ?: throw DriveException(
                "Drive did not start the upload",
                resId = R.string.status_drive_error_upload
            )

        val total = file.length()
        var offset = 0L
        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(CHUNK_SIZE)
            while (offset < total) {
                // Audit TOP 4: cooperative cancellation — a cancelled sync
                // stops between chunks instead of finishing the upload.
                currentCoroutineContext().ensureActive()
                raf.seek(offset)
                val want = minOf(CHUNK_SIZE.toLong(), total - offset).toInt()
                var read = 0
                while (read < want) {
                    val n = raf.read(buffer, read, want - read)
                    if (n < 0) break
                    read += n
                }
                if (read == 0) throw DriveException(
                    "Could not read the backup file",
                    resId = R.string.status_drive_error_upload
                )
                val conn = openConnection(sessionUri, token, "PUT")
                conn.doOutput = true
                conn.setRequestProperty("Content-Length", read.toString())
                conn.setRequestProperty(
                    "Content-Range", "bytes $offset-${offset + read - 1}/$total"
                )
                val code = cancellableExchange(conn) { c ->
                    c.outputStream.use { it.write(buffer, 0, read) }
                    c.responseCode
                }
                currentCoroutineContext().ensureActive()
                when (code) {
                    200, 201 -> {
                        onProgress(1f)
                        return JSONObject(checkedBody(conn)).getString("id")
                    }
                    308 -> {
                        val acked = conn.getHeaderField("Range")
                            ?.substringAfterLast('-')?.toLongOrNull()?.plus(1)
                        offset = acked ?: (offset + read)
                        onProgress(if (total > 0) offset.toFloat() / total else 0f)
                    }
                    else -> throw DriveHttpException(code, errorMessage(conn))
                }
            }
        }
        // All bytes sent but no completion response seen: ask the session.
        val status = openConnection(sessionUri, token, "PUT")
        status.doOutput = true
        status.setRequestProperty("Content-Length", "0")
        status.setRequestProperty("Content-Range", "bytes */$total")
        val statusCode = cancellableExchange(status) { c -> c.responseCode }
        currentCoroutineContext().ensureActive()
        if (statusCode == 200 || statusCode == 201) {
            onProgress(1f)
            return JSONObject(checkedBody(status)).getString("id")
        }
        throw DriveException(
            "Upload did not complete",
            resId = R.string.status_drive_error_upload
        )
    }

    private suspend fun downloadFile(
        token: String,
        fileId: String,
        target: File,
        onProgress: (Float) -> Unit
    ) {
        val conn = openConnection("$FILES_URL/$fileId?alt=media", token, "GET")
        val code = cancellableExchange(conn) { c -> c.responseCode }
        if (code !in 200..299) throw DriveHttpException(code, errorMessage(conn))
        val total = conn.contentLengthLong
        try {
            FileOutputStream(target).use { output ->
                cancellableExchange(conn) { c ->
                    c.inputStream.use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            copied += n
                            if (total > 0) onProgress(copied.toFloat() / total)
                        }
                    }
                }
                output.fd.sync()
            }
        } catch (t: Throwable) {
            // Audit fix M4: don't leave a half-downloaded restore file
            // behind on ANY failure — the old code only cleaned up on
            // cancellation, so a mid-download network error stranded the
            // partial drive-restore.zip in cache. The error still
            // propagates, so cancellation semantics are unchanged.
            runCatching { target.delete() }
            throw t
        }
        onProgress(1f)
    }

    /** MD5 of a Drive file's stored bytes, as reported by the API. */
    private fun remoteMd5(token: String, fileId: String): String? {
        val conn = openConnection("$FILES_URL/$fileId?fields=md5Checksum", token, "GET")
        return JSONObject(checkedBody(conn)).optString("md5Checksum")
            .takeIf { it.isNotBlank() }
    }

    private fun md5Hex(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun deleteFile(token: String, fileId: String) {
        val conn = openConnection("$FILES_URL/$fileId", token, "DELETE")
        val code = conn.responseCode
        if (code !in 200..299 && code != 404) throw DriveHttpException(code, errorMessage(conn))
    }
}
