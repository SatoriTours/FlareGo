package com.flarego.core

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.r2.R2_JOB_READ_ONLY_UNKNOWN
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.cloudflare.protocol.ActionPolicies
import com.flarego.core.cloudflare.protocol.ActionScope
import com.flarego.core.cloudflare.protocol.ActionSemantics
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.protocol.ResponseKind
import com.flarego.core.cloudflare.protocol.RiskLevel
import com.flarego.core.cloudflare.r2.CloudflareR2JobsClient
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2JobCreateRequest
import com.flarego.core.model.R2JobDetails
import com.flarego.core.model.R2JobListQuery
import com.flarego.core.model.R2JobStatus
import com.flarego.core.model.R2JobType
import com.flarego.core.model.R2StorageClass
import com.flarego.core.model.R2WriteTarget
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Batch C Task 1 (fix1): the typed bucket-job wire. Synthetic transport only; no cloud call and no
 * secret. The creation receipt is only ever `Accepted` for a trustworthy same-kind/same-parameter
 * ID, and the later independent GET remains the completion proof.
 */
class R2JobsClientTest {
    private val connection = Connection("r2-local", "fixture", "a".repeat(32))
    private val account = AccountScope(connection.id, connection.accountId)
    private val target = R2WriteTarget(account, "my-bucket")
    private val bucketPath = "/accounts/${connection.accountId}/r2/buckets/my-bucket"
    private val jobsPath = "$bucketPath/jobs"

    private val requests = mutableListOf<HttpRequest>()
    private var listStatus = 200
    private var getStatus = 200
    private var createStatus = 200
    private var listResult = """{"jobs":[]}"""
    private var getResult = prefixJob()
    private var createResult = prefixJob(id = "job-created", status = "ENQUEUED")

    private fun String.jsonEscaped(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private fun envelope(result: String) =
        """{"success":true,"errors":[],"messages":[],"result":$result}"""

    private fun prefixJob(
        id: String? = "job-1",
        status: String = "RUNNING",
        prefix: String = "",
        clear: Boolean? = null,
        deleted: Long? = 1350,
        extra: String = "",
    ): String {
        val idField = if (id == null) "" else "\"id\":\"${id.jsonEscaped()}\","
        val clearField = if (clear == null) "" else ",\"isBucketClear\":$clear"
        val deletedField = if (deleted == null) "" else ",\"deletedObjects\":$deleted"
        return """{$idField"jobType":"prefixDelete","status":"$status","startTime":"2026-10-22T00:00:00Z",$extra"prefixDelete":{"prefix":"$prefix"$clearField$deletedField}}"""
    }

    private fun migrationJob(
        id: String? = "job-2",
        status: String = "COMPLETED",
        counters: String = "\"migratedObjects\":10",
        extra: String = "",
    ): String {
        val idField = if (id == null) "" else "\"id\":\"${id.jsonEscaped()}\","
        return """{$idField"jobType":"storageClassMigration","status":"$status","startTime":"2026-10-22T00:00:00Z","endTime":"2026-10-22T00:10:00Z",$extra"storageClassMigration":{"sourceStorageClass":"Standard","destinationStorageClass":"InfrequentAccess",$counters}}"""
    }

    private val gateway = CloudflareGateway(
        HttpTransport { request ->
            requests += request
            when {
                request.path == bucketPath -> HttpResponse(200, envelope("""{"name":"my-bucket"}"""))
                request.method == "GET" && request.path.substringBefore('?') == jobsPath ->
                    if (listStatus == 200) HttpResponse(200, envelope(listResult)) else HttpResponse(listStatus, envelope("null"))
                request.method == "GET" && request.path.startsWith("$jobsPath/") ->
                    if (getStatus == 200) HttpResponse(200, envelope(getResult)) else HttpResponse(getStatus, envelope("null"))
                request.method == "POST" && request.path == jobsPath ->
                    if (createStatus == 200) HttpResponse(200, envelope(createResult)) else HttpResponse(createStatus, envelope("""{"errors":[{"code":10083}]}"""))
                else -> error("unexpected synthetic request ${request.method} ${request.path}")
            }
        },
        sleeper = {},
    )

    private val client = CloudflareR2JobsClient(connection, gateway)

    private fun mutations() = requests.filter { it.method != "GET" }

    @Test
    fun theThreeReviewedJobRoutesAreRegisteredExactlyOnce() {
        val list = assertNotNull(ActionPolicies.policy("r2-list-bucket-jobs"))
        assertEquals("GET", ActionPolicies.registry.registration("r2-list-bucket-jobs")!!.method)
        assertEquals(
            "/accounts/{account_id}/r2/buckets/{bucket_name}/jobs",
            ActionPolicies.registry.registration("r2-list-bucket-jobs")!!.pathTemplate,
        )
        assertEquals(ActionSemantics.READ, list.semantics)
        assertEquals(ResponseKind.ENVELOPE_JSON, list.responseKind)
        assertEquals(RiskLevel.READ_ONLY, list.risk)
        assertTrue(list.retry.enabled)

        val get = assertNotNull(ActionPolicies.policy("r2-get-bucket-job"))
        assertEquals(
            "/accounts/{account_id}/r2/buckets/{bucket_name}/jobs/{job_id}",
            ActionPolicies.registry.registration("r2-get-bucket-job")!!.pathTemplate,
        )
        assertEquals(ActionSemantics.READ, get.semantics)
        assertTrue(get.retry.enabled)

        val create = assertNotNull(ActionPolicies.policy("r2-create-bucket-job"))
        assertEquals("POST", ActionPolicies.registry.registration("r2-create-bucket-job")!!.method)
        assertEquals(ActionSemantics.WRITE, create.semantics)
        assertEquals(RiskLevel.DESTRUCTIVE, create.risk)
        assertFalse(create.retry.enabled)
        for (key in listOf("r2-list-bucket-jobs", "r2-get-bucket-job", "r2-create-bucket-job")) {
            assertEquals(ActionScope.ACCOUNT, ActionPolicies.registry.registration(key)!!.scope)
            assertFalse(ActionPolicies.registry.registration(key)!!.trailingPlaceholderSpansSegments)
        }
    }

    @Test
    fun listSendsTheExactQueryAndBoundsMaxKeysToTheClientRange() = runTest {
        listResult = """{"jobs":[${prefixJob()}],"nextContinuationToken":"tok/en+1"}"""
        val page = client.listJobs(
            target,
            R2JobListQuery(
                jobType = R2JobType.PREFIX_DELETE,
                status = R2JobStatus.RUNNING,
                maxKeys = 1000,
                continuationToken = "tok/en+1",
            ),
        )
        val request = requests.single()
        assertEquals(
            "$jobsPath?jobType=prefixDelete&status=RUNNING&maxKeys=1000&continuationToken=tok%2Fen%2B1",
            request.path,
        )
        assertEquals("default", request.headers["cf-r2-jurisdiction"])
        assertEquals(1, page.jobs.size)
        assertEquals("tok/en+1", page.nextContinuationToken)

        assertFailsWith<CloudFailure> { R2JobListQuery(status = R2JobStatus.RUNNING) }
        assertFailsWith<CloudFailure> { R2JobListQuery(jobType = R2JobType.PREFIX_DELETE, maxKeys = 0) }
        assertFailsWith<CloudFailure> { R2JobListQuery(jobType = R2JobType.PREFIX_DELETE, maxKeys = 1001) }
        listResult = """{"jobs":[${prefixJob()}]}"""
        assertEquals(1, client.listJobs(target, R2JobListQuery(jobType = R2JobType.PREFIX_DELETE, maxKeys = 1)).jobs.size)
    }

    @Test
    fun listDecodesBothKindsAndKeepsUnknownEntriesReadOnly() = runTest {
        listResult = """{"jobs":[${prefixJob()},${migrationJob()}],"nextContinuationToken":"next"}"""
        val page = client.listJobs(target, R2JobListQuery())
        assertEquals(2, page.jobs.size)
        val prefix = assertIs<R2JobDetails.PrefixDelete>(page.jobs[0].details).details
        assertEquals("", prefix.prefix)
        assertNull(prefix.isBucketClear, "an absent isBucketClear stays unknown, never guessed")
        assertEquals(1350L, prefix.deletedObjects)
        val migration = assertIs<R2JobDetails.StorageClassMigration>(page.jobs[1].details).details
        assertEquals(R2StorageClass.STANDARD, migration.sourceStorageClass)
        assertEquals(R2StorageClass.INFREQUENT_ACCESS, migration.destinationStorageClass)
        assertNull(migration.failedObjects)

        listResult = """{"jobs":[{"id":"j","jobType":"someFutureKind","status":"RUNNING","startTime":"t","futureKind":{"x":1}}]}"""
        val unknown = client.listJobs(target, R2JobListQuery())
        assertFalse(unknown.interpretable)
        assertEquals(R2_JOB_READ_ONLY_UNKNOWN, unknown.readOnlyReason)
        assertEquals("j", unknown.jobs.single().id)
        assertNull(unknown.jobs.single().jobType)
        assertFalse(unknown.jobs.single().interpretable)
    }

    @Test
    fun getJobRequiresTheRequestedIdAndKeepsUnknownStatusReadOnly() = runTest {
        getResult = prefixJob(id = "job-9", status = "RUNNING")
        val job = client.getJob(target, "job-9")
        assertEquals("job-9", job.id)
        assertEquals(R2JobStatus.RUNNING, job.status)
        assertTrue(job.interpretable)
        assertEquals("$jobsPath/job-9", requests.single().path)
        assertEquals(mapOf("cf-r2-jurisdiction" to "default"), requests.single().headers)

        getResult = prefixJob(id = "job-other")
        assertFalse(client.getJob(target, "job-9").interpretable, "a foreign echoed job id must not be accepted")

        getResult = prefixJob(status = "PAUSED")
        val unknownStatus = client.getJob(target, "job-9")
        assertNull(unknownStatus.status)
        assertFalse(unknownStatus.interpretable)
    }

    @Test
    fun createWithATrustworthySameKindIdReturnsAcceptedEvenWhenItReportsCompleted() = runTest {
        createResult = prefixJob(id = "job-created", status = "ENQUEUED", prefix = "logs/2026/", clear = false)
        val submission = client.createJob(target, R2JobCreateRequest.PrefixDelete("logs/2026/"))
        val request = mutations().single()
        assertEquals("POST", request.method)
        assertEquals(jobsPath, request.path)
        assertEquals(mapOf("cf-r2-jurisdiction" to "default", "cf-r2-data-catalog-check" to "true"), request.headers)
        assertEquals(
            """{"jobType":"prefixDelete","prefix":"logs/2026/"}""",
            Json.parseToJsonElement((request.payload as HttpBody.Text).value).toString(),
        )
        val accepted = assertIs<ExecutionReceipt.Accepted>(submission.receipt)
        assertEquals("$jobsPath/job-created", accepted.pollPath)
        assertEquals("job-created", submission.job?.id)
        assertEquals("job-created", submission.pollLocator?.jobId)

        // A POST that already reports COMPLETED is still only an acceptance; the independent GET
        // remains the completion proof.
        requests.clear()
        createResult = prefixJob(id = "job-done", status = "COMPLETED", prefix = "logs/2026/", clear = false)
        val completedOnPost = client.createJob(target, R2JobCreateRequest.PrefixDelete("logs/2026/"))
        assertIs<ExecutionReceipt.Accepted>(completedOnPost.receipt)
        assertEquals("job-done", completedOnPost.pollLocator?.jobId)
        assertEquals(1, mutations().size)
    }

    @Test
    fun emptyPrefixIsTheExplicitWholeBucketClear() = runTest {
        createResult = prefixJob(id = "job-clear", status = "ENQUEUED", prefix = "", clear = true)
        val submission = client.createJob(target, R2JobCreateRequest.PrefixDelete(""))
        assertEquals(
            """{"jobType":"prefixDelete","prefix":""}""",
            Json.parseToJsonElement((mutations().single().payload as HttpBody.Text).value).toString(),
        )
        assertEquals("true", mutations().single().headers["cf-r2-data-catalog-check"])
        assertIs<ExecutionReceipt.Accepted>(submission.receipt)
    }

    @Test
    fun createMigrationSendsBothClassesExplicitlyWithoutTheCatalogHeader() = runTest {
        createResult = migrationJob(id = "job-mig", status = "ENQUEUED")
        val submission = client.createJob(
            target,
            R2JobCreateRequest.StorageClassMigration(R2StorageClass.STANDARD, R2StorageClass.INFREQUENT_ACCESS),
        )
        val request = mutations().single()
        assertEquals(mapOf("cf-r2-jurisdiction" to "default"), request.headers)
        assertEquals(
            """{"jobType":"storageClassMigration","sourceStorageClass":"Standard","destinationStorageClass":"InfrequentAccess"}""",
            Json.parseToJsonElement((request.payload as HttpBody.Text).value).toString(),
        )
        assertIs<ExecutionReceipt.Accepted>(submission.receipt)
        assertEquals("job-mig", submission.pollLocator?.jobId)
    }

    @Test
    fun anUntrustworthyOrUnaddressableAnswerIsUnknownAndNeverCompleted() = runTest {
        // Missing ID.
        createResult = prefixJob(id = null, status = "ENQUEUED", prefix = "logs/2026/", clear = false)
        val missing = client.createJob(target, R2JobCreateRequest.PrefixDelete("logs/2026/"))
        assertIs<ExecutionReceipt.Unknown>(missing.receipt)
        assertNull(missing.job?.id)
        assertNull(missing.pollLocator)
        assertFalse(missing.job!!.interpretable)
        assertEquals(1, mutations().size)
        assertFalse(mutations().single().method == "GET")

        // Wrong kind.
        createResult = migrationJob(id = "job-other-kind")
        val wrongKind = client.createJob(target, R2JobCreateRequest.PrefixDelete("logs/2026/"))
        assertIs<ExecutionReceipt.Unknown>(wrongKind.receipt)
        assertNull(wrongKind.pollLocator)
        assertFalse(wrongKind.job!!.interpretable)

        // Wrong request parameter (prefix).
        createResult = prefixJob(id = "job-x", prefix = "other/", clear = false)
        val wrongPrefix = client.createJob(target, R2JobCreateRequest.PrefixDelete("logs/2026/"))
        assertIs<ExecutionReceipt.Unknown>(wrongPrefix.receipt)
        assertNull(wrongPrefix.pollLocator)

        // Unsafe echoed ID is not an addressable identity.
        createResult = prefixJob(id = "job id", prefix = "logs/2026/", clear = false)
        val unsafeId = client.createJob(target, R2JobCreateRequest.PrefixDelete("logs/2026/"))
        assertIs<ExecutionReceipt.Unknown>(unsafeId.receipt)
        assertNull(unsafeId.pollLocator)
        assertTrue(mutations().size >= 1)
        assertEquals(mutations().size, mutations().count { it.method == "POST" }, "no retry and no GET on the write path")
    }

    @Test
    fun aSentCreateWithAnUnexplainedResultShapeStaysUncertain() = runTest {
        createResult = "null"
        val nullResult = assertFailsWith<CloudFailure> {
            client.createJob(target, R2JobCreateRequest.PrefixDelete("logs/2026/"))
        }
        assertEquals(CloudFailureCode.INVALID_RESPONSE, nullResult.code)
        assertTrue(nullResult.uncertain, "a sent create with an unexplained result is uncertain, not a refusal")
        assertEquals(1, mutations().size)

        requests.clear()
        createResult = "[]"
        val arrayResult = assertFailsWith<CloudFailure> {
            client.createJob(target, R2JobCreateRequest.PrefixDelete("logs/2026/"))
        }
        assertTrue(arrayResult.uncertain)
        assertEquals(1, mutations().size)
    }

    @Test
    fun createJobIsSentOnceAndNeverRetriedOn429OrServerError() = runTest {
        createStatus = 429
        val limited = assertFailsWith<CloudFailure> {
            client.createJob(target, R2JobCreateRequest.PrefixDelete("logs/2026/"))
        }
        assertEquals(CloudFailureCode.RATE_LIMITED, limited.code)
        assertEquals(1, mutations().size)

        requests.clear()
        createStatus = 500
        val unavailable = assertFailsWith<CloudFailure> {
            client.createJob(target, R2JobCreateRequest.PrefixDelete("logs/2026/"))
        }
        assertTrue(unavailable.uncertain)
        assertEquals(1, mutations().size)
    }

    @Test
    fun optionalCountersBranchExclusionAndClearConsistencyAreStrict() = runTest {
        // Present-but-null/invalid optional counters are not a silent omission.
        getResult = migrationJob(counters = "\"migratedObjects\":10,\"failedObjects\":null")
        assertFalse(client.getJob(target, "job-2").interpretable, "a present null counter is not a valid omission")

        getResult = migrationJob(counters = "\"migratedObjects\":\"10\"")
        assertFalse(client.getJob(target, "job-2").interpretable, "a string counter is not a number")

        getResult = migrationJob(counters = "\"migratedObjects\":10,\"failedObjects\":-1")
        assertFalse(client.getJob(target, "job-2").interpretable, "a negative counter is invalid")

        getResult = migrationJob(counters = "\"migratedObjects\":99999999999999999999")
        assertFalse(client.getJob(target, "job-2").interpretable, "an out-of-range counter is invalid")

        // Both discriminators at once is ambiguous.
        getResult =
            """{"storageClassMigration":{"sourceStorageClass":"Standard","destinationStorageClass":"InfrequentAccess","migratedObjects":1},""" +
                prefixJob(id = "job-9").removePrefix("{")
        assertFalse(client.getJob(target, "job-9").interpretable, "both job branches must not be accepted together")

        // A non-empty prefix with isBucketClear=true (or an empty prefix with false) contradicts itself.
        getResult = prefixJob(id = "job-9", prefix = "logs/", clear = true)
        assertFalse(client.getJob(target, "job-9").interpretable)
        getResult = prefixJob(id = "job-9", prefix = "", clear = false)
        assertFalse(client.getJob(target, "job-9").interpretable)

        // A present non-string endTime is not an omission.
        getResult = prefixJob(id = "job-9").replace("\"status\":\"RUNNING\"", "\"status\":\"RUNNING\",\"endTime\":5")
        assertFalse(client.getJob(target, "job-9").interpretable)

        // A legal optional omission (isBucketClear/endTime absent, required counters present) works.
        getResult = prefixJob(id = "job-9", prefix = "", clear = null, deleted = 0)
        assertTrue(client.getJob(target, "job-9").interpretable)
        getResult = migrationJob(counters = "\"migratedObjects\":10")
        assertTrue(client.getJob(target, "job-2").interpretable)
    }

    @Test
    fun aMissingRequiredStatusIsNeverTrustedAndCanNeverMintALocator() = runTest {
        val noStatus = prefixJob(id = "job-9").replace(",\"status\":\"RUNNING\"", "")
        listResult = """{"jobs":[$noStatus]}"""
        val page = client.listJobs(target, R2JobListQuery())
        assertFalse(page.interpretable, "a job without its required status is not explainable")
        assertFalse(page.jobs.single().interpretable)

        getResult = noStatus
        assertFalse(client.getJob(target, "job-9").interpretable)

        createResult = noStatus.replace("\"prefixDelete\"", "\"prefixDelete\"").replace("\"job-9\"", "\"job-no-status\"")
        val submission = client.createJob(target, R2JobCreateRequest.PrefixDelete(""))
        assertIs<ExecutionReceipt.Unknown>(submission.receipt, "a required status must be present and known")
        assertNull(submission.pollLocator)
        assertEquals(1, mutations().size)
        assertEquals(1, mutations().count { it.method == "POST" })
    }

    @Test
    fun onlyAPathSafeOpaqueJobIdCanBecomeATrustedLocatorOrAReadTarget() = runTest {
        val unsafe = listOf("../x", "job/child", "job?x", "job#x", "job%2Fx", "job\\x", ".", "..")
        for (id in unsafe) {
            requests.clear()
            createResult = prefixJob(id = id, prefix = "logs/2026/", clear = false)
            val submission = client.createJob(target, R2JobCreateRequest.PrefixDelete("logs/2026/"))
            assertIs<ExecutionReceipt.Unknown>(submission.receipt, "an unaddressable id cannot be an acceptance: $id")
            assertNull(submission.pollLocator, id)
            assertEquals(1, mutations().size, "the POST is sent once and never retried for $id")

            getResult = prefixJob(id = id)
            assertFalse(client.getJob(target, "job-9").interpretable, "an unaddressable echoed id is display-only: $id")

            requests.clear()
            assertFailsWith<CloudFailure>("${id} must be refused locally as an input") { client.getJob(target, id) }
            assertTrue(requests.isEmpty(), "no out-of-shape read request is sent for $id")
        }

        // A normal provider UUID and a plain opaque identifier stay addressable.
        for (id in listOf("7f24658b-31d7-4b91-9a12-cb5a2c99b294", "job_abc-123.4")) {
            requests.clear()
            createResult = prefixJob(id = id, status = "ENQUEUED", prefix = "logs/2026/", clear = false)
            val accepted = client.createJob(target, R2JobCreateRequest.PrefixDelete("logs/2026/"))
            assertIs<ExecutionReceipt.Accepted>(accepted.receipt, id)
            assertEquals(id, accepted.pollLocator?.jobId, id)
            getResult = prefixJob(id = id)
            assertTrue(client.getJob(target, id).interpretable, id)
        }
    }

    @Test
    fun anUnsafeContinuationCursorKeepsThePageReadOnly() = runTest {
        listResult = """{"jobs":[${prefixJob()}],"nextContinuationToken":"bad token"}"""
        assertFalse(client.listJobs(target, R2JobListQuery()).interpretable, "a cursor with a space is not an opaque token")
        listResult = """{"jobs":[${prefixJob()}],"nextContinuationToken":"ok/en+1"}"""
        assertTrue(client.listJobs(target, R2JobListQuery()).interpretable)
    }

    @Test
    fun crossAccountTargetAndUntrustedIdentifiersAreRefusedBeforeTheRequest() = runTest {
        val foreign = R2WriteTarget(AccountScope("another-connection", "b".repeat(32)), "my-bucket")
        val mismatch = assertFailsWith<CloudFailure> { client.listJobs(foreign, R2JobListQuery()) }
        assertEquals(CloudFailureCode.SCOPE_MISMATCH, mismatch.code)
        assertTrue(requests.isEmpty())

        assertFailsWith<CloudFailure> { client.getJob(target, " ") }
        assertFailsWith<CloudFailure> { client.getJob(target, "job\n1") }
        assertFailsWith<CloudFailure> { R2JobCreateRequest.PrefixDelete("logs/2026") }
        assertFailsWith<CloudFailure> {
            R2JobCreateRequest.StorageClassMigration(R2StorageClass.STANDARD, R2StorageClass.STANDARD)
        }
        assertTrue(requests.isEmpty())
    }
}
