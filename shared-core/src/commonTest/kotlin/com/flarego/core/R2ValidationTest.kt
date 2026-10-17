package com.flarego.core

import com.flarego.core.application.r2.encodeR2ObjectKeyPath
import com.flarego.core.application.r2.validateR2BucketName
import com.flarego.core.application.r2.validateR2CreateInput
import com.flarego.core.application.r2.validateR2ObjectKey
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.R2BucketCreateInput
import com.flarego.core.model.R2BulkDeleteOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class R2ValidationTest {

    @Test
    fun rejectsIllegalBucketNamesAndAcceptsMinimalValidOne() {
        assertEquals("abc", validateR2BucketName("abc"))
        assertEquals("my-bucket-01", validateR2BucketName("my-bucket-01"))
        for (bad in listOf(
            "ab",
            "a".repeat(65),
            "Upper",
            "-lead",
            "trail-",
            "has_underscore",
            "192.168.0.1",
            "xn--punycode",
            "bucket-s3alias",
            "a..b",
        )) {
            assertFailsWith<CloudFailure> { validateR2BucketName(bad) }
        }
    }

    @Test
    fun keepsSlashesLiteralAndRejectsControlBytesInObjectKeys() {
        assertEquals("dir/sub/file.txt", validateR2ObjectKey("dir/sub/file.txt"))
        for (bad in listOf("", ".", "..", "/leading", "back\\slash", "nul\u0000", "a".repeat(1025))) {
            assertFailsWith<CloudFailure> { validateR2ObjectKey(bad) }
        }
    }

    @Test
    fun pathEncodingKeepsSlashesLiteralAndNeverTurnsTextIntoASeparator() {
        assertEquals("dir/sub/file.txt", encodeR2ObjectKeyPath("dir/sub/file.txt"))
        assertEquals("a%20b.txt", encodeR2ObjectKeyPath("a b.txt"))
        assertEquals("a%252Fb", encodeR2ObjectKeyPath("a%2Fb"))
        assertEquals("caf%C3%A9/%E6%97%A5.txt", encodeR2ObjectKeyPath("café/日.txt"))
    }

    @Test
    fun rejectsUnknownEnumsOnWriteButBulkDeleteOutcomeStaysBounded() {
        assertEquals(
            R2BucketCreateInput("abc", "weur", "Standard"),
            validateR2CreateInput(R2BucketCreateInput("abc", "weur", "Standard")),
        )
        assertEquals(
            R2BucketCreateInput("abc", null, null),
            validateR2CreateInput(R2BucketCreateInput("abc")),
        )
        assertFailsWith<CloudFailure> { validateR2CreateInput(R2BucketCreateInput("abc", "mars", null)) }
        assertFailsWith<CloudFailure> { validateR2CreateInput(R2BucketCreateInput("abc", null, "Glacier")) }

        for (bad in listOf(0, 1001)) {
            assertFailsWith<IllegalArgumentException> { R2BulkDeleteOutcome(bad, null, false) }
        }
        assertFailsWith<IllegalArgumentException> { R2BulkDeleteOutcome(3, 4, false) }
        assertEquals(R2BulkDeleteOutcome(3, 3, false), R2BulkDeleteOutcome(3, 3, false))
    }
}
