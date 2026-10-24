package com.flarego.core

import com.flarego.core.application.r2.R2TaskTargets
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudScope
import com.flarego.core.model.OperationTask
import com.flarego.core.model.R2ConfigurationKind
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.ResourceRef
import com.flarego.core.model.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared locator/group/gate vocabulary of batch A and batch B: default configuration
 * and batch A share one bucket identity, another jurisdiction never collides, and a
 * malformed but trusted R2 locator can never fail open.
 */
class R2TaskTargetsTest {
    private val connection = "local"
    private val account = AccountScope(connection, "account-one")
    private val otherAccount = AccountScope(connection, "account-two")
    private val bucket = "my-bucket"
    private val defaultTarget = R2WriteTarget(account, bucket)
    private val euTarget = R2WriteTarget(account, bucket, "eu")
    private val usTarget = R2WriteTarget(account, bucket, "us")

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
    fun theDefaultConfigurationSharesTheBatchABucketGroupWhileOtherRegionsNeverCollide() {
        assertEquals(ResourceRef(account, R2TaskTargets.BUCKET_KIND, bucket), R2TaskTargets.group(defaultTarget))
        val eu = R2TaskTargets.group(euTarget)
        val us = R2TaskTargets.group(usTarget)
        assertEquals(R2TaskTargets.CONFIGURATION_KIND, eu.kind)
        assertEquals("eu/$bucket", eu.externalId)
        assertEquals("us/$bucket", us.externalId)
        assertTrue(eu != us)
        assertTrue(eu != R2TaskTargets.group(defaultTarget))
        // A same-named bucket in another jurisdiction is a different identity, not a rename.
        assertTrue(R2TaskTargets.group(euTarget).kind != R2TaskTargets.group(defaultTarget).kind)
    }

    @Test
    fun anUnsettledDefaultConfigurationTaskBlocksBatchABucketAndObjectWrites() {
        val pending = task("r2-put-bucket-cors-policy", R2TaskTargets.reference(defaultTarget, R2ConfigurationKind.CORS))
        assertTrue(R2TaskTargets.blocks(pending, defaultTarget))
        assertFalse(R2TaskTargets.blocks(pending, euTarget), "another jurisdiction is another bucket identity")
        assertFalse(R2TaskTargets.blocks(pending, R2WriteTarget(account, "other-bucket")))
        assertFalse(R2TaskTargets.blocks(pending.copy(state = TaskState.SUCCEEDED), defaultTarget))
        assertFalse(R2TaskTargets.blocks(pending.copy(state = TaskState.FAILED), defaultTarget))
        for (state in listOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.PARTIAL, TaskState.ACCEPTED,
            TaskState.RECONCILING, TaskState.ACTION_REQUIRED)) {
            assertTrue(R2TaskTargets.blocks(pending.copy(state = state), defaultTarget), state.name)
        }
    }

    @Test
    fun anUnsettledBatchABucketOrObjectTaskBlocksOnlyTheDefaultConfiguration() {
        val bucketTask = task("r2-patch-bucket", ResourceRef(account, R2TaskTargets.BUCKET_KIND, bucket))
        assertTrue(R2TaskTargets.blocks(bucketTask, defaultTarget))
        assertFalse(R2TaskTargets.blocks(bucketTask, euTarget))
        val objectTask = task("r2-delete-object", ResourceRef(account, R2TaskTargets.OBJECT_KIND, "$bucket/a.txt"))
        assertTrue(R2TaskTargets.blocks(objectTask, defaultTarget))
        assertFalse(R2TaskTargets.blocks(objectTask, euTarget))
    }

    @Test
    fun anUnknownOrMismatchedTrustedR2LocatorFailsClosedAndOtherProductsAreNotClassified() {
        // A trusted R2 action with no locator at all cannot prove it is unrelated.
        assertTrue(R2TaskTargets.blocks(task("r2-put-bucket-cors-policy", null), defaultTarget))
        assertTrue(R2TaskTargets.blocks(task("r2-put-bucket-cors-policy", null), euTarget))
        // An action/ref-kind mismatch is not trusted evidence either.
        assertTrue(R2TaskTargets.blocks(task("r2-put-bucket-cors-policy", ResourceRef(account, R2TaskTargets.OBJECT_KIND, "$bucket/a.txt")), defaultTarget))
        assertTrue(R2TaskTargets.blocks(task("r2-put-bucket-cors-policy", R2TaskTargets.reference(defaultTarget, R2ConfigurationKind.LOCK)), defaultTarget))
        assertTrue(R2TaskTargets.blocks(task("r2-delete-bucket", R2TaskTargets.reference(euTarget, R2ConfigurationKind.CORS)), defaultTarget))
        // A locator of another scope cannot even be persisted: OperationTask refuses it.
        assertFailsWith<com.flarego.core.model.CloudFailure> {
            task("r2-delete-bucket", ResourceRef(otherAccount, R2TaskTargets.BUCKET_KIND, bucket))
        }
        // Unknown configuration-kind text cannot silently mean "not this bucket".
        assertTrue(R2TaskTargets.blocks(task("r2-put-bucket-cors-policy",
            ResourceRef(account, R2TaskTargets.CONFIGURATION_KIND, "eu/$bucket/FUTURE")), euTarget))
        // Another product is never classified as an R2 write.
        assertFalse(R2TaskTargets.blocks(task("dns-record-create", ResourceRef(account, R2TaskTargets.BUCKET_KIND, bucket)), defaultTarget))
        assertFalse(R2TaskTargets.blocks(task("r2-list-buckets", ResourceRef(account, R2TaskTargets.BUCKET_KIND, bucket)), defaultTarget))
    }

    @Test
    fun anotherAccountIsNeverBlockedAndASettledTaskNeverBlocks() {
        val pending = task("r2-put-bucket-lock-configuration", R2TaskTargets.reference(euTarget, R2ConfigurationKind.LOCK))
        assertFalse(R2TaskTargets.blocks(pending, R2WriteTarget(otherAccount, bucket, "eu")))
        assertTrue(R2TaskTargets.blocks(pending, euTarget))
    }

    @Test
    fun aConfigurationReferenceParsesReadOnlyIntoItsExactIdentity() {
        val reference = R2TaskTargets.reference(euTarget, R2ConfigurationKind.LOCK)
        val location = assertNotNull(R2TaskTargets.parse(reference))
        assertEquals(euTarget, R2WriteTarget(location.account, location.bucket, location.jurisdiction))
        assertEquals(R2ConfigurationKind.LOCK, location.kind)
        assertEquals(account, location.account)
        // A group locator is not a persisted write reference and no locator restores a command.
        assertNull(R2TaskTargets.parse(R2TaskTargets.group(euTarget)))
        assertNull(R2TaskTargets.parse(ResourceRef(account, R2TaskTargets.BUCKET_KIND, bucket)))
        assertNull(R2TaskTargets.parse(null))
        assertNull(R2TaskTargets.parse(ResourceRef(account, R2TaskTargets.CONFIGURATION_KIND, "mars/$bucket/CORS")))
        assertNull(R2TaskTargets.parse(ResourceRef(account, R2TaskTargets.CONFIGURATION_KIND, "eu/$bucket")))
        assertNull(R2TaskTargets.parse(ResourceRef(account, R2TaskTargets.CONFIGURATION_KIND, "eu/$bucket/CORS/extra")))
    }

    @Test
    fun aMalformedTrustedLocatorBlocksEveryLegalTargetButOldLegalLocatorsStillClassify() {
        val junk = listOf(
            task("r2-delete-bucket", ResourceRef(account, R2TaskTargets.BUCKET_KIND, "bad bucket")),
            task("r2-patch-bucket", ResourceRef(account, R2TaskTargets.BUCKET_KIND, "bad_bucket")),
            task("r2-delete-object", ResourceRef(account, R2TaskTargets.OBJECT_KIND, "no-slash")),
            task("r2-delete-object", ResourceRef(account, R2TaskTargets.OBJECT_KIND, "bad bucket/a.txt")),
            task("r2-delete-object", ResourceRef(account, R2TaskTargets.OBJECT_KIND, "my-bucket/../x")),
            task("r2-put-bucket-cors-policy", ResourceRef(account, R2TaskTargets.CONFIGURATION_KIND, "eu/bad bucket/CORS")),
            task("r2-put-bucket-lock-configuration", ResourceRef(account, R2TaskTargets.CONFIGURATION_KIND, "eu/my-bucket/MARS")),
        )
        for (malformed in junk) {
            for (legal in listOf(defaultTarget, euTarget, usTarget, R2WriteTarget(account, "other-bucket"))) {
                assertTrue(R2TaskTargets.blocks(malformed, legal), malformed.resourceRef.toString())
            }
        }
        // Old legal locators, including the bulk-delete form without a key, keep working.
        val bucket = task("r2-delete-bucket", ResourceRef(account, R2TaskTargets.BUCKET_KIND, "my-bucket"))
        assertTrue(R2TaskTargets.blocks(bucket, defaultTarget))
        assertFalse(R2TaskTargets.blocks(bucket, euTarget))
        val single = task("r2-delete-object", ResourceRef(account, R2TaskTargets.OBJECT_KIND, "my-bucket/甲😀 a.txt"))
        assertTrue(R2TaskTargets.blocks(single, defaultTarget))
        assertFalse(R2TaskTargets.blocks(single, euTarget))
        val bulk = task("r2-delete-objects", ResourceRef(account, R2TaskTargets.OBJECT_KIND, "my-bucket/"))
        assertTrue(R2TaskTargets.blocks(bulk, defaultTarget))
        assertFalse(R2TaskTargets.blocks(bulk, euTarget))
        val cors = task("r2-put-bucket-cors-policy", R2TaskTargets.reference(euTarget, R2ConfigurationKind.CORS))
        assertTrue(R2TaskTargets.blocks(cors, euTarget))
        assertFalse(R2TaskTargets.blocks(cors, defaultTarget))
        assertFalse(R2TaskTargets.blocks(cors, usTarget))
    }

    @Test
    fun domainLocatorsKeepTheirExactShapeAndTwoDomainsOfOneBucketBlockTheSameBucket() {
        val managed = task("r2-put-bucket-public-policy", R2TaskTargets.domainReference(euTarget))
        assertTrue(R2TaskTargets.blocks(managed, euTarget))
        assertFalse(R2TaskTargets.blocks(managed, defaultTarget))
        assertFalse(R2TaskTargets.blocks(managed, usTarget))
        val a = task("r2-edit-custom-domain-settings", R2TaskTargets.domainReference(euTarget, "cdn.example.com"))
        val b = task("r2-delete-custom-domain", R2TaskTargets.domainReference(euTarget, "assets.example.com"))
        assertTrue(a.resourceRef != b.resourceRef, "two domains keep distinct persisted locators")
        for (legal in listOf(defaultTarget, euTarget, usTarget, R2WriteTarget(account, "other-bucket"))) {
            assertTrue(R2TaskTargets.blocks(a, euTarget))
        }
        assertTrue(R2TaskTargets.blocks(a, euTarget) && R2TaskTargets.blocks(b, euTarget), "same bucket lock")
        assertFalse(R2TaskTargets.blocks(a, defaultTarget))

        val malformed = listOf(
            ResourceRef(account, R2TaskTargets.CONFIGURATION_KIND, "eu/my-bucket/MANAGED_DOMAIN/extra"),
            ResourceRef(account, R2TaskTargets.CONFIGURATION_KIND, "eu/my-bucket/CUSTOM_DOMAIN"),
            ResourceRef(account, R2TaskTargets.CONFIGURATION_KIND, "eu/my-bucket/CUSTOM_DOMAIN/bad_domain"),
            ResourceRef(account, R2TaskTargets.CONFIGURATION_KIND, "eu/my-bucket/CUSTOM_DOMAINS/x.example.com"),
            ResourceRef(account, R2TaskTargets.CONFIGURATION_KIND, "mars/my-bucket/MANAGED_DOMAIN"),
            ResourceRef(account, R2TaskTargets.CONFIGURATION_KIND, "eu/my-bucket/FUTURE_DOMAIN"),
        )
        for (reference in malformed) {
            for (legal in listOf(defaultTarget, euTarget, usTarget)) {
                assertTrue(
                    R2TaskTargets.blocks(task("r2-put-bucket-public-policy", reference), legal),
                    reference.externalId,
                )
            }
            assertNull(R2TaskTargets.parse(reference))
        }
        // Action/family mismatch is not trusted evidence.
        assertTrue(R2TaskTargets.blocks(task("r2-add-custom-domain", R2TaskTargets.domainReference(defaultTarget)), defaultTarget))
        assertTrue(R2TaskTargets.blocks(task("r2-put-bucket-public-policy", R2TaskTargets.domainReference(defaultTarget, "cdn.example.com")), defaultTarget))
    }

    @Test
    fun domainAndBatchOneLocatorsParseIntoTheirOwnFamiliesWithoutCommands() {
        val managed = assertNotNull(R2TaskTargets.parse(R2TaskTargets.domainReference(euTarget)))
        assertNull(managed.kind)
        assertEquals(com.flarego.core.model.R2DomainKind.MANAGED_DOMAIN, managed.domainKind)
        assertNull(managed.domain)
        val single = assertNotNull(R2TaskTargets.parse(R2TaskTargets.domainReference(euTarget, "cdn.example.com")))
        assertNull(single.kind)
        assertEquals(com.flarego.core.model.R2DomainKind.CUSTOM_DOMAIN, single.domainKind)
        assertEquals("cdn.example.com", single.domain)
        // Batch A/B1 locators are unchanged: a B1 kind and no domain family.
        val cors = assertNotNull(R2TaskTargets.parse(R2TaskTargets.reference(euTarget, R2ConfigurationKind.CORS)))
        assertEquals(R2ConfigurationKind.CORS, cors.kind)
        assertNull(cors.domainKind)
        assertNull(cors.domain)
        assertNull(R2TaskTargets.parse(ResourceRef(account, R2TaskTargets.BUCKET_KIND, bucket)))
    }
}
