package com.aistudio.mynaatnotebook.data.drive

/** What a sync attempt decided to do with the upload. */
enum class UploadDecision { UPLOAD, SKIP_EMPTY_OVER_EXISTING }

/**
 * The empty-library guard (audit round 2, S1/S2): an empty local library
 * must never silently become the newest Drive backup over a folder that
 * already holds backups — rotation keeps only the newest few, so one
 * empty upload can start the eviction of the user's only real backup,
 * and a later restore (or REPLACE) against the empty file is a data-loss
 * trap. A manual "Sync now" can still force the upload after an explicit
 * confirmation ([forced]); automatic and sign-in syncs never force.
 *
 * Pure and side-effect free so the rule is unit-testable without Drive.
 */
object DriveSyncPolicy {
    fun decideUpload(entryCount: Int, remoteBackupCount: Int, forced: Boolean): UploadDecision =
        if (!forced && entryCount == 0 && remoteBackupCount > 0) {
            UploadDecision.SKIP_EMPTY_OVER_EXISTING
        } else {
            UploadDecision.UPLOAD
        }
}
