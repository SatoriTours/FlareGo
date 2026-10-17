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
import com.flarego.core.model.R2ObjectTarget
import com.flarego.core.model.R2ObjectWrite
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.ByteSource
import com.flarego.core.ports.HttpResponseHead
import com.flarego.core.ports.TransferPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * R2 object writes: upload, single delete and bulk delete.
 *
 * The pinned schema states that bulk delete is **not** atomic — when one key fails the
 * others are still deleted — so a partial outcome must never be reported as complete,
 * and an unclear outcome must never be replayed.
 */
class R2ObjectWriteTest {
    private val connection = Connection("local", "Cloudflare", "account-one")
    private val account = AccountScope("local", "account-one")
    private val bucket = "my-bucket"
    private val requests = mutableListOf<HttpRequest>()
    private val uploaded = mutableListOf<Byte>()
    private var respond: suspend (HttpRequest) -> HttpResponse = { request ->
        // The ownership read of the bucket comes first on every write.
        if (request.method == "GET") ok(bucketJson()) else ok("""{"key":"a.txt"}""")
    }

    private class Source(private val bytes: ByteArray) : ByteSource {
        var closed = false
        private var index = 0

        override fun read(buffer: ByteArray): Int {
            if (index >= bytes.size) return -1
            val count = minOf(buffer.size, bytes.size - index)
            bytes.copyInto(buffer, 0, index, index + count)
            index += count
            return count
        }

        override fun close() {
            closed = true
        }
    }

    private val noTransfers = object : TransferPort {
        override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead =
            throw IllegalStateException("object writes must not use the transfer sink")
    }

    private fun ok(result: String) = HttpResponse(
        200,
        """{"success":true,"errors":[],"messages":[],"result":$result}""",
    )

    private fun bucketJson() = """{"name":"$bucket","location":"weur"}"""

    private fun client() = CloudflareR2Client(
        connection,
        CloudflareGateway(HttpTransport { request ->
            requests += request
            // Mimic the real transport's ownership: drain and close a one-shot stream.
            (request.payload as? HttpBody.Stream)?.let { stream ->
                val buffer = ByteArray(64)
                while (true) {
                    val count = stream.source.read(buffer)
                    if (count <= 0) break
                    for (index in 0 until count) uploaded += buffer[index]
                }
                stream.source.close()
            }
            respond(request)
        }),
        noTransfers,
    )

    private fun objectResponse(request: HttpRequest): HttpResponse =
        if (request.method == "GET") ok(bucketJson()) else ok("""{"key":"dir/a.txt","size":"3","etag":"e","uploaded":"2026-01-01T00:00:00Z"}""")

    @Test
    fun anUploadStreamsTheSourceOnceAndVerifiesTheEchoedKey() = runTest {
        respond = { objectResponse(it) }
        val source = Source(byteArrayOf(1, 2, 3))
        val entry = client().uploadObject(R2ObjectTarget(account, bucket, "dir/a.txt"), source, 3, "text/plain")

        assertEquals("dir/a.txt", entry.key)
        assertEquals(3L, entry.size)
        val put = requests.last()
        assertEquals("PUT", put.method)
        assertEquals("/accounts/account-one/r2/buckets/my-bucket/objects/dir/a.txt", put.path)
        assertIs<HttpBody.Stream>(put.payload)
        assertEquals(3L, (put.payload as HttpBody.Stream).length)
        assertEquals("text/plain", (put.payload as HttpBody.Stream).contentType)
        assertEquals(listOf<Byte>(1, 2, 3), uploaded)
        assertTrue(source.closed, "the transport owns and closes a one-shot stream")
    }

    @Test
    fun theDefaultContentTypeIsOctetStream() = runTest {
        respond = { objectResponse(it) }
        client().uploadObject(R2ObjectTarget(account, bucket, "dir/a.txt"), Source(ByteArray(0)), 0, null)
        assertEquals("application/octet-stream", (requests.last().payload as HttpBody.Stream).contentType)
    }

    @Test
    fun aRejectedUploadNeverOpensTheSourceAndNeverSends() = runTest {
        var opened = 0
        val failure = assertFailsWith<CloudFailure> {
            client().uploadObject(
                R2ObjectTarget(account, bucket, "/illegal-key"),
                Source(byteArrayOf(1)),
                1,
                null,
            ).also { opened++ }
        }
        assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)
        assertEquals(0, opened)
        assertFalse(requests.any { it.method == "PUT" || it.method == "DELETE" })
    }

    @Test
    fun anObjectBeyondTheSingleRequestLimitIsRefusedWithAClearMessage() = runTest {
        val failure = assertFailsWith<CloudFailure> {
            client().uploadObject(R2ObjectTarget(account, bucket, "huge.bin"), Source(ByteArray(0)), 5L * 1024 * 1024 * 1024 + 1, null)
        }
        assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY, failure.code)
        assertFalse(requests.any { it.method == "PUT" })
    }

    @Test
    fun aSingleDeleteVerifiesTheEchoedKey() = runTest {
        respond = { request -> if (request.method == "GET") ok(bucketJson()) else ok("""{"key":"dir/a.txt"}""") }
        val client = client()
        client.deleteObject(R2ObjectTarget(account, bucket, "dir/a.txt"))
        assertEquals("/accounts/account-one/r2/buckets/my-bucket/objects/dir/a.txt", requests.last().path)

        respond = { request -> if (request.method == "GET") ok(bucketJson()) else ok("""{"key":"someone-else.txt"}""") }
        assertFailsWith<CloudFailure> { client().deleteObject(R2ObjectTarget(account, bucket, "dir/a.txt")) }
    }

    @Test
    fun aBulkDeleteSendsOneJsonKeyArrayAndReportsACompleteSuccess() = runTest {
        val keys = (1..200).map { "dir/$it.txt" }
        respond = { request ->
            if (request.method == "GET") ok(bucketJson())
            else ok(keys.joinToString(",", "[", "]") { """{"key":"$it"}""" })
        }
        val outcome = client().deleteObjects(account, bucket, keys)

        assertEquals(200, outcome.requestedCount)
        assertEquals(200, outcome.confirmedDeleted)
        assertEquals(0, outcome.failedCount)
        assertFalse(outcome.uncertain)

        val delete = requests.last()
        assertEquals("/accounts/account-one/r2/buckets/my-bucket/objects", delete.path)
        val body = Json.parseToJsonElement(delete.body!!).jsonArray
        assertEquals(200, body.size)
        assertEquals("dir/200.txt", body.last().toString().trim('"'))
    }

    @Test
    fun moreThanOneThousandKeysIsRefusedWithZeroSends() = runTest {
        val failure = assertFailsWith<CloudFailure> { client().deleteObjects(account, bucket, (1..1001).map { "k$it" }) }
        assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)
        assertFalse(requests.any { it.method == "DELETE" })
    }

    @Test
    fun anIllegalKeyInABatchIsRefusedBeforeTheBatchIsSent() = runTest {
        assertFailsWith<CloudFailure> { client().deleteObjects(account, bucket, listOf("ok.txt", "../escape.txt")) }
        assertFalse(requests.any { it.method == "DELETE" })
    }

    @Test
    fun aPartialBulkDeleteIsNeverReportedAsCompleteAndIsNeverReplayed() = runTest {
        var deletes = 0
        respond = { request ->
            if (request.method == "GET") {
                ok(bucketJson())
            } else {
                deletes++
                // The provider deleted one key, failed the other; `result` is null on failure.
                HttpResponse(200, """{"success":false,"errors":[{"code":1}],"messages":[],"result":null}""")
            }
        }
        val outcome = client().deleteObjects(account, bucket, listOf("a.txt", "b.txt"))

        assertEquals(2, outcome.requestedCount)
        assertEquals(1, outcome.failedCount)
        assertEquals(null, outcome.confirmedDeleted)
        assertTrue(outcome.uncertain, "a partial delete must be reconciled, never replayed")
        assertEquals(1, deletes)
    }

    @Test
    fun anUnclearUploadOutcomeBecomesUnknownWithoutReplay() = runTest {
        var puts = 0
        respond = { request ->
            if (request.method == "GET") ok(bucketJson())
            else {
                puts++
                HttpResponse(500, "private-provider-marker")
            }
        }
        val receipt = client().writeObject(
            R2ObjectWrite.Upload(account, bucket, "a.txt", { Source(byteArrayOf(1)) }, 1, null),
        ) {}
        assertEquals(ExecutionReceipt.Unknown(), receipt)
        assertEquals(1, puts)
    }

    @Test
    fun aSuccessThatDoesNotEchoEveryKeyIsNotACompleteBulkDelete() = runTest {
        respond = { request ->
            if (request.method == "GET") ok(bucketJson()) else ok("""{"key":"a.txt"}""")
        }
        // `success: true` but only one of two requested keys came back.
        assertFailsWith<CloudFailure> { client().deleteObjects(account, bucket, listOf("a.txt", "b.txt")) }
    }
}
