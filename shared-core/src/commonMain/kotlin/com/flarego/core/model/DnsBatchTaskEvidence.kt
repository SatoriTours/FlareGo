package com.flarego.core.model

import com.flarego.core.application.operations.CanonicalJson
import com.flarego.core.cloudflare.generated.DnsSchemas
import com.flarego.core.ports.FingerprintPort
import kotlinx.serialization.json.*

/** Safe read locators and hashes only. This cannot recreate an executable request. */
data class DnsBatchItemEvidence(
    val kind: DnsBatchKind, val index: Int, val recordId: String?,
    val createType: String?, val createName: String?, val desiredHash: String,
    val beforeHash: String?, val providerId: String? = null,
    val creationPreconditionVersion: Int? = null,
)
data class DnsBatchTaskEvidence(
    val version: Int, val taskId: String, val scope: ZoneScope,
    val counts: List<Int>, val items: List<DnsBatchItemEvidence>, val manifestHash: String,
    val transaction: DnsBatchTransaction = DnsBatchTransaction.UNKNOWN,
)

/** Provider IDs/receipt/baselines are observations, excluded from desired write semantics. */
fun dnsBatchManifestHash(scope: ZoneScope, items: List<DnsBatchItemEvidence>, fingerprint: FingerprintPort): String =
    fingerprint.sha256(CanonicalJson.encode(buildJsonObject {
        put("connection",scope.connectionId); put("account",scope.account.accountId); put("zone",scope.zoneId)
        put("items",buildJsonArray { items.forEach { item -> add(buildJsonObject {
            put("kind",item.kind.name);put("index",item.index)
            put("record_id",item.recordId?.let(::JsonPrimitive) ?: JsonNull)
            put("create_type",item.createType?.let(::JsonPrimitive) ?: JsonNull)
            put("create_name",item.createName?.let(::JsonPrimitive) ?: JsonNull)
            put("desired_hash",item.desiredHash)
        }) } })
    }))

/** Throws before storage/send; invalid stored evidence must never authorize recovery. */
fun DnsBatchTaskEvidence.requireValid(task: OperationTask, fingerprint: FingerprintPort) {
    fun valid(ok: Boolean) { if (!ok) throw invalidInputFailure() }
    fun hash(s: String) = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }
    fun id(s: String) = validDnsBatchProviderId(s)
    valid(version == 1 && taskId == task.id && scope == task.scope && task.connectionId == scope.connectionId)
    valid(task.actionKey == "dns-records-for-a-zone-batch-dns-records" && task.resourceRef == ResourceRef(scope,"dns_batch",scope.zoneId))
    valid(counts.size == 4 && counts.all { it in 0..3500 } && counts.sum() in 1..3500 && counts.sum() == items.size)
    valid(hash(manifestHash) && manifestHash == task.desiredHash)
    val existing = mutableSetOf<String>(); val providers = mutableSetOf<String>(); val creations = mutableSetOf<Triple<String,String,String>>()
    var ordinal = 0
    for (kind in DnsBatchKind.entries) repeat(counts[kind.ordinal]) { index ->
        val item = items[ordinal++]
        valid(item.kind == kind && item.index == index && hash(item.desiredHash))
        item.beforeHash?.let { valid(hash(it)) }
        if (kind == DnsBatchKind.POST) {
            valid(item.recordId == null && item.beforeHash == null && item.creationPreconditionVersion == 1)
            val type = item.createType ?: throw invalidInputFailure()
            val name = item.createName ?: throw invalidInputFailure()
            valid(type in DnsSchemas.types && name.length in 1..253 && name == name.lowercase() && !name.endsWith("."))
            valid(name.split('.').all { label -> label.length in 1..63 && label.all { it in 'a'..'z' || it in '0'..'9' || it in "_-*" } && (label == "*" || '*' !in label) })
            valid(creations.add(Triple(type,name,item.desiredHash)))
        } else {
            val record = item.recordId ?: throw invalidInputFailure()
            valid(id(record) && existing.add(record) && item.createType == null && item.createName == null && item.creationPreconditionVersion == null && item.beforeHash != null)
            valid(item.providerId == null || item.providerId == record)
        }
        item.providerId?.let { valid(id(it) && providers.add(it)) }
    }
    // A create response may never alias an existing target, even if that target's response was lost.
    valid(items.filter { it.kind == DnsBatchKind.POST }.none { it.providerId in existing })
    if(transaction==DnsBatchTransaction.CONFIRMED_COMMIT) valid(items.all { it.providerId!=null })
    if(transaction==DnsBatchTransaction.CONFIRMED_REJECT) valid(items.all { it.providerId==null })
    valid(dnsBatchManifestHash(scope,items,fingerprint) == manifestHash)
}

/** Opaque IDs remain verbatim. Reject only length/empty/unsafe path text and malformed Unicode. */
internal fun validDnsBatchProviderId(value:String):Boolean {
    if(value.isBlank() || value.length>32 || value in setOf(".","..") || value.any { it.code<0x20 || it.code in 0x7f..0x9f || it=='/' || it=='\\' }) return false
    return value.indices.all { index -> when(value[index].code) {
        in 0xd800..0xdbff -> index+1<value.length && value[index+1].code in 0xdc00..0xdfff
        in 0xdc00..0xdfff -> index>0 && value[index-1].code in 0xd800..0xdbff
        else -> true
    } }
}
