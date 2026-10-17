package com.flarego.core.cloudflare.dns

import com.flarego.core.application.dns.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.cloudflare.zones.CloudflareZoneClient
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class CloudflareDnsFiles(private val connection: Connection, private val gateway: CloudflareGateway,
    private val transfers: TransferPort, private val fingerprint: FingerprintPort, private val limiter: DnsImportLimiter,
    private val ioDispatcher: CoroutineDispatcher, private val authority: DnsFilesAuthority = object : DnsFilesAuthority {}) : DnsFilesSession {
    override suspend fun observe(scope: ZoneScope): DnsFileImportState {
        requireScope(scope)
        return DnsFileImportState(CloudflareZoneClient(connection, gateway).get(scope), authority.mayImport(scope))
    }
    override suspend fun readback(scope: ZoneScope): DnsImportReadback {
        requireScope(scope)
        val page = CloudflareDnsClient(connection, gateway).list(scope, DnsFilter(), PageRequest(20))
        return DnsImportReadback(scope, page.items.size, page.total)
    }
    override suspend fun importRecords(scope: ZoneScope, source: ByteSource, length: Long, fingerprint: String, proxied: Boolean): DnsImportOutcome {
        val owned = DnsOwnedSource(source)
        try { return writeImport(scope, { owned }, length, fingerprint, proxied, {}, {}) }
        finally { withContext(NonCancellable + ioDispatcher) { try { owned.close() } catch (_: Exception) { throw dnsFileIoFailure() } } }
    }
    override suspend fun writeImport(scope: ZoneScope, source: suspend () -> ByteSource, length: Long, fingerprint: String, proxied: Boolean,
        beforeSend: (DnsFileImportState) -> Unit, afterRead: () -> Unit): DnsImportOutcome {
        val before = observe(scope)
        if (before.writeAllowed != true) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "DNS 导入写权限未知或不可用，请先验证当前 Token")
        val snapshot = readDnsImport(source(), length, fingerprint, this.fingerprint, ioDispatcher)
        val upload = DnsOwnedSource(snapshot.source())
        try {
            val request = HttpRequest("POST", path(scope, "import"), HttpBody.Multipart(listOf(
                MultipartPart("file", "zone.bind", "text/plain", HttpBody.Stream(upload, snapshot.length, "text/plain")),
                MultipartPart("proxied", contentType = "text/plain", body = HttpBody.Text(proxied.toString(), "text/plain")),
            )))
            requireRegistered(DNS_IMPORT_OPERATION, request)
            limiter.acquire() // Non-suspending admission; conservatively never refunded even if final guard rejects.
            beforeSend(before) // Last non-suspending check: all fallible preflight, including quota, precedes this.
            return try {
                val response = gateway.send(DNS_IMPORT_OPERATION, request)
                if (response.status != 200) return DnsImportOutcome.Unknown(if (response.status == 429) CloudFailureCode.RATE_LIMITED else CloudFailureCode.UNKNOWN_RESULT)
                val root = ResponseDecoders.envelope(response, true)
                val success = (root["success"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                val errors = root["errors"] as? JsonArray ?: return DnsImportOutcome.Unknown()
                if (root["messages"] !is JsonArray || success != true) return DnsImportOutcome.Unknown()
                val result = root["result"] as? JsonObject ?: return DnsImportOutcome.Unknown()
                for (key in listOf("zone_id", "id")) if (result.containsKey(key) && result[key] != JsonPrimitive(scope.zoneId)) return DnsImportOutcome.Unknown()
                val added = count(result["recs_added"]) ?: return DnsImportOutcome.Unknown()
                val parsed = count(result["total_records_parsed"]) ?: return DnsImportOutcome.Unknown()
                if (added > parsed) return DnsImportOutcome.Unknown()
                val observed = try { readback(scope) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                afterRead()
                when {
                    added > 0 && (errors.isNotEmpty() || added < parsed) -> DnsImportOutcome.Partial(added, parsed, observed)
                    errors.isNotEmpty() || added != parsed || observed == null -> DnsImportOutcome.Unknown()
                    else -> DnsImportOutcome.ReportedSuccess(added, parsed, observed)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { DnsImportOutcome.Unknown() }
        } finally { withContext(NonCancellable + ioDispatcher) { runCatching { upload.close() } } }
    }
    override suspend fun exportRecords(scope: ZoneScope, sink: ByteSink): HttpResponseHead {
        val checked = ExportSink(sink)
        var failed = false
        try {
            requireScope(scope)
            CloudflareZoneClient(connection, gateway).get(scope) // Independent owner proof; no import authority probe.
            val request = HttpRequest("GET", path(scope, "export"))
            requireRegistered(DNS_EXPORT_OPERATION, request)
            val head = transfers.download(request, checked)
            if (head.status != 200) throw CloudFailure(if (head.status == 403) CloudFailureCode.PERMISSION_REQUIRED else CloudFailureCode.INVALID_RESPONSE, "DNS 导出响应未被确认，请检查权限后重新导出")
            val types = head.headers.filterKeys { it.equals("Content-Type", true) }.values.flatten()
            if (types.size != 1 || types.single().substringBefore(';').trim().lowercase() != "text/plain" || head.transferredBytes != checked.count) throw exportInvalid()
            return head
        } catch (cancel: CancellationException) { failed = true; throw cancel }
        catch (failure: CloudFailure) { failed = true; throw failure }
        catch (_: Exception) { failed = true; throw dnsFileIoFailure() }
        finally {
            try { withContext(NonCancellable + ioDispatcher) { checked.close() } }
            catch (_: Exception) { if (!failed) throw dnsFileIoFailure() }
        }
    }
    private fun requireRegistered(key: String, request: HttpRequest) {
        val entry = ActionPolicies.registry.registration(key) ?: throw invalidInputFailure()
        if (!entry.matches(request.method, request.path)) throw invalidInputFailure()
    }
    private fun requireScope(scope: ZoneScope) {
        if (scope.zoneId.length > 32) throw invalidInputFailure()
        ScopeGuard.requireOwned(scope, connection)
    }
    private fun count(value: JsonElement?): Long? {
        val p = (value as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
        p.longOrNull?.let { return it.takeIf { n -> n >= 0 } }
        val number = p.doubleOrNull ?: return null
        return if (number.isFinite() && number >= 0 && number < Long.MAX_VALUE.toDouble() && number.toLong().toDouble() == number) number.toLong() else null
    }
}
private fun path(scope: ZoneScope, mode: String) = "/zones/${encodePath(scope.zoneId)}/dns_records/$mode"
private fun exportInvalid() = CloudFailure(CloudFailureCode.INVALID_RESPONSE, "导出内容不是可确认的官方 BIND 文件，请重新导出")

/** Bounded prefix screening plus exact byte forwarding; final Content-Type is still checked before commit. */
@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
private class ExportSink(private val sink: ByteSink) : ByteSink {
    private val closed = kotlin.concurrent.atomics.AtomicReference(false)
    private val prefix = mutableListOf<Byte>()
    private var accepted = false
    private var rejected = false
    var count = 0L; private set
    override fun write(buffer: ByteArray, offset: Int, count: Int) {
        if (rejected) throw exportInvalid()
        if (accepted) { sink.write(buffer, offset, count); this.count += count; return }
        for (i in offset until offset + count) {
            prefix += buffer[i]
            if (prefix.size > 4096) { rejected = true; throw exportInvalid() }
            val text = prefix.toByteArray().decodeToString().removePrefix("\uFEFF").trimStart()
            val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            if (text.isEmpty() || (prefix.size < 3 && prefix.indices.all { prefix[it] == bom[it] })) continue
            if (text.first() in setOf('{', '[', '<')) { rejected = true; throw exportInvalid() }
            accepted = true
            val bytes = prefix.toByteArray(); sink.write(bytes, 0, bytes.size); this.count += bytes.size; prefix.clear()
            val rest = offset + count - i - 1
            if (rest > 0) { sink.write(buffer, i + 1, rest); this.count += rest }
            return
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { if (!rejected && prefix.isNotEmpty()) { val bytes = prefix.toByteArray(); sink.write(bytes, 0, bytes.size); count += bytes.size; prefix.clear() } }
        finally { sink.close() }
    }
}
