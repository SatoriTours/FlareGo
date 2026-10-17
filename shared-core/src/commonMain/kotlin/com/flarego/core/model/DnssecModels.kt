package com.flarego.core.model

import kotlinx.serialization.json.*

/** Read-only provider data. A returned DS is not evidence of the registrar's current DS. */
data class DnssecDetails(val scope: ZoneScope, val status: String?, val ds: String?, val digest: String?,
    val algorithm: String?, val keyTag: Double?, val multiSigner: Boolean?, val presigned: Boolean?, val useNsec3: Boolean?,
    val raw: JsonObject)

/** Null omits a property; false is explicit. Fixed PATCH has four scalar optional properties. */
data class DnssecUpdateInput(val status: String? = null, val multiSigner: Boolean? = null,
    val presigned: Boolean? = null, val useNsec3: Boolean? = null) {
    fun fields(): JsonObject {
        if (status != null && status !in setOf("active", "disabled")) throw invalidInputFailure()
        val result = buildJsonObject {
            status?.let { put("status", it) }
            multiSigner?.let { put("dnssec_multi_signer", it) }
            presigned?.let { put("dnssec_presigned", it) }
            useNsec3?.let { put("dnssec_use_nsec3", it) }
        }
        if (result.isEmpty()) throw invalidInputFailure()
        return result
    }
    companion object {
        fun fromFields(fields: JsonObject): DnssecUpdateInput {
            if (fields.isEmpty() || fields.keys.any { it !in dnssecInputNames }) throw invalidInputFailure()
            fun bool(name: String): Boolean? = fields[name]?.let {
                (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull ?: throw invalidInputFailure()
            }
            val status = fields["status"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw invalidInputFailure() }
            return DnssecUpdateInput(status, bool("dnssec_multi_signer"), bool("dnssec_presigned"), bool("dnssec_use_nsec3")).also { it.fields() }
        }
    }
}

data class DnssecKeyHeader(val name: String?, val recordClass: Long?, val recordType: Long?, val ttl: Long?, val rdlength: Long?)
data class DnssecDnskey(val algorithm: Long?, val flags: Long?, val protocol: Long?, val publicKey: String?, val header: DnssecKeyHeader?)
/** Encrypted private key and encryption-key names are excluded from this public model/raw. */
data class DnssecKey(val scope: ZoneScope, val name: String?, val location: String?, val tag: String?,
    val dnskey: DnssecDnskey?, val signingPublicKey: String?, val raw: JsonObject)

enum class DnssecMutation(val operationId: String) {
    UPDATE("dnssec-edit-dnssec-status"), DELETE("dnssec-delete-dnssec-records")
}
sealed interface DnssecAction {
    val scope: ZoneScope
    data class Update(override val scope: ZoneScope, val input: DnssecUpdateInput) : DnssecAction
    data class Delete(override val scope: ZoneScope) : DnssecAction
}
interface DnssecAuthority {
    /** Verified credential/scope/operation write permission; not read access or local user consent. */
    fun mayWrite(scope: ZoneScope, mutation: DnssecMutation): Boolean? = null
    /** Supplier-verified Enterprise NSEC3 availability for this scope/credential. Unknown never enables it. */
    fun nsec3Available(scope: ZoneScope): Boolean? = null
}
data class DnssecSnapshot(val owner: ZoneDetails, val details: DnssecDetails, val writeAllowed: Boolean?, val nsec3Available: Boolean? = null)
enum class DnssecRisk { DISABLE, DELETE, CHANGE_MULTI_SIGNER, CHANGE_PRESIGNED, CHANGE_NSEC3 }
class DnssecRiskAcknowledgement internal constructor(val planId: String, val risk: DnssecRisk)
data class DnssecPreview(val scope: ZoneScope, val domain: String, val mutation: DnssecMutation, val risks: Set<DnssecRisk>,
    val registrarWarning: String, val impact: String, val concurrencyWarning: String)

internal val dnssecInputNames = setOf("status", "dnssec_multi_signer", "dnssec_presigned", "dnssec_use_nsec3")
internal val dnssecPendingStatuses = setOf("pending", "pending-disabled")
/** Fixed dnssec_identifier maxLength is 32 Unicode characters, not UTF-16 code units. */
internal fun requireDnssecScope(scope: ZoneScope) {
    var index = 0
    var characters = 0
    while (index < scope.zoneId.length) {
        val char = scope.zoneId[index++]
        if (char in '\uD800'..'\uDBFF') {
            if (index >= scope.zoneId.length || scope.zoneId[index] !in '\uDC00'..'\uDFFF') throw invalidInputFailure()
            index++
        } else if (char in '\uDC00'..'\uDFFF') throw invalidInputFailure()
        if (++characters > 32) throw invalidInputFailure()
    }
}
internal fun dnssecMutation(action: DnssecAction) = if (action is DnssecAction.Update) DnssecMutation.UPDATE else DnssecMutation.DELETE
internal fun dnssecFields(action: DnssecAction) = when (action) {
    is DnssecAction.Update -> action.input.fields()
    is DnssecAction.Delete -> buildJsonObject { put("delete", true) }
}
internal fun dnssecRef(scope: ZoneScope) = ResourceRef(scope, "dnssec", scope.zoneId)
internal fun dnssecFreeze(raw: JsonObject) = Json.parseToJsonElement(raw.toString()).jsonObject
internal fun dnssecMatches(details: DnssecDetails, fields: JsonObject) = fields.all { (name, value) -> details.raw[name] == value }
internal fun requireDnssecWritable(action: DnssecAction, snapshot: DnssecSnapshot) {
    if (snapshot.writeAllowed != true) throw CloudFailure(
        if (snapshot.writeAllowed == false) CloudFailureCode.PERMISSION_REQUIRED else CloudFailureCode.UNSUPPORTED_CAPABILITY,
        if (snapshot.writeAllowed == false) "没有已验证的 DNSSEC 写权限" else "DNSSEC 写权限尚未验证，当前只读")
    if (snapshot.details.scope != action.scope || snapshot.owner.ref != action.scope) throw scopeMismatchFailure()
    if (snapshot.details.status !in setOf("active", "disabled", "pending", "pending-disabled")) throw invalidInputFailure()
    if (action is DnssecAction.Update) {
        val fields = action.input.fields()
        if (action.input.useNsec3 == true && snapshot.nsec3Available != true) throw CloudFailure(
            CloudFailureCode.UNSUPPORTED_CAPABILITY, "开启 NSEC3 需要已验证的 Enterprise 资格，当前资格未知或不可用")
        if (action.input.presigned == true && (snapshot.owner.type != "secondary" ||
            (snapshot.owner.raw["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "secondary")) throw CloudFailure(
            CloudFailureCode.UNSUPPORTED_CAPABILITY, "预签名 DNSSEC 仅可在供应商已确认的 secondary Zone 开启")
        // Unknown old values cannot establish a safe change; explicitly unchanged fields are refused.
        if (fields.any { (name, value) -> snapshot.details.raw[name] == null || snapshot.details.raw[name] == JsonNull || snapshot.details.raw[name] == value }) {
            throw invalidInputFailure()
        }
    }
}

internal fun decodeDnssecDetails(scope: ZoneScope, row: JsonObject, writing: Boolean): DnssecDetails {
    dnssecResponseScope(row, scope, writing)
    // Optional nullable public material stays null; never borrow a schema example.
    for (name in listOf("digest_algorithm", "digest_type", "key_type", "modified_on", "public_key")) dnssecText(row, name, true, writing)
    dnssecNumber(row, "flags", writing)
    return DnssecDetails(scope, dnssecText(row, "status", false, writing), dnssecText(row, "ds", true, writing),
        dnssecText(row, "digest", true, writing), dnssecText(row, "algorithm", true, writing), dnssecNumber(row, "key_tag", writing),
        dnssecBoolean(row, "dnssec_multi_signer", writing), dnssecBoolean(row, "dnssec_presigned", writing),
        dnssecBoolean(row, "dnssec_use_nsec3", writing), dnssecFreeze(row))
}

internal fun decodeDnssecKey(scope: ZoneScope, row: JsonObject): DnssecKey {
    dnssecResponseScope(row, scope, false)
    val dnskey = dnssecObject(row, "DNSKEY")?.let { key ->
        val header = dnssecObject(key, "Hdr")?.let { hdr -> DnssecKeyHeader(dnssecText(hdr, "Name", true, false),
            dnssecInteger(hdr, "Class"), dnssecInteger(hdr, "Rrtype"), dnssecInteger(hdr, "Ttl"), dnssecInteger(hdr, "Rdlength")) }
        DnssecDnskey(dnssecInteger(key, "Algorithm"), dnssecInteger(key, "Flags"), dnssecInteger(key, "Protocol"),
            dnssecText(key, "PublicKey", true, false), header)
    }
    val signing = dnssecObject(row, "SigningKey")
    val publicKey = signing?.let { dnssecText(it, "pubkey", true, false) }
    // Keep read-only unknown public metadata, but never retain the signing secret subtree.
    val publicRow = JsonObject(row - "SigningKey" + (signing?.let {
        mapOf("SigningKey" to buildJsonObject { if (it.containsKey("pubkey")) put("pubkey", it.getValue("pubkey")) })
    } ?: emptyMap()))
    return DnssecKey(scope, dnssecText(row, "Name", false, false), dnssecText(row, "Location", false, false),
        dnssecText(row, "Tag", false, false), dnskey, publicKey, dnssecFreeze(publicRow))
}

internal fun dnssecResponseScope(row: JsonObject, scope: ZoneScope, writing: Boolean) {
    // Fixed response has no required id. If a provider supplies a locator it must agree.
    for (name in listOf("zone_id", "id")) if (row.containsKey(name) && dnssecText(row, name, false, writing) != scope.zoneId) throw dnssecInvalid(writing)
}
private fun dnssecText(row: JsonObject, name: String, nullable: Boolean, writing: Boolean): String? {
    val value = row[name] ?: return null
    if (nullable && value == JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw dnssecInvalid(writing)
}
private fun dnssecBoolean(row: JsonObject, name: String, writing: Boolean): Boolean? {
    val value = row[name] ?: return null
    return (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw dnssecInvalid(writing)
}
private fun dnssecNumber(row: JsonObject, name: String, writing: Boolean): Double? {
    val value = row[name] ?: return null
    if (value == JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: throw dnssecInvalid(writing)
}
private fun dnssecInteger(row: JsonObject, name: String): Long? {
    val value = row[name] ?: return null
    if (value == JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw dnssecInvalid(false)
}
private fun dnssecObject(row: JsonObject, name: String): JsonObject? = row[name]?.let { it as? JsonObject ?: throw dnssecInvalid(false) }
internal fun dnssecInvalid(writing: Boolean) = CloudFailure(CloudFailureCode.INVALID_RESPONSE,
    if (writing) "DNSSEC 结果尚未确认，请只读核对，不要重复发送" else "DNSSEC 响应格式无法识别", uncertain = writing)
