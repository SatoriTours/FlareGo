package com.flarego.core

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
import com.flarego.core.model.R2ObjectFilter
import com.flarego.core.model.R2ObjectTarget
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.HttpResponseHead
import com.flarego.core.ports.TransferPort
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R2 object listing and streamed download.
 *
 * The pinned schema is unusually specific here: an object key's slashes MUST be sent
 * literally rather than as `%2F`, the list result is a top-level array, and a download
 * returns the body with metadata in response headers.
 */
class R2ObjectTest {
    private val connection = Connection("local", "Cloudflare", "account-one")
    private val account = AccountScope("local", "account-one")
    private val bucket = "my-bucket"
    private val requests = mutableListOf<HttpRequest>()
    private var envelope: suspend (HttpRequest) -> HttpResponse = { ok("""[]""") }
    private var transfer: suspend (HttpRequest, ByteSink) -> HttpResponseHead = { _, _ -> HttpResponseHead(200, emptyMap(), 0) }

    private class Sink : ByteSink {
        val buffer = ByteArrayOutputStream()
        var closed = false

        override fun write(buffer: ByteArray, offset: Int, count: Int) {
            this.buffer.write(buffer, offset, count)
        }

        override fun close() {
            closed = true
        }
    }

    private val transfers = object : TransferPort {
        override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead {
            requests += request
            return transfer(request, sink)
        }
    }

    private fun ok(result: String, info: String? = null) = HttpResponse(
        200,
        """{"success":true,"errors":[],"messages":[],"result":$result${info?.let { ",\"result_info\":$it" } ?: ""}}""",
    )

    private fun bucketJson() = """{"name":"$bucket","location":"weur"}"""

    private fun client() = CloudflareR2Client(
        connection,
        CloudflareGateway(HttpTransport { request ->
            requests += request
            envelope(request)
        }),
        transfers,
    )

    @Test
    fun theObjectListDecodesTheResultArrayAndForwardsEveryFilter() = runTest {
        envelope = {
            ok(
                """[{"key":"dir/sub/file.txt","size":12,"etag":"abc","last_modified":"2026-01-01T00:00:00Z","future":1}]""",
                """{"cursor":"more","per_page":20}""",
            )
        }
        val page = client().listObjects(
            account,
            bucket,
            R2ObjectFilter(prefix = "dir/", delimiter = "/", startAfter = "dir/a"),
            PageRequest(limit = 20),
        )
        assertEquals(listOf("dir/sub/file.txt"), page.items.map { it.key })
        assertEquals(12L, page.items.single().size)
        assertEquals(PageToken.Cursor("more"), page.next)
        assertTrue(page.items.single().raw.containsKey("future"), "an unknown provider field must survive the read")

        val path = requests.single().path
        assertEquals("/accounts/account-one/r2/buckets/my-bucket/objects", path.substringBefore('?'))
        // Query values are percent-encoded as usual; only the key inside the path stays literal.
        assertTrue(path.contains("prefix=dir%2F"), path)
        assertTrue(path.contains("delimiter=%2F"), path)
        assertTrue(path.contains("start_after=dir%2Fa"), path)
        assertTrue(path.contains("per_page=20"), path)
    }

    @Test
    fun anIllegalDelimiterOrTokenIsRefusedWithZeroSends() = runTest {
        val client = client()
        assertFailsWith<CloudFailure> {
            client.listObjects(account, bucket, R2ObjectFilter(delimiter = "//"), PageRequest())
        }
        assertFailsWith<CloudFailure> {
            client.listObjects(account, bucket, page = PageRequest(token = PageToken.Number(2)))
        }
        assertFailsWith<CloudFailure> {
            client.listObjects(account, bucket, R2ObjectFilter(prefix = "x".repeat(1025)), PageRequest())
        }
        assertEquals(0, requests.size)
    }

    @Test
    fun aDownloadKeepsSlashesLiteralAndStreamsTheWholeBody() = runTest {
        envelope = { ok(bucketJson()) }
        val bytes = ByteArray(5 * 1024 * 1024) { 7 }
        transfer = { _, sink ->
            sink.write(bytes, 0, bytes.size)
            HttpResponseHead(
                200,
                mapOf("ETag" to listOf("\"abc\""), "Content-Type" to listOf("application/octet-stream")),
                bytes.size.toLong(),
            )
        }
        val sink = Sink()
        val head = client().downloadObject(R2ObjectTarget(account, bucket, "dir/sub/file.txt"), sink)

        assertEquals(5L * 1024 * 1024, head.transferredBytes)
        assertEquals("\"abc\"", head.etag)
        assertEquals("application/octet-stream", head.contentType)
        assertEquals(5 * 1024 * 1024, sink.buffer.size())
        // The ownership read comes first, then the single streamed object request.
        assertEquals(
            "/accounts/account-one/r2/buckets/my-bucket/objects/dir/sub/file.txt",
            requests.last().path,
        )
    }

    @Test
    fun aLiteralPercentInAKeyIsEncodedAndNeverBecomesASeparator() = runTest {
        envelope = { ok(bucketJson()) }
        client().downloadObject(R2ObjectTarget(account, bucket, "a%2Fb"), Sink())
        assertEquals("/accounts/account-one/r2/buckets/my-bucket/objects/a%252Fb", requests.last().path)
    }

    @Test
    fun documentsTheSharedValidatorLimitOnFragmentCharactersInAKey() = runTest {
        // Known limitation of the shared request-shape validator, not of the R2 encoding:
        // `report#1.csv` is encoded correctly to `report%231.csv`, but the validator rejects
        // any decoded `#` anywhere in a path, so the request is refused before any transfer.
        // Refusing is the safe outcome, and relaxing that shared check would touch every
        // policy, so it is recorded as a follow-up rather than changed here.
        envelope = { ok(bucketJson()) }
        val failure = assertFailsWith<CloudFailure> {
            client().downloadObject(R2ObjectTarget(account, bucket, "report#1.csv"), Sink())
        }
        assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)
        assertFalse(requests.any { it.path.contains("objects/") }, "nothing may be streamed")
    }

    @Test
    fun aFailedDownloadStatusNeverClaimsSuccess() = runTest {
        envelope = { ok(bucketJson()) }
        transfer = { _, _ -> HttpResponseHead(404, emptyMap(), 0) }
        val failure = assertFailsWith<CloudFailure> {
            client().downloadObject(R2ObjectTarget(account, bucket, "missing.txt"), Sink())
        }
        assertEquals(CloudFailureCode.NOT_FOUND, failure.code)
    }

    @Test
    fun anIllegalTargetKeyOrForeignScopeIsRefusedBeforeAnyRequest() = runTest {
        val client = client()
        assertFailsWith<CloudFailure> { client.downloadObject(R2ObjectTarget(account, bucket, "/leading"), Sink()) }
        assertFailsWith<CloudFailure> {
            client.downloadObject(R2ObjectTarget(AccountScope("local", "account-two"), bucket, "a.txt"), Sink())
        }
        assertFalse(requests.any { it.path.contains("/objects/") })
    }
}
