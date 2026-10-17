package com.flarego.core.model

import kotlinx.serialization.json.JsonObject

data class DnsBatchPatch(val id: String, val fields: JsonObject)
data class DnsBatchPut(val id: String, val input: DnsRecordInput)
data class DnsBatchInput(
    val deletes: List<String> = emptyList(), val patches: List<DnsBatchPatch> = emptyList(),
    val puts: List<DnsBatchPut> = emptyList(), val posts: List<DnsRecordInput> = emptyList(),
)
enum class DnsBatchKind { DELETE, PATCH, PUT, POST }
enum class DnsBatchTransaction { CONFIRMED_COMMIT, CONFIRMED_REJECT, UNKNOWN }
enum class DnsBatchVerification { MATCHED, NOT_APPLIED, UNKNOWN, CONFLICT }
data class DnsBatchItemRef(val kind: DnsBatchKind, val index: Int,
    val recordId: String? = null, val createType: String? = null, val createName: String? = null)
data class DnsBatchItemResult(val ref: DnsBatchItemRef, val providerId: String?, val verification: DnsBatchVerification)
data class DnsBatchOutcome(val transaction: DnsBatchTransaction, val items: List<DnsBatchItemResult>, val pending: List<DnsBatchItemRef>, val currentStateVerified: Boolean = false)
/** Trusted adapter observation; never a UI request body. */
data class DnsBatchSnapshot(val owner: ZoneDetails, val records: Map<String, DnsRecordDocument>,
    val postCandidates: List<List<DnsRecordDocument>>, val maxOperations: Int = 200)
internal fun batchUnavailable() = CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,"批量 DNS 尚未接入，当前未发送操作")

/** Only a trusted session/application authority can construct paid-quota evidence; never a draft/UI claim. */
internal data class VerifiedDnsBatchQuota(val scope:ZoneScope,val credentialRevision:Long,val expiresAtMs:Long)
internal class DnsBatchQuotaAuthority(private val evidence:(ZoneScope)->VerifiedDnsBatchQuota?,private val revision:()->Long,private val nowMs:()->Long) {
    fun maxOperations(scope:ZoneScope):Int = try {
        val proof=evidence(scope)
        val now=nowMs();val currentRevision=revision()
        if(proof!=null && proof.scope==scope && currentRevision>=0 && proof.credentialRevision==currentRevision && now>=0 && now<proof.expiresAtMs) 3500 else 200
    } catch(_:Exception) { 200 }
}
