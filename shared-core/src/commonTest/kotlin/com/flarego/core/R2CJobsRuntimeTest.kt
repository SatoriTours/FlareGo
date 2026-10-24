package com.flarego.core

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.operations.OperationClock
import com.flarego.core.application.operations.OperationIds
import com.flarego.core.application.r2.R2Command
import com.flarego.core.application.r2.R2LiveSelection
import com.flarego.core.application.r2.R2WriteRuntime
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.cloudflare.identity.AuthorizedR2ConfigurationSession
import com.flarego.core.cloudflare.identity.AuthorizedR2JobsSession
import com.flarego.core.cloudflare.identity.AuthorizedR2NotificationSession
import com.flarego.core.cloudflare.identity.AuthorizedR2Session
import com.flarego.core.cloudflare.identity.R2TokenAuthorityIssuer
import com.flarego.core.cloudflare.protocol.CloudflareGateway
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
import com.flarego.core.model.R2JobWrite
import com.flarego.core.model.R2ObjectEntry
import com.flarego.core.model.R2ObjectFilter
import com.flarego.core.model.R2ObjectHead
import com.flarego.core.model.R2ObjectTarget
import com.flarego.core.model.R2ObjectWrite
import com.flarego.core.model.R2StorageClass
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.TaskState
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.ByteSource
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.HttpResponseHead
import com.flarego.core.ports.OperationStore
import com.flarego.core.ports.R2Session
import com.flarego.core.ports.TokenKind
import com.flarego.core.ports.TransferPort
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Batch C Task 2, bucket jobs across the real stack: `R2WriteRuntime` + `R2JobsController` +
 * `AuthorizedR2JobsSession` + the reviewed wire client + a synthetic provider and a real
 * `R2TokenAuthorityIssuer`.
 *
 * Only the provider is simulated. Every assertion is about real behavior: exactly one POST, an
 * independent GET of the same job, a preflight that cannot prove "idle" refusing before any send, a
 * changed bucket state refusing the send, a missing job ID staying UNKNOWN, and the same runtime gate
 * and bucket lock batch A uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R2CJobsRuntimeTest {
    private val connection = Connection("r2-local", "fixture", "a".repeat(32))
    private val account = AccountScope(connection.id, connection.accountId)
    private val target = R2WriteTarget(account, "my-bucket")
    private val eu = R2WriteTarget(account, "my-bucket", "eu")
    private val accountResource = "com.cloudflare.api.account.${connection.accountId}"

    private fun envelope(result: JsonElement) =
        HttpResponse(
            200,
            buildJsonObject {
                put("success", true)
                put("errors", JsonArray(emptyList()))
                put("messages", JsonArray(emptyList()))
                put("result", result)
            }.toString(),
        )

    private class Store : OperationStore {
        val rows = mutableMapOf<String, OperationTask>()

        override fun get(id: String) = rows[id]

        override fun list(connectionId: String) = rows.values.filter { it.connectionId == connectionId }

        override fun put(task: OperationTask) {
            rows[task.id] = task
        }

        override fun putWithSummary(task: OperationTask, summary: Operation) = put(task)

        override fun removeConnection(connectionId: String) {
            rows.entries.removeAll { it.value.connectionId == connectionId }
        }
    }

    private inner class ReadSession : R2Session {
        override suspend fun getBucket(account: AccountScope, name: String): R2BucketDocument =
            R2BucketDocument(name, account, buildJsonObject { put("name", name); put("storage_class", "Standard") })

        override suspend fun write(write: R2BucketWrite, beforeSend: (R2BucketDocument?) -> Unit): ExecutionReceipt = error("unused")
        override suspend fun listBuckets(account: AccountScope, page: PageRequest): CloudPage<R2BucketDocument> = error("unused")
        override suspend fun createBucket(account: AccountScope, input: R2BucketCreateInput): R2BucketDocument = error("unused")
        override suspend fun createBucketByName(account: AccountScope, name: String, storageClass: String?): R2BucketDocument = error("unused")
        override suspend fun patchBucketStorageClass(account: AccountScope, name: String, storageClass: String): R2BucketDocument = error("unused")
        override suspend fun deleteBucket(account: AccountScope, name: String) = error("unused")
        override suspend fun listObjects(account: AccountScope, bucket: String, filter: R2ObjectFilter, page: PageRequest): CloudPage<R2ObjectEntry> = error("unused")
        override suspend fun downloadObject(target: R2ObjectTarget, sink: ByteSink): R2ObjectHead = error("unused")
        override suspend fun uploadObject(target: R2ObjectTarget, source: ByteSource, length: Long, contentType: String?, contentFingerprint: String): R2ObjectEntry = error("unused")
        override suspend fun deleteObject(target: R2ObjectTarget) = error("unused")
        override suspend fun deleteObjects(account: AccountScope, bucket: String, keys: List<String>): R2BulkDeleteOutcome = error("unused")
        override suspend fun writeObject(write: R2ObjectWrite, beforeSend: (R2ObjectEntry?) -> Unit): ExecutionReceipt = error("unused")
    }

    private inner class Harness(scope: TestScope) {
        var actual = R2LiveSelection(connection, 7, 1, null)
        var revision = 7L
        var ids = 0
        var now = 1_000L
        val store = Store()
        val read = ReadSession()
        val calls = mutableListOf<HttpRequest>()
        /** Provider job pages keyed by the continuation token that requests them. */
        var jobsByToken: Map<String?, List<JsonObject>> = mapOf(null to emptyList())
        var nextTokenByToken: Map<String?, String?> = mapOf(null to null)
        var jobsUninterpretable = false
        var jobRowsById = mutableMapOf<String, JsonObject>()
        var notificationQueues: List<JsonObject> = emptyList()
        var notificationBucketName: String = "my-bucket"
        var notificationsUninterpretable = false
        var createResult: JsonObject = buildJsonObject { put("ok", true) }
        /** A bucket-scoped grant instead of the required account-level write group. */
        var bucketScopedGrant = false
        /** Suspends the single POST so a test can cancel a waiter while it is in flight. */
        var postGate: CompletableDeferred<Unit>? = null
        /** Suspends the first authority verification read (the fresh proof phase). */
        var authorityGate: CompletableDeferred<Unit>? = null
        /** Suspends one preflight read whose path contains [readGatePath]. */
        var readGate: CompletableDeferred<Unit>? = null
        var readGatePath: String = "/jobs"
        private var readGateUsed = false

        /** Arms the read gate for the next matching read (after the preview already read once). */
        fun armReadGate() {
            readGateUsed = false
        }
        /** When set, a bucket read must carry one of these jurisdictions or the provider answers 404. */
        var bucketJurisdictions: Set<String>? = null
        /** Injects a failure for the admission's independent job GET (5xx). */
        var detailGetFailStatus: Int? = null
        var detailGetGate: CompletableDeferred<Unit>? = null

        private val transfers = object : TransferPort {
            override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead = error("unexpected download")
        }

        val issuer = R2TokenAuthorityIssuer(connection, 7, { revision }, OperationClock { now }, TokenKind.USER_OWNED)

        private val gateway = CloudflareGateway(
            HttpTransport { request ->
                calls += request
                when {
                    request.path == "/user/tokens/verify" -> {
                        authorityGate?.await()
                        envelope(buildJsonObject { put("id", "self"); put("status", "active") })
                    }
                    request.path.endsWith("/permission_groups") ->
                        envelope(
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("id", "admin")
                                        put("name", "Workers R2 Storage Write")
                                        put("scopes", JsonArray(listOf(JsonPrimitive("com.cloudflare.api.account"))))
                                    },
                                    buildJsonObject {
                                        put("id", "item")
                                        put("name", "Workers R2 Storage Bucket Item Write")
                                        put("scopes", JsonArray(listOf(JsonPrimitive("com.cloudflare.edge.r2.bucket"))))
                                    },
                                ),
                            ),
                        )
                    request.path.endsWith("/self") ->
                        envelope(
                            buildJsonObject {
                                put("id", "self")
                                put("status", "active")
                                put(
                                    "policies",
                                    JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("id", "p")
                                                put("effect", "allow")
                                                put(
                                                    "permission_groups",
                                                    JsonArray(listOf(buildJsonObject { put("id", if (bucketScopedGrant) "item" else "admin") })),
                                                )
                                                put(
                                                    "resources",
                                                    buildJsonObject {
                                                        put(
                                                            if (bucketScopedGrant) "com.cloudflare.edge.r2.bucket.${connection.accountId}_default_my-bucket"
                                                            else accountResource,
                                                            JsonPrimitive("*"),
                                                        )
                                                    },
                                                )
                                            },
                                        ),
                                    ),
                                )
                            },
                        )
                    request.path == "/accounts/${connection.accountId}" ->
                        envelope(buildJsonObject { put("id", connection.accountId) })
                    request.path == "/accounts/${connection.accountId}/r2/buckets/my-bucket" && request.method == "GET" -> {
                        if (!readGateUsed && request.path.contains(readGatePath)) {
                            readGateUsed = true
                            readGate?.await()
                        }
                        val jurisdiction = request.headers["cf-r2-jurisdiction"]
                        val allowed = bucketJurisdictions
                        if (allowed != null && (jurisdiction == null || jurisdiction !in allowed)) {
                            HttpResponse(404, "not found")
                        } else {
                            envelope(buildJsonObject { put("name", "my-bucket"); put("storage_class", "Standard") })
                        }
                    }
                    request.method == "GET" && request.path.substringBefore('?').endsWith("/jobs") -> {
                        if (!readGateUsed && request.path.contains(readGatePath)) {
                            readGateUsed = true
                            readGate?.await()
                        }
                        if (jobsUninterpretable) return@HttpTransport envelope(buildJsonObject { put("unexpected", true) })
                        val requested = Regex("[?&]continuationToken=([^&]+)").find(request.path)?.groupValues?.get(1)
                        val rows = jobsByToken[requested] ?: emptyList()
                        val next = nextTokenByToken[requested]
                        envelope(
                            buildJsonObject {
                                put("jobs", JsonArray(rows))
                                if (next != null) put("nextContinuationToken", next)
                            },
                        )
                    }
                    request.method == "POST" && request.path.substringBefore('?').endsWith("/jobs") -> {
                        postGate?.await()
                        envelope(createResult)
                    }
                    request.method == "GET" && request.path.substringBefore('?').contains("/jobs/") -> {
                        detailGetGate?.await()
                        detailGetFailStatus?.let { return@HttpTransport HttpResponse(it, "synthetic failure") }
                        val id = request.path.substringAfterLast('/')
                        val row = jobRowsById[id]
                        if (row == null) HttpResponse(404, "not found") else envelope(row)
                    }
                    request.path.endsWith("/event_notifications/r2/my-bucket/configuration") ->
                        if (notificationsUninterpretable) envelope(buildJsonObject { put("unexpected", true) })
                        else envelope(buildJsonObject { put("bucketName", notificationBucketName); put("queues", JsonArray(notificationQueues)) })
                    else -> error("unexpected synthetic request ${request.method} ${request.path}")
                }
            },
            sleeper = {},
        )

        val jobs = AuthorizedR2JobsSession(connection, gateway, issuer)
        val notifications = AuthorizedR2NotificationSession(connection, gateway, issuer)
        /** The batch B configuration session: its bucket read is the jurisdiction-aware wire request. */
        val configuration = AuthorizedR2ConfigurationSession(connection, gateway, issuer)

        val runtime = R2WriteRuntime(
            scope.backgroundScope,
            store,
            StandardTestDispatcher(scope.testScheduler),
            FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
            OperationClock { now },
            OperationIds { "runtime-${++ids}" },
            { actual },
            { listOf(connection) },
            { revision },
            { read },
            configSession = { configuration },
            jobSession = { jobs },
            notificationSession = { notifications },
        )

        /** A fresh runtime instance over the same store and provider (a process restart). */
        fun restartedRuntime(scope: TestScope): R2WriteRuntime =
            R2WriteRuntime(
                scope.backgroundScope,
                store,
                StandardTestDispatcher(scope.testScheduler),
                FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
                OperationClock { now },
                OperationIds { "restarted-${++ids}" },
                { actual },
                { listOf(connection) },
                { revision },
                { read },
                configSession = { configuration },
                jobSession = { jobs },
                notificationSession = { notifications },
            )

        fun select(command: R2Command) {
            actual = actual.copy(command = command)
        }

        fun mutations() = calls.filter { it.method != "GET" }

        fun jobDetailGets() = calls.filter { it.method == "GET" && Regex("/jobs/[^/?]+$").containsMatchIn(it.path) }
    }

    private fun prefix(prefix: String = "logs/") = R2Command.Job(R2JobWrite.PrefixDelete(target, prefix, manualMpuHandled = true))

    private fun migration() =
        R2Command.Job(R2JobWrite.StorageClassMigration(target, R2StorageClass.STANDARD, R2StorageClass.INFREQUENT_ACCESS))

    private fun prefixJobRow(id: String, status: String, prefix: String = "logs/") = buildJsonObject {
        put("id", id)
        put("jobType", "prefixDelete")
        put("status", status)
        put("startTime", "2026-10-22T00:00:00Z")
        put("prefixDelete", buildJsonObject { put("prefix", prefix); put("deletedObjects", 12) })
    }

    private fun migrationRow(
        id: String,
        status: String,
        skipped: Int? = 0,
        failed: Int? = 0,
        failedBytes: Int? = 0,
        skippedBytes: Int? = 0,
    ) = buildJsonObject {
        put("id", id)
        put("jobType", "storageClassMigration")
        put("status", status)
        put("startTime", "2026-10-22T00:00:00Z")
        put(
            "storageClassMigration",
            buildJsonObject {
                put("sourceStorageClass", "Standard")
                put("destinationStorageClass", "InfrequentAccess")
                put("migratedObjects", 4)
                skipped?.let { put("skippedObjects", it) }
                failed?.let { put("failedObjects", it) }
                failedBytes?.let { put("failedBytes", it) }
                skippedBytes?.let { put("skippedBytes", it) }
            },
        )
    }

    @Test
    fun oneCreatePostsOnceAndVerifiesTheSameJobWithAnIndependentGet() = runTest {
        val h = Harness(this)
        h.createResult = prefixJobRow("job-1", "COMPLETED")
        h.jobRowsById["job-1"] = prefixJobRow("job-1", "COMPLETED")
        val command = prefix()
        h.select(command)
        val preview = h.runtime.prepare(command)
        assertEquals(0, h.mutations().size, "a preview never sends")
        val task = h.runtime.confirm(preview.planId)
        assertEquals(1, h.mutations().size, "exactly one POST")
        assertEquals("POST", h.mutations().single().method)
        val body = (h.mutations().single().payload as HttpBody.Text).value
        assertTrue(body.contains("\"jobType\":\"prefixDelete\""), body)
        assertTrue(body.contains("\"prefix\":\"logs/\""), body)
        // One independent GET of exactly the created job, after the POST.
        val gets = h.jobDetailGets()
        assertEquals(1, gets.size)
        assertEquals("/accounts/${connection.accountId}/r2/buckets/my-bucket/jobs/job-1", gets.single().path)
        // The POST's own COMPLETED claim is only an acceptance.
        assertEquals(TaskState.ACCEPTED, task.state)
        assertNotNull(task.pollPath)
        assertEquals(TaskState.SUCCEEDED, h.runtime.reconcile(task.id).state)
        assertEquals(1, h.mutations().size, "a read-only recovery never sends")
    }

    @Test
    fun aMissingOrForeignJobIdStaysUnknownAndIsNeverGuessed() = runTest {
        val results = listOf(
            // No trustworthy id at all.
            buildJsonObject {
                put("jobType", "prefixDelete")
                put("status", "COMPLETED")
                put("startTime", "2026-10-22T00:00:00Z")
                put("prefixDelete", buildJsonObject { put("prefix", "logs/"); put("deletedObjects", 1) })
            },
            // An id that cannot be addressed as exactly one path segment.
            prefixJobRow("bad/id", "COMPLETED"),
            // A job of another kind than the submitted request.
            migrationRow("job-9", "COMPLETED"),
        )
        for (result in results) {
            val h = Harness(this)
            h.createResult = result
            h.select(prefix())
            val task = h.runtime.confirm(h.runtime.prepare(prefix()).planId)
            assertEquals(TaskState.UNKNOWN, task.state, "result=$result")
            assertNull(task.pollPath)
            assertEquals(1, h.mutations().size, "never a second POST")
            assertTrue(h.jobDetailGets().isEmpty(), "no guessed job is read")
        }
    }

    @Test
    fun aPreflightThatCannotProveIdleRefusesBeforeAnySend() = runTest {
        // A running job of the same bucket.
        val running = Harness(this)
        running.jobsByToken = mapOf(null to listOf(prefixJobRow("job-run", "RUNNING", "other/")))
        running.select(prefix())
        assertEquals(CloudFailureCode.CONFLICT, assertFailsWith<CloudFailure> { running.runtime.prepare(prefix()) }.code)
        assertEquals(0, running.mutations().size)

        // An uninterpretable job page.
        val unknown = Harness(this)
        unknown.jobsUninterpretable = true
        unknown.select(prefix())
        assertEquals(
            CloudFailureCode.UNSUPPORTED_CAPABILITY,
            assertFailsWith<CloudFailure> { unknown.runtime.prepare(prefix()) }.code,
        )
        assertEquals(0, unknown.mutations().size)

        // A repeated continuation cursor: the scan cannot be trusted as complete.
        val repeated = Harness(this)
        repeated.nextTokenByToken = mapOf(null to "t1", "t1" to "t1")
        repeated.jobsByToken = mapOf(null to emptyList(), "t1" to emptyList())
        repeated.select(prefix())
        assertEquals(CloudFailureCode.CONFLICT, assertFailsWith<CloudFailure> { repeated.runtime.prepare(prefix()) }.code)
        assertEquals(0, repeated.mutations().size)

        // More pages than the client safety budget allows (5).
        val tooMany = Harness(this)
        tooMany.nextTokenByToken = mapOf(null to "t1", "t1" to "t2", "t2" to "t3", "t3" to "t4", "t4" to "t5", "t5" to "t6")
        tooMany.jobsByToken = listOf(null, "t1", "t2", "t3", "t4", "t5").associateWith { emptyList<JsonObject>() }
        tooMany.select(prefix())
        assertEquals(CloudFailureCode.CONFLICT, assertFailsWith<CloudFailure> { tooMany.runtime.prepare(prefix()) }.code)
        assertEquals(0, tooMany.mutations().size)

        // A duplicated job identity in one scan.
        val duplicate = Harness(this)
        duplicate.jobsByToken = mapOf(null to listOf(prefixJobRow("job-x", "COMPLETED"), prefixJobRow("job-x", "COMPLETED")))
        duplicate.select(prefix())
        assertFailsWith<CloudFailure> { duplicate.runtime.prepare(prefix()) }
        assertEquals(0, duplicate.mutations().size)

        // A foreign notification echo: the known pre-state cannot be proven either.
        val foreign = Harness(this)
        foreign.notificationBucketName = "other-bucket"
        foreign.select(prefix())
        assertEquals(
            CloudFailureCode.UNSUPPORTED_CAPABILITY,
            assertFailsWith<CloudFailure> { foreign.runtime.prepare(prefix()) }.code,
        )
        assertEquals(0, foreign.mutations().size)
    }

    @Test
    fun aChangedBucketStateBetweenPreviewAndSendRefusesTheSend() = runTest {
        val h = Harness(this)
        h.createResult = prefixJobRow("job-1", "COMPLETED")
        h.jobRowsById["job-1"] = prefixJobRow("job-1", "COMPLETED")
        h.select(prefix())
        val preview = h.runtime.prepare(prefix())
        // Another client starts a job after the preview: the guard compares the real last read.
        h.jobsByToken = mapOf(null to listOf(prefixJobRow("someone-else", "RUNNING", "other/")))
        // The refusal happens before the typed send, so it is a trusted local failure, not a throw.
        val refused = h.runtime.confirm(preview.planId)
        assertEquals(TaskState.FAILED, refused.state)
        assertEquals(0, h.mutations().size, "a changed bucket state never sends")
        assertTrue(h.jobDetailGets().isEmpty(), "no job was ever created or read")
    }

    @Test
    fun aChangedOrStaleCommandNeverConfirmsThePreview() = runTest {
        val h = Harness(this)
        h.createResult = prefixJobRow("job-1", "COMPLETED")
        h.jobRowsById["job-1"] = prefixJobRow("job-1", "COMPLETED")
        h.select(prefix("logs/"))
        val preview = h.runtime.prepare(prefix("logs/"))
        // A different prefix is a different command, so the old preview cannot be confirmed.
        h.select(prefix("other/"))
        assertFailsWith<CloudFailure> { h.runtime.confirm(preview.planId) }
        assertEquals(0, h.mutations().size)

        // A withdrawn credential is not consent either.
        val second = Harness(this)
        second.createResult = prefixJobRow("job-2", "COMPLETED")
        second.jobRowsById["job-2"] = prefixJobRow("job-2", "COMPLETED")
        second.select(prefix())
        val planned = second.runtime.prepare(prefix())
        second.revision = -1
        assertFailsWith<CloudFailure> { second.runtime.confirm(planned.planId) }
        assertEquals(0, second.mutations().size)
    }

    @Test
    fun aBucketScopedGrantNeverMintsTheRestWriteProof() = runTest {
        val h = Harness(this)
        h.bucketScopedGrant = true
        h.createResult = prefixJobRow("job-1", "COMPLETED")
        h.select(prefix())
        val task = h.runtime.confirm(h.runtime.prepare(prefix()).planId)
        // The proof could not be minted: the task is a trusted failure and nothing was sent.
        assertEquals(TaskState.FAILED, task.state)
        assertEquals(0, h.mutations().size)
        assertTrue(h.jobDetailGets().isEmpty())
    }

    @Test
    fun aTerminalJobIsSettledOnlyByItsOwnVerifiedObservation() = runTest {
        // A COMPLETED prefix delete reports that job (never "the bucket is now empty").
        val done = Harness(this)
        done.createResult = prefixJobRow("job-1", "ENQUEUED")
        done.jobRowsById["job-1"] = prefixJobRow("job-1", "COMPLETED")
        done.select(prefix())
        val accepted = done.runtime.confirm(done.runtime.prepare(prefix()).planId)
        assertEquals(TaskState.ACCEPTED, accepted.state)
        assertEquals(TaskState.SUCCEEDED, done.runtime.reconcile(accepted.id).state)

        // A migration with skipped objects is never a full success.
        val partial = Harness(this)
        partial.createResult = migrationRow("job-m", "ENQUEUED")
        partial.jobRowsById["job-m"] = migrationRow("job-m", "COMPLETED", skipped = 2)
        partial.select(migration())
        val partialTask = partial.runtime.confirm(partial.runtime.prepare(migration()).planId)
        assertEquals(TaskState.ACTION_REQUIRED, partial.runtime.reconcile(partialTask.id).state)

        // Missing counters are never filled in with a guessed zero: unproven, not success.
        val unproven = Harness(this)
        unproven.createResult = migrationRow("job-m", "ENQUEUED")
        unproven.jobRowsById["job-m"] = migrationRow("job-m", "COMPLETED", skipped = null, failed = null)
        unproven.select(migration())
        val unprovenTask = unproven.runtime.confirm(unproven.runtime.prepare(migration()).planId)
        assertEquals(TaskState.UNKNOWN, unproven.runtime.reconcile(unprovenTask.id).state)

        // A terminal provider failure is neither success nor "never happened".
        val failed = Harness(this)
        failed.createResult = prefixJobRow("job-f", "ENQUEUED")
        failed.jobRowsById["job-f"] = prefixJobRow("job-f", "FAILED")
        failed.select(prefix())
        val failedTask = failed.runtime.confirm(failed.runtime.prepare(prefix()).planId)
        assertEquals(TaskState.ACTION_REQUIRED, failed.runtime.reconcile(failedTask.id).state)
        assertEquals(1, failed.mutations().size)
    }

    @Test
    fun aStillRunningJobKeepsTheAcceptedTaskInsteadOfBeingDowngraded() = runTest {
        val h = Harness(this)
        h.createResult = prefixJobRow("job-r", "ENQUEUED")
        h.jobRowsById["job-r"] = prefixJobRow("job-r", "RUNNING")
        h.select(prefix())
        val task = h.runtime.confirm(h.runtime.prepare(prefix()).planId)
        assertEquals(TaskState.ACCEPTED, task.state)
        assertEquals(CloudFailureCode.CONFLICT, assertFailsWith<CloudFailure> { h.runtime.reconcile(task.id) }.code)
        assertEquals(TaskState.ACCEPTED, h.store.rows.getValue(task.id).state, "a refused refresh never downgrades the row")
        assertEquals(1, h.mutations().size)
    }

    @Test
    fun aTaskWithoutItsOwnJobIdIsNeverRecoveredByGuessing() = runTest {
        val h = Harness(this)
        h.createResult = prefixJobRow("job-1", "ENQUEUED")
        h.jobRowsById["job-1"] = prefixJobRow("job-1", "COMPLETED")
        h.select(prefix())
        val task = h.runtime.confirm(h.runtime.prepare(prefix()).planId)
        assertEquals(TaskState.ACCEPTED, task.state)
        // The persisted row is what recovery has to work with: without its poll path, nothing is read.
        val tampered = task.copy(pollPath = null)
        h.store.rows[task.id] = tampered
        assertEquals(
            CloudFailureCode.INVALID_INPUT,
            assertFailsWith<CloudFailure> { h.runtime.reconcile(task.id) }.code,
        )
        assertEquals(1, h.jobDetailGets().size, "only the admission GET happened")
    }

    @Test
    fun theJobTaskSharesTheOneBucketGateWithBatchAOnTheSameBucketOnly() = runTest {
        val h = Harness(this)
        h.createResult = prefixJobRow("job-a", "ENQUEUED")
        h.jobRowsById["job-a"] = prefixJobRow("job-a", "ENQUEUED")
        h.select(prefix())
        val accepted = h.runtime.confirm(h.runtime.prepare(prefix()).planId)
        assertEquals(TaskState.ACCEPTED, accepted.state)
        // The accepted C task blocks another C creation on the same default bucket...
        h.select(prefix("more/"))
        assertFailsWith<CloudFailure> { h.runtime.prepare(prefix("more/")) }
        // ...and a batch A write of the same bucket.
        val patch = R2Command.Bucket(R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess"))
        h.select(patch)
        assertFailsWith<CloudFailure> { h.runtime.prepare(patch) }
        // A same-named bucket in another jurisdiction is a different resource and is not blocked.
        val euJob = R2Command.Job(R2JobWrite.PrefixDelete(eu, "logs/", manualMpuHandled = true))
        h.select(euJob)
        assertNotNull(h.runtime.prepare(euJob))
        assertEquals(1, h.mutations().size, "the eu preview performed no write")
    }

    @Test
    fun aCancelledWaiterNeverCancelsOrResendsTheAdmittedCreation() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.postGate = gate
        h.createResult = prefixJobRow("job-c", "ENQUEUED")
        h.jobRowsById["job-c"] = prefixJobRow("job-c", "COMPLETED")
        h.select(prefix())
        val planId = h.runtime.prepare(prefix()).planId
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { h.runtime.confirm(planId) }
        runCurrent()
        assertEquals(1, h.mutations().size, "the POST is in flight")
        waiter.cancel()
        gate.complete(Unit)
        runCurrent()
        val row = assertNotNull(h.store.rows.values.singleOrNull())
        assertEquals(TaskState.ACCEPTED, row.state, "the app-owned creation settles even without its waiter")
        assertEquals(1, h.mutations().size, "no resend")
        // A later confirmation shares the same task instead of posting again.
        val again = h.runtime.confirm(planId)
        assertEquals(row.id, again.id)
        assertEquals(1, h.mutations().size)
    }

    @Test
    fun theFinalPreflightHappensAfterTheFreshProofSoAPauseCannotHideAChange() = runTest {
        val h = Harness(this)
        h.createResult = prefixJobRow("job-1", "ENQUEUED")
        h.jobRowsById["job-1"] = prefixJobRow("job-1", "ENQUEUED")
        val gate = CompletableDeferred<Unit>()
        h.authorityGate = gate
        h.select(prefix())
        val planId = h.runtime.prepare(prefix()).planId
        val confirmation = launch { h.runtime.confirm(planId) }
        runCurrent()
        // Another client starts a job while the fresh proof is in flight.
        h.jobsByToken = mapOf(null to listOf(prefixJobRow("someone-else", "RUNNING", "other/")))
        gate.complete(Unit)
        runCurrent()
        confirmation.join()
        assertEquals(0, h.mutations().size, "a change during the proof phase must refuse the send")
        assertEquals(TaskState.FAILED, assertNotNull(h.store.rows.values.singleOrNull()).state)
        // The order the wire saw: the authority reads, then the deciding reads, then no POST.
        val lastAuthority = h.calls.indexOfLast { it.path.contains("/tokens/") || it.path.endsWith("/self") || it.path.endsWith("/permission_groups") }
        val lastDecidingRead = h.calls.indexOfLast { it.method == "GET" && (it.path.contains("/jobs") || it.path.contains("/event_notifications")) }
        assertTrue(lastAuthority in 0..lastDecidingRead, "authority=${lastAuthority} decidingRead=${lastDecidingRead}")
    }

    @Test
    fun theBucketPreflightReadsTheExactTargetJurisdictionAndNeverFallsBackToDefault() = runTest {
        val h = Harness(this)
        h.bucketJurisdictions = setOf("eu")
        h.createResult = prefixJobRow("job-eu", "ENQUEUED")
        h.jobRowsById["job-eu"] = prefixJobRow("job-eu", "ENQUEUED")
        val euCommand = R2Command.Job(R2JobWrite.PrefixDelete(eu, "logs/", manualMpuHandled = true))
        h.select(euCommand)
        val preview = h.runtime.prepare(euCommand)
        val task = h.runtime.confirm(preview.planId)
        assertEquals(TaskState.ACCEPTED, task.state)
        val bucketReads = h.calls.filter { it.method == "GET" && it.path.endsWith("/r2/buckets/my-bucket") }
        assertTrue(bucketReads.isNotEmpty(), "the preflight must really read the bucket")
        assertTrue(
            bucketReads.all { it.headers["cf-r2-jurisdiction"] == "eu" },
            "every bucket read must carry the target jurisdiction: ${bucketReads.map { it.headers }}",
        )
        // A default-jurisdiction target whose bucket does not exist is refused (no eu data reused).
        val mismatched = Harness(this)
        mismatched.bucketJurisdictions = setOf("eu")
        mismatched.select(prefix())
        assertFailsWith<CloudFailure> { mismatched.runtime.prepare(prefix()) }
        assertEquals(0, mismatched.mutations().size)
    }

    @Test
    fun aBucketClearWithKnownNotificationRulesIsRefusedLocally() = runTest {
        val clear = Harness(this)
        clear.notificationQueues = listOf(
            buildJsonObject {
                put("queueId", "queue-1")
                put("queueName", "events")
                put(
                    "rules",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("ruleId", "rule-1")
                                put("actions", JsonArray(listOf(JsonPrimitive("PutObject"))))
                            },
                        ),
                    ),
                )
            },
        )
        clear.select(prefix(""))
        assertEquals(CloudFailureCode.CONFLICT, assertFailsWith<CloudFailure> { clear.runtime.prepare(prefix("")) }.code)
        assertEquals(0, clear.mutations().size)

        // A non-empty prefix and a migration are not forbidden by that rule.
        val prefixed = Harness(this)
        prefixed.notificationQueues = clear.notificationQueues
        prefixed.createResult = prefixJobRow("job-p", "ENQUEUED")
        prefixed.jobRowsById["job-p"] = prefixJobRow("job-p", "ENQUEUED")
        prefixed.select(prefix("logs/"))
        assertEquals(TaskState.ACCEPTED, prefixed.runtime.confirm(prefixed.runtime.prepare(prefix("logs/")).planId).state)
        val migration = Harness(this)
        migration.notificationQueues = clear.notificationQueues
        migration.createResult = migrationRow("job-m", "ENQUEUED")
        migration.jobRowsById["job-m"] = migrationRow("job-m", "ENQUEUED")
        migration.select(R2Command.Job(R2JobWrite.StorageClassMigration(target, R2StorageClass.STANDARD, R2StorageClass.INFREQUENT_ACCESS)))
        assertEquals(TaskState.ACCEPTED, migration.runtime.confirm(migration.runtime.prepare(migration.actual.command as R2Command.Job).planId).state)
    }

    @Test
    fun aTrustedJobIdSurvivesAFailedAdmissionReadAndIsRecoveredAfterARestart() = runTest {
        val h = Harness(this)
        h.createResult = prefixJobRow("job-1", "ENQUEUED")
        h.detailGetFailStatus = 503
        h.select(prefix())
        val task = h.runtime.confirm(h.runtime.prepare(prefix()).planId)
        // The POST returned a trustworthy ID; one failed read must not erase it.
        assertNotNull(task.pollPath, "the accepted job keeps its read-only locator")
        assertEquals(TaskState.ACCEPTED, task.state)
        assertEquals(1, h.mutations().size)
        // A restarted runtime recovers exactly that job once the provider answers.
        h.detailGetFailStatus = null
        h.jobRowsById["job-1"] = prefixJobRow("job-1", "COMPLETED")
        val restarted = h.restartedRuntime(this)
        assertEquals(TaskState.SUCCEEDED, restarted.reconcile(task.id).state)
        assertEquals(1, h.mutations().size, "recovery is read-only")
    }

    @Test
    fun aMigrationIsOnlyAFullSuccessWhenEveryObservedFailureCounterIsZero() = runTest {
        // Failed bytes without failed objects: still a partial outcome.
        val failedBytes = Harness(this)
        failedBytes.createResult = migrationRow("job-m", "ENQUEUED")
        failedBytes.jobRowsById["job-m"] = buildJsonObject {
            put("id", "job-m")
            put("jobType", "storageClassMigration")
            put("status", "COMPLETED")
            put("startTime", "2026-10-22T00:00:00Z")
            put(
                "storageClassMigration",
                buildJsonObject {
                    put("sourceStorageClass", "Standard")
                    put("destinationStorageClass", "InfrequentAccess")
                    put("migratedObjects", 10)
                    put("skippedObjects", 0)
                    put("failedObjects", 0)
                    put("failedBytes", 512)
                    put("skippedBytes", 0)
                },
            )
        }
        failedBytes.select(migration())
        val partial = failedBytes.runtime.confirm(failedBytes.runtime.prepare(migration()).planId)
        assertEquals(TaskState.ACTION_REQUIRED, failedBytes.runtime.reconcile(partial.id).state)

        // Skipped bytes without skipped objects.
        val skippedBytes = Harness(this)
        skippedBytes.createResult = migrationRow("job-m", "ENQUEUED")
        skippedBytes.jobRowsById["job-m"] = buildJsonObject {
            put("id", "job-m")
            put("jobType", "storageClassMigration")
            put("status", "COMPLETED")
            put("startTime", "2026-10-22T00:00:00Z")
            put(
                "storageClassMigration",
                buildJsonObject {
                    put("sourceStorageClass", "Standard")
                    put("destinationStorageClass", "InfrequentAccess")
                    put("migratedObjects", 10)
                    put("skippedObjects", 0)
                    put("failedObjects", 0)
                    put("failedBytes", 0)
                    put("skippedBytes", 64)
                },
            )
        }
        skippedBytes.select(migration())
        val partial2 = skippedBytes.runtime.confirm(skippedBytes.runtime.prepare(migration()).planId)
        assertEquals(TaskState.ACTION_REQUIRED, skippedBytes.runtime.reconcile(partial2.id).state)

        // A complete, all-zero migration is a full success.
        val clean = Harness(this)
        clean.createResult = migrationRow("job-m", "ENQUEUED")
        clean.jobRowsById["job-m"] = buildJsonObject {
            put("id", "job-m")
            put("jobType", "storageClassMigration")
            put("status", "COMPLETED")
            put("startTime", "2026-10-22T00:00:00Z")
            put(
                "storageClassMigration",
                buildJsonObject {
                    put("sourceStorageClass", "Standard")
                    put("destinationStorageClass", "InfrequentAccess")
                    put("migratedObjects", 10)
                    put("skippedObjects", 0)
                    put("failedObjects", 0)
                    put("failedBytes", 0)
                    put("skippedBytes", 0)
                },
            )
        }
        clean.select(migration())
        val success = clean.runtime.confirm(clean.runtime.prepare(migration()).planId)
        assertEquals(TaskState.SUCCEEDED, clean.runtime.reconcile(success.id).state)
    }

    @Test
    fun aTerminalRowIsNeverRewrittenByALaterObservation() = runTest {
        // A FAILED (never sent) row stays FAILED even if the job later looks completed.
        val h = Harness(this)
        h.createResult = prefixJobRow("job-1", "ENQUEUED")
        h.detailGetFailStatus = 503
        h.select(prefix())
        val failed = h.runtime.confirm(h.runtime.prepare(prefix()).planId)
        assertEquals(TaskState.ACCEPTED, failed.state)
        // Force a trusted refusal instead: a changed bucket state turns the row FAILED.
        h.detailGetFailStatus = null
        h.jobRowsById["job-1"] = prefixJobRow("job-1", "COMPLETED")
        val refused = Harness(this)
        refused.createResult = prefixJobRow("job-x", "ENQUEUED")
        refused.select(prefix())
        val planId = refused.runtime.prepare(prefix()).planId
        refused.jobsByToken = mapOf(null to listOf(prefixJobRow("other", "RUNNING", "other/")))
        val refusedTask = refused.runtime.confirm(planId)
        assertEquals(TaskState.FAILED, refusedTask.state)
        refused.jobsByToken = mapOf(null to emptyList())
        assertEquals(TaskState.FAILED, refused.runtime.reconcile(refusedTask.id).state, "a refused history is final")
    }

    @Test
    fun theJobPreviewCopyDescribesTheRealJobScope() = runTest {
        val h = Harness(this)
        h.select(prefix(""))
        val clearImpact = h.runtime.prepare(prefix("")).detail.impact
        assertFalse(clearImpact.contains("作业启动后出现"), clearImpact)
        assertTrue(clearImpact.contains("作业启动时") || clearImpact.contains("启动前"), clearImpact)
        h.select(migration())
        val migrationImpact = h.runtime.prepare(migration()).detail.impact
        assertFalse(migrationImpact.contains("之后新增的对象也会迁移"), migrationImpact)
    }

    @Test
    fun aRevokedRouteOrCredentialDuringTheFreshProofRefusesTheSend() = runTest {
        // Route epoch change while the fresh authority observation is in flight.
        val route = Harness(this)
        route.createResult = prefixJobRow("job-1", "ENQUEUED")
        route.jobRowsById["job-1"] = prefixJobRow("job-1", "ENQUEUED")
        val routeGate = CompletableDeferred<Unit>()
        route.authorityGate = routeGate
        route.select(prefix())
        val routePlan = route.runtime.prepare(prefix()).planId
        val routeConfirmation = launch { route.runtime.confirm(routePlan) }
        runCurrent()
        route.actual = route.actual.copy(routeEpoch = route.actual.routeEpoch + 1)
        routeGate.complete(Unit)
        runCurrent()
        routeConfirmation.join()
        assertEquals(0, route.mutations().size, "a route change during the proof phase refuses the send")
        assertTrue(route.store.rows.values.none { it.state == TaskState.SUCCEEDED })

        // Credential withdrawal while the same observation is in flight.
        val credential = Harness(this)
        credential.createResult = prefixJobRow("job-2", "ENQUEUED")
        credential.jobRowsById["job-2"] = prefixJobRow("job-2", "ENQUEUED")
        val credentialGate = CompletableDeferred<Unit>()
        credential.authorityGate = credentialGate
        credential.select(prefix())
        val credentialPlan = credential.runtime.prepare(prefix()).planId
        val credentialConfirmation = launch { credential.runtime.confirm(credentialPlan) }
        runCurrent()
        credential.revision = -1
        credentialGate.complete(Unit)
        runCurrent()
        credentialConfirmation.join()
        assertEquals(0, credential.mutations().size)
        assertTrue(credential.store.rows.values.none { it.state == TaskState.SUCCEEDED })
    }

    @Test
    fun aRevocationDuringTheFinalPreflightRefusesTheSendAfterTheProof() = runTest {
        // The proof is already minted (its authority read completed); the deciding preflight suspends.
        val h = Harness(this)
        h.createResult = prefixJobRow("job-3", "ENQUEUED")
        h.jobRowsById["job-3"] = prefixJobRow("job-3", "ENQUEUED")
        h.select(prefix())
        val planId = h.runtime.prepare(prefix()).planId
        val gate = CompletableDeferred<Unit>()
        h.readGate = gate
        h.readGatePath = "/jobs"
        h.armReadGate()
        val confirmation = launch { h.runtime.confirm(planId) }
        runCurrent()
        assertTrue(h.mutations().size == 0, "still before the POST")
        h.revision = -1
        gate.complete(Unit)
        runCurrent()
        confirmation.join()
        assertEquals(0, h.mutations().size, "the final guard refuses after the proof when the credential is gone")
        assertTrue(h.store.rows.values.none { it.state == TaskState.SUCCEEDED })

        // Positive control: the same suspension without a revocation still sends exactly once.
        val ok = Harness(this)
        ok.createResult = prefixJobRow("job-4", "ENQUEUED")
        ok.jobRowsById["job-4"] = prefixJobRow("job-4", "ENQUEUED")
        ok.select(prefix())
        val okPlan = ok.runtime.prepare(prefix()).planId
        val okGate = CompletableDeferred<Unit>()
        ok.readGate = okGate
        ok.readGatePath = "/jobs"
        ok.armReadGate()
        val okConfirmation = launch { ok.runtime.confirm(okPlan) }
        runCurrent()
        okGate.complete(Unit)
        runCurrent()
        okConfirmation.join()
        assertEquals(1, ok.mutations().size, "without a revocation the same flow sends exactly once")
        assertEquals(TaskState.ACCEPTED, assertNotNull(ok.store.rows.values.singleOrNull()).state)
    }
}
