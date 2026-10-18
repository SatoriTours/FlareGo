package com.flarego.ui.r2

import com.flarego.core.application.operations.*
import com.flarego.core.application.r2.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.r2.CloudflareR2Client
import com.flarego.core.model.*
import com.flarego.core.ports.*
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class R2BrowserWriteTest {
    private val connection = Connection("one", "fixture", "account-one")
    private val context = R2BrowserContext(connection, 1, 1)
    private class Store : OperationStore {
        val rows = mutableMapOf<String, OperationTask>()
        override fun get(id: String) = rows[id]
        override fun list(connectionId: String) = rows.values.toList()
        override fun put(task: OperationTask) { rows[task.id] = task }
        override fun putWithSummary(task: OperationTask, summary: Operation) { put(task) }
        override fun removeConnection(connectionId: String) { rows.clear() }
    }
    private inner class Harness(scope: TestScope) {
        val requests = mutableListOf<HttpRequest>(); val store = Store(); var ids = 0; var revision = 1L
        val service = CloudflareR2Client(connection, CloudflareGateway(HttpTransport { r ->
            requests += r
            val result = when {
                r.path.contains("/objects?") -> """[{"key":"a.txt","size":3}]"""
                r.path.contains("/objects/") -> """{"key":"new.txt","size":"3","uploaded":"2026-01-01T00:00:00Z"}"""
                r.method == "GET" && r.path.endsWith("/new-bucket") -> return@HttpTransport HttpResponse(404, "absent")
                r.path.contains("/buckets?") -> """{"buckets":[{"name":"my-bucket"}]}"""
                r.method == "POST" -> """{"name":"new-bucket","storage_class":"Standard"}"""
                else -> """{"name":"my-bucket"}"""
            }
            (r.payload as? HttpBody.Stream)?.let { stream ->
                val buffer = ByteArray(8)
                while (stream.source.read(buffer) >= 0) { }
                stream.source.close()
            }
            HttpResponse(200,"""{"success":true,"result":$result}""")
        }), object : TransferPort { override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead = error("not download") })
        val runtime: R2WriteRuntime = R2WriteRuntime(scope.backgroundScope, store, StandardTestDispatcher(scope.testScheduler),
            FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
            OperationClock { 1_000 }, OperationIds { "ui-${++ids}" },
            { R2LiveSelection(connection, 1, browser.confirmationEpoch, browser.liveCommand()) },
            { listOf(connection) }, { revision }, { service })
        val browser: R2Browser = R2Browser(scope.backgroundScope, { context }, runtime) { service }
        fun draftCreate() { browser.onBucketIntent(R2BucketIntent.NameChanged("new-bucket")); browser.onBucketIntent(R2BucketIntent.SubmitCreate) }
    }
    @Test fun buttonPreparesButOnlySecondExplicitConfirmationSends() = runTest {
        val h = Harness(this); h.browser.refreshContext(); runCurrent()
        h.draftCreate(); runCurrent()
        assertNotNull(h.browser.state.value.preview)
        assertTrue(h.requests.all { it.method == "GET" })
        h.browser.confirmPreview(); runCurrent()
        assertEquals(1, h.requests.count { it.method == "POST" })
        assertEquals(TaskState.SUCCEEDED, h.runtime.tasks.value.single().state)
        assertNull(h.browser.state.value.preview)
    }
    @Test fun refusedConfirmationClearsBusyAndDoesNotOfferAStalePreview() = runTest {
        val h = Harness(this); h.browser.refreshContext(); runCurrent(); h.draftCreate(); runCurrent()
        h.revision = -1
        h.browser.confirmPreview(); runCurrent()
        assertFalse(h.browser.state.value.writing)
        assertNull(h.browser.state.value.preview)
        assertTrue(h.requests.all { it.method == "GET" })
    }
    @Test fun trustedStagedSourceOpensOnlyAfterConfirmationAndIsReleasedAfterSettlement() = runTest {
        val h = Harness(this); h.browser.refreshContext(); runCurrent()
        h.browser.onBucketIntent(R2BucketIntent.OpenBucket("my-bucket")); runCurrent()
        h.browser.onObjectIntent(R2ObjectIntent.UploadKeyChanged("new.txt"))
        h.browser.onObjectIntent(R2ObjectIntent.RequestUpload("new.txt"))
        var opened = 0; var released = 0; var closed = 0
        val content = object : R2UploadContent {
            override val length = 3L
            override val sha256 = "a".repeat(64)
            override suspend fun openSource(): ByteSource {
                opened++
                return object : ByteSource {
                    var sent = false
                    override fun read(buffer: ByteArray): Int { if (sent) return -1; sent = true; byteArrayOf(1, 2, 3).copyInto(buffer); return 3 }
                    override fun close() { closed++ }
                }
            }
            override fun release() { released++ }
        }
        h.browser.acceptUpload(h.browser.documentRequests.value!!.id, content)
        h.browser.onObjectIntent(R2ObjectIntent.ConfirmUpload); runCurrent()
        assertNotNull(h.browser.state.value.preview)
        assertEquals(0, opened)
        h.browser.confirmPreview(); runCurrent()
        assertEquals(1, opened); assertEquals(1, released); assertEquals(1, closed)
        assertEquals(TaskState.SUCCEEDED, h.runtime.tasks.value.single().state)
        assertNull(h.browser.state.value.stagedUpload)
        assertFalse(h.browser.state.value.objects!!.notice.orEmpty().contains("尚未上传"))
    }
    @Test fun editingAfterPreviewRevokesConfirmationWithoutSending() = runTest {
        val h = Harness(this); h.browser.refreshContext(); runCurrent(); h.draftCreate(); runCurrent()
        assertNotNull(h.browser.state.value.preview)
        h.browser.onBucketIntent(R2BucketIntent.NameChanged("other-bucket"))
        assertNull(h.browser.state.value.preview)
        h.browser.confirmPreview(); runCurrent()
        assertTrue(h.requests.all { it.method == "GET" })
    }
    @Test fun replacingTheFileRetiresTheOldSnapshotBeforeTheNewPickerReturns() = runTest {
        val h = Harness(this); h.browser.refreshContext(); runCurrent()
        h.browser.onBucketIntent(R2BucketIntent.OpenBucket("my-bucket")); runCurrent()
        h.browser.onObjectIntent(R2ObjectIntent.UploadKeyChanged("new.txt"))
        h.browser.onObjectIntent(R2ObjectIntent.RequestUpload("new.txt"))
        var released = 0
        h.browser.acceptUpload(h.browser.documentRequests.value!!.id, R2StagedUploadInfo(3, "a".repeat(64))) { released++ }
        h.browser.onObjectIntent(R2ObjectIntent.RequestUpload("new.txt"))
        assertNull(h.browser.state.value.stagedUpload)
        assertEquals(1, released)
        assertNull(h.browser.state.value.preview)
    }
    @Test fun displayMetadataAloneCannotProvideUploadBytesOrAWritePreview() = runTest {
        val h = Harness(this); h.browser.refreshContext(); runCurrent()
        h.browser.onBucketIntent(R2BucketIntent.OpenBucket("my-bucket")); runCurrent()
        h.browser.onObjectIntent(R2ObjectIntent.UploadKeyChanged("new.txt"))
        h.browser.onObjectIntent(R2ObjectIntent.RequestUpload("new.txt"))
        h.browser.acceptUpload(h.browser.documentRequests.value!!.id, R2StagedUploadInfo(3, "a".repeat(64))) { }
        h.browser.onObjectIntent(R2ObjectIntent.ConfirmUpload); runCurrent()
        assertNull(h.browser.state.value.preview)
        assertTrue(h.requests.all { it.method == "GET" })
        assertTrue(h.browser.state.value.objects!!.validationMessage!!.contains("文件"))
    }
}
