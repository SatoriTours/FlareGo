package com.flarego.platform

import kotlin.test.*

/** Real JDK SHA-256 vectors; this is not Android wiring evidence. */
class AndroidFingerprintTest {
    @Test
    fun knownVectorsMatchThePublishedDigests() {
        val fingerprint = AndroidFingerprint()
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", fingerprint.sha256(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", fingerprint.sha256("abc".encodeToByteArray()))
    }

    @Test
    fun additionalVectorsMatchIndependentlyComputedDigests() {
        val fingerprint = AndroidFingerprint()
        assertEquals("2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae", fingerprint.sha256("foo".encodeToByteArray()))
        assertEquals("fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9", fingerprint.sha256("bar".encodeToByteArray()))
        val combined = fingerprint.sha256(("abc".repeat(1_000)).encodeToByteArray())
        assertEquals(64, combined.length)
        assertTrue(combined.all { it in '0'..'9' || it in 'a'..'f' }, "lowercase hex only")
        assertNotEquals(combined, fingerprint.sha256(("abc".repeat(999)).encodeToByteArray()))
    }

    @Test
    fun everyDigestIsLowercaseHexOfTheRightLength() {
        val fingerprint = AndroidFingerprint()
        for (index in 0..32) {
            val digest = fingerprint.sha256(ByteArray(index) { it.toByte() })
            assertEquals(64, digest.length, index.toString())
            assertTrue(digest.all { it in '0'..'9' || it in 'a'..'f' }, index.toString())
        }
    }
}
