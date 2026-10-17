package com.flarego.core.cloudflare.dns

import com.flarego.core.application.dns.*
import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.cloudflare.zones.CloudflareZoneClient
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Credential-bound P1 gateway adapter. No credential, provider body or payload is logged/persisted. */
class CloudflareDnsClient private constructor(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    private val constraints: (ZoneScope) -> DnsConstraints,
    private val quota: DnsBatchQuotaAuthority?,
) : DnsBatchManagementSession {
    constructor(connection:Connection,gateway:CloudflareGateway,constraints:(ZoneScope)->DnsConstraints={DnsConstraints()}):this(connection,gateway,constraints,null)
    internal constructor(connection:Connection,gateway:CloudflareGateway,quota:DnsBatchQuotaAuthority,constraints:(ZoneScope)->DnsConstraints={DnsConstraints()}):this(connection,gateway,constraints,quota)
    private val batches=CloudflareDnsBatch(this,gateway,constraints,::batchMaxOperations)
    override fun batchMaxOperations(scope:ZoneScope):Int { ScopeGuard.requireOwned(scope,connection);return quota?.maxOperations(scope)?:200 }
    override suspend fun batch(scope:ZoneScope,input:DnsBatchInput):DnsBatchOutcome { val frozen=batchFreeze(input);batches.observe(scope,frozen);return writeBatch(scope,frozen) {} }
    override suspend fun observeBatch(scope:ZoneScope,input:DnsBatchInput)=batches.observe(scope,input)
    override suspend fun writeBatch(scope:ZoneScope,input:DnsBatchInput,beforeSend:(DnsBatchSnapshot)->Unit)=batches.write(scope,input,beforeSend)
    override suspend fun zone(scope: ZoneScope): ZoneDetails =
        requireDnsZone(CloudflareZoneClient(connection, gateway).get(scope), scope)

    override suspend fun list(scope: ZoneScope, filter: DnsFilter, page: PageRequest): CloudPage<DnsRecordDocument> {
        ScopeGuard.requireOwned(scope, connection)
        // Pinned list parameters are scalar strings. Repeating tag or inventing
        // tags[]=... has no proven serialization semantics in this schema.
        if (filter.tags.size > 1) throw dnsUnsupported("固定 Schema 的 tag 查询为单值；多个标签组合请使用官方控制台")
        if (page.limit !in 1..5_000_000 || page.token is PageToken.Cursor) throw invalidInputFailure()
        val filters = listOf("name.exact" to filter.name, "type" to filter.type, "content.exact" to filter.content,
            "comment.exact" to filter.comment, "tag" to filter.tags.singleOrNull()).filter { it.second != null }
        val query = filters.joinToString("&") { (key, value) -> "$key=${encodePath(value!!)}" }
        val strategy = PaginationStrategy.Number((page.token as? PageToken.Number)?.value ?: 1, page.limit)
        val owner = zone(scope)
        val path = "${collection(scope)}?" + (if (query.isEmpty()) "" else "$query&") +
            "match=all&tag_match=all&${Pagination.query(strategy)}"
        val root = readEnvelope(gateway, "dns-records-for-a-zone-list-dns-records", path)
        val rows = root["result"] as? JsonArray ?: throw dnsInvalidResponse()
        (root["result_info"] as? JsonObject)?.get("page")?.let {
            if (it !is JsonPrimitive || it.isString || it.intOrNull != strategy.page) throw dnsInvalidResponse()
        }
        val items = rows.map { decode(it as? JsonObject ?: throw dnsInvalidResponse(), scope, null, owner.name) }
        if (items.map { it.id }.distinct().size != items.size) throw dnsInvalidResponse()
        val metadata = Pagination.decode(root, strategy)
        return CloudPage(items, metadata.next, metadata.total)
    }
    override suspend fun get(scope: ZoneScope, recordId: String): DnsRecordDocument {
        ScopeGuard.requireOwned(scope, connection)
        if (recordId.isBlank()) throw invalidInputFailure()
        val owner = zone(scope)
        return readRecord(scope, recordId, owner.name)
    }
    override suspend fun create(scope: ZoneScope, input: DnsRecordInput): DnsRecordDocument =
        perform(DnsWriteAction(scope, null, input, DnsWriteMode.CREATE), false) {}.document ?: throw dnsUnknown()

    override suspend fun patch(scope: ZoneScope, recordId: String, fields: JsonObject): DnsRecordDocument {
        // Raw patch callers must resolve the current type/name first, but only
        // fields actually supplied by them enter the transport payload.
        val frozen = dnsCopy(fields)
        val current = get(scope, recordId)
        val type = frozen["type"]?.let { dnsString(it) } ?: dnsText(current.raw, "type")
        val name = frozen["name"]?.let { dnsString(it) } ?: dnsText(current.raw, "name")
        val input = DnsRecordInput(type, name, JsonObject(frozen - setOf("type", "name")))
        return perform(DnsWriteAction(scope, recordId, input, DnsWriteMode.PATCH), false) {}.document ?: throw dnsUnknown()
    }
    override suspend fun replace(scope: ZoneScope, recordId: String, input: DnsRecordInput): DnsRecordDocument =
        perform(DnsWriteAction(scope, recordId, input, DnsWriteMode.REPLACE), false) {}.document ?: throw dnsUnknown()

    override suspend fun delete(scope: ZoneScope, recordId: String) {
        val result = perform(DnsWriteAction(scope, recordId, null, DnsWriteMode.DELETE), false) {}
        if (result.receipt !is ExecutionReceipt.Completed) throw dnsUnknown()
    }
    override suspend fun write(action: DnsWriteAction, beforeSend: (DnsRecordDocument?) -> Unit): ExecutionReceipt =
        perform(action, true, beforeSend).receipt

    private data class Result(val document: DnsRecordDocument?, val receipt: ExecutionReceipt)
    private suspend fun perform(requested: DnsWriteAction, guarded: Boolean, beforeSend: (DnsRecordDocument?) -> Unit): Result {
        val action = requested.copy(input = requested.input?.let { it.copy(fields = dnsCopy(it.fields)) })
        requireDnsActionShape(action.recordId, action.input, action.mode)
        ScopeGuard.requireOwned(action.scope, connection)
        val owner = zone(action.scope)
        val observed = action.recordId?.let { readRecord(action.scope, it, owner.name) }
        val validated = action.input?.let { dnsWriteInput(owner.name, observed, it, action.mode, constraints(action.scope), allowNoop = guarded) }
        val payload = validated?.let {
            if (action.mode != DnsWriteMode.PATCH) dnsPayload(it)
            else JsonObject(it.fields + if (it.name != dnsText(observed!!.raw, "name").lowercase().removeSuffix(".")) mapOf("name" to JsonPrimitive(it.name)) else emptyMap())
        }
        val expected = when (action.mode) {
            DnsWriteMode.CREATE, DnsWriteMode.REPLACE -> dnsSemanticRecord(dnsPayload(validated!!))
            DnsWriteMode.PATCH -> dnsSemanticRecord(JsonObject(observed!!.raw + payload!!))
            DnsWriteMode.DELETE -> null
        }
        val method = when (action.mode) { DnsWriteMode.CREATE -> "POST"; DnsWriteMode.PATCH -> "PATCH"; DnsWriteMode.REPLACE -> "PUT"; DnsWriteMode.DELETE -> "DELETE" }
        val request = HttpRequest(method, action.recordId?.let { locator(action.scope, it) } ?: collection(action.scope), payload?.toString())
        // No suspension, session acquisition, ownership read or product callback
        // intervenes between this bound guard and the actual gateway send.
        beforeSend(observed)
        return try {
            val response = gateway.send(dnsActionKey(action.mode), request)
            val root = ResponseDecoders.envelope(response, writing = true)
            if (root["success"] != JsonPrimitive(true) || response.status == 202) throw dnsUnknown()
            val result = root["result"] as? JsonObject ?: throw dnsUnknown()
            val id = dnsText(result, "id")
            if (action.recordId != null && id != action.recordId) throw dnsUnknown()
            if (action.mode == DnsWriteMode.DELETE) {
                try { get(action.scope, id); Result(null, ExecutionReceipt.Unknown()) }
                catch (failure: CloudFailure) {
                    if (failure.code == CloudFailureCode.NOT_FOUND) Result(null, ExecutionReceipt.Completed()) else throw failure
                }
            } else {
                // A real provider id is mandatory. Completion is based on a fresh
                // ownership-checked detail read, never a local generated id.
                val actual = get(action.scope, id)
                if (dnsSemanticRecord(actual.raw) != expected) throw dnsUnknown()
                Result(actual, ExecutionReceipt.Completed())
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result(null, ExecutionReceipt.Unknown()) }
    }
    private suspend fun readRecord(scope: ZoneScope, id: String, zoneName: String): DnsRecordDocument {
        val root = readEnvelope(gateway, "dns-records-for-a-zone-dns-record-details", locator(scope, id))
        return decode(root["result"] as? JsonObject ?: throw dnsInvalidResponse(), scope, id, zoneName)
    }
    private fun decode(raw: JsonObject, scope: ZoneScope, expectedId: String?, zoneName: String): DnsRecordDocument {
        val id = dnsText(raw, "id")
        return requireDnsDocument(DnsRecordDocument(id, scope, raw), scope, expectedId ?: id, zoneName)
    }
}

private fun collection(scope: ZoneScope) = "/zones/${encodePath(scope.zoneId)}/dns_records"
private fun locator(scope: ZoneScope, id: String) = "${collection(scope)}/${encodePath(id)}"
private fun dnsString(value: JsonElement) = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalidInputFailure()
private fun dnsUnknown() = CloudFailure(CloudFailureCode.UNKNOWN_RESULT, "DNS 操作结果尚未确认，请只读核对云端状态，不要重复发送", uncertain = true)
