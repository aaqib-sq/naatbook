package com.aistudio.mynaatnotebook.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App lock policy tests (pure JVM — no Android runtime needed):
 * the wrong-PIN delay ladder and the salted PIN hash round-trip.
 * No auto-wipe exists anywhere in the policy: the delay only grows
 * and caps at 15 minutes.
 */
class LockAttemptPolicyTest {

    @Test
    fun `first two failures have no delay`() {
        assertEquals(0L, LockAttemptPolicy.delayAfterFailures(0))
        assertEquals(0L, LockAttemptPolicy.delayAfterFailures(1))
        assertEquals(0L, LockAttemptPolicy.delayAfterFailures(2))
    }

    @Test
    fun `delay escalates 30s then 1min then 5min then caps at 15min`() {
        assertEquals(30_000L, LockAttemptPolicy.delayAfterFailures(3))
        assertEquals(60_000L, LockAttemptPolicy.delayAfterFailures(4))
        assertEquals(300_000L, LockAttemptPolicy.delayAfterFailures(5))
        assertEquals(900_000L, LockAttemptPolicy.delayAfterFailures(6))
        assertEquals(900_000L, LockAttemptPolicy.delayAfterFailures(100))
    }

    @Test
    fun `pin hash round-trips and rejects wrong pins`() {
        val salt = PinHash.salt()
        val hash = PinHash.hash("1234", salt)
        assertTrue(PinHash.matches("1234", salt, hash))
        assertFalse(PinHash.matches("1235", salt, hash))
        assertFalse(PinHash.matches("1234", PinHash.salt(), hash))
    }

    @Test
    fun `same pin under different salts hashes differently`() {
        val hashA = PinHash.hash("1234", PinHash.salt())
        val hashB = PinHash.hash("1234", PinHash.salt())
        assertNotEquals(hashA, hashB)
    }

    @Test
    fun `only 4 digits are a valid pin`() {
        assertTrue(PinHash.isValidPin("1234"))
        assertFalse(PinHash.isValidPin("123"))
        assertFalse(PinHash.isValidPin("12345"))
        assertFalse(PinHash.isValidPin("12a4"))
        assertFalse(PinHash.isValidPin(""))
    }
}
