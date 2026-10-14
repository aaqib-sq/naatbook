package com.aistudio.mynaatnotebook.data.drive

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A Drive sync failure with enough structure for the UI to react:
 * reconnect the account, ask for consent, or report "no backup yet".
 */
class DriveException(
    message: String,
    val needsReconnect: Boolean = false,
    val noBackup: Boolean = false,
    val consentIntent: Intent? = null
) : IOException(message)

/** One backup file stored in the Drive folder. */
data class DriveBackupInfo(val fileId: String, val name: String, val createdAtMs: Long?)

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
        const val BACKUP_FILE_PREFIX = "naatbook-backup-"
        private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val TOKEN_SCOPE = "oauth2:$DRIVE_FILE_SCOPE"
        private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val CHUNK_SIZE = 1_048_576 // 1 MiB; a multiple of 256 KiB, as resumable uploads require
        private const val KEEP_BACKUPS = 2
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
     * and record the sync bookkeeping. Returns the exported entry count.
     */
    suspend fun syncNow(onProgress: (Float) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            withApi { token ->
                val folderId = ensureFolder(token)
                val hash = backupManager.contentHash()
                val staged = File(context.cacheDir, "drive-sync-upload.zip")
                try {
                    val count = backupManager.exportBackupToFile(staged) { p ->
                        onProgress(p * 0.3f)
                    }.getOrThrow()
                    val name = BACKUP_FILE_PREFIX +
                        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
                    uploadBackup(token, folderId, staged, name) { p ->
                        onProgress(0.3f + p * 0.7f)
                    }
                    // Rotation: keep only the newest backups.
                    listBackups(token, folderId).drop(KEEP_BACKUPS).forEach { old ->
                        runCatching { deleteFile(token, old.fileId) }
                    }
                    settingsStore.recordDriveSync(System.currentTimeMillis(), hash)
                    count
                } finally {
                    staged.delete()
                }
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
                    val target = File(context.cacheDir, "drive-restore.zip")
                    downloadFile(token, newest.fileId, target, onProgress)
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
                throw DriveException(e.message ?: "Network error")
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
            if (e.code != 401) throw DriveException(e.message ?: "Drive error ${e.code}")
        }
        runCatching { GoogleAuthUtil.invalidateToken(context, token) }
        token = accessToken(account)
        try {
            return block(token)
        } catch (e: DriveHttpException) {
            if (e.code == 401) {
                throw DriveException("Session expired — connect again", needsReconnect = true)
            }
            throw DriveException(e.message ?: "Drive error ${e.code}")
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
     * Resumable upload in 1 MiB chunks: a dropped connection loses at most
     * one chunk, and the server-acknowledged Range header re-syncs the
     * offset if a chunk was only partly received.
     */
    private fun uploadBackup(
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
        init.outputStream.use { it.write(meta.toString().toByteArray(Charsets.UTF_8)) }
        val initCode = init.responseCode
        if (initCode !in 200..299) throw DriveHttpException(initCode, errorMessage(init))
        val sessionUri = init.getHeaderField("Location")
            ?: throw DriveException("Drive did not start the upload")

        val total = file.length()
        var offset = 0L
        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(CHUNK_SIZE)
            while (offset < total) {
                raf.seek(offset)
                val want = minOf(CHUNK_SIZE.toLong(), total - offset).toInt()
                var read = 0
                while (read < want) {
                    val n = raf.read(buffer, read, want - read)
                    if (n < 0) break
                    read += n
                }
                if (read == 0) throw DriveException("Could not read the backup file")
                val conn = openConnection(sessionUri, token, "PUT")
                conn.doOutput = true
                conn.setRequestProperty("Content-Length", read.toString())
                conn.setRequestProperty(
                    "Content-Range", "bytes $offset-${offset + read - 1}/$total"
                )
                conn.outputStream.use { it.write(buffer, 0, read) }
                when (val code = conn.responseCode) {
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
        val statusCode = status.responseCode
        if (statusCode == 200 || statusCode == 201) {
            onProgress(1f)
            return JSONObject(checkedBody(status)).getString("id")
        }
        throw DriveException("Upload did not complete")
    }

    private fun downloadFile(
        token: String,
        fileId: String,
        target: File,
        onProgress: (Float) -> Unit
    ) {
        val conn = openConnection("$FILES_URL/$fileId?alt=media", token, "GET")
        val code = conn.responseCode
        if (code !in 200..299) throw DriveHttpException(code, errorMessage(conn))
        val total = conn.contentLengthLong
        FileOutputStream(target).use { output ->
            conn.inputStream.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    copied += n
                    if (total > 0) onProgress(copied.toFloat() / total)
                }
                output.fd.sync()
            }
        }
        onProgress(1f)
    }

    private fun deleteFile(token: String, fileId: String) {
        val conn = openConnection("$FILES_URL/$fileId", token, "DELETE")
        val code = conn.responseCode
        if (code !in 200..299 && code != 404) throw DriveHttpException(code, errorMessage(conn))
    }
}
