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
        var listedStorageClass: String? = "Standard"
        var detailStorageClass: String? = "Standard"
        var newBucketExists = false
        var newBucketClass = "Standard"
        val service = CloudflareR2Client(connection, CloudflareGateway(HttpTransport { r ->
            requests += r
            val result = when {
                r.path.contains("/objects?") -> """[{"key":"a.txt","size":3}]"""
                r.path.contains("/objects/") -> """{"key":"new.txt","size":"3","uploaded":"2026-01-01T00:00:00Z"}"""
                r.method == "GET" && r.path.endsWith("/new-bucket") -> {
                    if (!newBucketExists) return@HttpTransport HttpResponse(404, "absent")
                    """{"name":"new-bucket","storage_class":"$newBucketClass"}"""
                }
                r.path.contains("/buckets?") -> """{"buckets":[{"name":"my-bucket"${listedStorageClass?.let { ",\"storage_class\":\"$it\"" } ?: ""}}]}"""
                r.method == "POST" || (r.method == "PUT" && r.path.endsWith("/new-bucket")) -> {
                    newBucketExists = true
                    newBucketClass = r.headers["cf-r2-storage-class"] ?: "Standard"
                    """{"name":"new-bucket","storage_class":"${r.headers["cf-r2-storage-class"] ?: "Standard"}"}"""
                }
                r.method == "PATCH" -> {
                    detailStorageClass = r.headers["cf-r2-storage-class"]; listedStorageClass = detailStorageClass
                    """{"name":"my-bucket","storage_class":"$detailStorageClass"}"""
                }
                else -> """{"name":"my-bucket"${detailStorageClass?.let { ",\"storage_class\":\"$it\"" } ?: ""}}"""
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
    @Test fun cancellingManyPreviewsRevokesThemAndDoesNotExhaustTheRetentionWindow() = runTest {
        val h = Harness(this); h.browser.refreshContext(); runCurrent()
        repeat(96) {
            h.draftCreate(); runCurrent()
            val preview = assertNotNull(h.browser.state.value.preview)
            h.browser.cancelPreview()
            assertNull(h.browser.state.value.preview)
            assertFailsWith<CloudFailure> { h.runtime.confirm(preview.planId) }
        }
        h.draftCreate(); runCurrent()
        assertNotNull(h.browser.state.value.preview)
        assertTrue(h.requests.all { it.method == "GET" })
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
    @Test fun putModeClearsLocationHintAndSendsOnlyTheReviewedHeaderAfterConfirmation() = runTest {
        val h = Harness(this); h.browser.refreshContext(); runCurrent()
        h.browser.onBucketIntent(R2BucketIntent.NameChanged("new-bucket"))
        h.browser.onBucketIntent(R2BucketIntent.LocationHintChanged("weur"))
        h.browser.onBucketIntent(R2BucketIntent.CreateModeChanged(R2BucketCreateMode.PUT))
        assertNull(h.browser.state.value.buckets.draftLocationHint)
        h.browser.onBucketIntent(R2BucketIntent.LocationHintChanged("apac"))
        assertNull(h.browser.state.value.buckets.draftLocationHint)
        h.browser.onBucketIntent(R2BucketIntent.StorageClassChanged("InfrequentAccess"))
        h.browser.onBucketIntent(R2BucketIntent.SubmitCreate); runCurrent()
        assertTrue(h.browser.state.value.preview!!.detail.impact.contains("PUT"))
        assertTrue(h.requests.all { it.method == "GET" })
        h.browser.confirmPreview(); runCurrent()
        val write = h.requests.single { it.method != "GET" }
        assertEquals("PUT", write.method); assertEquals(HttpBody.Empty, write.payload)
        assertEquals("InfrequentAccess", write.headers["cf-r2-storage-class"])
        assertEquals(TaskState.SUCCEEDED, h.runtime.tasks.value.single().state)
    }
    @Test fun patchEditorPrefillsTheVisibleClassAndConfirmsAnEmptyBodyPatchOnce() = runTest {
        val h = Harness(this); h.browser.refreshContext(); runCurrent()
        h.browser.onBucketIntent(R2BucketIntent.RequestStorageClassEdit("my-bucket"))
        assertEquals("Standard", h.browser.state.value.buckets.editingOriginalStorageClass)
        assertEquals("Standard", h.browser.state.value.buckets.editingStorageClass)
        h.browser.onBucketIntent(R2BucketIntent.EditStorageClassChanged("InfrequentAccess"))
        h.browser.onBucketIntent(R2BucketIntent.SubmitStorageClassEdit); runCurrent()
        val preview = assertNotNull(h.browser.state.value.preview)
        assertTrue(preview.detail.impact.contains("Standard") && preview.detail.impact.contains("InfrequentAccess"))
        assertTrue(h.requests.all { it.method == "GET" })
        h.browser.confirmPreview(); h.browser.confirmPreview(); runCurrent()
        val patch = h.requests.single { it.method == "PATCH" }
        assertEquals(HttpBody.Empty, patch.payload)
        assertEquals("InfrequentAccess", patch.headers["cf-r2-storage-class"])
        assertEquals(1, h.store.rows.size)
    }
    @Test fun cancelOrChangingCreateModeRevokesThePatchPreview() = runTest {
        val h = Harness(this); h.browser.refreshContext(); runCurrent()
        for (intent in listOf(R2BucketIntent.CancelStorageClassEdit, R2BucketIntent.CreateModeChanged(R2BucketCreateMode.PUT))) {
            h.browser.onBucketIntent(R2BucketIntent.RequestStorageClassEdit("my-bucket"))
            h.browser.onBucketIntent(R2BucketIntent.EditStorageClassChanged("InfrequentAccess"))
            h.browser.onBucketIntent(R2BucketIntent.SubmitStorageClassEdit); runCurrent()
            assertNotNull(h.browser.state.value.preview)
            h.browser.onBucketIntent(intent)
            assertNull(h.browser.state.value.preview)
            h.browser.confirmPreview(); runCurrent()
        }
        assertTrue(h.requests.all { it.method == "GET" })
    }
    @Test fun unknownClassesAndNonVisibleBucketsCannotOpenAPatchEditor() = runTest {
        val h = Harness(this); h.listedStorageClass = "FutureArchive"
        h.browser.refreshContext(); runCurrent()
        h.browser.onBucketIntent(R2BucketIntent.RequestStorageClassEdit("my-bucket"))
        assertNull(h.browser.state.value.buckets.editingBucketName)
        assertTrue(h.browser.state.value.buckets.validationMessage.orEmpty().isNotBlank())
        h.browser.onBucketIntent(R2BucketIntent.RequestStorageClassEdit("not-visible"))
        h.browser.onBucketIntent(R2BucketIntent.EditStorageClassChanged("Standard"))
        h.browser.onBucketIntent(R2BucketIntent.SubmitStorageClassEdit); runCurrent()
        assertNull(h.browser.state.value.preview)
        assertTrue(h.requests.all { it.method == "GET" })
    }
    @Test fun providerClassDriftAfterPatchPreviewCannotSendAChangedSnapshot() = runTest {
        val h = Harness(this); h.browser.refreshContext(); runCurrent()
        h.browser.onBucketIntent(R2BucketIntent.RequestStorageClassEdit("my-bucket"))
        h.browser.onBucketIntent(R2BucketIntent.EditStorageClassChanged("InfrequentAccess"))
        h.browser.onBucketIntent(R2BucketIntent.SubmitStorageClassEdit); runCurrent()
        assertNotNull(h.browser.state.value.preview)
        h.detailStorageClass = "InfrequentAccess"
        h.browser.confirmPreview(); runCurrent()
        assertTrue(h.requests.all { it.method == "GET" })
        assertEquals(TaskState.FAILED, h.runtime.tasks.value.single().state)
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

    // ------------------------------------------------------------------ batch B2 domain settlement through the real root route

    /**
     * Drives the real root route ([R2Browser] + [R2WriteRuntime] + domain controller) with a
     * synthetic domain session, then inspects both the root and the configuration surface.
     */
    private inner class DomainHarness(scope: TestScope) {
        var ids = 0
        var revision = 1L
        val store = Store()
        var receipt: ExecutionReceipt = ExecutionReceipt.Completed()
        val domain = "cdn.example.com"
        val zoneId = "0123456789abcdef0123456789abcdef"
        var enabled = true
        var minTls = "1.2"
        val writes = mutableListOf<R2DomainConfigurationWrite>()

        private val rawRow: String
            get() = """{"domain":"$domain","enabled":$enabled,"minTLS":"$minTls","ciphers":["ECDHE-ECDSA-AES128-GCM-SHA256"],"zoneId":"$zoneId","zoneName":"example.com","status":{"ownership":"pending","ssl":"initializing"}}"""

        private fun row(name: String) = R2CustomDomain(
            domain = name,
            enabled = enabled,
            minTls = minTls,
            ciphers = listOf("ECDHE-ECDSA-AES128-GCM-SHA256"),
            zoneId = zoneId,
            zoneName = "example.com",
            status = R2CustomDomainStatus("pending", "initializing"),
            raw = kotlinx.serialization.json.Json.parseToJsonElement(rawRow) as kotlinx.serialization.json.JsonObject,
        )

        private fun document(target: R2WriteTarget, kind: R2DomainKind, name: String?): R2DomainDocument =
            when (kind) {
                R2DomainKind.MANAGED_DOMAIN -> R2DomainDocument(
                    target, kind,
                    kotlinx.serialization.json.Json.parseToJsonElement("""{"domain":"pub.r2.dev","enabled":false,"bucketId":"b"}""") as kotlinx.serialization.json.JsonObject,
                    true, null, managed = R2ManagedDomain("pub.r2.dev", false, "b"),
                )
                R2DomainKind.CUSTOM_DOMAINS -> R2DomainDocument(
                    target, kind,
                    kotlinx.serialization.json.Json.parseToJsonElement("""{"domains":[$rawRow]}""") as kotlinx.serialization.json.JsonObject,
                    true, null, customDomains = listOf(row(domain)),
                )
                R2DomainKind.CUSTOM_DOMAIN -> R2DomainDocument(
                    target, kind,
                    kotlinx.serialization.json.Json.parseToJsonElement(rawRow) as kotlinx.serialization.json.JsonObject,
                    true, null, customDomain = row(name ?: domain),
                )
            }

        private val domainSession = object : R2DomainConfigurationSession {
            override suspend fun getManagedDomain(target: R2WriteTarget) = document(target, R2DomainKind.MANAGED_DOMAIN, null)
            override suspend fun listCustomDomains(target: R2WriteTarget) = document(target, R2DomainKind.CUSTOM_DOMAINS, null)
            override suspend fun getCustomDomain(target: R2DomainTarget) = document(target.target, R2DomainKind.CUSTOM_DOMAIN, target.domain)
            override suspend fun putManagedDomain(target: R2WriteTarget, enabled: Boolean): ExecutionReceipt =
                writeDomainConfiguration(R2DomainConfigurationWrite.PutManagedDomain(target, enabled)) {}
            override suspend fun addCustomDomain(target: R2WriteTarget, input: R2CustomDomainCreateInput): ExecutionReceipt =
                writeDomainConfiguration(R2DomainConfigurationWrite.AddCustomDomain(target, input)) {}
            override suspend fun editCustomDomain(target: R2WriteTarget, domain: String, edit: R2CustomDomainEditInput): ExecutionReceipt =
                writeDomainConfiguration(R2DomainConfigurationWrite.EditCustomDomain(target, domain, edit)) {}
            override suspend fun deleteCustomDomain(target: R2WriteTarget, domain: String): ExecutionReceipt =
                writeDomainConfiguration(R2DomainConfigurationWrite.DeleteCustomDomain(target, domain)) {}

            override suspend fun writeDomainConfiguration(
                write: R2DomainConfigurationWrite,
                beforeSend: (R2DomainDocument?) -> Unit,
            ): ExecutionReceipt {
                val observationKind = when (write) {
                    is R2DomainConfigurationWrite.PutManagedDomain -> R2DomainKind.MANAGED_DOMAIN
                    is R2DomainConfigurationWrite.AddCustomDomain, is R2DomainConfigurationWrite.DeleteCustomDomain ->
                        R2DomainKind.CUSTOM_DOMAINS
                    is R2DomainConfigurationWrite.EditCustomDomain -> R2DomainKind.CUSTOM_DOMAIN
                }
                beforeSend(document(write.target, observationKind, write.domain))
                writes += write
                // Only a completed receipt changes the provider state; Unknown/Accepted do not.
                if (receipt is ExecutionReceipt.Completed) {
                    when (write) {
                        is R2DomainConfigurationWrite.EditCustomDomain -> {
                            write.edit.enabled?.let { enabled = it }
                            write.edit.minTls?.let { minTls = it }
                        }
                        else -> Unit
                    }
                }
                return receipt
            }
        }

        private val bucketSession = object : R2Session {
            override suspend fun listBuckets(account: AccountScope, page: PageRequest): CloudPage<R2BucketDocument> =
                CloudPage(listOf(R2BucketDocument("my-bucket", account, kotlinx.serialization.json.Json.parseToJsonElement("""{"name":"my-bucket"}""") as kotlinx.serialization.json.JsonObject)), null, 1)
            override suspend fun getBucket(account: AccountScope, name: String): R2BucketDocument = TODO()
            override suspend fun createBucket(account: AccountScope, input: R2BucketCreateInput): R2BucketDocument = TODO()
            override suspend fun createBucketByName(account: AccountScope, name: String, storageClass: String?): R2BucketDocument = TODO()
            override suspend fun patchBucketStorageClass(account: AccountScope, name: String, storageClass: String): R2BucketDocument = TODO()
            override suspend fun deleteBucket(account: AccountScope, name: String) = TODO()
            override suspend fun listObjects(account: AccountScope, bucket: String, filter: R2ObjectFilter, page: PageRequest): CloudPage<R2ObjectEntry> = TODO()
            override suspend fun downloadObject(target: R2ObjectTarget, sink: ByteSink): R2ObjectHead = TODO()
            override suspend fun uploadObject(target: R2ObjectTarget, source: ByteSource, length: Long, contentType: String?, contentFingerprint: String): R2ObjectEntry = TODO()
            override suspend fun deleteObject(target: R2ObjectTarget) = TODO()
            override suspend fun deleteObjects(account: AccountScope, bucket: String, keys: List<String>): R2BulkDeleteOutcome = TODO()
            override suspend fun write(write: R2BucketWrite, beforeSend: (R2BucketDocument?) -> Unit): ExecutionReceipt = TODO()
            override suspend fun writeObject(write: R2ObjectWrite, beforeSend: (R2ObjectEntry?) -> Unit): ExecutionReceipt = TODO()
        }

        val runtime: R2WriteRuntime = R2WriteRuntime(
            scope.backgroundScope, store, StandardTestDispatcher(scope.testScheduler),
            FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
            OperationClock { 1_000 }, OperationIds { "dom-ui-${++ids}" },
            { R2LiveSelection(connection, revision, browser.confirmationEpoch, browser.liveCommand()) },
            { listOf(connection) }, { revision }, { bucketSession },
            domainSession = { domainSession },
        )
        val browser: R2Browser = R2Browser(scope.backgroundScope, { context }, runtime, null, { domainSession }) { bucketSession }
        private val testScope = scope

        fun open() {
            browser.refreshContext()
            browser.onConfigurationIntent(R2ConfigurationIntent.Open("my-bucket"))
            browser.onConfigurationIntent(R2ConfigurationIntent.PanelSelected(R2ConfigurationPanel.CUSTOM_DOMAINS))
            testScope.runCurrent()
            browser.onConfigurationIntent(R2ConfigurationIntent.SelectCustomDomain(domain))
            testScope.runCurrent()
        }

        fun acknowledge() {
            browser.onConfigurationIntent(R2ConfigurationIntent.RisksChanged(R2ConfigurationRisks()))
            browser.onConfigurationIntent(R2ConfigurationIntent.TypedTargetNameChanged("my-bucket"))
            browser.onConfigurationIntent(R2ConfigurationIntent.TypedDomainNameChanged(domain))
        }

        fun prepareEdit(enabled: Boolean = false) {
            browser.onConfigurationIntent(R2ConfigurationIntent.CustomDomainEditEnabledChanged(enabled))
            acknowledge()
            browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
            testScope.runCurrent()
        }

        fun confirm() {
            browser.onConfigurationIntent(R2ConfigurationIntent.Confirm)
            testScope.runCurrent()
        }

        fun editCommand() = R2Command.Domain(
            R2DomainConfigurationWrite.EditCustomDomain(
                R2WriteTarget(AccountScope(connection.id, connection.accountId), "my-bucket"),
                domain,
                R2CustomDomainEditInput(enabled = false),
            ),
        )
    }

    @Test fun aDomainUnknownSettlesTheConfigurationSurfaceAndKeepsTheUnsettledGate() = runTest {
        val h = DomainHarness(this)
        h.receipt = ExecutionReceipt.Unknown()
        h.open()
        h.prepareEdit(enabled = false)
        assertNotNull(h.browser.state.value.preview)
        h.confirm()
        val task = h.store.rows.values.single()
        assertEquals(TaskState.UNKNOWN, task.state)
        assertEquals(1, h.writes.size)
        // The configuration surface must leave the preview state and show the UNKNOWN result.
        assertNull(h.browser.state.value.preview, "the root preview is gone")
        assertNull(h.browser.configuration.state.value.preview, "the configuration preview must not survive settlement")
        assertFalse(h.browser.configuration.state.value.writing)
        assertFalse(h.browser.state.value.writing)
        assertTrue(h.browser.configuration.state.value.notice.orEmpty().contains("UNKNOWN"), "configuration notice: ${h.browser.configuration.state.value.notice}")
        assertTrue(h.browser.state.value.notice.orEmpty().contains("UNKNOWN"))
        // Repeat confirmation cannot resend (no preview, no new mutation).
        h.confirm()
        assertEquals(1, h.writes.size)
        // The same-bucket unsettled gate still blocks a new preview.
        h.prepareEdit(enabled = true)
        assertNull(h.browser.state.value.preview)
        assertTrue(h.browser.configuration.state.value.validationMessage.orEmpty().isNotBlank())
        assertEquals(1, h.writes.size)
    }

    @Test fun aDomainSuccessReloadsThePageAndKeepsAConfigurationOnlyNotice() = runTest {
        val h = DomainHarness(this)
        h.open()
        h.prepareEdit(enabled = false)
        assertNotNull(h.browser.state.value.preview)
        h.confirm()
        val task = h.store.rows.values.single()
        assertEquals(TaskState.SUCCEEDED, task.state)
        val config = h.browser.configuration.state.value
        assertNull(config.preview)
        assertFalse(config.writing)
        // The notice survives the post-settle reload and keeps the configuration-save meaning.
        assertTrue(config.notice.orEmpty().contains("配置写入已按同锁回读"), "notice: ${config.notice}")
        assertTrue(config.notice.orEmpty().contains("不宣称域名/SSL/连通性就绪"))
        // The page shows the verified read-back state, not the old draft.
        assertFalse(config.loading)
        assertFalse(config.customDomains.single().enabled)
        assertFalse(h.enabled)
        assertNull(h.browser.state.value.preview)
    }

    @Test fun aDomainAcceptedOrRefusedTaskIsClassifiedOnTheConfigurationSurface() = runTest {
        val accepted = DomainHarness(this)
        accepted.receipt = ExecutionReceipt.Accepted()
        accepted.open()
        accepted.prepareEdit(enabled = false)
        accepted.confirm()
        assertEquals(TaskState.ACCEPTED, accepted.store.rows.values.single().state)
        assertNull(accepted.browser.configuration.state.value.preview)
        assertTrue(accepted.browser.configuration.state.value.notice.orEmpty().contains("ACCEPTED"))

        val refused = DomainHarness(this)
        refused.open()
        refused.prepareEdit(enabled = false)
        // The provider state changes between preview and send: the trusted pre-send check refuses.
        refused.enabled = true
        refused.minTls = "1.3"
        refused.confirm()
        assertEquals(TaskState.FAILED, refused.store.rows.values.single().state)
        assertNull(refused.browser.configuration.state.value.preview)
        assertTrue(refused.browser.configuration.state.value.notice.orEmpty().contains("未发送"))
        assertEquals(0, refused.writes.size)
    }

    @Test fun reconcilingADomainTaskSettlesTheConfigurationSurfaceWithAConfigurationNotice() = runTest {
        val h = DomainHarness(this)
        h.receipt = ExecutionReceipt.Unknown()
        h.open()
        h.prepareEdit(enabled = false)
        h.confirm()
        val task = h.store.rows.values.single()
        assertEquals(TaskState.UNKNOWN, task.state)
        // The provider applied the edit after the uncertain receipt.
        h.enabled = false
        h.browser.reconcileTask(task.id)
        runCurrent()
        assertEquals(TaskState.SUCCEEDED, h.store.rows.getValue(task.id).state)
        assertTrue(h.browser.configuration.state.value.notice.orEmpty().contains("配置写入已按同锁回读"))
        assertNull(h.browser.configuration.state.value.preview)
        assertEquals(1, h.writes.size, "reconciliation is read-only")
    }
}
