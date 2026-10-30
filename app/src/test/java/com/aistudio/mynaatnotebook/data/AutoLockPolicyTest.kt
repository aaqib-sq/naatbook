package com.aistudio.mynaatnotebook.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto-lock policy tests (pure JVM — no Android runtime needed):
 * the foreground re-lock decision across Immediate / grace timers /
 * device-lock-unlock cycles.
 */
class AutoLockPolicyTest {

    @Test
    fun `lock off never relocks`() {
        assertFalse(
            AutoLockPolicy.shouldRelock(
                lockEnabled = false,
                lockOnDeviceUnlock = true,
                deviceUnlockedSinceBackground = true,
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
                lockOnDeviceUnlock = true,
                deviceUnlockedSinceBackground = false,
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
                lockOnDeviceUnlock = true,
                deviceUnlockedSinceBackground = false,
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
                lockOnDeviceUnlock = true,
                deviceUnlockedSinceBackground = false,
                autoLockMinutes = 10,
                backgroundedAtMs = 1000L,
                nowMs = 1000L + 10L * 60_000L
            )
        )
        assertTrue(
            AutoLockPolicy.shouldRelock(
                lockEnabled = true,
                lockOnDeviceUnlock = false,
                deviceUnlockedSinceBackground = false,
                autoLockMinutes = 30,
                backgroundedAtMs = 1000L,
                nowMs = 1000L + 31L * 60_000L
            )
        )
    }

    @Test
    fun `device unlock cycle relocks even inside grace when toggle on`() {
        assertTrue(
            AutoLockPolicy.shouldRelock(
                lockEnabled = true,
                lockOnDeviceUnlock = true,
                deviceUnlockedSinceBackground = true,
                autoLockMinutes = 30,
                backgroundedAtMs = 1000L,
                nowMs = 1001L
            )
        )
    }

    @Test
    fun `device unlock cycle does not relock when toggle off and inside grace`() {
        assertFalse(
            AutoLockPolicy.shouldRelock(
                lockEnabled = true,
                lockOnDeviceUnlock = false,
                deviceUnlockedSinceBackground = true,
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
                lockOnDeviceUnlock = false,
                deviceUnlockedSinceBackground = false,
                autoLockMinutes = 10,
                backgroundedAtMs = 0L,
                nowMs = 5000L
            )
        )
    }
}
