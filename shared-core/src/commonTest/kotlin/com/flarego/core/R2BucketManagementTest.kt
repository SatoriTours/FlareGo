package com.flarego.core

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.r2.CloudflareR2Client
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.PageRequest
import com.flarego.core.model.PageToken
import com.flarego.core.model.R2BucketCreateInput
import com.flarego.core.model.R2BucketWrite
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The R2 bucket control plane over the P1 gateway. These assertions pin the two pinned
 * schema shapes (`result.buckets`, body-less `PUT`/`PATCH` with a storage-class header),
 * the cursor pagination, and the honesty rules for reads, writes and destructive sends.
 */
class R2BucketManagementTest {
    private val connection = Connection("local", "Cloudflare", "account-one")
    private val account = AccountScope("local", "account-one")
    private val foreignAccount = AccountScope("local", "account-two")
    private val requests = mutableListOf<HttpRequest>()
    private var respond: suspend (HttpRequest) -> HttpResponse = { ok("""{"buckets":[]}""") }

    private fun ok(result: String, info: String? = null) = HttpResponse(
        200,
        """{"success":true,"errors":[],"messages":[],"result":$result${info?.let { ",\"result_info\":$it" } ?: ""}}""",
    )

    private fun bucketRow(name: String = "my-bucket", extra: String = """""") =
        """{"name":"$name","creation_date":"2026-01-01T00:00:00Z","location":"weur","storage_class":"Standard"$extra}"""

    private fun client() = CloudflareR2Client(connection, CloudflareGateway(HttpTransport { request ->
        requests += request
        respond(request)
    }))

    private fun List<HttpRequest>.ofMethod(method: String) = filter { it.method == method }

    @Test
    fun theBucketListDecodesTheBucketsWrapperAndStopsAtTheLastCursor() = runTest {
        respond = { request ->
            val cursor = request.path.substringAfter("cursor=", "")
            if (cursor.isEmpty()) ok("""{"buckets":[${bucketRow()}]}""", """{"cursor":"c2","count":1,"total_count":2}""")
            else ok("""{"buckets":[${bucketRow("second-bucket")}]}""", """{"count":1,"total_count":2}""")
        }
        val client = client()
        val first = client.listBuckets(account, PageRequest(limit = 20))
        assertEquals(listOf("my-bucket"), first.items.map { it.name })
        assertEquals(PageToken.Cursor("c2"), first.next)
        assertEquals(2L, first.total)
        assertTrue(first.items.single().raw.containsKey("storage_class"))

        val second = client.listBuckets(account, PageRequest(limit = 20, token = first.next))
        assertEquals(listOf("second-bucket"), second.items.map { it.name })
        assertEquals(null, second.next)
        assertTrue(requests.last().path.contains("per_page=20"), "the requested page size must reach the provider")
    }

    @Test
    fun aRepeatedCursorIsAnErrorInsteadOfAnEndlessLoop() = runTest {
        respond = { ok("""{"buckets":[]}""", """{"cursor":"loop","count":0}""") }
        assertFailsWith<CloudFailure> { client().listBuckets(account, PageRequest(token = PageToken.Cursor("loop"))) }
    }

    @Test
    fun anOutOfRangePageSizeOrNumericTokenIsRefusedBeforeAnySend() = runTest {
        val client = client()
        assertFailsWith<CloudFailure> { client.listBuckets(account, PageRequest(limit = 1001)) }
        assertFailsWith<CloudFailure> { client.listBuckets(account, PageRequest(token = PageToken.Number(2))) }
        assertEquals(0, requests.size)
    }

    @Test
    fun aForeignAccountScopeIsRefusedBeforeAnySend() = runTest {
        assertFailsWith<CloudFailure> { client().listBuckets(foreignAccount, PageRequest()) }
        assertFailsWith<CloudFailure> { client().getBucket(foreignAccount, "my-bucket") }
        assertEquals(0, requests.size)
    }

    @Test
    fun theBucketReadKeepsUnknownProviderFieldsAndRejectsAnIllegalNameLocally() = runTest {
        respond = { ok(bucketRow(extra = ""","future_meta":{"preserve":true}""")) }
        val bucket = client().getBucket(account, "my-bucket")
        assertEquals("my-bucket", bucket.name)
        assertEquals(JsonPrimitive("weur"), bucket.raw.getValue("location"))
        assertTrue(bucket.raw.containsKey("future_meta"), "an unknown provider field must survive the read")
        assertEquals("/accounts/account-one/r2/buckets/my-bucket", requests.single().path)

        requests.clear()
        assertFailsWith<CloudFailure> { client().getBucket(account, "Illegal") }
        assertEquals(0, requests.size)
    }

    @Test
    fun createSendsOnlyTheReviewedBodyFields() = runTest {
        respond = { ok(bucketRow()) }
        client().createBucket(account, R2BucketCreateInput("my-bucket"))
        val minimal = requests.single()
        assertEquals("POST", minimal.method)
        assertEquals("""{"name":"my-bucket"}""", minimal.body)

        requests.clear()
        client().createBucket(account, R2BucketCreateInput("my-bucket", "weur", "InfrequentAccess"))
        assertEquals("""{"name":"my-bucket","locationHint":"weur","storageClass":"InfrequentAccess"}""", requests.single().body)
    }

    @Test
    fun createRefusesAnUnknownEnumWithoutSendingAnything() = runTest {
        respond = { ok(bucketRow()) }
        assertFailsWith<CloudFailure> { client().createBucket(account, R2BucketCreateInput("my-bucket", "mars", null)) }
        assertFailsWith<CloudFailure> { client().createBucket(account, R2BucketCreateInput("my-bucket", null, "Glacier")) }
        assertEquals(0, requests.size)
    }

    @Test
    fun thePutAndPatchVariantsCarryTheirStorageClassInTheHeaderAndNoBody() = runTest {
        respond = { request -> if (request.method == "GET") ok(bucketRow()) else ok(bucketRow()) }
        val client = client()

        client.createBucketByName(account, "my-bucket", "InfrequentAccess")
        val put = requests.single()
        assertEquals("PUT", put.method)
        assertEquals("/accounts/account-one/r2/buckets/my-bucket", put.path)
        assertEquals("InfrequentAccess", put.headers["cf-r2-storage-class"])
        assertEquals(HttpBody.Empty, put.payload)

        requests.clear()
        client.patchBucketStorageClass(account, "my-bucket", "Standard")
        val patch = requests.ofMethod("PATCH").single()
        assertEquals("Standard", patch.headers["cf-r2-storage-class"])
        assertEquals(HttpBody.Empty, patch.payload)
        // The write is preceded by one independent ownership read of the same bucket.
        assertEquals(listOf("GET", "PATCH"), requests.map { it.method })
    }

    @Test
    fun anUnknownStorageClassIsRefusedBeforeAnySend() = runTest {
        respond = { ok(bucketRow()) }
        assertFailsWith<CloudFailure> { client().createBucketByName(account, "my-bucket", "Glacier") }
        assertFailsWith<CloudFailure> { client().patchBucketStorageClass(account, "my-bucket", "Glacier") }
        assertEquals(0, requests.size)
    }

    @Test
    fun aProviderPreconditionFailureIsExplainableAndIsNeverRetried() = runTest {
        var deletes = 0
        respond = { request ->
            when (request.method) {
                "GET" -> ok(bucketRow())
                else -> {
                    deletes++
                    HttpResponse(400, "private-provider-marker")
                }
            }
        }
        val failure = assertFailsWith<CloudFailure> { client().deleteBucket(account, "my-bucket") }
        assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)
        assertFalse(failure.publicMessage.contains("private-provider-marker"), "provider text must never leak")
        assertEquals(1, deletes, "a rejected delete must not be replayed")
    }

    @Test
    fun aDeleteWhoseProviderNameDoesNotMatchIsRefusedBeforeTheDeleteIsSent() = runTest {
        respond = { request ->
            if (request.method == "GET") ok(bucketRow("someone-elses-bucket")) else ok("""{}""")
        }
        assertFailsWith<CloudFailure> { client().deleteBucket(account, "my-bucket") }
        assertEquals(listOf("GET"), requests.map { it.method })
    }

    @Test
    fun anUnclearProviderOutcomeBecomesUnknownAndIsNeverReplayed() = runTest {
        var writes = 0
        respond = { request ->
            if (request.method == "GET") ok(bucketRow()) else {
                writes++
                HttpResponse(500, "private-provider-marker")
            }
        }
        val receipt = client().write(R2BucketWrite.PatchStorageClass(account, "my-bucket", "Standard")) {}
        assertEquals(ExecutionReceipt.Unknown(), receipt)
        assertEquals(1, writes)
    }
}
