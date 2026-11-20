package com.aistudio.mynaatnotebook.data

import com.aistudio.mynaatnotebook.data.drive.DriveSyncPolicy
import com.aistudio.mynaatnotebook.data.drive.UploadDecision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The empty-library guard (audit round 2, S1/S2; hardened by the
 * nine-fixes round, flaw 2): an empty library NEVER syncs to Drive — not
 * over existing backups, and not even as the first backup. The old
 * "forced" escape (the manual confirm path) is closed entirely, so the
 * policy takes only the entry count.
 */
class DriveSyncPolicyTest {

    @Test
    fun `empty library never uploads - over existing backups`() {
        assertEquals(
            UploadDecision.SKIP_EMPTY_OVER_EXISTING,
            DriveSyncPolicy.decideUpload(entryCount = 0)
        )
    }

    @Test
    fun `empty library never uploads - even as the first backup`() {
        // The old policy allowed an empty first backup into an empty
        // folder; the nine-fixes round closes that too — there is nothing
        // to back up, and an empty file must never become a backup.
        assertEquals(
            UploadDecision.SKIP_EMPTY_OVER_EXISTING,
            DriveSyncPolicy.decideUpload(entryCount = 0)
        )
    }

    @Test
    fun `non-empty library always uploads`() {
        assertEquals(
            UploadDecision.UPLOAD,
            DriveSyncPolicy.decideUpload(entryCount = 1)
        )
        assertEquals(
            UploadDecision.UPLOAD,
            DriveSyncPolicy.decideUpload(entryCount = 42)
        )
    }
}
