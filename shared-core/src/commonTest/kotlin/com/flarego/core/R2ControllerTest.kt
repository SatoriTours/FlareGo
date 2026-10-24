package com.flarego.core

import com.flarego.core.application.operations.ChangePlans
import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.operations.LiveOperationContext
import com.flarego.core.application.operations.OperationClock
import com.flarego.core.application.operations.OperationContextResolver
import com.flarego.core.application.operations.OperationCoordinator
import com.flarego.core.application.operations.OperationIds
import com.flarego.core.application.r2.R2Controller
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.CloudPage
import com.flarego.core.model.CloudScope
import com.flarego.core.model.Connection
import com.flarego.core.model.Operation
import com.flarego.core.model.OperationTask
import com.flarego.core.model.PageRequest
import com.flarego.core.model.R2BucketCreateInput
import com.flarego.core.model.R2BucketDocument
import com.flarego.core.model.R2BucketWrite
import com.flarego.core.model.R2BulkDeleteOutcome
import com.flarego.core.model.R2ObjectEntry
import com.flarego.core.model.R2ObjectFilter
import com.flarego.core.model.R2ObjectHead
import com.flarego.core.model.R2ObjectTarget
import com.flarego.core.model.R2ObjectWrite
import com.flarego.core.model.ResourceRef
import com.flarego.core.model.TaskState
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.ByteSource
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.OperationStore
import com.flarego.core.ports.R2Session
import java.security.MessageDigest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

/**
 * R2 bucket writes go through the shared confirmation machinery. These assertions pin
 * the composite rules: one send per confirmation, a frozen target that must still match
 * fresh provider evidence, a typed name for destructive deletes, and a read-only
 * reconciliation that never replays.
 */
class R2ControllerTest {
    private val connection = Connection("local", "Cloudflare", "account-one")
    private val account = AccountScope("local", "account-one")
    private val hash = FingerprintPort { bytes ->
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun bucketRow(name: String, storageClass: String = "Standard"): JsonObject =
        buildJsonObject {
            put("name", name)
            put("storage_class", storageClass)
            put("creation_date", "2026-01-01T00:00:00Z")
        }

    /** Only the members the controller uses are real; the rest would be a test bug. */
    private inner class Session : R2Session {
        val buckets = mutableMapOf<String, JsonObject>()
        val objects = mutableListOf<String>()
        var gets = 0
        var sends = 0
        var afterWrite: (R2BucketWrite) -> Unit = {}
        var failPostWriteRead = false
        var receipt: ExecutionReceipt = ExecutionReceipt.Completed()
        var refuseBeforeGuard = false
        var refuseAfterGuard = false
        var skipGuard = false
        var objectLists = 0
        var failObjectList = false
        var afterObjectWrite: (R2ObjectWrite) -> Unit = {}
        var objectPage: (suspend (R2ObjectFilter, PageRequest) -> CloudPage<R2ObjectEntry>)? = null
        val objectQueries = mutableListOf<Pair<R2ObjectFilter, PageRequest>>()

        override suspend fun getBucket(account: AccountScope, name: String): R2BucketDocument {
            gets++
            if (sends > 0 && failPostWriteRead) error("synthetic post-receipt read failure")
            val raw = buckets[name] ?: throw CloudFailure(CloudFailureCode.NOT_FOUND, "未找到请求的资源，请刷新后重试")
            return R2BucketDocument(name, account, raw)
        }

        override suspend fun listObjects(
            account: AccountScope,
            bucket: String,
            filter: R2ObjectFilter,
            page: PageRequest,
        ): CloudPage<R2ObjectEntry> {
            objectLists++
            objectQueries += filter to page
            if (failObjectList) error("synthetic post-delete read failure")
            objectPage?.let { return it(filter, page) }
            val prefix = filter.prefix ?: ""
            val items =
                objects.filter { it.startsWith(prefix) }.map {
                    R2ObjectEntry(it, 1, "etag", null, buildJsonObject { put("key", it) })
                }
            return CloudPage(items, null, items.size.toLong())
        }

        override suspend fun write(write: R2BucketWrite, beforeSend: (R2BucketDocument?) -> Unit): ExecutionReceipt {
            val creating = write is R2BucketWrite.Create || write is R2BucketWrite.CreateByName
            val observed = if (creating) null else getBucket(write.scope, nameOf(write))
            if (refuseBeforeGuard) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "synthetic pre-dispatch refusal")
            if (!skipGuard) beforeSend(observed)
            if (refuseAfterGuard) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "synthetic final-proof refusal")
            sends++
            afterWrite(write)
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
        override suspend fun downloadObject(target: R2ObjectTarget, sink: ByteSink): R2ObjectHead = TODO()
        override suspend fun uploadObject(
            target: R2ObjectTarget,
            source: ByteSource,
            length: Long,
            contentType: String?,
            contentFingerprint: String,
        ): R2ObjectEntry = TODO()

        override suspend fun deleteObject(target: R2ObjectTarget) = TODO()
        override suspend fun deleteObjects(account: AccountScope, bucket: String, keys: List<String>): R2BulkDeleteOutcome = TODO()

        override suspend fun writeObject(write: R2ObjectWrite, beforeSend: (R2ObjectEntry?) -> Unit): ExecutionReceipt {
            if (refuseBeforeGuard) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "synthetic pre-dispatch refusal")
            if (!skipGuard) beforeSend(null)
            if (refuseAfterGuard) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "synthetic final-proof refusal")
            sends++
            afterObjectWrite(write)
            return receipt
        }
    }

    private class Resolver : OperationContextResolver {
        var live: LiveOperationContext? = null
        var valid = true
        override fun current() = live
        override fun liveConnections() = listOfNotNull(live?.connection)
        override fun isLive(scope: CloudScope, credentialRevision: Long?) =
            valid && (credentialRevision == null || credentialRevision == live?.credentialRevision)
    }

    private class Store : OperationStore {
        val rows = mutableMapOf<String, OperationTask>()
        override fun get(id: String) = rows[id]
        override fun list(connectionId: String) = rows.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { rows[task.id] = task }
        override fun removeConnection(connectionId: String) { rows.entries.removeAll { it.value.connectionId == connectionId } }
        override fun putWithSummary(task: OperationTask, summary: Operation) { put(task) }
    }

    private inner class Harness(scope: TestScope) {
        val resolver = Resolver()
        var now = 1_000L
        var ids = 0
        val clock = OperationClock { now }
        val plans = ChangePlans(resolver, clock, OperationIds { "r2-${++ids}" })
        val store = Store()
        val session = Session()
        val coordinator = OperationCoordinator(plans, store, resolver, clock, scope.backgroundScope, StandardTestDispatcher(scope.testScheduler))
        val controller = R2Controller(resolver, plans, coordinator, clock, hash, store, StandardTestDispatcher(scope.testScheduler)) { session }

        fun select(write: R2BucketWrite, revision: Long = 1) {
            resolver.live = LiveOperationContext(connection, write.scope, revision, controller.requestFingerprint(write))
        }

        suspend fun prepare(write: R2BucketWrite): com.flarego.core.application.operations.ChangePlan {
            select(write)
            return controller.prepare(write)
        }

        fun confirm(plan: com.flarego.core.application.operations.ChangePlan) =
            plans.confirm(plan.id, plan.scope, plan.credentialRevision, plan.requestFingerprint, now)

        fun selectObject(write: R2ObjectWrite, revision: Long = 1) {
            resolver.live = LiveOperationContext(connection, write.scope, revision, controller.requestObjectFingerprint(write))
        }

        suspend fun prepareObject(write: R2ObjectWrite): com.flarego.core.application.operations.ChangePlan {
            selectObject(write)
            return controller.prepareObject(write)
        }

        fun confirmObject(plan: com.flarego.core.application.operations.ChangePlan, write: R2ObjectWrite) =
            plans.confirm(plan.id, plan.scope, plan.credentialRevision, controller.requestObjectFingerprint(write), now)
    }

    private val uploadFingerprint = "c".repeat(64)

    private fun upload(key: String, fingerprint: String = uploadFingerprint, length: Long = 3) =
        R2ObjectWrite.Upload(account, "my-bucket", key, { throw IllegalStateException("no source is opened in these tests") }, length, null, fingerprint)

    private fun readyHarness(scope: TestScope): Harness {
        val harness = Harness(scope)
        harness.session.buckets["my-bucket"] = bucketRow("my-bucket")
        return harness
    }

    @Test
    fun deleteNeedsTheTypedBucketNameBeforeAnythingIsRead() = runTest {
        val harness = Harness(this)
        harness.session.buckets["my-bucket"] = bucketRow("my-bucket")
        val wrong = R2BucketWrite.Delete(account, "my-bucket", "my-bucke")
        harness.select(wrong)
        val failure = assertFailsWith<CloudFailure> { harness.controller.prepare(wrong) }
        assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)
        assertEquals(0, harness.session.gets, "a mistyped delete must not read anything")
    }

    @Test
    fun bulkDeleteCompletionCannotClaimSuccessWhileARequestedKeyStillExists() = runTest {
        val h = readyHarness(this)
        h.session.objects += listOf("dir/a.txt", "dir/b.txt")
        val write = R2ObjectWrite.BulkDelete(account, "my-bucket", listOf("dir/a.txt", "dir/b.txt"))
        val task = h.controller.submitObject(write, h.confirmObject(h.prepareObject(write), write))
        assertEquals(TaskState.UNKNOWN, task.state)
        assertEquals(1, h.session.objectLists)
        assertEquals(1, h.session.sends)
        assertFailsWith<CloudFailure> { h.prepareObject(write) }
    }

    @Test
    fun bulkDeleteCompletionRequiresAnExhaustedReadWithNoRequestedKeys() = runTest {
        val h = readyHarness(this)
        h.session.objects += listOf("dir/a.txt", "dir/b.txt", "dir/unrelated.txt")
        val keys = listOf("dir/a.txt", "dir/b.txt")
        h.session.afterObjectWrite = { h.session.objects.removeAll(keys.toSet()) }
        val write = R2ObjectWrite.BulkDelete(account, "my-bucket", keys)
        val task = h.controller.submitObject(write, h.confirmObject(h.prepareObject(write), write))
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals(1, h.session.objectLists)
        assertEquals("dir/", h.session.objectQueries.single().first.prefix)
        assertEquals(1000, h.session.objectQueries.single().second.limit)
        assertEquals(1, h.session.sends)
    }

    @Test
    fun bulkPostReadFailureIsUnknownWithoutReplayingTheDelete() = runTest {
        val h = readyHarness(this); h.session.failObjectList = true
        val write = R2ObjectWrite.BulkDelete(account, "my-bucket", listOf("a.txt"))
        val task = h.controller.submitObject(write, h.confirmObject(h.prepareObject(write), write))
        assertEquals(TaskState.UNKNOWN, task.state)
        assertEquals(1, h.session.objectLists)
        assertEquals(TaskState.ACTION_REQUIRED, h.controller.reconcileObject(task.id).state)
        assertEquals(1, h.session.sends)
    }

    @Test
    fun bulkPostReadRejectsRepeatedCursorsAndStopsAtABoundedBudgetWithUnicodeSafePrefix() = runTest {
        for (repeated in listOf(true, false)) {
            val h = readyHarness(this)
            h.session.objectPage = { _, _ -> CloudPage(emptyList(), com.flarego.core.model.PageToken.Cursor(
                if (repeated) "repeat" else "page-${h.session.objectLists}"), null) }
            val write = R2ObjectWrite.BulkDelete(account, "my-bucket", listOf("甲😀/a.txt", "甲😁/b.txt"))
            val task = h.controller.submitObject(write, h.confirmObject(h.prepareObject(write), write))
            assertEquals(TaskState.UNKNOWN, task.state)
            assertEquals(if (repeated) 2 else 5, h.session.objectLists)
            assertTrue(h.session.objectQueries.all { it.first.prefix == "甲" && it.second.limit == 1000 })
            assertEquals(1, h.session.sends)
        }
    }

    @Test
    fun bulkPostReadForwardsTheCursorAndChecksLaterPagesBeforeSettling() = runTest {
        for (remaining in listOf(true, false)) {
            val h = readyHarness(this)
            h.session.objectPage = { _, page ->
                if (page.token == null) CloudPage(emptyList(), com.flarego.core.model.PageToken.Cursor("last-page"), null)
                else {
                    assertEquals(com.flarego.core.model.PageToken.Cursor("last-page"), page.token)
                    val rows = if (remaining) listOf(R2ObjectEntry("dir/b.txt", 1, null, null, buildJsonObject { put("key", "dir/b.txt") })) else emptyList()
                    CloudPage(rows, null, null)
                }
            }
            val write = R2ObjectWrite.BulkDelete(account, "my-bucket", listOf("dir/a.txt", "dir/b.txt"))
            val task = h.controller.submitObject(write, h.confirmObject(h.prepareObject(write), write))
            assertEquals(if (remaining) TaskState.UNKNOWN else TaskState.SUCCEEDED, task.state)
            assertEquals(2, h.session.objectLists)
            assertEquals(1, h.session.sends)
        }
    }

    @Test
    fun bulkPartialAcceptedAndUnknownReceiptsDoNotPretendToHaveACompleteReadback() = runTest {
        for ((receipt, state) in listOf(ExecutionReceipt.Partial("部分拒绝") to TaskState.PARTIAL,
            ExecutionReceipt.Accepted() to TaskState.ACCEPTED, ExecutionReceipt.Unknown() to TaskState.UNKNOWN)) {
            val h = readyHarness(this); h.session.receipt = receipt
            val write = R2ObjectWrite.BulkDelete(account, "my-bucket", listOf("a.txt"))
            assertEquals(state, h.controller.submitObject(write, h.confirmObject(h.prepareObject(write), write)).state)
            assertEquals(0, h.session.objectLists)
            assertEquals(1, h.session.sends)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun cancellingTheBulkUiWaiterDoesNotCancelItsAppOwnedPostRead() = runTest {
        val h = readyHarness(this)
        val release = CompletableDeferred<Unit>()
        h.session.objectPage = { _, _ -> release.await(); CloudPage(emptyList(), null, 0) }
        val write = R2ObjectWrite.BulkDelete(account, "my-bucket", listOf("a.txt"))
        val confirmed = h.confirmObject(h.prepareObject(write), write)
        val waiter = async { h.controller.submitObject(write, confirmed) }
        runCurrent()
        assertEquals(TaskState.RUNNING, h.store.rows.values.single().state)
        assertEquals(1, h.session.sends)
        waiter.cancelAndJoin()
        release.complete(Unit); runCurrent()
        assertEquals(TaskState.SUCCEEDED, h.store.rows.values.single().state)
        assertEquals(1, h.session.objectLists)
        assertEquals(1, h.session.sends)
    }

    @Test
    fun aDeleteReceiptCannotClaimSuccessWhileTheBucketIsStillPresent() = runTest {
        val h = readyHarness(this)
        val write = R2BucketWrite.Delete(account, "my-bucket", "my-bucket")
        val task = h.controller.submit(write, h.confirm(h.prepare(write)))
        assertEquals(TaskState.UNKNOWN, task.state)
        assertEquals(3, h.session.gets)
        assertEquals(TaskState.UNKNOWN, h.controller.reconcile(task.id).state)
        assertEquals(1, h.session.sends)
        assertFailsWith<CloudFailure> { h.prepare(write) }
    }

    @Test
    fun aDeleteSucceedsOnlyAfterAPostWriteReadProvesAbsence() = runTest {
        val h = readyHarness(this)
        h.session.afterWrite = { h.session.buckets.remove("my-bucket") }
        val write = R2BucketWrite.Delete(account, "my-bucket", "my-bucket")
        val task = h.controller.submit(write, h.confirm(h.prepare(write)))
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals(3, h.session.gets)
        assertEquals(1, h.session.sends)
    }

    @Test
    fun aPatchReceiptCannotClaimAnUnchangedUnknownOrMissingDefaultClassWasApplied() = runTest {
        for (value in listOf<String?>("Standard", "FutureArchive", null)) {
            val h = readyHarness(this)
            h.session.afterWrite = {
                h.session.buckets["my-bucket"] = if (value == null)
                    JsonObject(bucketRow("my-bucket").filterKeys { it != "storage_class" }) else bucketRow("my-bucket", value)
            }
            val write = R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess")
            val task = h.controller.submit(write, h.confirm(h.prepare(write)))
            assertEquals(TaskState.UNKNOWN, task.state, "unproved class: $value")
            assertEquals(1, h.session.sends)
        }
    }

    @Test
    fun aPatchSucceedsWhenThePostWriteReadMatchesTheRequestedClass() = runTest {
        val h = readyHarness(this)
        h.session.afterWrite = { h.session.buckets["my-bucket"] = bucketRow("my-bucket", "InfrequentAccess") }
        val write = R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess")
        assertEquals(TaskState.SUCCEEDED, h.controller.submit(write, h.confirm(h.prepare(write))).state)
        assertEquals(3, h.session.gets)
        assertEquals(1, h.session.sends)
    }

    @Test
    fun postAndPutReceiptsCannotClaimADifferentDefaultClassWasApplied() = runTest {
        for (write in listOf(R2BucketWrite.Create(account, R2BucketCreateInput("my-bucket", storageClass = "InfrequentAccess")),
            R2BucketWrite.CreateByName(account, "my-bucket", "InfrequentAccess"))) {
            val h = Harness(this)
            h.session.afterWrite = { h.session.buckets["my-bucket"] = bucketRow("my-bucket", "Standard") }
            assertEquals(TaskState.UNKNOWN, h.controller.submit(write, h.confirm(h.prepare(write))).state)
            assertEquals(1, h.session.sends)
        }
    }

    @Test
    fun postAndPutSucceedWhenThePostWriteReadMatchesTheRequestedClass() = runTest {
        for (write in listOf(R2BucketWrite.Create(account, R2BucketCreateInput("my-bucket", storageClass = "InfrequentAccess")),
            R2BucketWrite.CreateByName(account, "my-bucket", "InfrequentAccess"))) {
            val h = Harness(this)
            h.session.afterWrite = { h.session.buckets["my-bucket"] = bucketRow("my-bucket", "InfrequentAccess") }
            assertEquals(TaskState.SUCCEEDED, h.controller.submit(write, h.confirm(h.prepare(write))).state)
            assertEquals(3, h.session.gets)
            assertEquals(1, h.session.sends)
        }
    }

    @Test
    fun postReceiptReadFailureStaysUnknownAndNeverResendsTheMutation() = runTest {
        for (write in listOf(R2BucketWrite.CreateByName(account, "new-bucket", "Standard"),
            R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess"), R2BucketWrite.Delete(account, "my-bucket", "my-bucket"))) {
            val h = readyHarness(this)
            h.session.failPostWriteRead = true
            val task = h.controller.submit(write, h.confirm(h.prepare(write)))
            assertEquals(TaskState.UNKNOWN, task.state)
            assertEquals(TaskState.UNKNOWN, h.controller.reconcile(task.id).state)
            assertEquals(1, h.session.sends)
        }
    }

    @Test
    fun acceptedAndUnknownReceiptsDoNotPretendToHaveACompletedPostcondition() = runTest {
        for ((receipt, state) in listOf(ExecutionReceipt.Accepted() to TaskState.ACCEPTED, ExecutionReceipt.Unknown() to TaskState.UNKNOWN)) {
            val h = readyHarness(this)
            h.session.receipt = receipt
            val write = R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess")
            assertEquals(state, h.controller.submit(write, h.confirm(h.prepare(write))).state)
            assertEquals(2, h.session.gets)
            assertEquals(1, h.session.sends)
        }
    }

    @Test
    fun aProvenPreDispatchRefusalSettlesWithoutReplayAndOnlyAFreshPlanMayWrite() = runTest {
        val h = readyHarness(this)
        val write = R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess")
        val confirmed = h.confirm(h.prepare(write))
        h.session.refuseBeforeGuard = true
        val refused = h.controller.submit(write, confirmed)
        assertEquals(TaskState.FAILED, refused.state)
        assertEquals(0, h.session.sends)
        assertEquals(refused.id, h.controller.submit(write, confirmed).id)
        val reads = h.session.gets
        assertEquals(TaskState.FAILED, h.controller.reconcile(refused.id).state)
        assertEquals(reads, h.session.gets, "a settled refusal is not turned back into uncertainty by another read")
        h.session.refuseBeforeGuard = false
        h.session.afterWrite = { h.session.buckets["my-bucket"] = bucketRow("my-bucket", "InfrequentAccess") }
        val fresh = h.controller.submit(write, h.confirm(h.prepare(write)))
        assertEquals(TaskState.SUCCEEDED, fresh.state)
        assertNotEquals(refused.id, fresh.id)
        assertEquals(1, h.session.sends)
        assertEquals(TaskState.FAILED, h.store.rows.getValue(refused.id).state)
    }

    @Test
    fun theSameErrorAfterTheSendBoundaryCannotBeReclassifiedAsNotDispatched() = runTest {
        val h = readyHarness(this)
        h.session.refuseAfterGuard = true
        val write = R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess")
        val task = h.controller.submit(write, h.confirm(h.prepare(write)))
        assertEquals(TaskState.RUNNING, task.state)
        assertFailsWith<CloudFailure> { h.prepare(write) }
        assertEquals(0, h.session.sends)
    }

    @Test
    fun objectRefusalsUseTheSameTrustedBoundaryAndCannotBeRevivedByReadOnlyReconciliation() = runTest {
        for (before in listOf(true, false)) {
            val h = readyHarness(this)
            h.session.refuseBeforeGuard = before
            h.session.refuseAfterGuard = !before
            val write = R2ObjectWrite.Delete(account, "my-bucket", "a.txt", "a.txt")
            val task = h.controller.submitObject(write, h.confirmObject(h.prepareObject(write), write))
            assertEquals(if (before) TaskState.FAILED else TaskState.RUNNING, task.state)
            if (before) {
                val reads = h.session.gets
                assertEquals(TaskState.FAILED, h.controller.reconcileObject(task.id).state)
                assertEquals(reads, h.session.gets)
                h.resolver.live = h.resolver.live!!.copy(scope = AccountScope("local", "other-account"))
                assertFailsWith<CloudFailure> { h.controller.reconcileObject(task.id) }
                assertEquals(TaskState.FAILED, h.store.rows.getValue(task.id).state)
            }
            assertEquals(0, h.session.sends)
        }
    }

    @Test
    fun anAdapterThatReturnsCompletionWithoutTheFinalGuardCannotClaimSuccess() = runTest {
        val h = readyHarness(this)
        h.session.skipGuard = true
        h.session.afterWrite = { h.session.buckets["my-bucket"] = bucketRow("my-bucket", "InfrequentAccess") }
        val write = R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess")
        val task = h.controller.submit(write, h.confirm(h.prepare(write)))
        assertEquals(TaskState.UNKNOWN, task.state)
        assertEquals(1, h.session.sends)
    }

    @Test
    fun createIsRefusedWhenTheBucketAlreadyExists() = runTest {
        val harness = Harness(this)
        harness.session.buckets["my-bucket"] = bucketRow("my-bucket")
        val failure = assertFailsWith<CloudFailure> { harness.prepare(R2BucketWrite.Create(account, R2BucketCreateInput("my-bucket"))) }
        assertEquals(CloudFailureCode.CONFLICT, failure.code)
    }

    @Test
    fun aPatchWithoutARealChangeIsRefused() = runTest {
        val harness = Harness(this)
        harness.session.buckets["my-bucket"] = bucketRow("my-bucket", "Standard")
        val failure = assertFailsWith<CloudFailure> {
            harness.prepare(R2BucketWrite.PatchStorageClass(account, "my-bucket", "Standard"))
        }
        assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)
        assertEquals(0, harness.session.sends)
    }

    @Test
    fun unknownOrMissingStorageClassKeepsTheBucketReadOnly() = runTest {
        for (value in listOf<String?>("FutureArchive", null)) {
            val h = Harness(this)
            h.session.buckets["my-bucket"] = if (value == null)
                JsonObject(bucketRow("my-bucket").filterKeys { it != "storage_class" }) else bucketRow("my-bucket", value)
            val error = assertFailsWith<CloudFailure> {
                h.prepare(R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess"))
            }
            assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY, error.code)
            assertEquals(0, h.session.sends)
        }
    }

    @Test
    fun patchPreviewShowsBothFrozenClassesAndLeavesExistingObjectsUnchanged() = runTest {
        val h = readyHarness(this)
        val plan = h.prepare(R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess"))
        val impact = h.controller.preview(plan.id).impact
        assertTrue(impact.contains("Standard"))
        assertTrue(impact.contains("InfrequentAccess"))
        assertTrue(impact.contains("已有对象"))
        assertEquals(0, h.session.sends)
    }

    @Test
    fun putPreviewStatesTheActualCreateVariantWithoutAPostLocationHint() = runTest {
        val h = Harness(this)
        val plan = h.prepare(R2BucketWrite.CreateByName(account, "new-bucket", "InfrequentAccess"))
        val impact = h.controller.preview(plan.id).impact
        assertTrue(impact.contains("PUT"))
        assertTrue(impact.contains("InfrequentAccess"))
        assertTrue(!impact.contains("locationHint"))
        assertEquals(0, h.session.sends)
    }

    @Test
    fun unsupportedHeaderClassesCannotEvenProduceAPreparedPlan() = runTest {
        val h = readyHarness(this)
        for (write in listOf(R2BucketWrite.PatchStorageClass(account, "my-bucket", "FutureArchive"),
            R2BucketWrite.CreateByName(account, "new-bucket", "FutureArchive"))) {
            val error = assertFailsWith<CloudFailure> { h.prepare(write) }
            assertEquals(CloudFailureCode.INVALID_INPUT, error.code)
        }
        assertEquals(0, h.session.gets)
        assertEquals(0, h.session.sends)
    }

    @Test
    fun aBucketChangedBetweenPrepareAndSubmitBlocksTheSend() = runTest {
        val harness = Harness(this)
        harness.session.buckets["my-bucket"] = bucketRow("my-bucket", "Standard")
        val write = R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess")
        val plan = harness.prepare(write)
        val confirmed = harness.confirm(plan)

        // The provider state moved after the preview: the frozen before-hash no longer matches.
        harness.session.buckets["my-bucket"] = buildJsonObject {
            bucketRow("my-bucket", "Standard").forEach { (key, value) -> put(key, value) }
            put("changed_elsewhere", true)
        }
        val task = harness.controller.submit(write, confirmed)
        // The final guard throws before the typed send, so nothing was sent...
        assertEquals(0, harness.session.sends, "a conflict detected at the final guard must block the send")
        // The R2-only trusted boundary now proves this refusal was before dispatch.
        assertEquals(TaskState.FAILED, task.state)
        assertEquals(TaskState.FAILED, harness.controller.reconcile(task.id).state)
    }

    @Test
    fun oneConfirmationAdmitsExactlyOneSend() = runTest {
        val harness = Harness(this)
        harness.session.buckets["my-bucket"] = bucketRow("my-bucket", "Standard")
        val write = R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess")
        val confirmed = harness.confirm(harness.prepare(write))

        val first = harness.controller.submit(write, confirmed)
        val second = harness.controller.submit(write, confirmed)
        assertEquals(first.id, second.id)
        assertEquals(1, harness.session.sends)
    }

    @Test
    fun anUnsettledPriorBucketOperationBlocksANewPrepare() = runTest {
        val harness = Harness(this)
        harness.session.buckets["my-bucket"] = bucketRow("my-bucket")
        harness.store.rows["prior"] = OperationTask(
            id = "prior",
            connectionId = connection.id,
            scope = account,
            actionKey = "r2-delete-bucket",
            resourceRef = ResourceRef(account, "r2_bucket", "my-bucket"),
            requestFingerprint = "a".repeat(64),
            desiredHash = "b".repeat(64),
            state = TaskState.UNKNOWN,
            safeDetail = "未结算",
            providerRequestId = null,
            pollPath = null,
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )
        val failure = assertFailsWith<CloudFailure> {
            harness.prepare(R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess"))
        }
        assertEquals(CloudFailureCode.UNKNOWN_RESULT, failure.code)
        assertTrue(failure.uncertain, "an unsettled prior write must not be presented as a fresh start")
        assertEquals(0, harness.session.sends)
    }

    @Test
    fun confirmationIsRefusedAfterTheCredentialRevisionChanges() = runTest {
        val harness = Harness(this)
        harness.session.buckets["my-bucket"] = bucketRow("my-bucket", "Standard")
        val write = R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess")
        val plan = harness.prepare(write)
        harness.select(write, revision = 2)
        assertFailsWith<CloudFailure> { harness.confirm(plan) }
        assertEquals(0, harness.session.sends)
    }

    @Test
    fun reconciliationMatchesADeleteOnlyWhenTheBucketIsReallyGone() = runTest {
        val harness = Harness(this)
        harness.session.buckets["my-bucket"] = bucketRow("my-bucket")
        val write = R2BucketWrite.Delete(account, "my-bucket", "my-bucket")
        val confirmed = harness.confirm(harness.prepare(write))
        val task = harness.controller.submit(write, confirmed)
        assertEquals(TaskState.UNKNOWN, task.state)

        // The receipt and a still-present bucket cannot prove successful deletion.
        val stillThere = harness.controller.reconcile(task.id)
        assertEquals(TaskState.UNKNOWN, stillThere.state)

        harness.session.buckets.remove("my-bucket")
        assertEquals(TaskState.SUCCEEDED, harness.controller.reconcile(task.id).state)
    }

    @Test
    fun preexistingKeyNeverProvesUnknownUploadContentWasApplied() = runTest {
        val h = readyHarness(this)
        h.session.objects += "existing.txt"
        val write = upload("existing.txt")
        val task = h.controller.submitObject(write, h.confirmObject(h.prepareObject(write), write))
        h.store.rows[task.id] = task.copy(state = TaskState.UNKNOWN)
        assertEquals(TaskState.UNKNOWN, h.controller.reconcileObject(task.id).state)
        assertEquals(1, h.session.sends)
    }

    @Test
    fun singleDeleteAbsenceMatchesTheCompleteFrozenTargetHash() = runTest {
        val h = readyHarness(this)
        val write = R2ObjectWrite.Delete(account, "my-bucket", "dir/a.txt", "dir/a.txt")
        val task = h.controller.submitObject(write, h.confirmObject(h.prepareObject(write), write))
        h.store.rows[task.id] = task.copy(state = TaskState.UNKNOWN)
        assertEquals(TaskState.SUCCEEDED, h.controller.reconcileObject(task.id).state)
        assertEquals(1, h.session.sends)
    }

    @Test
    fun callerOwnedBatchListCannotMutateThePreparedPayload() = runTest {
        val h = readyHarness(this)
        val keys = mutableListOf("a.txt")
        val draft = R2ObjectWrite.BulkDelete(account, "my-bucket", keys)
        val plan = h.prepareObject(draft)
        keys += "unapproved.txt"
        val actual = R2ObjectWrite.BulkDelete(account, "my-bucket", listOf("a.txt"))
        val task = h.controller.submitObject(actual, h.confirmObject(plan, actual))
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals(1, h.session.sends)
    }

    @Test
    fun actionRequiredAfterPartialBatchStillBlocksNewWrites() = runTest {
        val h = readyHarness(this)
        val write = R2ObjectWrite.BulkDelete(account, "my-bucket", listOf("a.txt", "b.txt"))
        val task = h.controller.submitObject(write, h.confirmObject(h.prepareObject(write), write))
        h.store.rows[task.id] = task.copy(state = TaskState.PARTIAL)
        assertEquals(TaskState.ACTION_REQUIRED, h.controller.reconcileObject(task.id).state)
        assertFailsWith<CloudFailure> { h.prepareObject(upload("other.txt")) }
        assertEquals(1, h.session.sends)
    }

    @Test
    fun createdBucketCanReconcileItsRequestedStorageClassWithoutInventingLocationGuarantees() = runTest {
        val h = Harness(this)
        val write = R2BucketWrite.Create(account, R2BucketCreateInput("my-bucket", "weur", "InfrequentAccess"))
        val task = h.controller.submit(write, h.confirm(h.prepare(write)))
        h.store.rows[task.id] = task.copy(state = TaskState.UNKNOWN)
        h.session.buckets["my-bucket"] = bucketRow("my-bucket", "Standard")
        assertEquals(TaskState.UNKNOWN, h.controller.reconcile(task.id).state)
        h.session.buckets["my-bucket"] = bucketRow("my-bucket", "InfrequentAccess")
        assertEquals(TaskState.SUCCEEDED, h.controller.reconcile(task.id).state)
    }

    @Test
    fun anObjectDeleteNeedsTheTypedKeyBeforeAnythingIsRead() = runTest {
        val harness = readyHarness(this)
        val wrong = R2ObjectWrite.Delete(account, "my-bucket", "dir/a.txt", "dir/b.txt")
        harness.selectObject(wrong)
        val failure = assertFailsWith<CloudFailure> { harness.controller.prepareObject(wrong) }
        assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)
        assertEquals(0, harness.session.gets)
    }

    @Test
    fun aBulkDeleteWithDuplicateKeysIsRefused() = runTest {
        val harness = readyHarness(this)
        val write = R2ObjectWrite.BulkDelete(account, "my-bucket", listOf("a.txt", "a.txt"))
        harness.selectObject(write)
        val failure = assertFailsWith<CloudFailure> { harness.controller.prepareObject(write) }
        assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)
        assertEquals(0, harness.session.sends)
    }

    @Test
    fun anUploadWhoseContentChangedCannotBeConfirmed() = runTest {
        val harness = readyHarness(this)
        val before = upload("dir/a.txt", fingerprint = "c".repeat(64))
        val plan = harness.prepareObject(before)
        // The user picked a different (or modified) file after the preview.
        val after = upload("dir/a.txt", fingerprint = "d".repeat(64))
        harness.selectObject(after)
        assertFailsWith<CloudFailure> { harness.confirmObject(plan, after) }
        assertEquals(0, harness.session.sends)
    }

    @Test
    fun anUploadInvalidatesThePlanWhenTheStagedBytesChange() = runTest {
        val harness = readyHarness(this)
        val planned = upload("dir/a.txt")
        val confirmed = harness.confirmObject(harness.prepareObject(planned), planned)
        // Same target, different content fingerprint: the frozen plan no longer matches.
        val changed = upload("dir/a.txt", fingerprint = "e".repeat(64))
        assertFailsWith<CloudFailure> { harness.controller.submitObject(changed, confirmed) }
        assertEquals(0, harness.session.sends)
    }

    @Test
    fun reconcileKeepsAnUploadUnknownEvenWhenTheKeyIsListed() = runTest {
        val harness = readyHarness(this)
        val write = upload("dir/a.txt")
        val task = harness.controller.submitObject(write, harness.confirmObject(harness.prepareObject(write), write))
        assertEquals(TaskState.SUCCEEDED, task.state)

        // Not listed yet: the read proves nothing, so the task returns to UNKNOWN.
        assertEquals(TaskState.UNKNOWN, harness.controller.reconcileObject(task.id).state)

        harness.session.objects += "dir/a.txt"
        assertEquals(TaskState.UNKNOWN, harness.controller.reconcileObject(task.id).state)
    }

    @Test
    fun aBulkDeleteReconciliationAsksForAVerificationInsteadOfPretending() = runTest {
        val harness = readyHarness(this)
        val write = R2ObjectWrite.BulkDelete(account, "my-bucket", listOf("a.txt", "b.txt"))
        val task = harness.controller.submitObject(write, harness.confirmObject(harness.prepareObject(write), write))
        assertEquals(TaskState.SUCCEEDED, task.state)

        // Proving a thousand keys are gone needs a per-key readback the app cannot do
        // cheaply, so the outcome asks the user to verify instead of claiming success.
        assertEquals(TaskState.ACTION_REQUIRED, harness.controller.reconcileObject(task.id).state)
    }

    @Test
    fun anUnsettledObjectWriteBlocksABucketChangeForTheSameBucket() = runTest {
        val harness = readyHarness(this)
        harness.store.rows["prior-object"] = OperationTask(
            id = "prior-object",
            connectionId = connection.id,
            scope = account,
            actionKey = "r2-delete-object",
            resourceRef = ResourceRef(account, "r2_object", "my-bucket/dir/a.txt"),
            requestFingerprint = "a".repeat(64),
            desiredHash = "b".repeat(64),
            state = TaskState.UNKNOWN,
            safeDetail = "未结算",
            providerRequestId = null,
            pollPath = null,
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )
        val failure = assertFailsWith<CloudFailure> {
            harness.prepare(R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess"))
        }
        assertEquals(CloudFailureCode.UNKNOWN_RESULT, failure.code)

        // The same object write also blocks a sibling object write in that bucket.
        val objectWrite = upload("dir/other.txt")
        harness.selectObject(objectWrite)
        assertFailsWith<CloudFailure> { harness.controller.prepareObject(objectWrite) }
    }

    @Test
    fun previewDescribesTheRealImpactOfEachAction() = runTest {
        val harness = Harness(this)
        harness.session.buckets["my-bucket"] = bucketRow("my-bucket", "Standard")
        val patch = R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess")
        val plan = harness.prepare(patch)
        val preview = harness.controller.preview(plan.id)
        assertEquals("my-bucket", preview.bucket)
        assertTrue(preview.impact.contains("新上传对象"), "the preview must not claim existing objects change")
        assertTrue(preview.concurrencyWarning.contains("CAS"), "the residual race window must be stated")
    }
}
