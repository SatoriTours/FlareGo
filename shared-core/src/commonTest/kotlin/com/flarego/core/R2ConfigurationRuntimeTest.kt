package com.flarego.core

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.operations.OperationClock
import com.flarego.core.application.operations.OperationIds
import com.flarego.core.application.r2.R2Command
import com.flarego.core.application.r2.R2LiveSelection
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
import com.flarego.core.model.R2CorsAllowed
import com.flarego.core.model.R2CorsRule
import com.flarego.core.model.R2ObjectEntry
import com.flarego.core.model.R2ObjectFilter
import com.flarego.core.model.R2ObjectHead
import com.flarego.core.model.R2ObjectTarget
import com.flarego.core.model.R2ObjectWrite
import com.flarego.core.model.R2WriteTarget
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shared runtime of batch A and batch A/B configuration writes: one issuer, one
 * coordinator, one bucket identity per default target, one 64-slot preview window and
 * one unsettled gate in both directions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R2ConfigurationRuntimeTest {
    private val connection = Connection("local", "fixture", "account-one")
    private val account = AccountScope("local", "account-one")
    private val target = R2WriteTarget(account, "my-bucket")

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

    private val corsRule = R2CorsRule(id = "one", allowed = R2CorsAllowed(listOf("GET"), listOf("https://a.example")))

    private inner class BucketSession : R2Session {
        val rows = mutableMapOf("my-bucket" to bucketRow("my-bucket", "Standard"))
        var sends = 0
        var receipt: ExecutionReceipt = ExecutionReceipt.Completed()

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
            val creating = write is R2BucketWrite.Create || write is R2BucketWrite.CreateByName
            if (!creating) beforeSend(getBucket(write.scope, nameOf(write)))
            sends++
            if (write is R2BucketWrite.PatchStorageClass) rows[write.name] = bucketRow(write.name, write.storageClass)
            return receipt
        }

        private fun nameOf(write: R2BucketWrite) =
            when (write) {
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

    private inner class Harness(scope: TestScope, withConfiguration: Boolean = true) {
        var actual = R2LiveSelection(connection, 1, 1, null)
        var revision = 1L
        var ids = 0
        var now = 1_000L
        val store = Store()
        val bucket = BucketSession()
        val configuration = FakeConfigurationSession(account).apply {
            emptyRules(target)
            emptyRules(R2WriteTarget(account, "my-bucket", "eu"))
        }
        val runtime = R2WriteRuntime(
            scope.backgroundScope, store, StandardTestDispatcher(scope.testScheduler),
            FingerprintPort { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            },
            OperationClock { now }, OperationIds { "runtime-${++ids}" },
            { actual }, { listOf(connection) }, { revision }, { bucket },
            configSession = if (withConfiguration) ({ configuration.open(); configuration }) else null,
        )

        fun select(command: R2Command) { actual = actual.copy(command = command) }

        fun cors(rules: List<R2CorsRule> = listOf(corsRule), on: R2WriteTarget = target) =
            R2Command.Configuration(R2ConfigurationWrite.PutCors(on, rules))

        fun patch() = R2Command.Bucket(R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess"))
    }

    @Test
    fun aDefaultConfigurationAndABucketWriteShareOneUnsettledGateInBothDirections() = runTest {
        val first = Harness(this)
        first.configuration.receipt = ExecutionReceipt.Unknown()
        val config = first.cors()
        first.select(config)
        val preview = first.runtime.prepare(config)
        assertEquals(TaskState.UNKNOWN, first.runtime.confirm(preview.planId).state)
        first.select(first.patch())
        val blocked = assertFailsWith<CloudFailure> { first.runtime.prepare(first.patch()) }
        assertEquals(CloudFailureCode.UNKNOWN_RESULT, blocked.code)
        assertEquals(0, first.bucket.sends)

        val second = Harness(this)
        second.bucket.receipt = ExecutionReceipt.Unknown()
        second.select(second.patch())
        val bucketPreview = second.runtime.prepare(second.patch())
        assertEquals(TaskState.UNKNOWN, second.runtime.confirm(bucketPreview.planId).state)
        second.select(second.cors())
        assertFailsWith<CloudFailure> { second.runtime.prepare(second.cors()) }
        assertEquals(0, second.configuration.sends.size)
    }

    @Test
    fun anotherJurisdictionIsNotBlockedByAnUnsettledDefaultWriteAndKeepsItsOwnGroup() = runTest {
        val h = Harness(this)
        h.bucket.receipt = ExecutionReceipt.Unknown()
        h.select(h.patch())
        assertEquals(TaskState.UNKNOWN, h.runtime.confirm(h.runtime.prepare(h.patch()).planId).state)
        val eu = h.cors(on = R2WriteTarget(account, "my-bucket", "eu"))
        h.select(eu)
        val preview = h.runtime.prepare(eu)
        assertEquals(TaskState.SUCCEEDED, h.runtime.confirm(preview.planId).state)
        assertEquals(1, h.configuration.sends.size)
        assertEquals("eu", h.configuration.sends.single().target.jurisdiction)
        assertNotNull(h.store.rows.values.firstOrNull { it.state == TaskState.UNKNOWN })
    }

    @Test
    fun mixedPreviewsShareTheSixtyFourSlotWindowAndAreCancelledIndependently() = runTest {
        val h = Harness(this)
        val previews = mutableListOf<String>()
        repeat(32) {
            val bucket = h.patch()
            h.select(bucket)
            previews += h.runtime.prepare(bucket).planId
            val config = h.cors()
            h.select(config)
            previews += h.runtime.prepare(config).planId
        }
        assertEquals(64, previews.toSet().size)
        val overflow = h.cors()
        h.select(overflow)
        assertFailsWith<CloudFailure> { h.runtime.prepare(overflow) }
        assertTrue(h.runtime.discardPreview(previews.last()))
        val replacement = h.cors()
        h.select(replacement)
        val fresh = h.runtime.prepare(replacement)
        assertEquals(TaskState.SUCCEEDED, h.runtime.confirm(fresh.planId).state)
        assertEquals(1, h.configuration.sends.size)
        assertEquals(0, h.bucket.sends)
        // An expired window reclaims capacity without reviving an old preview.
        h.now += 300_000
        val expired = h.cors()
        h.select(expired)
        val late = h.runtime.prepare(expired)
        assertFailsWith<CloudFailure> { h.runtime.confirm(previews.first()) }
        assertEquals(TaskState.SUCCEEDED, h.runtime.confirm(late.planId).state)
        assertEquals(2, h.configuration.sends.size)
    }

    @Test
    fun aConfigurationConfirmationCannotSurviveACredentialRouteOrDraftChange() = runTest {
        for (change in listOf("credential", "route", "draft")) {
            val h = Harness(this)
            val write = h.cors()
            h.select(write)
            val preview = h.runtime.prepare(write)
            when (change) {
                "credential" -> h.revision = -1
                "route" -> h.actual = h.actual.copy(routeEpoch = 2)
                "draft" -> h.select(h.cors(listOf(corsRule.copy(id = "two"))))
            }
            assertFailsWith<CloudFailure> { h.runtime.confirm(preview.planId) }
            assertEquals(0, h.configuration.sends.size, change)
        }
    }

    @Test
    fun aRuntimeWithoutAConfigurationSessionNeverPreparesAConfigurationCommand() = runTest {
        val h = Harness(this, withConfiguration = false)
        val command = R2Command.Configuration(R2ConfigurationWrite.PutCors(target, listOf(corsRule)))
        h.select(command)
        val failure = assertFailsWith<CloudFailure> { h.runtime.prepare(command) }
        assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY, failure.code)
        assertTrue(h.configuration.reads.isEmpty())
    }

    @Test
    fun configurationTasksArePublishedAndReconcileReadOnlyThroughTheSharedRuntime() = runTest {
        val h = Harness(this)
        h.configuration.receipt = ExecutionReceipt.Unknown()
        val command = h.cors()
        h.select(command)
        val task = h.runtime.confirm(h.runtime.prepare(command).planId)
        assertEquals(TaskState.UNKNOWN, task.state)
        assertEquals(1, h.runtime.tasks.value.size)
        // A fresh command is still gated by the unsettled configuration task.
        h.select(h.cors())
        assertFailsWith<CloudFailure> { h.runtime.prepare(h.cors()) }
        h.configuration.put(target, com.flarego.core.model.R2ConfigurationKind.CORS,
            """{"rules":[{"id":"one","allowed":{"methods":["GET"],"origins":["https://a.example"],"headers":[]},"exposeHeaders":[]}]}""")
        assertEquals(TaskState.SUCCEEDED, h.runtime.reconcile(task.id).state)
        assertEquals(1, h.configuration.sends.size)
        // The settled bucket is writable again.
        h.select(h.cors())
        assertNotNull(h.runtime.prepare(h.cors()))
    }

    @Test
    fun aConfigurationConfirmationNeedsTheLiveCommandAndNeverFallsBackToATask() = runTest {
        val h = Harness(this)
        val write = h.cors()
        h.select(write)
        val preview = h.runtime.prepare(write)
        h.actual = h.actual.copy(command = null)
        assertFailsWith<CloudFailure> { h.runtime.confirm(preview.planId) }
        assertEquals(0, h.configuration.sends.size)
    }

    @Test
    fun configurationDeletesAreFrozenDeleteCommandsAndAreNeverSentBeforeConfirmation() = runTest {
        for (write in listOf(
            R2ConfigurationWrite.DeleteCors(target),
            R2ConfigurationWrite.DeleteLock(target),
        )) {
            val h = Harness(this)
            h.configuration.onApply = {
                h.configuration.put(target, com.flarego.core.model.R2ConfigurationKind.CORS, """{"rules":[]}""")
                h.configuration.put(target, com.flarego.core.model.R2ConfigurationKind.LOCK, """{"rules":[]}""")
            }
            val command = R2Command.Configuration(write)
            h.select(command)
            val preview = h.runtime.prepare(command)
            assertEquals(0, h.configuration.sends.size, write::class.simpleName.orEmpty())
            val task = h.runtime.confirm(preview.planId)
            assertEquals(TaskState.SUCCEEDED, task.state)
            assertEquals(listOf<R2ConfigurationWrite>(write), h.configuration.sends)
        }
    }
}
