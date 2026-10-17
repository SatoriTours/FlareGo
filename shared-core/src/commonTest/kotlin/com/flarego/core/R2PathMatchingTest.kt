package com.flarego.core

import com.flarego.core.cloudflare.protocol.ActionPolicy
import com.flarego.core.cloudflare.protocol.ActionRegistration
import com.flarego.core.cloudflare.protocol.ActionScope
import com.flarego.core.cloudflare.protocol.ActionSemantics
import com.flarego.core.cloudflare.protocol.ReadRetryPolicy
import com.flarego.core.cloudflare.protocol.ResponseKind
import com.flarego.core.cloudflare.protocol.RiskLevel
import com.flarego.core.model.CloudFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R2 object keys are sent as literal path text: the provider requires `/` in a key to
 * stay literal rather than become `%2F`. That needs one reviewed exception to the
 * "one placeholder is one segment" rule, and the exception must not open a way to
 * smuggle an extra path segment.
 */
class R2PathMatchingTest {

    private val objectTemplate = "/accounts/{account_id}/r2/buckets/{bucket_name}/objects/{object_key}"

    private fun objectAction(spans: Boolean) = ActionRegistration(
        ActionPolicy("r2-get-object", ActionSemantics.READ, ResponseKind.BINARY, RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)),
        "GET",
        objectTemplate,
        ActionScope.ACCOUNT,
        trailingPlaceholderSpansSegments = spans,
    )

    @Test
    fun aSlashKeyStaysOneReviewedPlaceholder() {
        val action = objectAction(spans = true)
        assertTrue(action.matches("GET", "/accounts/A/r2/buckets/b/objects/dir/sub/file.txt"))
        assertTrue(action.matches("GET", "/accounts/A/r2/buckets/b/objects/file.txt"))
        assertTrue(action.matches("GET", "/accounts/A/r2/buckets/b/objects/caf%C3%A9/%E6%97%A5.txt"))
        assertFalse(action.matches("DELETE", "/accounts/A/r2/buckets/b/objects/dir/sub/file.txt"))
    }

    @Test
    fun theSameSlashKeyIsRefusedWithoutTheReviewedFlag() {
        val action = objectAction(spans = false)
        assertTrue(action.matches("GET", "/accounts/A/r2/buckets/b/objects/file.txt"))
        assertFalse(action.matches("GET", "/accounts/A/r2/buckets/b/objects/dir/sub/file.txt"))
    }

    @Test
    fun encodedOrDoubleEncodedSeparatorsNeverReachTheKey() {
        val action = objectAction(spans = true)
        assertFalse(action.matches("GET", "/accounts/A/r2/buckets/b/objects/dir%2Fsub.txt"))
        assertFalse(action.matches("GET", "/accounts/A/r2/buckets/b/objects/dir%2fsub.txt"))
        // The provider decodes once, so `%252F` is the legitimate encoding of a key that
        // really contains the text `%2F`; it never becomes a separator at the provider.
        assertTrue(action.matches("GET", "/accounts/A/r2/buckets/b/objects/a%252Fb"))
        assertTrue(action.matches("GET", "/accounts/A/r2/buckets/b/objects/dir%252Fsub.txt"))
    }

    @Test
    fun documentsTheSharedValidatorLimitOnALiteralPercent() {
        val action = objectAction(spans = true)
        // Known limitation of the shared request-shape validator, not of this rule: a
        // literal `%` whose following two characters decode to an invalid UTF-8 byte
        // fails the invalid-UTF-8 stage check before matching runs. Tracked as a
        // follow-up in the R2 batch A plan; changing it would touch every policy.
        assertFalse(action.matches("GET", "/accounts/A/r2/buckets/b/objects/a%25bc"))
        assertTrue(action.matches("GET", "/accounts/A/r2/buckets/b/objects/a%25zz"))
    }

    @Test
    fun anEmptyKeyOrABraceInTheKeyIsRefused() {
        val action = objectAction(spans = true)
        assertFalse(action.matches("GET", "/accounts/A/r2/buckets/b/objects/"))
        assertFalse(action.matches("GET", "/accounts/A/r2/buckets/b/objects/{other}"))
    }

    @Test
    fun theFixedSegmentsMustStillMatchExactly() {
        val action = objectAction(spans = true)
        assertFalse(action.matches("GET", "/accounts/A/r2/buckets/b/object/dir/file.txt"))
        assertFalse(action.matches("GET", "/zones/A/r2/buckets/b/objects/dir/file.txt"))
    }

    @Test
    fun theSpanningFlagRequiresATrailingPlaceholder() {
        val thrown = assertFailsWith<CloudFailure> {
            ActionRegistration(
                ActionPolicy("r2-list-objects", ActionSemantics.READ, ResponseKind.ENVELOPE_JSON, RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)),
                "GET",
                "/accounts/{account_id}/r2/buckets/{bucket_name}/objects",
                ActionScope.ACCOUNT,
                trailingPlaceholderSpansSegments = true,
            )
        }
        assertEquals("输入无效：标识不能为空，数值必须为正数", thrown.publicMessage)
    }
}
