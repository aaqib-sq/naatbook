package com.aistudio.mynaatnotebook.data

import com.aistudio.mynaatnotebook.data.drive.DriveSyncPolicy
import com.aistudio.mynaatnotebook.data.drive.UploadDecision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The empty-library guard (audit round 2, S1/S2): an empty library must
 * never silently become the newest Drive backup over existing backups.
 */
class DriveSyncPolicyTest {

    @Test
    fun `empty library over existing backups is skipped`() {
        assertEquals(
            UploadDecision.SKIP_EMPTY_OVER_EXISTING,
            DriveSyncPolicy.decideUpload(entryCount = 0, remoteBackupCount = 1, forced = false)
        )
        assertEquals(
            UploadDecision.SKIP_EMPTY_OVER_EXISTING,
            DriveSyncPolicy.decideUpload(entryCount = 0, remoteBackupCount = 2, forced = false)
        )
    }

    @Test
    fun `empty library into an empty folder still uploads - the genuine first backup`() {
        assertEquals(
            UploadDecision.UPLOAD,
            DriveSyncPolicy.decideUpload(entryCount = 0, remoteBackupCount = 0, forced = false)
        )
    }

    @Test
    fun `non-empty library always uploads`() {
        assertEquals(
            UploadDecision.UPLOAD,
            DriveSyncPolicy.decideUpload(entryCount = 1, remoteBackupCount = 2, forced = false)
        )
        assertEquals(
            UploadDecision.UPLOAD,
            DriveSyncPolicy.decideUpload(entryCount = 42, remoteBackupCount = 0, forced = false)
        )
    }

    @Test
    fun `forced upload overrides the guard - the confirmed manual path`() {
        assertEquals(
            UploadDecision.UPLOAD,
            DriveSyncPolicy.decideUpload(entryCount = 0, remoteBackupCount = 2, forced = true)
        )
    }
}
