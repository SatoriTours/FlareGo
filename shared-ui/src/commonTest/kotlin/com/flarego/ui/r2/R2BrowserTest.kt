package com.flarego.ui.r2

import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.r2.CloudflareR2Client
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class R2BrowserTest {
    private val connection = Connection("one", "账号一", "account-one")
    private val context = R2BrowserContext(connection, 1, 1)
    private fun ok(result: String, cursor: String? = null) = HttpResponse(200,
        """{"success":true,"errors":[],"messages":[],"result":$result${cursor?.let { ",\"result_info\":{\"cursor\":\"$it\"}" } ?: ""}}""")

    private class Sink : ByteSink {
        var closed = false
        var count = 0
        override fun write(buffer: ByteArray, offset: Int, count: Int) { this.count += count }
        override fun close() { closed = true }
    }
    private fun api(requests: MutableList<HttpRequest>, send: suspend (HttpRequest) -> HttpResponse) =
        CloudflareR2Client(connection, CloudflareGateway(HttpTransport { r -> requests += r; send(r) }),
            object : TransferPort {
                override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead {
                    requests += request
                    sink.write(byteArrayOf(1, 2, 3), 0, 3); sink.close()
                    return HttpResponseHead(200, emptyMap(), 3)
                }
            })

    @Test fun bucketAndObjectNavigationUsesTypedCursorRequests() = runTest {
        val requests = mutableListOf<HttpRequest>()
        val service = api(requests) { r -> when {
            r.path.contains("/objects?") -> ok("""[{"key":"dir/a.txt","size":3}]""")
            r.path.contains("cursor=next") -> ok("""{"buckets":[{"name":"second-bucket"}]}""")
            else -> ok("""{"buckets":[{"name":"my-bucket","location":"weur"}]}""", "next")
        } }
        val browser = R2Browser(backgroundScope, { context }, { service })
        browser.refreshContext(); runCurrent()
        assertEquals(listOf("my-bucket"), browser.state.value.buckets.rows.map { it.name })
        browser.onBucketIntent(R2BucketIntent.LoadMore); runCurrent()
        assertEquals(listOf("my-bucket", "second-bucket"), browser.state.value.buckets.rows.map { it.name })
        browser.onBucketIntent(R2BucketIntent.OpenBucket("my-bucket")); runCurrent()
        assertEquals("dir/a.txt", browser.state.value.objects!!.rows.single().key)
        assertTrue(requests.last().path.startsWith("/accounts/account-one/r2/buckets/my-bucket/objects?"))
    }

    @Test fun lateAccountResultAndErrorsCannotPublishIntoAnotherAccount() = runTest {
        var actual: R2BrowserContext? = context
        val gate = CompletableDeferred<Unit>()
        val service = api(mutableListOf()) { withContext(NonCancellable) { gate.await() }; ok("""{"buckets":[{"name":"old-private-bucket"}]}""") }
        val browser = R2Browser(backgroundScope, { actual }, { service })
        browser.refreshContext(); runCurrent()
        actual = context.copy(connection = Connection("two", "账号二", "account-two"))
        browser.refreshContext(); runCurrent()
        gate.complete(Unit); runCurrent()
        assertTrue(browser.state.value.buckets.rows.isEmpty())
        assertEquals(actual, browser.state.value.context)
    }

    @Test fun uploadResultAfterDraftOrRouteChangeIsReleasedNotAccepted() = runTest {
        val service = api(mutableListOf()) { ok("""{"buckets":[{"name":"my-bucket"}]}""") }
        val browser = R2Browser(backgroundScope, { context }, { service })
        browser.refreshContext(); runCurrent()
        browser.onBucketIntent(R2BucketIntent.OpenBucket("my-bucket")); runCurrent()
        browser.onObjectIntent(R2ObjectIntent.UploadKeyChanged("old.txt"))
        browser.onObjectIntent(R2ObjectIntent.RequestUpload("old.txt"))
        val request = browser.documentRequests.value!!
        browser.onObjectIntent(R2ObjectIntent.UploadKeyChanged("new.txt"))
        var released = 0
        browser.acceptUpload(request.id, R2StagedUploadInfo(3, "a".repeat(64)), { released++ })
        assertEquals(1, released)
        assertNull(browser.state.value.stagedUpload)
        assertEquals("new.txt", browser.state.value.objects!!.uploadKey)
    }

    @Test fun selectionIsLimitedToVisibleKeysAndChangesInvalidatePendingDelete() = runTest {
        val service = api(mutableListOf()) { r -> if (r.path.contains("objects?")) ok("""[{"key":"a.txt","size":1}]""") else ok("""{"buckets":[{"name":"my-bucket"}]}""") }
        val browser = R2Browser(backgroundScope, { context }, { service })
        browser.refreshContext(); runCurrent()
        browser.onBucketIntent(R2BucketIntent.OpenBucket("my-bucket")); runCurrent()
        browser.onObjectIntent(R2ObjectIntent.ToggleSelection("invisible.txt"))
        assertTrue(browser.state.value.objects!!.selected.isEmpty())
        browser.onObjectIntent(R2ObjectIntent.ToggleSelection("a.txt"))
        browser.onObjectIntent(R2ObjectIntent.RequestBulkDelete)
        assertTrue(browser.state.value.objects!!.pendingBulkDelete)
        browser.onObjectIntent(R2ObjectIntent.ClearSelection)
        assertFalse(browser.state.value.objects!!.pendingBulkDelete)
    }

    @Test fun writesNeverEscapeTheUnimplementedAccountAuthorityGate() = runTest {
        val requests = mutableListOf<HttpRequest>()
        val service = api(requests) { r -> if (r.path.contains("objects?")) ok("""[{"key":"a.txt","size":1}]""") else ok("""{"buckets":[{"name":"my-bucket"}]}""") }
        val browser = R2Browser(backgroundScope, { context }, { service })
        browser.refreshContext(); runCurrent()
        browser.onBucketIntent(R2BucketIntent.NameChanged("new-bucket"))
        browser.onBucketIntent(R2BucketIntent.SubmitCreate)
        browser.onBucketIntent(R2BucketIntent.RequestDelete("my-bucket"))
        browser.onBucketIntent(R2BucketIntent.TypedDeleteNameChanged("my-bucket"))
        browser.onBucketIntent(R2BucketIntent.ConfirmDelete)
        browser.onBucketIntent(R2BucketIntent.OpenBucket("my-bucket")); runCurrent()
        browser.onObjectIntent(R2ObjectIntent.RequestDelete("a.txt"))
        browser.onObjectIntent(R2ObjectIntent.TypedDeleteKeyChanged("a.txt"))
        browser.onObjectIntent(R2ObjectIntent.ConfirmDelete)
        browser.onObjectIntent(R2ObjectIntent.ConfirmUpload)
        runCurrent()
        assertTrue(requests.all { it.method == "GET" })
        assertTrue(browser.state.value.objects!!.validationMessage!!.contains("写权限"))
    }

    @Test fun editingUploadKeyDuringAReadDoesNotStrandTheLoadingState() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = api(mutableListOf()) { r ->
            if (r.path.contains("objects?")) { gate.await(); ok("""[{"key":"a.txt","size":1}]""") }
            else ok("""{"buckets":[{"name":"my-bucket"}]}""")
        }
        val browser = R2Browser(backgroundScope, { context }, { service })
        browser.refreshContext(); runCurrent()
        browser.onBucketIntent(R2BucketIntent.OpenBucket("my-bucket")); runCurrent()
        browser.onObjectIntent(R2ObjectIntent.UploadKeyChanged("new.txt"))
        gate.complete(Unit); runCurrent()
        assertFalse(browser.state.value.objects!!.loading)
        assertEquals("new.txt", browser.state.value.objects!!.uploadKey)
        assertEquals("a.txt", browser.state.value.objects!!.rows.single().key)
    }

    @Test fun staleDownloadClosesSinkWithoutSendingObjectRequest() = runTest {
        val requests = mutableListOf<HttpRequest>()
        val service = api(requests) { r -> if (r.path.contains("objects?")) ok("""[{"key":"a.txt","size":1}]""") else ok("""{"buckets":[{"name":"my-bucket"}]}""") }
        val browser = R2Browser(backgroundScope, { context }, { service })
        browser.refreshContext(); runCurrent()
        browser.onBucketIntent(R2BucketIntent.OpenBucket("my-bucket")); runCurrent()
        browser.onObjectIntent(R2ObjectIntent.RequestDownload("a.txt"))
        val request = browser.documentRequests.value!!
        browser.onObjectIntent(R2ObjectIntent.BackToBuckets)
        val sink = Sink()
        assertFailsWith<CloudFailure> { browser.download(request.id, sink) }
        assertTrue(sink.closed)
        assertTrue(requests.none { it.path.endsWith("/objects/a.txt") })
    }
}
