package com.aistudio.mynaatnotebook.data.drive

/** What a sync attempt decided to do with the upload. */
enum class UploadDecision { UPLOAD, SKIP_EMPTY_OVER_EXISTING }

/**
 * The empty-library guard (audit round 2, S1/S2; hardened by the
 * nine-fixes round, flaw 2): an empty local library must NEVER become a
 * Drive backup. The old "forced" escape — a manual "Sync now" confirming
 * past the guard — is closed: an empty upload could evict the user's
 * only real backups through rotation, and no confirmation makes that
 * safe. An empty library simply has nothing to back up.
 *
 * Pure and side-effect free so the rule is unit-testable without Drive.
 */
object DriveSyncPolicy {
    fun decideUpload(entryCount: Int): UploadDecision =
        if (entryCount == 0) {
            UploadDecision.SKIP_EMPTY_OVER_EXISTING
        } else {
            UploadDecision.UPLOAD
        }
}
