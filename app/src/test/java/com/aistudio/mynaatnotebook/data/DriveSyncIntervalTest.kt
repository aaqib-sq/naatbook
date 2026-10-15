package com.aistudio.mynaatnotebook.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSyncIntervalTest {

    private val hourMs = 60L * 60 * 1000
    private val dayMs = 24 * hourMs

    private fun shouldFire(
        interval: DriveSyncInterval,
        lastSyncAgoMs: Long,
        hashChanged: Boolean = true,
        connected: Boolean = true,
        autoSyncOn: Boolean = true
    ): Boolean {
        val nowMs = 1_790_000_000_000L
        return shouldAutoSync(
            interval = interval,
            connected = connected,
            autoSyncOn = autoSyncOn,
            hashChanged = hashChanged,
            lastSyncAtMs = nowMs - lastSyncAgoMs,
            nowMs = nowMs
        )
    }

    // --- interval gating ---

    @Test
    fun `open interval fires whenever content changed`() {
        assertTrue(shouldFire(DriveSyncInterval.OPEN, lastSyncAgoMs = hourMs))
        assertTrue(shouldFire(DriveSyncInterval.OPEN, lastSyncAgoMs = 6 * dayMs))
    }

    @Test
    fun `daily blocked just under 24h and allowed after`() {
        assertFalse(shouldFire(DriveSyncInterval.DAILY, lastSyncAgoMs = 23 * hourMs))
        assertTrue(shouldFire(DriveSyncInterval.DAILY, lastSyncAgoMs = 25 * hourMs))
    }

    @Test
    fun `weekly blocked at 6 days and allowed at 8 days`() {
        assertFalse(shouldFire(DriveSyncInterval.WEEKLY, lastSyncAgoMs = 6 * dayMs))
        assertTrue(shouldFire(DriveSyncInterval.WEEKLY, lastSyncAgoMs = 8 * dayMs))
    }

    @Test
    fun `never synced is always due when content changed`() {
        val nowMs = 1_790_000_000_000L
        DriveSyncInterval.values().forEach { interval ->
            assertTrue(
                shouldAutoSync(interval, true, true, true, 0L, nowMs)
            )
        }
    }

    // --- preconditions ---

    @Test
    fun `unchanged content never fires even on open interval`() {
        assertFalse(
            shouldFire(
                DriveSyncInterval.OPEN,
                lastSyncAgoMs = 30 * dayMs,
                hashChanged = false
            )
        )
    }

    @Test
    fun `not connected or feature off never fires`() {
        assertFalse(
            shouldFire(
                DriveSyncInterval.OPEN,
                lastSyncAgoMs = 30 * dayMs,
                connected = false
            )
        )
        assertFalse(
            shouldFire(
                DriveSyncInterval.OPEN,
                lastSyncAgoMs = 30 * dayMs,
                autoSyncOn = false
            )
        )
    }

    // --- key parsing ---

    @Test
    fun `fromKey maps known keys and falls back to weekly`() {
        assertEquals(DriveSyncInterval.OPEN, DriveSyncInterval.fromKey("open"))
        assertEquals(DriveSyncInterval.DAILY, DriveSyncInterval.fromKey("daily"))
        assertEquals(DriveSyncInterval.WEEKLY, DriveSyncInterval.fromKey("weekly"))
        assertEquals(DriveSyncInterval.WEEKLY, DriveSyncInterval.fromKey(null))
        assertEquals(DriveSyncInterval.WEEKLY, DriveSyncInterval.fromKey("bogus"))
    }
}
