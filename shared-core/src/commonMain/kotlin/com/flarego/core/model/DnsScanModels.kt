package com.flarego.core.model

import com.flarego.core.application.operations.*
import com.flarego.core.application.dns.*
import com.flarego.core.cloudflare.generated.DnsSchemas
import com.flarego.core.ports.FingerprintPort
import kotlinx.serialization.json.*

const val DNS_SCAN_REVIEW_OPERATION = "dns-records-for-a-zone-review-dns-scan"
const val DNS_SCAN_APPLY_OPERATION = "dns-records-for-a-zone-apply-dns-scan-results"
const val DNS_SCAN_TRIGGER_OPERATION = "dns-records-for-a-zone-trigger-dns-scan"
const val DNS_USAGE_OPERATION = "dns-records-for-a-zone-get-usage"
data class DnsScanCandidate(val id: String, val record: DnsRecordDocument, val selectionHash: String)
data class DnsScanReviewInput(val acceptIds: List<String>, val rejectIds: List<String>, val expectedCandidateHashes: Map<String, String>)
sealed interface DnsScanAction {
    val scope: ZoneScope
    data class Trigger(override val scope: ZoneScope) : DnsScanAction
    data class Apply(override val scope: ZoneScope, val input: DnsScanReviewInput) : DnsScanAction
}
data class DnsScanSnapshot(val owner: ZoneDetails, val candidates: List<DnsScanCandidate>, val writeAllowed: Boolean?, val constraints: DnsConstraints = DnsConstraints())
data class DnsScanApplyOutcome(val receipt: ExecutionReceipt, val accepted: List<DnsRecordDocument> = emptyList(), val rejected: List<String> = emptyList())
data class DnsScanPreview(val scope: ZoneScope, val domain: String, val acceptIds: List<String>, val rejectIds: List<String>, val records: List<DnsRecordDocument>, val warnings: List<String>, val candidateHashes: Map<String,String> = emptyMap())
data class DnsUsage(val scope: ZoneScope, val recordUsage: Long, val recordQuota: Long?, val raw: kotlinx.serialization.json.JsonObject)
internal fun dnsScanUnavailable() = CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, "DNS 扫描与设置后端尚未实现，当前不能发送")

internal fun t7Scope(scope: ZoneScope) {
    if (scope.zoneId.isBlank() || scope.zoneId.length > 32) throw invalidInputFailure()
}
internal fun t7Id(id: String): String = id.takeIf { it.isNotBlank() && it.length <= 32 } ?: throw dnsInvalidResponse()
internal fun t7Key(action: DnsScanAction) = if (action is DnsScanAction.Trigger) DNS_SCAN_TRIGGER_OPERATION else DNS_SCAN_APPLY_OPERATION
internal fun t7Freeze(action: DnsScanAction): DnsScanAction = when (action) {
    is DnsScanAction.Trigger -> action.copy()
    is DnsScanAction.Apply -> action.copy(input = action.input.copy(acceptIds=action.input.acceptIds.toList(), rejectIds=action.input.rejectIds.toList(), expectedCandidateHashes=action.input.expectedCandidateHashes.toMap()))
}
internal fun t7Selection(input: DnsScanReviewInput) {
    val ids=input.acceptIds+input.rejectIds
    if(ids.isEmpty() || ids.distinct().size!=ids.size || ids.any { it.isBlank() || it.length>32 } ||
        input.expectedCandidateHashes.keys!=ids.toSet() || input.expectedCandidateHashes.values.any { !Regex("[0-9a-f]{64}").matches(it) }) throw invalidInputFailure()
}
internal fun t7CandidateHash(scope: ZoneScope, raw: JsonObject, hash: FingerprintPort) = hash.sha256(CanonicalJson.encode(buildJsonObject {
    put("scope",dnsScopeFields(scope));put("candidate",raw)
}))
internal fun t7Candidate(raw: JsonObject, owner: ZoneDetails, hash: FingerprintPort): DnsScanCandidate {
    val id=t7Id(dnsText(raw,"id"))
    val doc=requireDnsDocument(DnsRecordDocument(id,owner.ref,raw),owner.ref,id,owner.name)
    return DnsScanCandidate(id,doc,t7CandidateHash(owner.ref,doc.raw,hash))
}
internal fun t7Checked(snapshot: DnsScanSnapshot, scope: ZoneScope, hash: FingerprintPort): DnsScanSnapshot {
    val owner=requireDnsZone(snapshot.owner,scope)
    val rows=snapshot.candidates.map {
        val actual=t7Candidate(it.record.raw,owner,hash)
        if(it.id!=actual.id || it.record.scope!=scope || it.record.id!=actual.id || it.selectionHash!=actual.selectionHash) throw scopeMismatchFailure()
        actual
    }
    if(rows.map { it.id }.distinct().size!=rows.size) throw dnsInvalidResponse()
    return snapshot.copy(owner=owner.copy(raw=dnsCopy(owner.raw)),candidates=rows,constraints=snapshot.constraints.copy(proxyTypes=snapshot.constraints.proxyTypes.toSet()))
}
internal fun t7Writable(allowed: Boolean?) {
    if(allowed!=true) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED,"当前Token的目标Zone写权限未知或不可用，请先验证权限")
}
private val scanReadonly=setOf("id","meta","proxiable","created_on","modified_on","comment_modified_on","tags_modified_on","zone_id","zone_name")
internal fun t7Accept(record: DnsRecordDocument, zoneName: String, constraints: DnsConstraints): JsonObject {
    val raw=record.raw;val type=dnsText(raw,"type").uppercase()
    val schema=DnsSchemas.forType(type,DnsWriteMethod.PATCH) ?: throw dnsUnsupported("未来DNS类型仅供查看，接受候选需要needs_review")
    val properties=LocalDnsSchema(schema).properties().keys
    val extra=raw.keys-properties-scanReadonly-(if("data" in properties && "content" !in properties) setOf("content") else emptySet())
    if(extra.isNotEmpty()) throw dnsUnsupported("候选包含未知可能可写字段，接受需要needs_review")
    val input=DnsRecordInput(type,dnsText(raw,"name"),JsonObject(raw.filterKeys { it in properties && it !in setOf("name","type") }))
    try {
        // Official scan accepts uses PATCH shape. POST validation only adds product completeness rules.
        validateDnsInput(zoneName,input,constraints,DnsWriteMethod.POST)
        val normalized=validateDnsInput(zoneName,input,constraints,DnsWriteMethod.PATCH)
        return dnsPayload(normalized)
    } catch (_: CloudException) { throw CloudFailure(CloudFailureCode.INVALID_INPUT,"候选记录不能安全投影为完整的官方可写记录，请重新审核") }
}
internal fun t7ApplyPayload(snapshot: DnsScanSnapshot, input: DnsScanReviewInput, constraints: DnsConstraints): JsonObject {
    t7Selection(input)
    val selected=(input.acceptIds+input.rejectIds).associateWith { id -> snapshot.candidates.singleOrNull { it.id==id } ?: throw dnsConflict() }
    if(selected.any { (id,row) -> input.expectedCandidateHashes[id]!=row.selectionHash }) throw dnsConflict()
    val accepts=input.acceptIds.map { id ->
        val candidate=selected.getValue(id);val wire=t7Accept(candidate.record,snapshot.owner.name,constraints)
        val semantic=dnsSemanticRecord(wire)
        if(snapshot.candidates.any { other -> other.id!=id && runCatching { dnsSemanticRecord(other.record.raw)==semantic }.getOrDefault(false) })
            throw CloudFailure(CloudFailureCode.CONFLICT,"候选内容相同但身份有歧义，无法安全按选择接受，请使用官方控制台审核")
        wire
    }
    return buildJsonObject {
        if(accepts.isNotEmpty()) put("accepts",JsonArray(accepts))
        if(input.rejectIds.isNotEmpty()) put("rejects",JsonArray(input.rejectIds.map { buildJsonObject { put("id",it) } }))
    }
}
