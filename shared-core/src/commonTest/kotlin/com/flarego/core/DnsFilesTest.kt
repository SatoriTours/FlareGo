package com.flarego.core

import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.model.*
import kotlin.test.*
import com.flarego.core.application.operations.*
import com.flarego.core.application.dns.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.dns.CloudflareDnsFiles
import com.flarego.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

class DnsFilesTest {
    @Test fun twoFixedFileOperationsRegisterCorrectMethodsPathsResponseKindsAndNoWriteReplay() {
        val actual = dnsFilesRegistrations().associateBy { it.policy.key }
        assertEquals(setOf(DNS_IMPORT_OPERATION, DNS_EXPORT_OPERATION), actual.keys)
        val upload = actual.getValue(DNS_IMPORT_OPERATION)
        assertEquals("POST", upload.method); assertEquals("/zones/{zone_id}/dns_records/import", upload.pathTemplate)
        assertEquals(ActionScope.ZONE, upload.scope); assertEquals(ActionSemantics.WRITE, upload.policy.semantics)
        assertEquals(ResponseKind.ENVELOPE_JSON, upload.policy.responseKind); assertFalse(upload.policy.retry.enabled)
        val download = actual.getValue(DNS_EXPORT_OPERATION)
        assertEquals("GET", download.method); assertEquals("/zones/{zone_id}/dns_records/export", download.pathTemplate)
        assertEquals(ActionScope.ZONE, download.scope); assertEquals(ActionSemantics.READ, download.policy.semantics)
        assertEquals(ResponseKind.TEXT, download.policy.responseKind)
    }
    @Test fun helperIsConnectedToRealGatewayRegistry() {
        assertEquals(2, dnsFilesRegistrations().size)
        for (registration in dnsFilesRegistrations()) assertEquals(registration, ActionPolicies.registry.registration(registration.policy.key))
    }
    private val connection = Connection("local", "Cloudflare", "account-one")
    private val zone = ZoneScope(AccountScope("local", "account-one"), "zone-one")
    private val hash = FingerprintPort { bytes -> java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
    private val original = ("$" + "ORIGIN example.com.\r\n$" + "TTL 300\r\nwww IN A 192.0.2.1 ; cf_tags=cf-proxied:false,keep:raw\r\n$" + "GENERATE 1-2 host$ IN A 192.0.2.$\r\n").encodeToByteArray()
    private fun ok(result: JsonElement, success: Boolean = true, errors: JsonArray = JsonArray(emptyList())) = HttpResponse(200, buildJsonObject {
        put("success", success); put("errors", errors); put("messages", JsonArray(emptyList())); put("result", result)
    }.toString())
    private class Source(val bytes: ByteArray, val beforeRead: (() -> Unit)? = null, val closeError: Boolean = false) : ByteSource {
        var offset = 0; var closes = 0; var zero = false
        override fun read(buffer: ByteArray): Int {
            beforeRead?.invoke()
            if (zero) return 0
            if (offset == bytes.size) return -1
            val count = minOf(buffer.size, bytes.size - offset, 4096)
            bytes.copyInto(buffer, 0, offset, offset + count); offset += count; return count
        }
        override fun close() { closes++; if (closeError) throw IllegalStateException("private-source-marker") }
    }
    private class Sink : ByteSink {
        val data = mutableListOf<Byte>(); var closes = 0; var fail = false
        override fun write(buffer: ByteArray, offset: Int, count: Int) {
            if (fail) throw IllegalStateException("private-disk-full-marker")
            data += buffer.copyOfRange(offset, offset + count).toList()
        }
        override fun close() { closes++ }
        fun bytes() = data.toByteArray()
    }
    private class Store : OperationStore {
        val rows = mutableMapOf<String, OperationTask>()
        override fun get(id: String) = rows[id]
        override fun list(connectionId: String) = rows.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { rows[task.id] = task }
        override fun putWithSummary(task: OperationTask, summary: Operation) { put(task) }
        override fun removeConnection(connectionId: String) { rows.entries.removeAll { it.value.connectionId == connectionId } }
    }
    private class Resolver : OperationContextResolver {
        var live: LiveOperationContext? = null; var valid = true
        override fun current() = live
        override fun liveConnections() = listOfNotNull(live?.connection)
        override fun isLive(scope: CloudScope, credentialRevision: Long?) = valid && (credentialRevision == null || credentialRevision == live?.credentialRevision)
    }
    private inner class Files : DnsFileStore {
        var bytes = original.copyOf(); var denied = false; val sources = mutableListOf<Source>()
        var commitFailure = false; var diskFailure = false; var transactions = mutableListOf<Tx>()
        val ref get() = DnsFileRef("opaque-file", "zone.bind", bytes.size.toLong(), hash.sha256(bytes))
        override suspend fun open(ref: DnsFileRef): ByteSource {
            if (denied) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "文件权限已失效，请重新选择文件")
            return Source(bytes.copyOf()).also { sources += it }
        }
        override suspend fun beginExport(target: DnsExportTarget): DnsExportTransaction = Tx().also { transactions += it }
        override suspend fun release(ref: DnsFileRef) = Unit
        inner class Tx : DnsExportTransaction {
            override val sink = Sink().also { it.fail = diskFailure }; var committed = false; var aborted = false
            override suspend fun commit(): DnsFileRef {
                if (commitFailure) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "文件权限已失效，请重新选择目标")
                committed = true; val data = sink.bytes()
                return DnsFileRef("exported", "zone.bind", data.size.toLong(), hash.sha256(data))
            }
            override suspend fun abort() { aborted = true; sink.close(); sink.data.clear() }
        }
    }
    private inner class Harness(val test: TestScope, val owner: Connection = connection, sharedLimiter: DnsImportLimiter? = null) {
        val scope = ZoneScope(AccountScope(owner.id, owner.accountId), "zone-one")
        var now = 1_000L; var sequence = 0
        val clock = OperationClock { now }; val limiter = sharedLimiter ?: DnsImportLimiter(clock)
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val resolver = Resolver(); val store = Store(); val files = Files()
        val plans = ChangePlans(resolver, clock, OperationIds { "file-${++sequence}" })
        val coordinator = OperationCoordinator(plans, store, resolver, clock, test.backgroundScope, dispatcher)
        val requests = mutableListOf<HttpRequest>(); val uploads = mutableListOf<ByteArray>()
        var authorityFailure = false
        var permission: Boolean? = true; var ownerAccount: String? = owner.accountId; var ownerId: String? = null
        var onOwnerRead: (() -> Unit)? = null; var onSession: (() -> Unit)? = null; var afterUpload: (() -> Unit)? = null
        var throwWrite = false; var writeResponse: HttpResponse? = null; var readDenied = false
        var exportedBytes = original.copyOf(); var exportStatus = 200; var exportType = "text/plain; charset=utf-8"
        var exportFailure: Throwable? = null; var onDownload: (() -> Unit)? = null
        val transport = HttpTransport { request ->
            requests += request
            val id = request.path.substringBefore('?').split('/').getOrElse(2) { "" }
            if (request.method == "GET") {
                if (request.path.contains("dns_records")) {
                    if (readDenied) HttpResponse(403, "private-read-marker") else {
                        val root = Json.parseToJsonElement(ok(buildJsonArray { add(buildJsonObject {
                            put("id", "r1"); put("type", "A"); put("name", "example.com"); put("content", "192.0.2.1"); put("ttl", 300); put("proxied", false)
                        }) }).body).jsonObject
                        HttpResponse(200, JsonObject(root + ("result_info" to buildJsonObject { put("page", 1); put("per_page", 20); put("count", 1); put("total_count", 1); put("total_pages", 1) })).toString())
                    }
                } else {
                    onOwnerRead?.invoke()
                    ok(buildJsonObject { put("id", ownerId ?: id); put("name", "example.com"); put("type", "full"); put("status", "active"); put("paused", false)
                        ownerAccount?.let { put("account", buildJsonObject { put("id", it) }) }
                    })
                }
            } else {
                val multipart = request.payload as HttpBody.Multipart
                val file = multipart.parts.single { it.name == "file" }.body as HttpBody.Stream
                val bytes = mutableListOf<Byte>(); val buffer = ByteArray(8192)
                try { while (true) { val n = file.source.read(buffer); if (n < 0) break; bytes += buffer.copyOf(n).toList() } }
                finally { file.source.close() }
                uploads += bytes.toByteArray(); afterUpload?.invoke()
                if (throwWrite) throw IllegalStateException("private-timeout-marker")
                writeResponse ?: ok(buildJsonObject { put("recs_added", 1); put("total_records_parsed", 1) })
            }
        }
        val transfer = object : TransferPort {
            override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead {
                requests += request
                try {
                    if (exportStatus in 200..299) sink.write(exportedBytes, 0, exportedBytes.size)
                    onDownload?.invoke(); exportFailure?.let { throw it }
                    return HttpResponseHead(exportStatus, mapOf("Content-Type" to listOf(exportType)), if (exportStatus in 200..299) exportedBytes.size.toLong() else 0)
                } finally { sink.close() }
            }
        }
        val api = CloudflareDnsFiles(owner, CloudflareGateway(transport), transfer, hash, limiter, dispatcher, object : DnsFilesAuthority {
            override fun mayImport(scope: ZoneScope): Boolean? {
                if (authorityFailure) throw IllegalStateException("private-authority-marker")
                return permission
            }
        })
        val controller = DnsFileController(resolver, plans, coordinator, clock, hash, store, dispatcher, files, dispatcher) { onSession?.invoke(); api }
        fun select(file: DnsFileRef = files.ref, proxied: Boolean = false, target: ZoneScope = scope) {
            resolver.live = LiveOperationContext(owner, target, 1, controller.requestFingerprint(target, file, proxied))
        }
        suspend fun prepare(file: DnsFileRef = files.ref, proxied: Boolean = false, target: ZoneScope = scope): ChangePlan {
            select(file, proxied, target); return controller.prepareImport(target, file, proxied)
        }
        fun confirm(plan: ChangePlan) = plans.confirm(plan.id, plan.scope, plan.credentialRevision, plan.requestFingerprint, now)
        fun restored(): DnsFileController {
            val p = ChangePlans(resolver, clock, OperationIds { "restored-${++sequence}" })
            val c = OperationCoordinator(p, store, resolver, clock, test.backgroundScope, dispatcher)
            return DnsFileController(resolver, p, c, clock, hash, store, dispatcher, files, dispatcher) { api }
        }
        val writes get() = requests.filter { it.method == "POST" }
        suspend fun direct(bytes: ByteArray = original, proxied: Boolean = false): Pair<DnsImportOutcome, Source> {
            val source = Source(bytes)
            return api.importRecords(scope, source, bytes.size.toLong(), hash.sha256(bytes), proxied) to source
        }
    }

    @Test fun multipartUsesFileBytesAndStringBooleanWithoutRewritingDirectivesOrTags() = runTest {
        for (proxied in listOf(false, true)) {
            val h = Harness(this); val (outcome, source) = h.direct(proxied = proxied)
            assertIs<DnsImportOutcome.ReportedSuccess>(outcome)
            val body = h.writes.single().payload as HttpBody.Multipart
            assertEquals(setOf("file", "proxied"), body.parts.map { it.name }.toSet())
            assertEquals(proxied.toString(), (body.parts.single { it.name == "proxied" }.body as HttpBody.Text).value)
            assertEquals("text/plain", body.parts.single { it.name == "file" }.contentType)
            assertContentEquals(original, h.uploads.single()); assertEquals(1, source.closes)
            assertTrue(h.requests.any { it.method == "GET" && "dns_records?" in it.path })
        }
    }
    @Test fun exact262144BytesAcceptedBut262145RefusedWithSourceClosedAndZeroUpload() = runTest {
        val h = Harness(this)
        val accepted = ByteArray(262144) { ';'.code.toByte() }
        assertIs<DnsImportOutcome.ReportedSuccess>(h.direct(accepted).first)
        h.requests.clear(); h.uploads.clear()
        val source = Source(ByteArray(262145))
        assertFailsWith<CloudFailure> { h.api.importRecords(h.scope, source, 262145, hash.sha256(source.bytes), false) }
        assertEquals(1, source.closes); assertEquals(0, h.writes.size)
    }
    @Test fun wrongLengthHashEmptyAndNoProgressCloseSourceBeforeAnySend() = runTest {
        for (mode in 0..3) {
            val h = Harness(this); val source = Source(if (mode == 2) byteArrayOf() else original)
            if (mode == 3) source.zero = true
            assertFailsWith<CloudFailure> { h.api.importRecords(h.scope, source, if (mode == 0) 1 else source.bytes.size.toLong(), if (mode == 1) "0".repeat(64) else hash.sha256(source.bytes), false) }
            assertEquals(1, source.closes); assertEquals(0, h.writes.size)
        }
    }
    @Test fun includeIsUnsupportedUnknownDirectivesAndGenerateKeepUnknownRecordCount() = runTest {
        val h = Harness(this); h.files.bytes = ("$" + "FUTURE vendor extension\n" + original.decodeToString()).encodeToByteArray()
        val p = h.prepare(); val preview = h.controller.preview(p.id)
        assertNull(preview.recordCount)
        val warnings = preview.warnings.joinToString(" ")
        for (term in listOf("未知", "dry-run", "cf-proxied", "部分", "CAS")) assertTrue(warnings.contains(term))
        h.files.bytes = ("$" + "INCLUDE private-zone-path\n").encodeToByteArray()
        val failure = assertFailsWith<CloudFailure> { h.prepare() }
        assertTrue(failure.publicMessage.contains("INCLUDE")); assertFalse(failure.toString().contains("private-zone-path"))
        assertEquals(0, h.writes.size)
    }
    @Test fun changedFileAfterConfirmationOrDuringFreshOwnerReadCannotSend() = runTest {
        for (duringOwner in listOf(false, true)) {
            val h = Harness(this); val file = h.files.ref; val p = h.prepare(file); val c = h.confirm(p)
            if (duringOwner) h.onOwnerRead = { h.files.bytes = "mutated-content".encodeToByteArray() }
            else h.files.bytes = "mutated-content".encodeToByteArray()
            val result = runCatching { h.controller.submitImport(h.scope, file, false, c) }
            assertEquals(0, h.writes.size)
            assertTrue(result.isFailure || result.getOrNull()?.state != TaskState.SUCCEEDED)
            assertTrue(h.files.sources.all { it.closes == 1 })
        }
    }
    @Test fun previewDoesNotConsumeGlobalLimiterAndFourthSendAcrossConnectionsAndControllersIsRefused() = runTest {
        var now = 1000L; val limiter = DnsImportLimiter(OperationClock { now }); val counts = mutableListOf<Int>()
        for (index in 0..3) {
            val h = Harness(this, Connection("c$index", "Cloudflare", "a$index"), limiter)
            repeat(4) { h.prepare() }
            val p = h.prepare(); val result = runCatching { h.controller.submitImport(h.scope, h.files.ref, false, h.confirm(p)) }
            counts += h.writes.size
            if (index < 3) assertEquals(TaskState.SUCCEEDED, result.getOrThrow().state)
        }
        assertEquals(listOf(1, 1, 1, 0), counts)
    }
    @Test fun localFourthRejectionIsNotAppliedAndDoesNotPermanentlyBlockTheZone() = runTest {
        val h = Harness(this)
        repeat(3) {
            val p = h.prepare()
            assertEquals(TaskState.SUCCEEDED, h.controller.submitImport(h.scope, h.files.ref, false, h.confirm(p)).state)
        }
        val fourth = h.prepare()
        val rejected = runCatching { h.controller.submitImport(h.scope, h.files.ref, false, h.confirm(fourth)) }
        assertEquals(3, h.writes.size, "Fourth local rejection must send nothing")
        assertEquals(TaskState.ACTION_REQUIRED, h.store.get(fourth.id)?.state, "Definite zero-send must not leave a permanently unsettled import")
        assertEquals(CloudFailureCode.RATE_LIMITED, (rejected.exceptionOrNull() as? CloudFailure)?.code)
        h.now += 60_000
        val next = h.prepare()
        assertEquals(TaskState.SUCCEEDED, h.controller.submitImport(h.scope, h.files.ref, false, h.confirm(next)).state)
        assertEquals(4, h.writes.size)
    }
    @Test fun preSendFileConflictAndPermissionChangeAreNotAppliedAndAllowNewPreview() = runTest {
        for (permissionChange in listOf(false, true)) {
            val h = Harness(this); val file = h.files.ref; val p = h.prepare(file)
            if (permissionChange) h.permission = false else h.files.bytes = "changed original".encodeToByteArray()
            val rejected = runCatching { h.controller.submitImport(h.scope, file, false, h.confirm(p)) }
            assertEquals(0, h.writes.size)
            assertEquals(TaskState.ACTION_REQUIRED, h.store.get(p.id)?.state)
            assertIs<CloudFailure>(rejected.exceptionOrNull())
            h.permission = true
            h.prepare(h.files.ref)
        }
    }
    @Test fun exportNeverConsultsImportWriteAuthorityEvenIfItsProbeThrows() = runTest {
        val h = Harness(this); h.select(); h.authorityFailure = true
        val result = h.controller.exportRecords(h.scope, DnsExportTarget("target", "zone.bind"))
        assertEquals(h.scope, result.scope)
        assertContentEquals(original, h.files.transactions.single().sink.bytes())
        assertTrue(h.files.transactions.single().committed); assertEquals(0, h.writes.size)
    }
    @Test fun shortJsonDisguisedAsPlainTextIsNotCommitted() = runTest {
        for (body in listOf("{}", "[]")) {
            val h = Harness(this); h.select(); h.exportedBytes = body.encodeToByteArray()
            assertFailsWith<CloudFailure> { h.controller.exportRecords(h.scope, DnsExportTarget("target", "zone.bind")) }
            assertTrue(h.files.transactions.single().aborted)
            assertFalse(h.files.transactions.single().committed)
        }
    }
    @Test fun rollingWindowIsExactAndTimeoutQuotaIsNeverRefunded() = runTest {
        val h = Harness(this); h.throwWrite = true
        repeat(3) { assertIs<DnsImportOutcome.Unknown>(h.direct().first) }
        h.requests.clear(); h.now = 60999
        assertEquals(CloudFailureCode.RATE_LIMITED, assertFailsWith<CloudFailure> { h.direct() }.code)
        assertEquals(0, h.writes.size)
        h.now = 61000; assertIs<DnsImportOutcome.Unknown>(h.direct().first)
        assertEquals(1, h.writes.size)
    }
    @Test fun permissionAndIndependentOwnerFailuresCloseDirectSourceAndDoNotImport() = runTest {
        for (mode in 0..4) {
            val h = Harness(this); val source = Source(original)
            when (mode) { 0 -> h.permission = null; 1 -> h.permission = false; 2 -> h.ownerAccount = "other"; 3 -> h.ownerAccount = null; else -> h.ownerId = "other-zone" }
            assertFailsWith<CloudFailure> { h.api.importRecords(h.scope, source, original.size.toLong(), hash.sha256(original), false) }
            assertEquals(1, source.closes); assertEquals(0, h.writes.size)
        }
    }
    @Test fun forgedConfirmationChangedInputCredentialAndExpiryNeverImport() = runTest {
        for (mode in 0..4) {
            val h = Harness(this); val file = h.files.ref; val p = h.prepare(file); val c = h.confirm(p)
            when (mode) { 0 -> h.resolver.live = h.resolver.live!!.copy(requestFingerprint = "0".repeat(64)); 1 -> h.resolver.live = h.resolver.live!!.copy(credentialRevision = 2); 2 -> h.now += ChangePlans.TTL_MS; 3 -> h.resolver.valid = false }
            assertFailsWith<CloudFailure> { h.controller.submitImport(h.scope, file, mode == 4, if (mode == 4) ConfirmedPlan(p, "forged") else c) }
            assertEquals(0, h.writes.size)
        }
    }
    @Test fun freshSessionOwnerAndPermissionChangesBeforeGuardRefuseImport() = runTest {
        for (mode in 0..3) {
            val h = Harness(this); val p = h.prepare(); val file = h.files.ref; val c = h.confirm(p)
            when (mode) { 0 -> h.onSession = { h.resolver.live = h.resolver.live!!.copy(credentialRevision = 2) }; 1 -> h.onOwnerRead = { h.resolver.live = h.resolver.live!!.copy(requestFingerprint = "0".repeat(64)) }; 2 -> h.permission = false; else -> h.ownerAccount = "other" }
            val result = runCatching { h.controller.submitImport(h.scope, file, false, c) }
            assertEquals(0, h.writes.size); assertTrue(result.isFailure || result.getOrNull()?.state != TaskState.SUCCEEDED)
        }
    }
    @Test fun partialImportPreservesPartialAndNeverAutomaticallyImportsAgain() = runTest {
        for (withErrors in listOf(false, true)) {
            val h = Harness(this)
            h.writeResponse = ok(buildJsonObject { put("recs_added", 1); put("total_records_parsed", if (withErrors) 1 else 2) }, errors = if (withErrors) buildJsonArray { add(buildJsonObject { put("code", 1001); put("message", "private-partial-marker") }) } else JsonArray(emptyList()))
            val file = h.files.ref; val p = h.prepare(file); val c = h.confirm(p)
            val task = h.controller.submitImport(h.scope, file, false, c)
            assertEquals(TaskState.PARTIAL, task.state); assertEquals(1, h.writes.size)
            assertFalse(task.toString().contains("private-partial-marker")); assertFalse(task.toString().contains("192.0.2"))
            h.requests.clear()
            assertEquals(TaskState.PARTIAL, h.restored().reconcile(task.id).state)
            assertEquals(0, h.writes.size)
            assertFailsWith<CloudFailure> { h.prepare(file) }
        }
    }
    @Test fun missingCountsFalseMalformedErrorsWrongTarget429AndLostReceiptStayUnknownWithoutRetry() = runTest {
        for (mode in 0..6) {
            val h = Harness(this); val file = h.files.ref; val p = h.prepare(file)
            h.writeResponse = when (mode) {
                0 -> ok(JsonObject(emptyMap()))
                1 -> ok(buildJsonObject { put("recs_added", 1); put("total_records_parsed", 1) }, success = false)
                2 -> HttpResponse(200, "private-invalid-json")
                3 -> ok(buildJsonObject { put("recs_added", 1); put("total_records_parsed", 1); put("zone_id", "wrong") })
                4 -> HttpResponse(429, "private-rate-limit")
                else -> null
            }
            if (mode == 5) h.throwWrite = true
            if (mode == 6) h.readDenied = true
            val task = h.controller.submitImport(h.scope, file, false, h.confirm(p))
            assertEquals(TaskState.UNKNOWN, task.state); assertEquals(1, h.writes.size)
            assertFalse(task.toString().contains("private-"))
        }
    }
    @Test fun lostReceiptCannotBeReconciledByEqualInventoryCountOrRestoreOldPayload() = runTest {
        val h = Harness(this); val file = h.files.ref; val p = h.prepare(file); val c = h.confirm(p)
        h.throwWrite = true
        val task = h.controller.submitImport(h.scope, file, false, c)
        h.throwWrite = false; h.requests.clear()
        assertEquals(TaskState.UNKNOWN, h.restored().reconcile(task.id).state)
        assertEquals(0, h.writes.size)
        assertFailsWith<CloudFailure> { h.restored().submitImport(h.scope, file, false, c) }
        assertFailsWith<CloudFailure> { h.prepare(file) }
    }
    @Test fun sharedGateBlocksImportAfterSingleBatchOrImportUnknownIncludingPrepreparedConfirmAndMissingRefs() = runTest {
        val counts = mutableListOf<Int>()
        for (key in listOf(DNS_IMPORT_OPERATION, "dns-records-for-a-zone-patch-dns-record", "dns-records-for-a-zone-batch-dns-records")) for (missing in listOf(false, true)) {
            val h = Harness(this); val file = h.files.ref
            val first = h.prepare(file); val c1 = h.confirm(first)
            val second = h.prepare(file, true); val c2 = h.confirm(second)
            h.select(file); h.throwWrite = true
            val unknown = h.controller.submitImport(h.scope, file, false, c1)
            h.store.put(unknown.copy(actionKey = key, resourceRef = if (missing) null else ResourceRef(h.scope, "future_dns", "opaque")))
            h.throwWrite = false; h.requests.clear(); h.select(file, true)
            runCatching { h.controller.submitImport(h.scope, file, true, c2) }
            counts += h.writes.size
            assertFailsWith<CloudFailure> { h.prepare(file) }
            val other = h.scope.copy(zoneId = "zone-two"); val p = h.prepare(file, target = other)
            assertEquals(TaskState.SUCCEEDED, h.controller.submitImport(other, file, false, h.confirm(p)).state)
        }
        assertEquals(List(6) { 0 }, counts)
    }
    @Test fun exportPreservesExactBindBytesAndCommitsOnlyOfficialTextPlainSuccess() = runTest {
        val h = Harness(this); h.select()
        val result = h.controller.exportRecords(h.scope, DnsExportTarget("new-empty-target", "zone.bind"))
        val tx = h.files.transactions.single()
        assertTrue(tx.committed); assertFalse(tx.aborted); assertContentEquals(original, tx.sink.bytes())
        assertEquals(hash.sha256(original), result.file.sha256); assertEquals(original.size.toLong(), result.head.transferredBytes)
        assertEquals(0, h.writes.size)
    }
    @Test fun jsonHtmlWrongStatusOrMissingContentTypeNeverCommitExport() = runTest {
        for (mode in 0..4) {
            val h = Harness(this); h.select()
            when (mode) {
                0 -> { h.exportType = "application/json"; h.exportedBytes = "{\"success\":false}".encodeToByteArray() }
                1 -> { h.exportType = "text/plain"; h.exportedBytes = " \r\n{\"success\":false}".encodeToByteArray() }
                2 -> { h.exportType = "text/plain"; h.exportedBytes = "<html>private-error</html>".encodeToByteArray() }
                3 -> h.exportStatus = 403
                else -> h.exportType = ""
            }
            assertFailsWith<CloudFailure> { h.controller.exportRecords(h.scope, DnsExportTarget("target", "zone.bind")) }
            val tx = h.files.transactions.single(); assertFalse(tx.committed); assertTrue(tx.aborted)
        }
    }
    @Test fun diskFullPermissionLossAndCancelAbortOwnedExportWithoutDeletingOriginalSource() = runTest {
        for (mode in 0..2) {
            val h = Harness(this); h.select()
            when (mode) { 0 -> h.files.diskFailure = true; 1 -> h.files.commitFailure = true; else -> h.exportFailure = CancellationException("fixture-cancel") }
            val result = runCatching { h.controller.exportRecords(h.scope, DnsExportTarget("target", "zone.bind")) }
            assertTrue(result.isFailure); assertTrue(h.files.transactions.single().aborted)
            assertContentEquals(original, h.files.bytes)
            assertFalse(result.exceptionOrNull().toString().contains("private-disk-full-marker"))
        }
    }
    @Test fun filePermissionReadFailureCloseFailureAndCancellationDoNotSendOrLeakContent() = runTest {
        val denied = Harness(this); val file = denied.files.ref; denied.files.denied = true
        assertFailsWith<CloudFailure> { denied.prepare(file) }; assertEquals(0, denied.writes.size)
        val h = Harness(this); val close = Source(original, closeError = true)
        val failure = assertFailsWith<CloudFailure> { h.api.importRecords(h.scope, close, original.size.toLong(), hash.sha256(original), false) }
        assertFalse(failure.toString().contains("private-source-marker")); assertEquals(1, close.closes)
        val cancelled = Source(original, beforeRead = { throw CancellationException("fixture-cancel") })
        assertFailsWith<CancellationException> { h.api.importRecords(h.scope, cancelled, original.size.toLong(), hash.sha256(original), false) }
        assertEquals(1, cancelled.closes); assertEquals(0, h.writes.size)
    }
    @Test fun readOnlyExportRemainsAvailableDuringUnknownImportButStaleReadContextAbortsLocalTarget() = runTest {
        val h = Harness(this); val file = h.files.ref; val p = h.prepare(file); h.throwWrite = true
        h.controller.submitImport(h.scope, file, false, h.confirm(p)); h.throwWrite = false; h.requests.clear()
        h.controller.exportRecords(h.scope, DnsExportTarget("target", "zone.bind"))
        assertEquals(0, h.writes.size)
        h.onDownload = { h.resolver.live = h.resolver.live!!.copy(credentialRevision = 2) }
        assertFailsWith<CloudFailure> { h.controller.exportRecords(h.scope, DnsExportTarget("next", "zone.bind")) }
        assertTrue(h.files.transactions.last().aborted)
    }
    @Test fun defaultUnknownAuthorityRefusesImportAndClosesSuppliedSource() = runTest {
        val h = Harness(this); val source = Source(original)
        val api = CloudflareDnsFiles(connection, CloudflareGateway(h.transport), h.transfer, hash, h.limiter, h.dispatcher)
        assertFailsWith<CloudFailure> { api.importRecords(zone, source, original.size.toLong(), hash.sha256(original), false) }
        assertEquals(1, source.closes); assertEquals(0, h.writes.size)
    }
}
