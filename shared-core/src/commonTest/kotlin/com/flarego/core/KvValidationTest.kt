package com.flarego.core

import com.flarego.core.application.kv.KvValidation
import com.flarego.core.model.*
import kotlin.test.*

class KvValidationTest {
    @Test fun keysKeepExactUnicodeCaseAndReservedCharactersWithin512Utf8Bytes() {
        for (key in listOf("A/a?b#c%2F\\", "键".repeat(170) + "ab", "é", "e\u0301", "😀".repeat(128))) {
            assertEquals(key, KvValidation.key(key))
        }
        for (key in listOf("", ".", "..", " ", "a b", "a\nb", "键".repeat(171), "😀".repeat(129), "\uD800")) {
            assertFailsWith<CloudFailure> { KvValidation.key(key) }
        }
    }
    @Test fun arbitraryJsonMetadataUsesSerializedUtf8BytesAndNeverPrintsContent() {
        for (json in listOf("null", "false", "123", "[]", "{}", "\"private-marker\"")) {
            val metadata = KvValidation.metadata(json)
            assertFalse(metadata.toString().contains("private-marker"))
        }
        KvValidation.metadata("\"" + "a".repeat(1022) + "\"")
        assertFailsWith<CloudFailure> { KvValidation.metadata("\"" + "a".repeat(1023) + "\"") }
        assertFailsWith<CloudFailure> { KvValidation.metadata("{bad-private-marker}") }
    }
    @Test fun expiryNeverMeansExplicitRemovalAndRelativeOrAbsoluteMinimumIs60Seconds() {
        assertEquals(KvExpiry.Never, KvValidation.expiry(KvExpiry.Never, 1000))
        assertEquals(KvExpiry.Ttl(60), KvValidation.expiry(KvExpiry.Ttl(60), 1000))
        assertEquals(KvExpiry.Absolute(1060), KvValidation.expiry(KvExpiry.Absolute(1060), 1000))
        for (expiry in listOf(KvExpiry.Ttl(59), KvExpiry.Ttl(-1), KvExpiry.Absolute(1059), KvExpiry.Absolute(-1))) {
            assertFailsWith<CloudFailure> { KvValidation.expiry(expiry, 1000) }
        }
        assertFailsWith<CloudFailure> { KvValidation.expiry(KvExpiry.Absolute(Long.MAX_VALUE), Long.MAX_VALUE - 10) }
    }
    @Test fun fullBatchKeysAreCopiedWithoutTrimmingAndDuplicatesOrLimitsAreRejected() {
        val original = mutableListOf("Key", "key", "a/b")
        val frozen = KvValidation.keys(original, 100)
        original.clear(); assertEquals(listOf("Key", "key", "a/b"), frozen)
        assertFailsWith<CloudFailure> { KvValidation.keys(emptyList(), 100) }
        assertFailsWith<CloudFailure> { KvValidation.keys(listOf("x", "x"), 10000) }
        assertFailsWith<CloudFailure> { KvValidation.keys((0..100).map { "key-$it" }, 100) }
        assertEquals(10000, KvValidation.keys((0 until 10000).map { "key-$it" }, 10000).size)
    }
}
