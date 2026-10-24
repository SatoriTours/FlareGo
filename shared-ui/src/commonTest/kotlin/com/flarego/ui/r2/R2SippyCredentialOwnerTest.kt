package com.flarego.ui.r2

import com.flarego.core.model.R2SippyProvider
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.AccountScope
import com.flarego.core.model.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 5c: the memory-only Sippy credential owner.
 *
 * No assertion prints a credential value; ownership and readiness are observed through presence
 * metadata, the non-secret revision and the live write's lease state.
 */
class R2SippyCredentialOwnerTest {
    private val connection = Connection("r2-local", "fixture", "account-one")
    private val account = AccountScope(connection.id, connection.accountId)
    private val target = R2WriteTarget(account, "my-bucket")
    private val marker = "SYNTHETIC-OWNER-SECRET-MARKER-3d7"

    private fun owner(port: FakeSecureWindowPort?, changes: MutableList<Long> = mutableListOf()) =
        R2SippyCredentialOwner(
            secureWindow = { port },
            onDraftChanged = { changes += 1 },
        )

    private fun fill(owner: R2SippyCredentialOwner, provider: R2SippyProvider = R2SippyProvider.AWS) {
        assertTrue(owner.selectProvider(provider))
        when (provider) {
            R2SippyProvider.AWS -> {
                owner.setField(R2SippySecretField.SOURCE_BUCKET, R2SippySecretInput.of("source-bucket"))
                owner.setField(R2SippySecretField.SOURCE_REGION, R2SippySecretInput.of("us-east-1"))
                owner.setField(R2SippySecretField.SOURCE_ACCESS_KEY_ID, R2SippySecretInput.of("SYNTHETIC-SOURCE-KEY-ID"))
                owner.setField(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY, R2SippySecretInput.of(marker))
            }
            R2SippyProvider.GCS -> {
                owner.setField(R2SippySecretField.SOURCE_BUCKET, R2SippySecretInput.of("source-bucket"))
                owner.setField(R2SippySecretField.GCS_CLIENT_EMAIL, R2SippySecretInput.of("synthetic@example.invalid"))
                owner.setField(R2SippySecretField.GCS_PRIVATE_KEY, R2SippySecretInput.of(marker))
            }
            R2SippyProvider.S3 -> {
                owner.setField(R2SippySecretField.SOURCE_BUCKET_URL, R2SippySecretInput.of("https://s3.example.invalid/bucket"))
                owner.setField(R2SippySecretField.SOURCE_ACCESS_KEY_ID, R2SippySecretInput.of("SYNTHETIC-SOURCE-KEY-ID"))
                owner.setField(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY, R2SippySecretInput.of(marker))
            }
            R2SippyProvider.AZURE -> {
                owner.setField(R2SippySecretField.AZURE_ACCOUNT_NAME, R2SippySecretInput.of("syntheticstorage"))
                owner.setField(R2SippySecretField.SOURCE_CONTAINER, R2SippySecretInput.of("synthetic-container"))
                owner.setField(R2SippySecretField.AZURE_SAS_TOKEN, R2SippySecretInput.of(marker))
            }
        }
        owner.setField(R2SippySecretField.DESTINATION_ACCESS_KEY_ID, R2SippySecretInput.of("SYNTHETIC-R2-KEY-ID"))
        owner.setField(R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY, R2SippySecretInput.of(marker))
    }

    @Test
    fun withoutTheRealWindowProtectionNoSecretIsStoredAndNoWriteExists() {
        val denied = owner(FakeSecureWindowPort(grant = false))
        assertFalse(denied.beginSecureEditing())
        assertFalse(denied.setField(R2SippySecretField.SOURCE_BUCKET, R2SippySecretInput.of("bucket")))
        assertNull(denied.liveWrite(target))
        assertFalse(denied.publicState.secureReady)
        assertTrue(denied.publicState.filled.isEmpty())

        // No bridge at all is a refusal too, never an optimistic "secure".
        val missing = owner(null)
        assertFalse(missing.beginSecureEditing())
        assertNull(missing.liveWrite(target))
        assertFalse(missing.publicState.secureReady)
    }

    @Test
    fun theEditorsPublicStateCarriesPresenceMetadataOnly() {
        val port = FakeSecureWindowPort()
        val changes = mutableListOf<Long>()
        val owner = owner(port, changes)
        assertTrue(owner.beginSecureEditing())
        assertTrue(owner.publicState.secureReady)
        fill(owner)

        val state = owner.publicState
        assertTrue(state.complete)
        assertTrue(state.filled.contains(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY))
        assertFalse(state.toString().contains(marker), "a credential value never reaches the public state")
        // Every edit produced a new draft binding and revoked the previous preview.
        assertTrue(changes.isNotEmpty())
        assertTrue(state.revision > 0)
        assertNotNull(owner.liveWrite(target))
        assertEquals(R2SippyProvider.AWS, state.provider)
    }

    @Test
    fun secretInputAndTheLiveWriteAreRedactedInTheirOwnRendering() {
        val input = R2SippySecretInput.of(marker)
        assertFalse(input.toString().contains(marker))
        val port = FakeSecureWindowPort()
        val owner = owner(port)
        owner.beginSecureEditing()
        fill(owner)
        val write = assertNotNull(owner.liveWrite(target))
        assertFalse(write.toString().contains(marker), "the typed write never prints the secret")
        assertEquals(target, write.target)
        // A masked/echoed GET value can never be reused: the owner only reports presence.
        assertFalse(owner.publicState.toString().contains("SYNTHETIC-SOURCE-KEY-ID"))
    }

    @Test
    fun everyEditRetiresThePreviousDraftAndWithdrawReleasesTheMaterial() {
        val owner = owner(FakeSecureWindowPort())
        owner.beginSecureEditing()
        fill(owner)
        val first = assertNotNull(owner.liveWrite(target))
        val firstRevision = owner.publicState.revision

        owner.setField(R2SippySecretField.SOURCE_REGION, R2SippySecretInput.of("eu-west-1"))
        val second = assertNotNull(owner.liveWrite(target))
        assertFalse(first.lease === second.lease, "a new binding is generated for the edited draft")
        assertFalse(first.lease.isDraft, "the previous draft material is released")
        assertTrue(second.lease.isDraft)
        assertTrue(owner.publicState.revision > firstRevision)

        owner.withdraw()
        assertFalse(owner.publicState.complete)
        assertNull(owner.liveWrite(target))
        assertFalse(second.lease.isDraft, "withdrawing releases the current material")
        assertTrue(owner.publicState.filled.isEmpty())
    }

    @Test
    fun aProviderSwitchNeverCarriesAnyPreviousPrivateValueOver() {
        val owner = owner(FakeSecureWindowPort())
        owner.beginSecureEditing()
        fill(owner, R2SippyProvider.AWS)
        assertTrue(owner.publicState.complete)
        assertTrue(owner.selectProvider(R2SippyProvider.GCS))
        assertFalse(owner.publicState.complete)
        assertTrue(owner.publicState.filled.isEmpty())
        assertNull(owner.liveWrite(target))
        // A field that does not belong to the selected provider is refused.
        assertFalse(owner.setField(R2SippySecretField.SOURCE_REGION, R2SippySecretInput.of("eu")))
    }

    @Test
    fun aLostWindowOrANewGenerationWithdrawsTheMaterialAndRequiresReEntry() {
        val first = FakeSecureWindowPort(generation = 11L)
        val second = FakeSecureWindowPort(generation = 12L)
        var port: SecureWindowPort? = first
        val owner =
            R2SippyCredentialOwner(secureWindow = { port }, onDraftChanged = {})
        assertTrue(owner.beginSecureEditing())
        fill(owner)
        val write = assertNotNull(owner.liveWrite(target))

        // A stale generation never clears the new window's session, and the new window is not ours.
        owner.onWindowDetached(10L)
        assertTrue(owner.publicState.secureReady)

        // The real window leaves: readiness and material go away, and the old write cannot be used.
        port = second
        owner.onWindowDetached(11L)
        assertFalse(owner.publicState.secureReady)
        assertNull(owner.liveWrite(target))
        assertFalse(write.lease.isDraft)
        // The outgoing window keeps its own protection until its own close(): the app never uncovers
        // a surface that may still be the visible one. The *new* window must acquire by itself.
        assertEquals(0, first.releases)
        assertEquals(setOf(SecurePurpose.SIPPY_CREDENTIALS), first.purposes)
        assertEquals(0, second.requests)
        // Re-entry needs the new window.
        assertTrue(owner.beginSecureEditing())
        assertEquals(1, second.requests)
    }

    @Test
    fun theRealFlagBeingClearedWhileEditingIsDetectedAndClearsTheMaterial() {
        val port = FakeSecureWindowPort()
        val owner = owner(port)
        owner.beginSecureEditing()
        fill(owner)
        assertNotNull(owner.liveWrite(target))
        // Someone cleared the flag (or the purpose was released): the editor must notice.
        port.purposes.clear()
        assertFalse(owner.isSecureNow())
        assertFalse(owner.publicState.secureReady)
        assertNull(owner.liveWrite(target))
        assertFalse(owner.publicState.complete)
    }
}

/**
 * Task 5c fix1: a replaced window must lose readiness *immediately*, not at the next getter, and the
 * secret eligibility must be withdrawn before the window protection is released.
 */
class R2SippyCredentialOwnerWindowTest {
    private val connection = Connection("r2-local", "fixture", "account-one")
    private val account = AccountScope(connection.id, connection.accountId)
    private val target = R2WriteTarget(account, "my-bucket")
    private val marker = "SYNTHETIC-WINDOW-SECRET-MARKER-2f4"

    /** Records what the owner looks like at the exact moment the protection is released. */
    private class RecordingPort(
        override val generation: Long,
        var grant: Boolean = true,
        var onRelease: (() -> Unit)? = null,
    ) : SecureWindowPort {
        /**
         * Live handles, exactly like the production owner's identity set: a purpose is held while at
         * least one handle for it is live, so releasing one handle never uncovers another one.
         */
        val handles = mutableSetOf<SecureWindowSession>()
        val purposes: Set<SecurePurpose> get() = handles.map { it.purpose }.toSet()
        val requested = mutableListOf<SecurePurpose>()
        var stateAtRelease: R2SippyCredentialUiState? = null
        var writeAtRelease: Any? = null
        /** True models an observable clean frame; false an unobservable window. */
        var barrierAvailable = true
        var releases = 0
        val armed = mutableSetOf<SecureWindowSession>()

        override fun armRetirement(session: SecureWindowSession): Boolean {
            if (!barrierAvailable) return false
            if (session.generation != generation) return false
            armed += session
            return true
        }

        override fun contentRemoved(session: SecureWindowSession) {
            if (!armed.remove(session)) return
            release(session)
        }

        override fun request(purpose: SecurePurpose): SecureWindowSession? {
            if (!grant) return null
            requested += purpose
            val session = Session(purpose)
            handles += session
            return session
        }

        override fun release(session: SecureWindowSession) {
            if (session.generation != generation) return
            if (!handles.remove(session)) return
            releases++
            stateAtRelease = null
            onRelease?.invoke()
        }

        inner class Session(override val purpose: SecurePurpose) : SecureWindowSession {
            override val generation: Long get() = this@RecordingPort.generation

            override fun isProtected(): Boolean =
                this@RecordingPort.generation == generation && grant && handles.contains(this)
        }
    }

    private fun fill(owner: R2SippyCredentialOwner) {
        owner.setField(R2SippySecretField.SOURCE_BUCKET, R2SippySecretInput.of("source-bucket"))
        owner.setField(R2SippySecretField.SOURCE_REGION, R2SippySecretInput.of("us-east-1"))
        owner.setField(R2SippySecretField.SOURCE_ACCESS_KEY_ID, R2SippySecretInput.of("SYNTHETIC-KEY-ID"))
        owner.setField(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY, R2SippySecretInput.of(marker))
        owner.setField(R2SippySecretField.DESTINATION_ACCESS_KEY_ID, R2SippySecretInput.of("SYNTHETIC-R2-KEY"))
        owner.setField(R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY, R2SippySecretInput.of(marker))
    }

    @Test
    fun attachingANewWindowImmediatelyRevokesTheOldReadyStateAndThePrivateMaterial() {
        val old = RecordingPort(generation = 31L)
        val fresh = RecordingPort(generation = 32L)
        var current: SecureWindowPort? = old
        lateinit var owner: R2SippyCredentialOwner
        owner = R2SippyCredentialOwner(secureWindow = { current }, onDraftChanged = {})
        assertTrue(owner.beginSecureEditing())
        fill(owner)
        val write = assertNotNull(owner.liveWrite(target))
        assertTrue(owner.publicState.secureReady)
        assertTrue(owner.publicState.complete)
        assertEquals(setOf(SecurePurpose.SIPPY_CREDENTIALS), old.purposes)

        // The Activity is replaced before the old one is destroyed: the app attaches the new window.
        current = fresh
        owner.onWindowAttached(fresh.generation)

        // Immediately (no getter, no old onDestroy): nothing is ready and nothing is readable.
        assertFalse(owner.publicState.secureReady, "a replaced window must not stay 'ready'")
        assertFalse(owner.publicState.complete)
        assertTrue(owner.publicState.filled.isEmpty())
        assertNull(owner.liveWrite(target))
        assertFalse(
            owner.setField(R2SippySecretField.SOURCE_REGION, R2SippySecretInput.of("eu-west-1")),
            "secret input must be refused on the new, still unprotected window",
        )
        assertFalse(write.lease.isDraft, "the old draft material was released")
        // The outgoing surface keeps its own protection until its own close(); the new window must
        // acquire protection itself before anything becomes ready again.
        assertEquals(setOf(SecurePurpose.SIPPY_CREDENTIALS), old.purposes)
        assertTrue(fresh.purposes.isEmpty())
        assertFalse(fresh.stateAtRelease?.secureReady ?: false)

        assertTrue(owner.beginSecureEditing())
        assertEquals(setOf(SecurePurpose.SIPPY_CREDENTIALS), fresh.purposes)
        assertTrue(owner.publicState.secureReady)
        // The old window is still protected; the new ready state was acquired by the new window.
        assertEquals(setOf(SecurePurpose.SIPPY_CREDENTIALS), old.purposes)
    }

    @Test
    fun theLiveCheckItselfRejectsAReplacedPortEvenWithoutAnAttachNotification() {
        val old = RecordingPort(generation = 41L)
        val fresh = RecordingPort(generation = 42L)
        var current: SecureWindowPort? = old
        val owner = R2SippyCredentialOwner(secureWindow = { current }, onDraftChanged = {})
        assertTrue(owner.beginSecureEditing())
        fill(owner)
        val write = assertNotNull(owner.liveWrite(target))

        // Only the pointer changed (no explicit notification): the live check must notice identity.
        current = fresh
        assertFalse(owner.isSecureNow())
        assertFalse(owner.publicState.secureReady)
        assertNull(owner.liveWrite(target))
        assertFalse(write.lease.isDraft)
        assertTrue(owner.publicState.filled.isEmpty())
    }

    @Test
    fun secretEligibilityIsWithdrawnBeforeTheWindowProtectionIsReleased() {
        val port = RecordingPort(generation = 51L)
        lateinit var owner: R2SippyCredentialOwner
        port.onRelease = {
            // Observed exactly when the protection is being withdrawn.
            port.stateAtRelease = owner.publicState
            port.writeAtRelease = owner.liveWrite(target)
        }
        owner = R2SippyCredentialOwner(secureWindow = { port }, onDraftChanged = {})
        assertTrue(owner.beginSecureEditing())
        fill(owner)
        assertNotNull(owner.liveWrite(target))
        // The mounted surface captures the immutable session epoch while it draws the secrets.
        val surfaceEpoch = assertNotNull(owner.publicState.secureEpoch)

        owner.endSecureEditing()

        // Fail-closed ordering: eligibility/material are gone, but the protection is *not* released
        // until the composition reports the surface gone and the window observes a clean commit.
        assertFalse(owner.publicState.secureReady)
        assertFalse(owner.publicState.complete)
        assertTrue(port.purposes.isNotEmpty(), "the protection must be kept while no clean commit is known")
        owner.onSecretSurfaceRemoved(surfaceEpoch)

        val atRelease = assertNotNull(port.stateAtRelease, "the protection was released after the clean-frame signal")
        assertFalse(atRelease.secureReady, "eligibility is revoked before the flag is released")
        assertFalse(atRelease.complete)
        assertTrue(atRelease.filled.isEmpty())
        assertNull(port.writeAtRelease, "no live write exists at the moment the protection leaves")
        assertTrue(port.purposes.isEmpty())
    }

    @Test
    fun anUnobservableWindowKeepsProtectingAfterTheSessionIsRevoked() {
        val port = RecordingPort(generation = 71L).apply { barrierAvailable = false }
        val owner = R2SippyCredentialOwner(secureWindow = { port }, onDraftChanged = {})
        assertTrue(owner.beginSecureEditing())
        fill(owner)
        val write = assertNotNull(owner.liveWrite(target))
        val surfaceEpoch = assertNotNull(owner.publicState.secureEpoch)

        owner.endSecureEditing()
        owner.onSecretSurfaceRemoved(surfaceEpoch)

        // Fail-closed: eligibility and material are gone, the stale session cannot execute anything,
        // and the window protection is still held because no clean commit could be observed.
        assertFalse(owner.publicState.secureReady)
        assertNull(owner.liveWrite(target))
        assertNull(owner.currentValue(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY))
        assertFalse(write.lease.isDraft)
        assertTrue(port.purposes.isNotEmpty(), "an unobservable window must keep its protection")
        assertEquals(0, port.releases)
    }

    @Test
    fun aReentryKeepsTheOutstandingRetirementSoBothHandlesAreEventuallyReleased() {
        val port = RecordingPort(generation = 81L)
        val owner = R2SippyCredentialOwner(secureWindow = { port }, onDraftChanged = {})
        assertTrue(owner.beginSecureEditing())
        fill(owner)
        val firstEpoch = assertNotNull(owner.publicState.secureEpoch)
        owner.endSecureEditing()
        assertTrue(port.purposes.isNotEmpty(), "the retired handle keeps the protection until its clean frame")
        assertTrue(owner.beginSecureEditing(), "re-entry must acquire a second live handle")
        fill(owner)
        val secondEpoch = assertNotNull(owner.publicState.secureEpoch)
        assertTrue(firstEpoch != secondEpoch, "a re-entry must get its own session epoch")
        owner.endSecureEditing()
        // Each mounted surface reports its own removal exactly once.
        owner.onSecretSurfaceRemoved(firstEpoch)
        owner.onSecretSurfaceRemoved(secondEpoch)
        assertTrue(port.armed.isEmpty(), "both retired handles must be confirmed by their own surface")
        assertTrue(port.purposes.isEmpty(), "no protection may stay behind after both clean frames")
    }

    @Test
    fun aLateOrForeignSurfaceReportCannotConfirmANewerSession() {
        val port = RecordingPort(generation = 91L)
        val owner = R2SippyCredentialOwner(secureWindow = { port }, onDraftChanged = {})
        assertTrue(owner.beginSecureEditing())
        fill(owner)
        val oldEpoch = assertNotNull(owner.publicState.secureEpoch)
        owner.endSecureEditing()
        assertTrue(owner.beginSecureEditing(), "the editor re-enters")
        fill(owner)
        val newEpoch = assertNotNull(owner.publicState.secureEpoch)
        assertTrue(oldEpoch != newEpoch, "a new live session has its own epoch")
        assertEquals(1, port.armed.size, "the older retirement is still outstanding")

        // A late report of the *older* surface confirms only the older retirement.
        owner.onSecretSurfaceRemoved(oldEpoch)
        assertEquals(0, port.armed.size, "the older retirement is confirmed by its own report")
        assertTrue(owner.publicState.secureReady, "the live session is untouched")
        assertTrue(owner.publicState.complete, "the newer draft is intact")
        assertNotNull(owner.currentValue(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY))

        // Duplicate / foreign / empty reports change nothing at all.
        owner.onSecretSurfaceRemoved(oldEpoch)
        owner.onSecretSurfaceRemoved(0L)
        owner.onSecretSurfaceRemoved(null)
        assertTrue(owner.publicState.secureReady)
        assertTrue(owner.publicState.complete)
        assertEquals(0, port.armed.size)

        // The live session retires on its own; only its own epoch may confirm it.
        owner.endSecureEditing()
        assertEquals(1, port.armed.size)
        owner.onSecretSurfaceRemoved(oldEpoch)
        assertEquals(1, port.armed.size, "an older epoch cannot confirm the newer retirement")
        owner.onSecretSurfaceRemoved(newEpoch)
        assertTrue(port.armed.isEmpty())
        assertTrue(port.purposes.isEmpty(), "no protection stays behind")
    }

    @Test
    fun aScreenExitConfirmsARetirementWhoseSecretSurfaceNeverMounted() {
        val port = RecordingPort(generation = 111L)
        val owner = R2SippyCredentialOwner(secureWindow = { port }, onDraftChanged = {})
        val mount = owner.newSurfaceMount()
        assertTrue(owner.beginSecureEditing())
        val epoch = assertNotNull(owner.publicState.secureEpoch)
        // begin+end land before any recomposition could mount the epoch's secret surface: no
        // composition ever holds this epoch, so no branch report can ever arrive for it.
        owner.endSecureEditing()
        assertFalse(owner.publicState.secureReady)
        assertEquals(1, port.armed.size, "the retirement is armed and keeps the protection")
        assertTrue(epoch > 0L)
        // The whole screen leaves: the only remaining reporter is the mount itself.
        owner.onSurfaceMountDisposed(mount)
        assertTrue(
            port.armed.isEmpty(),
            "the exiting screen must confirm every already-retired handle whose surface is gone",
        )
    }

    @Test
    fun aScreenExitNeverReleasesWithoutTheCleanFrameEvidence() {
        val port = RecordingPort(generation = 113L).apply { barrierAvailable = false }
        val owner = R2SippyCredentialOwner(secureWindow = { port }, onDraftChanged = {})
        val mount = owner.newSurfaceMount()
        assertTrue(owner.beginSecureEditing())
        fill(owner)
        assertTrue(owner.beginSecureEditing().not() || owner.publicState.secureReady)
        owner.endSecureEditing()
        // The window cannot observe a clean frame: nothing may be released, not even on exit.
        owner.onSurfaceMountDisposed(mount)
        assertTrue(port.purposes.isNotEmpty(), "an unobservable window keeps its protection")
        assertTrue(port.armed.isEmpty(), "no retirement is left armed, but the handle still protects")
    }

    @Test
    fun aDetachedWindowOfAnotherGenerationNeverTouchesTheCurrentReadyState() {
        val current = RecordingPort(generation = 61L)
        val owner = R2SippyCredentialOwner(secureWindow = { current }, onDraftChanged = {})
        assertTrue(owner.beginSecureEditing())
        fill(owner)
        owner.onWindowDetached(99L)
        assertTrue(owner.publicState.secureReady)
        assertTrue(owner.publicState.complete)
        owner.onWindowDetached(61L)
        assertFalse(owner.publicState.secureReady)
        assertFalse(owner.publicState.complete)
    }
}

/**
 * Task 6a: the minimal private getter and the client-side input bound.
 */
class R2SippyCredentialOwnerFormTest {
    private val connection = Connection("r2-local", "fixture", "account-one")
    private val target = R2WriteTarget(AccountScope(connection.id, connection.accountId), "my-bucket")
    private val marker = "SYNTHETIC-FORM-SECRET-MARKER-9a3"

    private fun owner(port: FakeSecureWindowPort = FakeSecureWindowPort()) =
        R2SippyCredentialOwner(secureWindow = { port }, onDraftChanged = {})

    @Test
    fun thePrivateGetterOnlyServesTheFieldItWasAskedForWhileProtected() {
        val port = FakeSecureWindowPort()
        val owner = owner(port)
        assertTrue(owner.beginSecureEditing())
        assertTrue(owner.setField(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY, R2SippySecretInput.of(marker)))
        assertTrue(owner.currentValue(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY)?.reveal() == marker)
        // Only that field; another field of the same provider is not invented.
        assertNull(owner.currentValue(R2SippySecretField.SOURCE_ACCESS_KEY_ID))
        // A field of another provider is refused.
        assertNull(owner.currentValue(R2SippySecretField.GCS_PRIVATE_KEY))
        // The public state still carries presence only.
        assertFalse(owner.publicState.toString().contains(marker))
        // Losing the protection withdraws the getter and the material.
        port.purposes.clear()
        assertNull(owner.currentValue(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY))
        assertFalse(owner.publicState.complete)
    }

    @Test
    fun anOversizedValueIsRejectedInsteadOfTruncatedOrEchoed() {
        val owner = owner()
        owner.beginSecureEditing()
        val allowed = "a".repeat(R2_SIPPY_MAX_INPUT_BYTES)
        assertTrue(owner.setField(R2SippySecretField.SOURCE_BUCKET, R2SippySecretInput.of(allowed)))
        val oversized = "b".repeat(R2_SIPPY_MAX_INPUT_BYTES + 1)
        assertFalse(owner.setField(R2SippySecretField.SOURCE_BUCKET, R2SippySecretInput.of(oversized)))
        // The previous value is untouched (no truncation), and the error never echoes the input.
        assertTrue(owner.currentValue(R2SippySecretField.SOURCE_BUCKET)?.reveal() == allowed)
        val error = assertNotNull(owner.publicState.error)
        assertFalse(error.contains(oversized))
        assertFalse(error.contains("bbb"))
    }

    @Test
    fun aRejectedOversizedEntryDoesNotCreateADraftOrLeakIntoTheState() {
        val owner = owner()
        owner.beginSecureEditing()
        val oversized = "c".repeat(R2_SIPPY_MAX_INPUT_BYTES * 2)
        assertFalse(owner.setField(R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY, R2SippySecretInput.of(oversized)))
        assertNull(owner.currentValue(R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY))
        assertFalse(owner.publicState.filled.contains(R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY))
        assertFalse(owner.publicState.toString().contains("cccc"))
        assertNull(owner.liveWrite(target))
    }
}

/**
 * Task 6a fix2: a *rejected* oversized edit must not leave the previous draft and its acknowledgements
 * usable — every edit (accepted or refused) has to invalidate the issued preview.
 */
class R2SippyCredentialOwnerRejectedEditTest {
    private val marker = "SYNTHETIC-REJECT-OLD-MARKER-3b8"

    @Test
    fun aRejectedOversizedEditRetiresTheOldDraftAndItsPreview() {
        val port = FakeSecureWindowPort()
        val changes = mutableListOf<Long>()
        val owner = R2SippyCredentialOwner(secureWindow = { port }, onDraftChanged = { changes += 1 })
        assertTrue(owner.beginSecureEditing())
        assertTrue(owner.setField(R2SippySecretField.SOURCE_BUCKET, R2SippySecretInput.of("source-bucket")))
        assertTrue(owner.setField(R2SippySecretField.SOURCE_REGION, R2SippySecretInput.of("us-east-1")))
        assertTrue(owner.setField(R2SippySecretField.SOURCE_ACCESS_KEY_ID, R2SippySecretInput.of("SYNTHETIC-SOURCE-KEY")))
        assertTrue(owner.setField(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY, R2SippySecretInput.of(marker)))
        assertTrue(owner.setField(R2SippySecretField.DESTINATION_ACCESS_KEY_ID, R2SippySecretInput.of("SYNTHETIC-R2-KEY")))
        assertTrue(owner.setField(R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY, R2SippySecretInput.of(marker)))
        val valid = assertNotNull(owner.liveWrite(R2WriteTarget(AccountScope("c", "a"), "my-bucket")))
        val changesBefore = changes.size

        // The oversized value is refused: no truncation, no echo ... and no stale draft survives it.
        assertFalse(owner.setField(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY, R2SippySecretInput.of("x".repeat(9 * 1024))))
        assertFalse(valid.lease.isDraft, "the previous draft material must be released by the refused edit")
        assertNull(owner.liveWrite(R2WriteTarget(AccountScope("c", "a"), "my-bucket")))
        assertFalse(owner.publicState.complete)
        assertTrue(changes.size > changesBefore, "a refused edit must still invalidate the issued preview")
        assertFalse(owner.publicState.error.isNullOrBlank())
        // The rejected value is not stored and not echoed; the previous valid text is kept (this is
        // "reject, never truncate"), but it can no longer be submitted without a fresh preview+ack.
        val kept = assertNotNull(owner.currentValue(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY))
        assertTrue(kept.reveal() != "x".repeat(9 * 1024))
        assertFalse(owner.publicState.toString().contains("xxxx"))
    }
}
