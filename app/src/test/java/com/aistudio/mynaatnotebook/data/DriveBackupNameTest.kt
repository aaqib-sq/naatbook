package com.aistudio.mynaatnotebook.data

import com.aistudio.mynaatnotebook.data.drive.driveBackupEntryCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * UX batch 1 (audit B1): the restore chooser reads each backup's entry
 * count out of its Drive file name ("...-n42.zip"); names from before the
 * count-in-name change have none and must read as unknown, never as 0.
 */
class DriveBackupNameTest {

    @Test
    fun `count is read from a sync file name`() {
        assertEquals(42, driveBackupEntryCount("naatbook-backup-20261020-190700-n42.zip"))
        assertEquals(0, driveBackupEntryCount("naatbook-backup-20261020-190700-n0.zip"))
        assertEquals(7, driveBackupEntryCount("naatbook-backup-20260101-000000-n7.zip"))
    }

    @Test
    fun `older or foreign names have no count`() {
        assertNull(driveBackupEntryCount("naatbook-backup-20260101-000000.zip"))
        assertNull(driveBackupEntryCount("NaatBook_backup_2026-10-20_1907.zip"))
        assertNull(driveBackupEntryCount("something-else.zip"))
        assertNull(driveBackupEntryCount("naatbook-backup-n5.zip.bak"))
    }
}
