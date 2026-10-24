package com.flarego.ui.r2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 3, the memory-only three-stage confirmation owner for destructive C writes (spec §10.6).
 *
 * Owner-level tests: no Compose, no plan, no task, no saved state. Every assertion is about the state
 * machine the root uses as its execution guard, including its **handle binding**: a dialog is bound to
 * the confirmation handle (not to a fingerprint alone), and a ticket is valid only while that handle is
 * still the current final stage and the live route/credential binding is the one it was opened with.
 */
class R2CDeletionConfirmationTest {
    private var binding = "route-1/cred-1"

    private fun owner() = R2CDeletionConfirmation { binding }

    private fun draft(
        kind: R2CDeleteKind = R2CDeleteKind.PREFIX_DELETE,
        bucket: String = "my-bucket",
        fingerprint: String = "fp-1",
        scopeText: String = "account/jurisdiction/my-bucket/prefix logs/",
    ) = R2CConfirmationDraft(kind, bucket, fingerprint, scopeText)

    private fun atFinal(owner: R2CDeletionConfirmation, d: R2CConfirmationDraft = draft()): Pair<Long, R2CConfirmationTicket> {
        val handle = owner.begin(d)
        assertTrue(owner.acknowledgeRisk(handle, d.fingerprint))
        owner.typeBucketName(handle, d.bucket)
        val ticket = assertNotNull(owner.openFinal(handle, d.fingerprint, binding))
        return handle to ticket
    }

    @Test
    fun theThreeStagesAdvanceOnlyInOrderAndNeverWithADefaultSelection() {
        val owner = owner()
        assertEquals(R2CStage.NONE, owner.state.stage)
        val handle = owner.begin(draft())
        assertEquals(R2CStage.RISK, owner.state.stage)
        // No default acknowledgement: only the explicit call advances stage 1.
        assertTrue(owner.acknowledgeRisk(handle, "fp-1"))
        assertEquals(R2CStage.TYPED_NAME, owner.state.stage)
        // Stage 2 cannot be skipped.
        assertNull(owner.openFinal(handle, "fp-1", binding))
        assertEquals(R2CStage.TYPED_NAME, owner.state.stage)
        owner.typeBucketName(handle, "my-bucket")
        val ticket = assertNotNull(owner.openFinal(handle, "fp-1", binding))
        assertEquals(R2CStage.FINAL, owner.state.stage)
        assertEquals(R2CDeleteKind.PREFIX_DELETE, ticket.kind)
        assertEquals(handle, ticket.handle)
    }

    @Test
    fun theTypedBucketNameIsExactAndNeverTrimmedOrCaseFolded() {
        val owner = owner()
        val handle = owner.begin(draft())
        owner.acknowledgeRisk(handle, "fp-1")
        for (wrong in listOf("my-bucket ", " my-bucket", "My-Bucket", "my-bucke", "MY-BUCKET")) {
            owner.typeBucketName(handle, wrong)
            assertNull(owner.openFinal(handle, "fp-1", binding), "wrong name must not advance: '$wrong'")
            assertEquals(R2C_CONFIRMATION_NAME_MISMATCH, owner.state.error)
            assertEquals(R2CStage.TYPED_NAME, owner.state.stage)
        }
        owner.typeBucketName(handle, "")
        assertFalse(owner.state.typedMatches)
        assertNull(owner.openFinal(handle, "fp-1", binding))
        owner.typeBucketName(handle, "my-bucket")
        assertTrue(owner.state.typedMatches)
        assertNotNull(owner.openFinal(handle, "fp-1", binding))
    }

    @Test
    fun anyDraftChangeRevokesEveryStageAndCannotAuthoriseTheNewDraft() {
        val owner = owner()
        val handle = owner.begin(draft())
        owner.acknowledgeRisk(handle, "fp-1")
        owner.typeBucketName(handle, "my-bucket")
        assertNull(owner.openFinal(handle, "fp-2", binding), "a changed live fingerprint cannot open the final stage")
        assertEquals(R2CStage.NONE, owner.state.stage)
        // The old handle cannot drive a new confirmation either.
        assertFalse(owner.acknowledgeRisk(handle, "fp-2"))
    }

    @Test
    fun aRetainedTicketCannotBeConsumedAfterCancelOrRestart() {
        val owner = owner()
        val (_, first) = atFinal(owner)
        owner.revoke()
        assertEquals(R2CStage.NONE, owner.state.stage)
        assertFalse(first.consumeOnce(), "a ticket retained by a cancelled dialog is dead")
        assertEquals(false, owner.isOpenFor("fp-1"))

        // Re-opening the *same* draft must mint a new, independent permission.
        val (_, second) = atFinal(owner)
        assertNotEquals(first.handle, second.handle)
        assertFalse(first.consumeOnce(), "the old ticket stays dead after a restart")
        assertTrue(second.consumeOnce(), "the new ticket is the only valid one")
        assertFalse(second.consumeOnce())
    }

    @Test
    fun anOldDialogCallbackCannotAuthoriseTheReopenedFinalWithTheSameFingerprint() {
        val owner = owner()
        val (oldHandle, oldTicket) = atFinal(owner)
        owner.revoke()
        // The operator redoes exactly the same draft: same fingerprint, new confirmation handle.
        val (newHandle, newTicket) = atFinal(owner)
        assertNotEquals(oldHandle, newHandle)
        // The old dialog's callback can only present its own ticket/handle.
        assertFalse(oldTicket.consumeOnce())
        assertNull(owner.openFinal(oldHandle, "fp-1", binding), "an old handle cannot reach the reopened final stage")
        assertTrue(newTicket.consumeOnce(), "only the current dialog authorises the write")
        assertFalse(newTicket.consumeOnce())
    }

    @Test
    fun theSameFingerprintWithAChangedRouteOrCredentialIsNotAuthorised() {
        val owner = owner()
        val (_, ticket) = atFinal(owner)
        // The payload fingerprint is unchanged; only the route/credential binding moved.
        binding = "route-2/cred-1"
        assertFalse(ticket.consumeOnce(), "a ticket is bound to the live route/credential it was opened with")
        assertEquals(R2CStage.FINAL, owner.state.stage, "the surface still shows the stage, but nothing can be submitted")

        // A new final stage opened under the new binding is required, and only it may be consumed.
        val (_, fresh) = atFinal(owner)
        assertTrue(fresh.consumeOnce())
        assertFalse(ticket.consumeOnce())
    }

    @Test
    fun aTicketIsConsumedExactlyOnceSoARepeatedFinalSubmitCannotReachASecondWrite() {
        val owner = owner()
        val (_, ticket) = atFinal(owner)
        assertTrue(ticket.consumeOnce(), "the first submission consumes it")
        assertFalse(ticket.consumeOnce(), "a repeated click cannot reach a second write")
        assertFalse(ticket.consumeOnce())
    }

    @Test
    fun theScopeIsKeptVisibleThroughEveryStage() {
        for (kind in R2CDeleteKind.entries) {
            val scope = "account/jurisdiction/my-bucket/${kind.name}"
            val owner = owner()
            val handle = owner.begin(draft(kind = kind, scopeText = scope))
            assertEquals(scope, owner.state.scopeText)
            owner.acknowledgeRisk(handle, "fp-1")
            assertEquals(scope, owner.state.scopeText)
            owner.typeBucketName(handle, "my-bucket")
            owner.openFinal(handle, "fp-1", binding)
            assertEquals(scope, owner.state.scopeText)
            assertEquals(kind, owner.state.kind)
        }
    }
}
