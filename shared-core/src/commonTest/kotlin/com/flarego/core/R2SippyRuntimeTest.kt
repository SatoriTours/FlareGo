package com.flarego.core

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.operations.OperationClock
import com.flarego.core.application.operations.OperationIds
import com.flarego.core.application.r2.R2Command
import com.flarego.core.application.r2.R2LiveSelection
import com.flarego.core.application.r2.R2SippyAwsSourceSecrets
import com.flarego.core.application.r2.R2SippyDestinationSecrets
import com.flarego.core.application.r2.R2SippyLeaseState
import com.flarego.core.application.r2.R2SippySecretLease
import com.flarego.core.application.r2.R2SippySecrets
import com.flarego.core.application.r2.R2SippyWrite
import com.flarego.core.application.r2.R2WriteRuntime
import com.flarego.core.application.r2.requireDraft
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
import com.flarego.core.model.R2CorsAllowed
import com.flarego.core.model.R2CorsRule
import com.flarego.core.model.R2DomainConfigurationWrite
import com.flarego.core.model.R2ObjectEntry
import com.flarego.core.model.R2ObjectFilter
import com.flarego.core.model.R2ObjectHead
import com.flarego.core.model.R2ObjectTarget
import com.flarego.core.model.R2ObjectWrite
import com.flarego.core.model.R2SippyDestinationRef
import com.flarego.core.model.R2SippyProvider
import com.flarego.core.model.R2SippySnapshot
import com.flarego.core.model.R2SippySourceRef
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.TaskState
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.ByteSource
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.OperationStore
import com.flarego.core.ports.R2Session
import com.flarego.core.ports.R2SippySession
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 5b core: the Sippy controller inside the shared R2 runtime.
 *
 * Only the provider session is simulated. Every assertion observes real controller/runtime
 * behaviour: one issuer, one 64-slot window, one bucket lock, one task store, the memory-only draft
 * lease ownership across preview -> admission -> transport, and the conservative write-after rules.
 * No assertion ever prints a credential value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R2SippyRuntimeTest {
    private val connection = Connection("local", "fixture", "account-one")
    private val account = AccountScope("local", "account-one")
    private val target = R2WriteTarget(account, "my-bucket")
    private val eu = R2WriteTarget(account, "my-bucket", "eu")

    // Obviously synthetic material; never a real key and never printed by an assertion.
    private val sourceKeyId = "SYNTHETIC-SIPPY-SOURCE-KEY-ID"
    private val sourceSecret = "SYNTHETIC-SIPPY-SOURCE-SECRET-MARKER-9f1"
    private val destinationKeyId = "SYNTHETIC-SIPPY-DEST-KEY-ID"
    private val destinationSecret = "SYNTHETIC-SIPPY-DEST-SECRET-MARKER-7c2"

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

    private fun disabledSnapshot() =
        R2SippySnapshot(enabled = false, destination = null, source = null, editable = true, detailsProvided = false)

    private fun enabledSnapshot(provider: R2SippyProvider = R2SippyProvider.AWS) =
        R2SippySnapshot(
            enabled = true,
            destination = R2SippyDestinationRef(account.accountId, "my-bucket", "r2-echoed-access-key-id"),
            source =
                when (provider) {
                    R2SippyProvider.AWS -> R2SippySourceRef(provider, bucket = "source-bucket", region = "us-east-1")
                    R2SippyProvider.GCS -> R2SippySourceRef(provider, bucket = "source-bucket")
                    R2SippyProvider.S3 -> R2SippySourceRef(provider, bucketUrl = "https://s3.example.invalid/b")
                    R2SippyProvider.AZURE -> R2SippySourceRef(provider, container = "synthetic-container")
                },
            editable = true,
        )

    /**
     * A provider double that follows the production wire order: an independent safe read, then the
     * product's before-send callback (which composes the final guard), then the single admission and
     * the single send. The transport can be held open to observe admission ownership.
     */
    private inner class FakeSippySession : R2SippySession {
        var snapshot: R2SippySnapshot = disabledSnapshot()
        var receipt: ExecutionReceipt = ExecutionReceipt.Completed()
        var readFailure: CloudFailure? = null
        /** Suspends the next configuration read, so a slow prepare can cross the plan TTL. */
        var readGate: (suspend () -> Unit)? = null
        var beforeProductCallback: (() -> Unit)? = null
        var sendGate: (suspend () -> Unit)? = null
        var onApply: ((R2SippyWrite) -> Unit)? = null

        val reads = mutableListOf<R2WriteTarget>()
        val sends = mutableListOf<R2SippyWrite>()
        val admissionStates = mutableListOf<R2SippyLeaseState?>()
        var lastObserved: R2SippySnapshot? = null

        fun open() = Unit

        override suspend fun getSippy(target: R2WriteTarget): R2SippySnapshot {
            reads += target
            readGate?.let { gate -> readGate = null; gate() }
            readFailure?.let { throw it }
            lastObserved = snapshot
            return snapshot
        }

        override suspend fun putSippy(target: R2WriteTarget, lease: R2SippySecretLease): ExecutionReceipt =
            writeSippy(R2SippyWrite.PutSippy(target, lease)) {}

        override suspend fun deleteSippy(target: R2WriteTarget): ExecutionReceipt =
            writeSippy(R2SippyWrite.DeleteSippy(target)) {}

        override suspend fun writeSippy(
            write: R2SippyWrite,
            beforeSend: (R2SippySnapshot?) -> Unit,
        ): ExecutionReceipt {
            val lease = (write as? R2SippyWrite.PutSippy)?.lease
            // The real wire refuses a non-draft before even reading the bucket.
            lease?.requireDraft()
            val observed = getSippy(write.target)
            beforeProductCallback?.invoke()
            beforeSend(observed)
            // Admission is the last local step before the single send.
            if (lease != null) {
                lease.admitForTransport() ?: throw CloudFailure(CloudFailureCode.INVALID_INPUT, "not a draft")
            }
            sends += write
            admissionStates += lease?.leaseState
            try {
                sendGate?.invoke()
                onApply?.invoke(write)
                return receipt
            } finally {
                lease?.completeTransport()
            }
        }
    }

    private inner class BucketSession : R2Session {
        val rows = mutableMapOf("my-bucket" to bucketRow("my-bucket", "Standard"))
        var receipt: ExecutionReceipt = ExecutionReceipt.Completed()
        var sends = 0

        private fun bucketRow(name: String, storageClass: String?): JsonObject =
            buildJsonObject {
                put("name", name)
                storageClass?.let { put("storage_class", it) }
            }

        override suspend fun getBucket(account: AccountScope, name: String): R2BucketDocument {
            val raw = rows[name] ?: throw CloudFailure(CloudFailureCode.NOT_FOUND, "未找到请求的资源")
            return R2BucketDocument(name, account, raw)
        }

        override suspend fun write(write: R2BucketWrite, beforeSend: (R2BucketDocument?) -> Unit): ExecutionReceipt {
            beforeSend(getBucket(account, "my-bucket"))
            sends++
            return receipt
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

    private inner class Harness(scope: TestScope, withSippy: Boolean = true) {
        var actual = R2LiveSelection(connection, 1, 1, null)
        var revision = 1L
        var ids = 0
        var now = 1_000L
        val store = Store()
        val bucket = BucketSession()
        val sippy = FakeSippySession()
        val configuration = FakeConfigurationSession(account).apply { emptyRules(target) }
        val domains = FakeDomainSession(account).apply { open() }
        val leases = mutableListOf<R2SippySecretLease>()
        val runtime = R2WriteRuntime(
            scope.backgroundScope, store, StandardTestDispatcher(scope.testScheduler),
            FingerprintPort { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            },
            OperationClock { now }, OperationIds { "runtime-${++ids}" },
            { actual }, { listOf(connection) }, { revision }, { bucket },
            configSession = { configuration.open(); configuration },
            domainSession = { domains.open(); domains },
            sippySession = if (withSippy) ({ sippy.open(); sippy }) else null,
        )

        fun select(command: R2Command) { actual = actual.copy(command = command) }

        fun draft(): R2SippySecretLease {
            val lease = R2SippySecrets.draft(
                R2SippyAwsSourceSecrets(sourceKeyId, sourceSecret, "source-bucket", "us-east-1"),
                R2SippyDestinationSecrets(destinationKeyId, destinationSecret),
            )
            leases += lease
            return lease
        }

        fun put(lease: R2SippySecretLease, on: R2WriteTarget = target) =
            R2Command.Sippy(R2SippyWrite.PutSippy(on, lease))

        fun delete(on: R2WriteTarget = target) = R2Command.Sippy(R2SippyWrite.DeleteSippy(on))

        fun patch() = R2Command.Bucket(R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess"))

        fun cors() = R2Command.Configuration(
            R2ConfigurationWrite.PutCors(
                target,
                listOf(R2CorsRule(id = "one", allowed = R2CorsAllowed(listOf("GET"), listOf("https://a.example")))),
            ),
        )

        fun managed() = R2Command.Domain(R2DomainConfigurationWrite.PutManagedDomain(target, true))
    }

    @Test
    fun aPutPreviewIsInertAndOnlyOneExplicitConfirmationSendsOnce() = runTest {
        val h = Harness(this)
        val lease = h.draft()
        h.sippy.onApply = { h.sippy.snapshot = enabledSnapshot() }
        val command = h.put(lease)
        h.select(command)
        val preview = h.runtime.prepare(command)
        assertEquals(0, h.sippy.sends.size, "a preview never sends the sensitive body")
        assertTrue(h.sippy.reads.isNotEmpty())
        assertEquals(R2SippyLeaseState.DRAFT, lease.leaseState)

        val task = h.runtime.confirm(preview.planId)
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals(1, h.sippy.sends.size)
        assertEquals(R2SippyLeaseState.CLAIMED, lease.leaseState)
        assertFalse(lease.retainsMaterial)
        val owned = assertNotNull(h.runtime.sippyMaterialObservation(preview.planId))
        assertEquals(R2SippyLeaseState.CONSUMED, owned.state)
        assertFalse(owned.retainsMaterial)

        // A duplicate confirmation of the same issued plan shares the cached task: no second
        // consumption and no second HTTP.
        val again = h.runtime.confirm(preview.planId)
        assertEquals(task.id, again.id)
        assertEquals(1, h.sippy.sends.size)
        assertEquals(1, h.sippy.admissionStates.size)
        assertEquals(1, h.store.rows.size)
    }

    @Test
    fun changedCredentialBindingRejectsOldPreview() = runTest {
        val h = Harness(this)
        h.sippy.onApply = { h.sippy.snapshot = enabledSnapshot() }
        val first = h.draft()
        h.select(h.put(first))
        val preview = h.runtime.prepare(h.put(first))

        // The real editor produced a new draft (new random binding) after the input changed.
        val second = h.draft()
        assertTrue(first.bindingId != second.bindingId)
        h.select(h.put(second))
        assertFailsWith<CloudFailure> { h.runtime.confirm(preview.planId) }
        assertEquals(0, h.sippy.sends.size)

        // The editor also retired the old draft as part of the change; the plan stays unusable.
        assertTrue(first.retire())
        h.select(h.put(first))
        assertFailsWith<CloudFailure> { h.runtime.confirm(preview.planId) }
        assertEquals(0, h.sippy.sends.size)

        // A fresh preview of the live draft is the only admissible path.
        val freshCommand = h.put(second)
        h.select(freshCommand)
        val fresh = h.runtime.prepare(freshCommand)
        assertEquals(TaskState.SUCCEEDED, h.runtime.confirm(fresh.planId).state)
        assertEquals(1, h.sippy.sends.size)
    }

    @Test
    fun secretsNeverReachPreviewPlanTaskOrFingerprint() = runTest {
        val h = Harness(this)
        val lease = h.draft()
        val command = h.put(lease)
        h.select(command)
        val preview = h.runtime.prepare(command)
        val task = h.runtime.confirm(preview.planId)

        val observable =
            listOf(
                lease.toString(),
                lease.bindingId,
                command.toString(),
                preview.toString(),
                preview.detail.impact,
                preview.detail.concurrencyWarning,
                preview.targets.joinToString(),
                task.toString(),
                task.safeDetail,
                task.requestFingerprint,
                task.resourceRef.toString(),
                task.resourceRef?.externalId.orEmpty(),
                h.store.rows.values.single().toString(),
            )
        for (value in observable) {
            assertFalse(value.contains(sourceSecret), "a source secret leaked into an observable value")
            assertFalse(value.contains(destinationSecret), "a destination secret leaked into an observable value")
        }
        assertEquals(64, task.requestFingerprint.length)
        assertEquals(R2SippyLeaseState.CLAIMED, lease.leaseState)
        assertFalse(lease.retainsMaterial)
    }

    @Test
    fun retiredDraftDoesNotCloseAdmittedSecretTransport() = runTest {
        val h = Harness(this)
        val lease = h.draft()
        h.sippy.onApply = { h.sippy.snapshot = enabledSnapshot() }
        val command = h.put(lease)
        h.select(command)
        val preview = h.runtime.prepare(command)
        val gate = CompletableDeferred<Unit>()
        h.sippy.sendGate = { gate.await() }

        val waiter = launch { h.runtime.confirm(preview.planId) }
        runCurrent()
        assertEquals(1, h.sippy.sends.size, "the admitted transport was entered exactly once")
        assertEquals(R2SippyLeaseState.CLAIMED, lease.leaseState)
        val inFlight = assertNotNull(h.runtime.sippyMaterialObservation(preview.planId))
        assertEquals(R2SippyLeaseState.SENDING, inFlight.state)
        // The final guard moved the material into the one-shot encoded body: the lease no longer
        // holds it, and the body is written exactly once by this admitted transport.
        assertFalse(inFlight.retainsMaterial)

        // The UI waiter disappears and the editor retires the draft while the send is in flight.
        waiter.cancel()
        runCurrent()
        assertFalse(lease.retire(), "an editor retirement can never take an admitted lease")
        h.runtime.discardPreview(preview.planId)

        gate.complete(Unit)
        runCurrent()
        val task = h.store.rows.values.single()
        assertEquals(TaskState.SUCCEEDED, task.state)
        val owned = assertNotNull(h.runtime.sippyMaterialObservation(preview.planId))
        assertEquals(R2SippyLeaseState.CONSUMED, owned.state)
        assertFalse(owned.retainsMaterial)
        assertFalse(lease.retainsMaterial)
    }

    @Test
    fun unknownPutIsNeverProvenByIdenticalPublicMetadata() = runTest {
        val h = Harness(this)
        val lease = h.draft()
        h.sippy.receipt = ExecutionReceipt.Unknown()
        h.sippy.onApply = { h.sippy.snapshot = enabledSnapshot() }
        val command = h.put(lease)
        h.select(command)
        val task = h.runtime.confirm(h.runtime.prepare(command).planId)
        assertEquals(TaskState.UNKNOWN, task.state)
        assertEquals(1, h.sippy.sends.size)
        val readsAfterSend = h.sippy.reads.size

        // The provider now reports exactly the public configuration we asked for. A public GET can
        // never prove that the new secret was applied, so the task stays unknown and is not resent.
        assertEquals(TaskState.UNKNOWN, h.runtime.reconcile(task.id).state)
        assertEquals(1, h.sippy.sends.size)
        assertEquals(readsAfterSend, h.sippy.reads.size, "a PUT reconcile never re-reads to claim success")
        val next = h.put(h.draft())
        h.select(next)
        assertUnsettled { h.runtime.prepare(next) }
    }

    @Test
    fun completedPutIsVerifiedOnlyByTheExplicitPublicSavedState() = runTest {
        val verified = Harness(this)
        verified.sippy.onApply = { verified.sippy.snapshot = enabledSnapshot() }
        verified.select(verified.put(verified.draft()))
        val ok = verified.runtime.confirm(verified.runtime.prepare(verified.actual.command!!).planId)
        assertEquals(TaskState.SUCCEEDED, ok.state)
        assertEquals(1, verified.sippy.sends.size)

        // A provider acknowledgement without the matching observable configuration is not success.
        val unverified = Harness(this)
        unverified.select(unverified.put(unverified.draft()))
        val task = unverified.runtime.confirm(unverified.runtime.prepare(unverified.actual.command!!).planId)
        assertEquals(TaskState.UNKNOWN, task.state)
        assertEquals(1, unverified.sippy.sends.size)
    }

    @Test
    fun aDeleteIsProvenOnlyByAnIndependentlyReadDisabledState() = runTest {
        val h = Harness(this)
        h.sippy.snapshot = enabledSnapshot()
        val command = h.delete()
        h.select(command)
        val preview = h.runtime.prepare(command)
        // An already disabled configuration needs no new DELETE.
        h.sippy.snapshot = disabledSnapshot()
        assertFailsWith<CloudFailure> { h.runtime.prepare(command) }

        h.sippy.snapshot = enabledSnapshot()
        h.sippy.onApply = { h.sippy.snapshot = disabledSnapshot() }
        val task = h.runtime.confirm(preview.planId)
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals("r2-delete-bucket-sippy-config", task.actionKey)
        assertEquals(1, h.sippy.sends.size)

        // Counterexample: a DELETE acknowledgement whose read-back still shows enabled stays unknown,
        // and a later independent disabled read may settle it.
        val other = Harness(this)
        other.sippy.snapshot = enabledSnapshot()
        other.select(other.delete())
        val unknown = other.runtime.confirm(other.runtime.prepare(other.actual.command!!).planId)
        assertEquals(TaskState.UNKNOWN, unknown.state)
        other.sippy.snapshot = disabledSnapshot()
        assertEquals(TaskState.SUCCEEDED, other.runtime.reconcile(unknown.id).state)
        assertEquals(1, other.sippy.sends.size)
    }

    @Test
    fun beforeSendAndFinalGuardDriftBothProduceZeroWrites() = runTest {
        // (1) The provider read drifted between prepare and submit: the controller's own before-send
        // comparison refuses, still before the typed send.
        val readDrift = Harness(this)
        val first = readDrift.draft()
        readDrift.select(readDrift.put(first))
        val readPreview = readDrift.runtime.prepare(readDrift.actual.command!!)
        val drifted = enabledSnapshot(R2SippyProvider.GCS)
        readDrift.sippy.snapshot = drifted
        val readTask = readDrift.runtime.confirm(readPreview.planId)
        assertEquals(TaskState.FAILED, readTask.state)
        assertEquals(0, readDrift.sippy.sends.size)
        assertEquals(drifted, readDrift.sippy.lastObserved, "the drift was in the provider read")

        // (2) The provider read still matches the plan, but the app-owned credential/route is revoked
        // after that read: the composed final guard refuses, still before the send.
        val guardDrift = Harness(this)
        val second = guardDrift.draft()
        guardDrift.select(guardDrift.put(second))
        val guardPreview = guardDrift.runtime.prepare(guardDrift.actual.command!!)
        val original = guardDrift.sippy.snapshot
        guardDrift.sippy.beforeProductCallback = { guardDrift.actual = guardDrift.actual.copy(routeEpoch = 2) }
        val guardTask = guardDrift.runtime.confirm(guardPreview.planId)
        assertEquals(TaskState.FAILED, guardTask.state)
        assertEquals(0, guardDrift.sippy.sends.size)
        assertEquals(original, guardDrift.sippy.lastObserved, "the provider read matched; the final guard refused")
        assertFalse(second.isAdmitted, "a pre-send refusal never admits the material")
        assertEquals(R2SippyLeaseState.CLAIMED, second.leaseState)
        assertFalse(second.retainsMaterial, "a pre-send refusal releases the task-owned material")
    }

    @Test
    fun sippySharesOneLockAndUnsettledGateWithAAndB1AndB2InBothDirections() = runTest {
        // Sippy unsettled blocks A, B1 and B2 on the same bucket.
        val blocked = Harness(this)
        blocked.sippy.receipt = ExecutionReceipt.Unknown()
        blocked.sippy.onApply = { blocked.sippy.snapshot = enabledSnapshot() }
        blocked.select(blocked.put(blocked.draft()))
        assertEquals(
            TaskState.UNKNOWN,
            blocked.runtime.confirm(blocked.runtime.prepare(blocked.actual.command!!).planId).state,
        )
        blocked.select(blocked.patch())
        assertUnsettled { blocked.runtime.prepare(blocked.actual.command!!) }
        blocked.select(blocked.cors())
        assertUnsettled { blocked.runtime.prepare(blocked.actual.command!!) }
        blocked.select(blocked.managed())
        assertUnsettled { blocked.runtime.prepare(blocked.actual.command!!) }
        assertEquals(0, blocked.bucket.sends)
        assertEquals(0, blocked.configuration.sends.size)
        assertEquals(0, blocked.domains.sends.size)

        // ... and each of A, B1 and B2 blocks a Sippy preview of the same bucket.
        for (kind in listOf("bucket", "configuration", "domain")) {
            val h = Harness(this)
            val command =
                when (kind) {
                    "bucket" -> h.patch().also { h.bucket.receipt = ExecutionReceipt.Unknown() }
                    "configuration" -> h.cors().also { h.configuration.receipt = ExecutionReceipt.Unknown() }
                    else -> h.managed().also { h.domains.receipt = ExecutionReceipt.Unknown() }
                }
            h.select(command)
            assertEquals(TaskState.UNKNOWN, h.runtime.confirm(h.runtime.prepare(command).planId).state, kind)
            h.select(h.put(h.draft()))
            assertUnsettled { h.runtime.prepare(h.actual.command!!) }
            assertEquals(0, h.sippy.sends.size, kind)
        }

        // A different jurisdiction is a different bucket identity and stays writable.
        val independent = Harness(this)
        independent.sippy.receipt = ExecutionReceipt.Unknown()
        independent.sippy.onApply = { independent.sippy.snapshot = enabledSnapshot() }
        independent.select(independent.put(independent.draft()))
        assertEquals(
            TaskState.UNKNOWN,
            independent.runtime.confirm(independent.runtime.prepare(independent.actual.command!!).planId).state,
        )
        independent.sippy.receipt = ExecutionReceipt.Completed()
        val euCommand = independent.put(independent.draft(), eu)
        independent.select(euCommand)
        assertEquals(TaskState.SUCCEEDED, independent.runtime.confirm(independent.runtime.prepare(euCommand).planId).state)
        assertEquals("eu", (independent.sippy.sends.last() as R2SippyWrite.PutSippy).target.jurisdiction)
    }

    @Test
    fun aReadFailureOrUninterpretableSnapshotNeverBecomesAFirstEnableBaseline() = runTest {
        // A configuration this app cannot interpret (an unknown/echoed member was dropped) stays
        // read-only: no preview, no task row and no send.
        val readOnly = Harness(this)
        readOnly.sippy.snapshot =
            R2SippySnapshot(
                enabled = false,
                destination = null,
                source = null,
                editable = false,
                readOnlyReason = "该 Sippy 配置包含应用尚未支持的字段或取值，仅可查看",
            )
        val command = readOnly.put(readOnly.draft())
        readOnly.select(command)
        val failure = assertFailsWith<CloudFailure> { readOnly.runtime.prepare(command) }
        assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY, failure.code)
        assertEquals(0, readOnly.sippy.sends.size)
        assertEquals(0, readOnly.store.rows.size)

        // A 404/transport failure is never an "unconfigured" baseline either.
        val missing = Harness(this)
        missing.sippy.readFailure = CloudFailure(CloudFailureCode.NOT_FOUND, "未找到请求的资源")
        val blocked = missing.put(missing.draft())
        missing.select(blocked)
        val notFound = assertFailsWith<CloudFailure> { missing.runtime.prepare(blocked) }
        assertEquals(CloudFailureCode.NOT_FOUND, notFound.code)
        assertEquals(0, missing.sippy.sends.size)
        assertEquals(0, missing.store.rows.size)

        // A DELETE against an uninterpretable state is refused as well.
        val deleteReadOnly = Harness(this)
        deleteReadOnly.sippy.snapshot = readOnly.sippy.snapshot
        val delete = deleteReadOnly.delete()
        deleteReadOnly.select(delete)
        assertEquals(
            CloudFailureCode.UNSUPPORTED_CAPABILITY,
            assertFailsWith<CloudFailure> { deleteReadOnly.runtime.prepare(delete) }.code,
        )
        assertEquals(0, deleteReadOnly.sippy.sends.size)
    }

    @Test
    fun mixedPreviewWindowExpiryAndPruneReleaseOnlyUnclaimedSippyMaterial() = runTest {
        val h = Harness(this)
        val sippyPlans = mutableListOf<String>()
        val sippyLeases = mutableListOf<R2SippySecretLease>()
        repeat(21) {
            val lease = h.draft()
            sippyLeases += lease
            val command = h.put(lease)
            h.select(command)
            sippyPlans += h.runtime.prepare(command).planId
            val bucket = h.patch()
            h.select(bucket)
            h.runtime.prepare(bucket)
            val config = h.cors()
            h.select(config)
            h.runtime.prepare(config)
        }
        assertEquals(63, sippyPlans.size + 21 * 2)
        val sixtyFourth = h.cors()
        h.select(sixtyFourth)
        h.runtime.prepare(sixtyFourth)
        // The whole window is shared and the 65th preparation is refused.
        val overflow = h.cors()
        h.select(overflow)
        assertFailsWith<CloudFailure> { h.runtime.prepare(overflow) }

        // Discarding a Sippy preview releases only that never-claimed draft.
        assertTrue(h.runtime.discardPreview(sippyPlans.last()))
        assertEquals(R2SippyLeaseState.RETIRED, sippyLeases.last().leaseState)
        assertFalse(sippyLeases.last().retainsMaterial)
        assertEquals(R2SippyLeaseState.DRAFT, sippyLeases.first().leaseState)
        assertTrue(sippyLeases.first().retainsMaterial)

        // Expiry prunes the unclaimed drafts of the same window without reviving an old preview.
        h.now += 300_000
        val late = h.draft()
        val lateCommand = h.put(late)
        h.select(lateCommand)
        val latePreview = h.runtime.prepare(lateCommand)
        assertEquals(R2SippyLeaseState.RETIRED, sippyLeases.first().leaseState)
        assertFailsWith<CloudFailure> { h.runtime.confirm(sippyPlans.first()) }

        // The consumed lease of a sent write can never be replayed.
        h.sippy.onApply = { h.sippy.snapshot = enabledSnapshot() }
        assertEquals(TaskState.SUCCEEDED, h.runtime.confirm(latePreview.planId).state)
        assertEquals(R2SippyLeaseState.CLAIMED, late.leaseState)
        assertFalse(late.retainsMaterial)
        assertEquals(
            R2SippyLeaseState.CONSUMED,
            assertNotNull(h.runtime.sippyMaterialObservation(latePreview.planId)).state,
        )
        h.select(h.put(late))
        assertFailsWith<CloudFailure> { h.runtime.prepare(h.actual.command!!) }
        assertEquals(1, h.sippy.sends.size)
    }

    @Test
    fun aForeignDestinationSnapshotIsRejectedBeforeTheWriteAndNeverSettlesAReadback() = runTest {
        fun foreignDestination() = R2SippyDestinationRef("b".repeat(32), "other-bucket", "foreign-key-id")

        // (a) A known-disabled configuration of another bucket is not this bucket's baseline.
        val before = Harness(this)
        before.sippy.snapshot =
            R2SippySnapshot(enabled = false, destination = foreignDestination(), source = null, editable = true)
        val command = before.put(before.draft())
        before.select(command)
        val failure = assertFailsWith<CloudFailure> { before.runtime.prepare(command) }
        assertEquals(CloudFailureCode.SCOPE_MISMATCH, failure.code)
        assertEquals(0, before.sippy.sends.size)
        assertEquals(0, before.store.rows.size)

        // A DELETE is refused on the same evidence.
        val deleteBefore = Harness(this)
        deleteBefore.sippy.snapshot =
            R2SippySnapshot(enabled = true, destination = foreignDestination(), source = null, editable = true)
        val delete = deleteBefore.delete()
        deleteBefore.select(delete)
        assertEquals(
            CloudFailureCode.SCOPE_MISMATCH,
            assertFailsWith<CloudFailure> { deleteBefore.runtime.prepare(delete) }.code,
        )
        assertEquals(0, deleteBefore.sippy.sends.size)

        // (b) A read-back of another bucket is no proof of this write: the task stays unknown.
        val after = Harness(this)
        after.sippy.onApply = {
            after.sippy.snapshot =
                R2SippySnapshot(enabled = true, destination = foreignDestination(), source = enabledSnapshot().source, editable = true)
        }
        after.select(after.put(after.draft()))
        val task = after.runtime.confirm(after.runtime.prepare(after.actual.command!!).planId)
        assertEquals(TaskState.UNKNOWN, task.state)
        assertEquals(1, after.sippy.sends.size)

        // (c) A DELETE acknowledged but read back from another bucket is not "disabled here", and a
        // reconcile over that same foreign state never settles it.
        val del = Harness(this)
        del.sippy.snapshot = enabledSnapshot()
        val deleteCommand = del.delete()
        del.select(deleteCommand)
        val preview = del.runtime.prepare(deleteCommand)
        del.sippy.onApply = {
            del.sippy.snapshot =
                R2SippySnapshot(enabled = false, destination = foreignDestination(), source = null, editable = true)
        }
        val pending = del.runtime.confirm(preview.planId)
        assertEquals(TaskState.UNKNOWN, pending.state)
        assertEquals(TaskState.UNKNOWN, del.runtime.reconcile(pending.id).state)
        assertEquals(1, del.sippy.sends.size)
    }

    @Test
    fun aRepeatedOrConcurrentPrepareOfTheSameDraftIsRefusedWithoutHarmingTheLivePreview() = runTest {
        val h = Harness(this)
        h.sippy.onApply = { h.sippy.snapshot = enabledSnapshot() }
        val lease = h.draft()
        val command = h.put(lease)
        h.select(command)
        val first = h.runtime.prepare(command)
        // The same material cannot back two previews: the second is refused, not silently shared.
        assertFailsWith<CloudFailure> { h.runtime.prepare(command) }
        assertEquals(R2SippyLeaseState.DRAFT, lease.leaseState)
        assertTrue(lease.retainsMaterial)
        // The live preview is untouched and still confirmable.
        assertEquals(TaskState.SUCCEEDED, h.runtime.confirm(first.planId).state)
        assertEquals(1, h.sippy.sends.size)

        // Two drafts: discarding one unclaimed preview never retires the other live one.
        val liveDraft = h.draft()
        val discardedDraft = h.draft()
        val live = h.put(liveDraft)
        h.select(live)
        val livePreview = h.runtime.prepare(live)
        val discarded = h.put(discardedDraft)
        h.select(discarded)
        val discardedPreview = h.runtime.prepare(discarded)
        assertTrue(h.runtime.discardPreview(discardedPreview.planId))
        assertEquals(R2SippyLeaseState.RETIRED, discardedDraft.leaseState)
        assertFalse(discardedDraft.retainsMaterial)
        assertEquals(R2SippyLeaseState.DRAFT, liveDraft.leaseState)
        assertTrue(liveDraft.retainsMaterial)
        h.select(live)
        assertEquals(TaskState.SUCCEEDED, h.runtime.confirm(livePreview.planId).state)
    }

    @Test
    fun aPrepareThatCrossesTheTtlStillRetiresTheExpiredPreviewMaterial() = runTest {
        val h = Harness(this)
        val expired = h.draft()
        val expiredCommand = h.put(expired)
        h.select(expiredCommand)
        val expiredPreview = h.runtime.prepare(expiredCommand)
        assertEquals(R2SippyLeaseState.DRAFT, expired.leaseState)

        // The next prepare is held open inside its provider read until after the first plan's TTL.
        val gate = CompletableDeferred<Unit>()
        h.sippy.readGate = { gate.await() }
        val slow = h.draft()
        val slowCommand = h.put(slow)
        h.select(slowCommand)
        var slowPlanId: String? = null
        val prepareJob = launch { slowPlanId = h.runtime.prepare(slowCommand).planId }
        runCurrent()
        h.now += 300_000
        gate.complete(Unit)
        runCurrent()
        assertNotNull(slowPlanId)

        // The CAS filter inside the suspended prepare dropped the expired entry and released its
        // never-claimed material instead of leaking it until the next opportunistic prune.
        assertEquals(R2SippyLeaseState.RETIRED, expired.leaseState)
        assertFalse(expired.retainsMaterial)
        assertFailsWith<CloudFailure> { h.runtime.confirm(expiredPreview.planId) }
        assertEquals(0, h.sippy.sends.size)
    }

    @Test
    fun aRuntimeWithoutASippySessionNeverPreparesOrConfirmsASippyWrite() = runTest {
        val h = Harness(this, withSippy = false)
        val command = h.put(h.draft())
        h.select(command)
        val failure = assertFailsWith<CloudFailure> { h.runtime.prepare(command) }
        assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY, failure.code)
        assertEquals(0, h.sippy.reads.size)
        assertEquals(0, h.sippy.sends.size)

        // Without the live editor command there is no confirmation even when a session exists.
        val live = Harness(this)
        val lease = live.draft()
        val liveCommand = live.put(lease)
        live.select(liveCommand)
        val preview = live.runtime.prepare(liveCommand)
        live.actual = live.actual.copy(command = null)
        assertFailsWith<CloudFailure> { live.runtime.confirm(preview.planId) }
        assertEquals(0, live.sippy.sends.size)
        assertEquals(R2SippyLeaseState.DRAFT, lease.leaseState)
    }

    private suspend fun assertUnsettled(block: suspend () -> Unit) {
        val failure = assertFailsWith<CloudFailure> { block() }
        assertEquals(CloudFailureCode.UNKNOWN_RESULT, failure.code)
        assertTrue(failure.uncertain)
    }
}
