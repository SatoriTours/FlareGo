package com.flarego.core

import com.flarego.core.application.r2.R2SippyAwsSourceSecrets
import com.flarego.core.application.r2.R2SippyDestinationSecrets
import com.flarego.core.application.r2.R2SippySecrets
import com.flarego.core.application.r2.R2TaskTargets
import com.flarego.core.model.AccountScope
import com.flarego.core.model.OperationTask
import com.flarego.core.model.R2ConfigurationKind
import com.flarego.core.model.R2SippyAction
import com.flarego.core.model.R2SippyWritePreview
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.ResourceRef
import com.flarego.core.model.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 5b: the trusted Sippy locator/family and the safe preview payload.
 *
 * The locator is read-only identity: it can never restore an executable command or a credential,
 * and a malformed or borrowed locator fails closed instead of unlocking a bucket.
 */
class R2SippyLocatorTest {
    private val account = AccountScope("local", "account-one")
    private val otherAccount = AccountScope("local", "account-two")
    private val bucket = "my-bucket"
    private val target = R2WriteTarget(account, bucket)
    private val eu = R2WriteTarget(account, bucket, "eu")

    private fun task(
        actionKey: String,
        reference: ResourceRef?,
        state: TaskState = TaskState.UNKNOWN,
        scope: AccountScope = account,
    ) = OperationTask(
        id = "task",
        connectionId = scope.connectionId,
        scope = scope,
        actionKey = actionKey,
        resourceRef = reference,
        requestFingerprint = "a".repeat(64),
        desiredHash = "b".repeat(64),
        state = state,
        safeDetail = "任务状态未确定",
        providerRequestId = null,
        pollPath = null,
        createdAtMs = 1,
        updatedAtMs = 1,
    )

    @Test
    fun theSippyFamilyIsAKnownWriteFamilyWithItsOwnLocator() {
        assertEquals("SIPPY", R2TaskTargets.familyOf("r2-put-bucket-sippy-config"))
        assertEquals("SIPPY", R2TaskTargets.familyOf("r2-delete-bucket-sippy-config"))
        assertEquals("CORS", R2TaskTargets.familyOf("r2-put-bucket-cors-policy"))
        assertTrue(R2TaskTargets.ALL_WRITE_ACTIONS.containsAll(R2TaskTargets.SIPPY_ACTIONS))

        val reference = R2TaskTargets.sippyReference(eu)
        assertEquals(R2TaskTargets.CONFIGURATION_KIND, reference.kind)
        assertEquals("eu/$bucket/SIPPY", reference.externalId)
        val parsed = assertNotNull(R2TaskTargets.parse(reference))
        assertEquals(account, parsed.account)
        assertEquals(bucket, parsed.bucket)
        assertEquals("eu", parsed.jurisdiction)
        assertTrue(parsed.sippy)
        assertNull(parsed.kind)
        assertNull(parsed.domainKind)
        assertNull(parsed.domain)

        // Sippy shares the batch A bucket group in the default jurisdiction only.
        assertEquals(ResourceRef(account, R2TaskTargets.BUCKET_KIND, bucket), R2TaskTargets.group(target))
        assertEquals(ResourceRef(account, R2TaskTargets.CONFIGURATION_KIND, "eu/$bucket"), R2TaskTargets.group(eu))
    }

    @Test
    fun anUnsettledSippyTaskBlocksExactlyItsOwnBucket() {
        val put = task("r2-put-bucket-sippy-config", R2TaskTargets.sippyReference(target))
        assertTrue(R2TaskTargets.blocks(put, target))
        assertFalse(R2TaskTargets.blocks(put, eu), "another jurisdiction is another bucket")
        assertFalse(R2TaskTargets.blocks(put, R2WriteTarget(otherAccount, bucket)), "another account is not blocked")
        assertFalse(R2TaskTargets.blocks(put.copy(state = TaskState.SUCCEEDED), target))
        // A settled task never blocks.
        assertFalse(R2TaskTargets.blocks(put.copy(state = TaskState.FAILED), target))
    }

    @Test
    fun aMalformedOrBorrowedSippyLocatorFailsClosed() {
        // A trailing segment is not the reviewed Sippy locator.
        val malformed = ResourceRef(account, R2TaskTargets.CONFIGURATION_KIND, "default/$bucket/SIPPY/extra")
        assertNull(R2TaskTargets.parse(malformed))
        assertTrue(R2TaskTargets.blocks(task("r2-put-bucket-sippy-config", malformed), target))

        // A Sippy action whose locator names another family blocks instead of being compared loosely.
        val cors = ResourceRef(account, R2TaskTargets.CONFIGURATION_KIND, "default/$bucket/${R2ConfigurationKind.CORS.name}")
        assertTrue(R2TaskTargets.blocks(task("r2-put-bucket-sippy-config", cors), target))

        // A batch B1 action cannot borrow a Sippy locator either.
        val sippy = R2TaskTargets.sippyReference(target)
        assertTrue(R2TaskTargets.blocks(task("r2-put-bucket-cors-policy", sippy), target))

        // A Sippy task without a locator blocks every R2 target of its account.
        assertTrue(R2TaskTargets.blocks(task("r2-delete-bucket-sippy-config", null), target))
        assertFalse(R2TaskTargets.blocks(task("r2-delete-bucket-sippy-config", null), R2WriteTarget(otherAccount, bucket)))
    }

    @Test
    fun theSafePreviewPayloadHoldsOnlyPublicMetadata() {
        val marker = "SYNTHETIC-PREVIEW-SECRET-MARKER-7d"
        val lease = R2SippySecrets.draft(
            R2SippyAwsSourceSecrets("SYNTHETIC-KEY-ID", marker, "source-bucket", "us-east-1"),
            R2SippyDestinationSecrets("SYNTHETIC-DEST-KEY-ID", marker),
        )
        val preview =
            R2SippyWritePreview(
                action = R2SippyAction.ENABLE,
                target = target,
                provider = lease.safeSource.provider,
                source = lease.safeSource,
                destinationAccessKeyConfigured = true,
            )
        assertEquals("source-bucket", preview.source?.bucket)
        assertEquals("us-east-1", preview.source?.region)
        assertTrue(preview.destinationAccessKeyConfigured)
        assertFalse(preview.toString().contains(marker))
        assertFalse(preview.toString().contains("SYNTHETIC-KEY-ID"))
        // The lease's own public projection never carries the credential either.
        assertFalse(lease.toString().contains(marker))
    }
}
