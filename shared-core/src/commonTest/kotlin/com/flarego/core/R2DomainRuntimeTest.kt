package com.flarego.core

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.operations.OperationClock
import com.flarego.core.application.operations.OperationIds
import com.flarego.core.application.r2.R2Command
import com.flarego.core.application.r2.R2LiveSelection
import com.flarego.core.application.r2.R2TaskTargets
import com.flarego.core.application.r2.R2WriteRuntime
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.CloudPage
import com.flarego.core.model.Connection
import com.flarego.core.model.Operation
import com.flarego.core.model.OperationTask
import com.flarego.core.model.PageRequest
import com.flarego.core.model.R2BucketCreateInput
import com.flarego.core.model.R2BucketDocument
import com.flarego.core.model.R2BucketWrite
import com.flarego.core.model.R2BulkDeleteOutcome
import com.flarego.core.model.R2ConfigurationWrite
import com.flarego.core.model.R2CustomDomainCreateInput
import com.flarego.core.model.R2CustomDomainEditInput
import com.flarego.core.model.R2CorsAllowed
import com.flarego.core.model.R2CorsRule
import com.flarego.core.model.R2DomainConfigurationWrite
import com.flarego.core.model.R2ObjectEntry
import com.flarego.core.model.R2ObjectFilter
import com.flarego.core.model.R2ObjectHead
import com.flarego.core.model.R2ObjectTarget
import com.flarego.core.model.R2ObjectWrite
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.ResourceRef
import com.flarego.core.model.TaskState
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.ByteSource
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.OperationStore
import com.flarego.core.ports.R2Session
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shared runtime for batch A, batch B1 and batch B2 domain writes: one issuer, one plan
 * window, one task store and one bucket identity. Two domains of one bucket are distinct
 * locators but share the bucket lock/gate, and reconciliation of one never settles the other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R2DomainRuntimeTest {
    private val connection = Connection("local", "fixture", "account-one")
    private val account = AccountScope("local", "account-one")
    private val target = R2WriteTarget(account, "my-bucket")
    private val eu = R2WriteTarget(account, "my-bucket", "eu")
    private val domain = "cdn.example.com"
    private val otherDomain = "assets.example.com"
    private val zoneId = "0123456789abcdef0123456789abcdef"

    private class Store : OperationStore {
        val rows = mutableMapOf<String, OperationTask>()
        override fun get(id: String) = rows[id]
        override fun list(connectionId: String) = rows.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { rows[task.id] = task }
        override fun putWithSummary(task: OperationTask, summary: Operation) = put(task)
        override fun removeConnection(connectionId: String) {
            rows.entries.removeAll { it.value.connectionId == connectionId }
        }
    }

    private class BucketSession : R2Session {
        var sends = 0
        var receipt: ExecutionReceipt = ExecutionReceipt.Completed()
        private fun row(name: String) = buildJsonObject { put("name", name); put("storage_class", "Standard") }
        override suspend fun getBucket(account: AccountScope, name: String): R2BucketDocument =
            R2BucketDocument(name, account, row(name))
        override suspend fun write(write: R2BucketWrite, beforeSend: (R2BucketDocument?) -> Unit): ExecutionReceipt {
            if (write !is R2BucketWrite.Create && write !is R2BucketWrite.CreateByName) {
                beforeSend(getBucket(write.scope, nameOf(write)))
            }
            sends++
            return receipt
        }
        private fun nameOf(write: R2BucketWrite) = when (write) {
            is R2BucketWrite.Create -> write.input.name
            is R2BucketWrite.CreateByName -> write.name
            is R2BucketWrite.PatchStorageClass -> write.name
            is R2BucketWrite.Delete -> write.name
        }
        override suspend fun listBuckets(account: AccountScope, page: PageRequest): CloudPage<R2BucketDocument> = TODO()
        override suspend fun createBucket(account: AccountScope, input: R2BucketCreateInput): R2BucketDocument = TODO()
        override suspend fun createBucketByName(account: AccountScope, name: String, storageClass: String?): R2BucketDocument = TODO()
        override suspend fun patchBucketStorageClass(account: AccountScope, name: String, storageClass: String): R2BucketDocument = TODO()
        override suspend fun deleteBucket(account: AccountScope, name: String) = TODO()
        override suspend fun listObjects(account: AccountScope, bucket: String, filter: R2ObjectFilter, page: PageRequest): CloudPage<R2ObjectEntry> = TODO()
        override suspend fun downloadObject(target: R2ObjectTarget, sink: ByteSink): R2ObjectHead = TODO()
        override suspend fun uploadObject(target: R2ObjectTarget, source: ByteSource, length: Long, contentType: String?, contentFingerprint: String): R2ObjectEntry = TODO()
        override suspend fun deleteObject(target: R2ObjectTarget) = TODO()
        override suspend fun deleteObjects(account: AccountScope, bucket: String, keys: List<String>): R2BulkDeleteOutcome = TODO()
        override suspend fun writeObject(write: R2ObjectWrite, beforeSend: (R2ObjectEntry?) -> Unit): ExecutionReceipt = TODO()
    }

    private inner class Harness(scope: TestScope, withDomains: Boolean = true) {
        var actual = R2LiveSelection(connection, 1, 1, null)
        var revision = 1L
        var ids = 0
        val store = Store()
        val bucket = BucketSession()
        val configuration = FakeConfigurationSession(account).apply {
            emptyRules(target)
            emptyRules(eu)
        }
        val domains = FakeDomainSession(account).apply {
            seedDomain(domain, enabled = true)
            seedDomain(otherDomain, enabled = true)
        }
        val runtime = R2WriteRuntime(
            scope.backgroundScope, store, StandardTestDispatcher(scope.testScheduler),
            FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
            OperationClock { 1_000 }, OperationIds { "domrt-${++ids}" },
            { actual }, { listOf(connection) }, { revision }, { bucket },
            configSession = { configuration.open(); configuration },
            domainSession = if (withDomains) ({ domains.open(); domains }) else null,
        )

        fun select(command: R2Command) { actual = actual.copy(command = command) }

        fun managed(on: R2WriteTarget = target, enabled: Boolean = true) =
            R2Command.Domain(R2DomainConfigurationWrite.PutManagedDomain(on, enabled))

        fun edit(domainName: String = domain, on: R2WriteTarget = target, enabled: Boolean = false) =
            R2Command.Domain(R2DomainConfigurationWrite.EditCustomDomain(on, domainName, R2CustomDomainEditInput(enabled = enabled)))

        fun add(on: R2WriteTarget = target, domainName: String = "new.example.com") =
            R2Command.Domain(
                R2DomainConfigurationWrite.AddCustomDomain(
                    on,
                    R2CustomDomainCreateInput(domainName, zoneId, enabled = false),
                ),
            )

        fun patch() = R2Command.Bucket(R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess"))

        fun cors() = R2Command.Configuration(
            R2ConfigurationWrite.PutCors(target, listOf(R2CorsRule(allowed = R2CorsAllowed(listOf("GET"), listOf("https://a.example"))))),
        )
    }

    @Test
    fun anUnsettledDomainWriteSharesTheBucketGateWithBatchAAndB1InBothDirections() = runTest {
        val first = Harness(this)
        first.domains.receipt = ExecutionReceipt.Unknown()
        first.select(first.managed())
        assertEquals(TaskState.UNKNOWN, first.runtime.confirm(first.runtime.prepare(first.managed()).planId).state)
        first.select(first.patch())
        assertFailsWith<CloudFailure> { first.runtime.prepare(first.patch()) }
        first.select(first.cors())
        assertFailsWith<CloudFailure> { first.runtime.prepare(first.cors()) }
        assertEquals(0, first.bucket.sends)
        assertEquals(0, first.configuration.sends.size)

        val second = Harness(this)
        second.bucket.receipt = ExecutionReceipt.Unknown()
        second.select(second.patch())
        assertEquals(TaskState.UNKNOWN, second.runtime.confirm(second.runtime.prepare(second.patch()).planId).state)
        second.select(second.managed())
        assertFailsWith<CloudFailure> { second.runtime.prepare(second.managed()) }
        assertEquals(0, second.domains.sends.size)

        val third = Harness(this)
        third.configuration.receipt = ExecutionReceipt.Unknown()
        third.select(third.cors())
        assertEquals(TaskState.UNKNOWN, third.runtime.confirm(third.runtime.prepare(third.cors()).planId).state)
        third.select(third.managed())
        assertFailsWith<CloudFailure> { third.runtime.prepare(third.managed()) }
        assertEquals(0, third.domains.sends.size)
    }

    @Test
    fun anotherRegionOrTheOtherDomainOfTheSameBucketNeverBorrowsAConfirmation() = runTest {
        val h = Harness(this)
        val command = h.edit()
        h.select(command)
        val plan = h.runtime.prepare(command)
        h.select(h.edit(domainName = otherDomain))
        assertFailsWith<CloudFailure> { h.runtime.confirm(plan.planId) }
        assertEquals(0, h.domains.sends.size)

        val region = Harness(this)
        val regionCommand = region.edit()
        region.select(regionCommand)
        val regionPlan = region.runtime.prepare(regionCommand)
        region.actual = region.actual.copy(routeEpoch = 2)
        assertFailsWith<CloudFailure> { region.runtime.confirm(regionPlan.planId) }
        assertEquals(0, region.domains.sends.size)
    }

    @Test
    fun twoDomainLocatorsOfOneBucketBlockEachOtherAndReconcilingOneDoesNotSettleTheOther() = runTest {
        val h = Harness(this)
        h.select(h.edit())
        val a = h.runtime.confirm(h.runtime.prepare(h.edit()).planId)
        assertEquals(TaskState.SUCCEEDED, a.state)
        h.select(h.edit(domainName = otherDomain))
        val b = h.runtime.confirm(h.runtime.prepare(h.edit(domainName = otherDomain)).planId)
        assertEquals(TaskState.SUCCEEDED, b.state)
        assertEquals(2, h.domains.sends.size)

        // Simulate recovery: both tasks are unsettled again with their own real locators/desired hash.
        h.store.rows[a.id] = a.copy(state = TaskState.UNKNOWN)
        h.store.rows[b.id] = b.copy(state = TaskState.UNKNOWN)
        h.select(h.edit())
        assertFailsWith<CloudFailure> { h.runtime.prepare(h.edit()) }
        val reconciled = h.runtime.reconcile(a.id)
        assertEquals(TaskState.SUCCEEDED, reconciled.state)
        assertEquals(TaskState.UNKNOWN, h.store.rows.getValue(b.id).state)
        assertEquals(2, h.domains.sends.size)
    }

    @Test
    fun mixedConfigurationAndDomainPreviewsShareTheSixtyFourSlotWindow() = runTest {
        val h = Harness(this)
        val previews = mutableListOf<String>()
        repeat(32) {
            h.select(h.cors())
            previews += h.runtime.prepare(h.cors()).planId
            h.select(h.edit())
            previews += h.runtime.prepare(h.edit()).planId
        }
        assertEquals(64, previews.toSet().size)
        h.select(h.managed())
        assertFailsWith<CloudFailure> { h.runtime.prepare(h.managed()) }
        assertTrue(h.runtime.discardPreview(previews.last()))
        val overflow = h.edit()
        h.select(overflow)
        val fresh = h.runtime.prepare(overflow)
        assertEquals(TaskState.SUCCEEDED, h.runtime.confirm(fresh.planId).state)
        assertEquals(1, h.domains.sends.size)
        assertEquals(0, h.configuration.sends.size)
    }

    @Test
    fun aDomainCommandNeedsTheLiveCommandAndAConfiguredSession() = runTest {
        val h = Harness(this)
        h.select(h.managed())
        val plan = h.runtime.prepare(h.managed())
        h.actual = h.actual.copy(command = null)
        assertFailsWith<CloudFailure> { h.runtime.confirm(plan.planId) }
        assertEquals(0, h.domains.sends.size)

        val missing = Harness(this, withDomains = false)
        missing.select(missing.managed())
        val failure = assertFailsWith<CloudFailure> { missing.runtime.prepare(missing.managed()) }
        assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY, failure.code)
        assertTrue(missing.domains.reads.isEmpty())
    }

    @Test
    fun domainTasksArePublishedThroughTheSharedRuntime() = runTest {
        val h = Harness(this)
        h.select(h.managed())
        val task = h.runtime.confirm(h.runtime.prepare(h.managed()).planId)
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals(listOf(task.id), h.runtime.tasks.value.map { it.id })
        assertNotNull(task.resourceRef)
        assertTrue(task.resourceRef!!.externalId.contains("MANAGED_DOMAIN"))
    }

    private fun task(id: String, actionKey: String, reference: ResourceRef, state: TaskState) = OperationTask(
        id = id,
        connectionId = connection.id,
        scope = account,
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
    fun anAddedDomainWithAWrongZoneStaysUnknownThroughTheRuntimeAndIsNotResent() = runTest {
        val h = Harness(this)
        h.domains.onApply = {
            h.domains.collection += FakeDomainSession.json(
                """{"domain":"new.example.com","enabled":false,"minTLS":"1.2","ciphers":["ECDHE-ECDSA-AES128-GCM-SHA256"],"zoneId":"fedcba9876543210fedcba9876543210","zoneName":"example.com","status":{"ownership":"pending","ssl":"initializing"}}""",
            )
        }
        val command = h.add(domainName = "new.example.com")
        h.select(command)
        val task = h.runtime.confirm(h.runtime.prepare(command).planId)
        assertEquals(TaskState.UNKNOWN, task.state)
        assertEquals(1, h.domains.sends.size)
        assertEquals(TaskState.UNKNOWN, h.runtime.reconcile(task.id).state)
        assertEquals(1, h.domains.sends.size)
    }
}
