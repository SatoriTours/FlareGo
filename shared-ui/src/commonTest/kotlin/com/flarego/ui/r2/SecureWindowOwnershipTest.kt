package com.flarego.ui.r2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 5c: `FLAG_SECURE` purpose ownership.
 *
 * The pre-existing connect/token dialog purpose and the Sippy credential purpose share one owner, so
 * one purpose releasing can never clear the other's flag, a pre-existing flag is never cleared, and
 * no other window flag is involved (the Android adapter only ever adds/clears this one bit).
 */
class SecureWindowOwnershipTest {
    @Test
    fun onePurposeReleasingNeverClearsAnotherPurposesFlag() {
        val ownership = SecureWindowOwnership(generation = 1L, baselineSecure = false)
        assertTrue(ownership.acquire(SecurePurpose.CONNECTION_TOKEN), "the first purpose must set the flag")
        assertFalse(ownership.acquire(SecurePurpose.SIPPY_CREDENTIALS), "a second purpose needs no change")
        assertEquals(setOf(SecurePurpose.CONNECTION_TOKEN, SecurePurpose.SIPPY_CREDENTIALS), ownership.purposes)

        // The old connect dialog leaves: the Sippy editor still needs protection.
        assertFalse(ownership.release(SecurePurpose.CONNECTION_TOKEN), "another purpose still holds the flag")
        assertEquals(setOf(SecurePurpose.SIPPY_CREDENTIALS), ownership.purposes)

        // The last release clears exactly the bit this owner set.
        assertTrue(ownership.release(SecurePurpose.SIPPY_CREDENTIALS))
        assertTrue(ownership.purposes.isEmpty())
        // A repeated / foreign release is a no-op.
        assertFalse(ownership.release(SecurePurpose.SIPPY_CREDENTIALS))
        assertFalse(ownership.release(SecurePurpose.CONNECTION_TOKEN))
    }

    @Test
    fun aPreExistingFlagIsPreservedAndNeverClearedByThisOwner() {
        val ownership = SecureWindowOwnership(generation = 2L, baselineSecure = true)
        assertFalse(ownership.acquire(SecurePurpose.SIPPY_CREDENTIALS), "an existing flag needs no change")
        assertFalse(ownership.release(SecurePurpose.SIPPY_CREDENTIALS), "ours was not the bit that set it")
        assertFalse(ownership.close())
        // Window teardown with our own bit: it is cleared only when this owner set it.
        val owned = SecureWindowOwnership(generation = 3L, baselineSecure = false)
        owned.acquire(SecurePurpose.SIPPY_CREDENTIALS)
        assertTrue(owned.close())
        assertFalse(owned.close())
    }

    @Test
    fun closingReleasesEveryHeldPurposeOfThatGeneration() {
        val ownership = SecureWindowOwnership(generation = 4L, baselineSecure = false)
        ownership.acquire(SecurePurpose.CONNECTION_TOKEN)
        ownership.acquire(SecurePurpose.SIPPY_CREDENTIALS)
        assertTrue(ownership.close())
        assertTrue(ownership.purposes.isEmpty())
    }
}

/**
 * Task 5c fix1: one purpose may be requested more than once; every handle is a reference, and the
 * flag disappears only when the last reference of the last purpose is released.
 */
class SecureWindowOwnershipHandlesTest {
    @Test
    fun twoHandlesForOnePurposeKeepTheFlagUntilTheLastOneIsReleased() {
        val ownership = SecureWindowOwnership(generation = 7L, baselineSecure = false)
        assertTrue(ownership.acquire(SecurePurpose.SIPPY_CREDENTIALS))
        assertFalse(ownership.acquire(SecurePurpose.SIPPY_CREDENTIALS))
        assertEquals(2, ownership.liveHandles(SecurePurpose.SIPPY_CREDENTIALS))
        assertEquals(setOf(SecurePurpose.SIPPY_CREDENTIALS), ownership.purposes)

        assertFalse(
            ownership.release(SecurePurpose.SIPPY_CREDENTIALS),
            "one live handle still needs the flag",
        )
        assertEquals(1, ownership.liveHandles(SecurePurpose.SIPPY_CREDENTIALS))
        assertTrue(ownership.release(SecurePurpose.SIPPY_CREDENTIALS))
        assertEquals(0, ownership.liveHandles(SecurePurpose.SIPPY_CREDENTIALS))
        // A repeated / foreign release is a no-op and never clears anything twice.
        assertFalse(ownership.release(SecurePurpose.SIPPY_CREDENTIALS))
        assertFalse(ownership.release(SecurePurpose.CONNECTION_TOKEN))
    }

    @Test
    fun aRepeatedReleaseOfOnePurposeNeverClearsAnotherPurposesHandles() {
        val ownership = SecureWindowOwnership(generation = 8L, baselineSecure = false)
        assertTrue(ownership.acquire(SecurePurpose.SIPPY_CREDENTIALS))
        assertFalse(ownership.acquire(SecurePurpose.CONNECTION_TOKEN))
        assertFalse(ownership.acquire(SecurePurpose.CONNECTION_TOKEN))
        assertFalse(ownership.release(SecurePurpose.CONNECTION_TOKEN))
        assertFalse(ownership.release(SecurePurpose.CONNECTION_TOKEN))
        assertEquals(0, ownership.liveHandles(SecurePurpose.CONNECTION_TOKEN))
        assertEquals(1, ownership.liveHandles(SecurePurpose.SIPPY_CREDENTIALS))
        assertTrue(ownership.release(SecurePurpose.SIPPY_CREDENTIALS))
    }

    @Test
    fun closeReleasesEveryHandleAndPreservesAPreExistingFlag() {
        val owned = SecureWindowOwnership(generation = 9L, baselineSecure = false)
        owned.acquire(SecurePurpose.SIPPY_CREDENTIALS)
        owned.acquire(SecurePurpose.SIPPY_CREDENTIALS)
        owned.acquire(SecurePurpose.CONNECTION_TOKEN)
        assertTrue(owned.close())
        assertEquals(0, owned.liveHandles(SecurePurpose.SIPPY_CREDENTIALS))
        assertTrue(owned.purposes.isEmpty())

        val preserved = SecureWindowOwnership(generation = 10L, baselineSecure = true)
        preserved.acquire(SecurePurpose.SIPPY_CREDENTIALS)
        assertFalse(preserved.close(), "a flag this owner did not set is never cleared")
    }
}
