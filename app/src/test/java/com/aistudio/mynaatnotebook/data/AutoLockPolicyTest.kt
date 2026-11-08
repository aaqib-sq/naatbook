package com.aistudio.mynaatnotebook.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto-lock policy tests (pure JVM — no Android runtime needed):
 * the foreground re-lock decision across Immediate / grace timers.
 * (The "lock on device unlock" toggle was removed 2026-11-08, so a
 * device-lock/unlock cycle no longer forces a re-lock at all.)
 */
class AutoLockPolicyTest {

    @Test
    fun `lock off never relocks`() {
        assertFalse(
            AutoLockPolicy.shouldRelock(
                lockEnabled = false,
                autoLockMinutes = 0,
                backgroundedAtMs = 1000L,
                nowMs = 2000L
            )
        )
    }

    @Test
    fun `immediate always relocks`() {
        assertTrue(
            AutoLockPolicy.shouldRelock(
                lockEnabled = true,
                autoLockMinutes = 0,
                backgroundedAtMs = 1000L,
                nowMs = 1001L
            )
        )
    }

    @Test
    fun `inside grace period stays unlocked`() {
        assertFalse(
            AutoLockPolicy.shouldRelock(
                lockEnabled = true,
                autoLockMinutes = 10,
                backgroundedAtMs = 1000L,
                nowMs = 1000L + 9L * 60_000L
            )
        )
    }

    @Test
    fun `past grace period relocks`() {
        assertTrue(
            AutoLockPolicy.shouldRelock(
                lockEnabled = true,
                autoLockMinutes = 10,
                backgroundedAtMs = 1000L,
                nowMs = 1000L + 10L * 60_000L
            )
        )
        assertTrue(
            AutoLockPolicy.shouldRelock(
                lockEnabled = true,
                autoLockMinutes = 30,
                backgroundedAtMs = 1000L,
                nowMs = 1000L + 31L * 60_000L
            )
        )
    }

    @Test
    fun `device unlock cycle no longer relocks inside grace`() {
        assertFalse(
            AutoLockPolicy.shouldRelock(
                lockEnabled = true,
                autoLockMinutes = 30,
                backgroundedAtMs = 1000L,
                nowMs = 1001L
            )
        )
    }

    @Test
    fun `never backgrounded relocks when timer set`() {
        assertTrue(
            AutoLockPolicy.shouldRelock(
                lockEnabled = true,
                autoLockMinutes = 10,
                backgroundedAtMs = 0L,
                nowMs = 5000L
            )
        )
    }
}
