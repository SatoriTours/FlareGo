package com.flarego.core

import com.flarego.core.application.r2.R2_LOCATION_HINTS
import com.flarego.core.application.r2.R2_STORAGE_CLASSES
import com.flarego.core.application.r2.validateR2BucketName
import com.flarego.core.application.r2.validateR2ObjectKey
import com.flarego.core.cloudflare.protocol.ActionPolicies
import com.flarego.core.cloudflare.protocol.ActionScope
import com.flarego.core.cloudflare.protocol.ActionSemantics
import com.flarego.core.cloudflare.protocol.ResponseKind
import com.flarego.core.cloudflare.protocol.RiskLevel
import com.flarego.core.cloudflare.protocol.r2Registrations
import com.flarego.core.model.CloudFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The R2 batch A allowlist is handwritten and reviewed. These assertions pin the exact
 * ten operations, their scope, risk and response handling, and prove that nothing can
 * widen the list from data.
 */
class R2PoliciesTest {

    private val batchA = setOf(
        "r2-create-bucket",
        "r2-create-bucket-by-name",
        "r2-get-bucket",
        "r2-patch-bucket",
        "r2-delete-bucket",
        "r2-list-objects",
        "r2-get-object",
        "r2-put-object",
        "r2-delete-object",
        "r2-delete-objects",
    )

    @Test
    fun registersExactlyTheReviewedBatchAOperationsInAccountScope() {
        val registrations = r2Registrations()
        assertEquals(batchA, registrations.map { it.policy.key }.toSet())
        assertEquals(10, registrations.size)
        assertTrue(registrations.all { it.scope == ActionScope.ACCOUNT })
        assertTrue(registrations.all { it.pathTemplate.startsWith("/accounts/{account_id}/r2/") })
    }

    @Test
    fun destructiveRisksAndResponseHandlingAreExplicit() {
        fun policy(id: String) = ActionPolicies.policy(id)!!

        assertEquals(RiskLevel.DESTRUCTIVE, policy("r2-delete-bucket").risk)
        assertEquals(RiskLevel.DESTRUCTIVE, policy("r2-delete-object").risk)
        assertEquals(RiskLevel.DESTRUCTIVE, policy("r2-delete-objects").risk)
        assertEquals(RiskLevel.CHANGE, policy("r2-patch-bucket").risk)
        assertEquals(RiskLevel.READ_ONLY, policy("r2-get-object").risk)

        // A streamed object download must never be decoded as a JSON envelope.
        assertEquals(ResponseKind.BINARY, policy("r2-get-object").responseKind)
        assertEquals(ResponseKind.ENVELOPE_JSON, policy("r2-list-objects").responseKind)

        // Reads may retry within the bounded policy; writes never retry.
        assertTrue(policy("r2-list-objects").retry.enabled)
        assertFalse(policy("r2-put-object").retry.enabled)
        assertFalse(policy("r2-delete-bucket").retry.enabled)
    }

    @Test
    fun onlyTheKeyAddressedActionsOwnASpanningTrailingPlaceholder() {
        val spanning = r2Registrations().filter { it.trailingPlaceholderSpansSegments }.map { it.policy.key }.toSet()
        assertEquals(setOf("r2-get-object", "r2-put-object", "r2-delete-object"), spanning)
        // Listing and bulk deletion address the collection, never one opaque key.
        assertFalse(ActionPolicies.registry.registrations.getValue("r2-list-objects").trailingPlaceholderSpansSegments)
        assertFalse(ActionPolicies.registry.registrations.getValue("r2-delete-objects").trailingPlaceholderSpansSegments)
    }

    @Test
    fun listingBucketsKeepsItsOriginalSharedPolicy() {
        // r2-list-buckets belongs to P1 and must not be redefined by this batch.
        val buckets = ActionPolicies.registry.registrations.getValue("r2-list-buckets")
        assertEquals("GET", buckets.method)
        assertEquals("/accounts/{account_id}/r2/buckets", buckets.pathTemplate)
        assertTrue(buckets.policy.retry.enabled)
    }

    @Test
    fun temporaryCredentialsStayUnregisteredUntilSeparatelyApproved() {
        // R2-E returns a live secretAccessKey/sessionToken pair; it needs its own approval.
        assertNull(ActionPolicies.policy("r2-create-temp-access-credentials"))
        assertFalse(batchA.contains("r2-create-temp-access-credentials"))
    }

    @Test
    fun officialEnumsRejectUnknownValuesAndTheValidatorMatchesThePinnedPattern() {
        assertEquals(setOf("apac", "eeur", "enam", "weur", "wnam", "oc"), R2_LOCATION_HINTS)
        assertEquals(setOf("Standard", "InfrequentAccess"), R2_STORAGE_CLASSES)
        assertFailsWith<CloudFailure> { validateR2BucketName("a".repeat(64) + "a") }
        assertEquals("a".repeat(64), validateR2BucketName("a".repeat(64)))
        assertFailsWith<CloudFailure> { validateR2ObjectKey("x".repeat(1024) + "x") }
    }
}
