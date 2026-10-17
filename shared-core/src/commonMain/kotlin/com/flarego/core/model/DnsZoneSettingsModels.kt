package com.flarego.core.model

import kotlinx.serialization.json.*

const val DNS_SETTINGS_GET_OPERATION = "dns-settings-for-a-zone-list-dns-settings"
const val DNS_SETTINGS_PATCH_OPERATION = "dns-settings-for-a-zone-update-dns-settings"
enum class DnsZoneSettingKey(val wire: String) {
    FLATTEN_ALL_CNAMES("flatten_all_cnames"), MULTI_PROVIDER("multi_provider"), NS_TTL("ns_ttl"),
    SECONDARY_OVERRIDES("secondary_overrides"), ZONE_MODE("zone_mode"), NAMESERVERS("nameservers"), SOA("soa"), INTERNAL_DNS("internal_dns")
}
sealed interface DnsNameservers {
    data class Cloudflare(val advanced: Boolean) : DnsNameservers
    data class Existing(val type: String, val nsSet: Int?) : DnsNameservers
    data class CustomSet(val id: String) : DnsNameservers
}
data class DnsSoaPatch(val expire: JsonPrimitive? = null, val minTtl: JsonPrimitive? = null,
    val mname: JsonElement? = null, val refresh: JsonPrimitive? = null, val retry: JsonPrimitive? = null,
    val rname: String? = null, val ttl: JsonPrimitive? = null)
data class DnsInternalDnsPatch(val referenceZoneId: String)
data class DnsSettingsProjection(val changed: JsonObject, val nameservers: DnsNameservers? = null,
    val soa: DnsSoaPatch? = null, val internalDns: DnsInternalDnsPatch? = null)
data class DnsSettingsEligibility(val fields: Set<DnsZoneSettingKey> = emptySet(),
    val nestedPatchSemanticsVerified: Set<DnsZoneSettingKey> = emptySet(),
    val nameserverTypes: Set<String> = emptySet(), val ownedCustomSetIds: Set<String> = emptySet(),
    val verifiedReferenceZoneIds: Set<String> = emptySet(), val existingNameserverSets: Map<String,Set<Int>> = emptyMap())
data class DnsZoneSettings(val scope: ZoneScope, val raw: JsonObject)
data class DnsZoneSettingsSnapshot(val owner: ZoneDetails, val settings: DnsZoneSettings,
    val writeAllowed: Boolean?, val eligibility: DnsSettingsEligibility)
data class DnsZoneSettingsAction(val scope: ZoneScope, val changed: JsonObject)
enum class DnsSettingsRisk { CHANGE_NAMESERVERS, CHANGE_ZONE_MODE }
class DnsSettingsRiskAcknowledgement internal constructor(val planId: String, val risk: DnsSettingsRisk)
data class DnsZoneSettingsPreview(val scope: ZoneScope, val domain: String, val projection: DnsSettingsProjection,
    val risks: Set<DnsSettingsRisk>, val warnings: List<String>,
    val before: com.flarego.core.application.dns.DnsSettingsReadView? = null,
    val after: com.flarego.core.application.dns.DnsSettingsReadView? = null)

// Fixed OpenAPI 06dbba919204662d42e758e1f5b1dc4e262e99ec8540e7bf564de920707b9461.
internal val t7SoaKeys=setOf("expire","min_ttl","mname","refresh","retry","rname","ttl")
internal val t7SettingsKeys=DnsZoneSettingKey.entries.map { it.wire }.toSet()
internal fun t7BadSetting()=CloudFailure(CloudFailureCode.INVALID_INPUT,"DNS设置输入不符合固定审核定义，请检查字段、范围和明确变化")
internal fun t7ReviewSetting()=CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,"needs_review：该设置的资格、未知字段或安全嵌套更新方式尚未验证")
internal fun t7Decimal(value: JsonElement): String {
    val p=value as? JsonPrimitive ?: throw t7BadSetting()
    if(p.isString || value==JsonNull || p.content.length>512) throw t7BadSetting()
    val match=Regex("(-?)(0|[1-9][0-9]*)(?:\\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?").matchEntire(p.content) ?: throw t7BadSetting()
    val whole=match.groupValues[2];val fraction=match.groupValues[3]
    val exponent=match.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: if(match.groupValues[4].isEmpty()) 0 else throw t7BadSetting()
    if(exponent !in -512..512) throw t7BadSetting()
    val digits=whole+fraction;val split=whole.length+exponent
    val integral=when { split<=0->"0";split>=digits.length->digits+"0".repeat(split-digits.length);else->digits.substring(0,split) }.trimStart('0').ifEmpty { "0" }
    val decimal=when { split<=0->"0".repeat(-split)+digits;split>=digits.length->"";else->digits.substring(split) }.trimEnd('0')
    val sign=if(match.groupValues[1]=="-" && (integral!="0" || decimal.isNotEmpty())) "-" else ""
    return sign+integral+if(decimal.isEmpty()) "" else ".$decimal"
}
private fun decimalCompare(left: String, right: String): Int {
    if(left.startsWith('-') || right.startsWith('-')) {
        if(left.startsWith('-')!=right.startsWith('-')) return if(left.startsWith('-')) -1 else 1
        return -decimalCompare(left.removePrefix("-"),right.removePrefix("-"))
    }
    val a=left.substringBefore('.');val b=right.substringBefore('.')
    if(a.length!=b.length) return a.length.compareTo(b.length)
    if(a!=b) return a.compareTo(b)
    val af=left.substringAfter('.',"");val bf=right.substringAfter('.',"");val size=maxOf(af.length,bf.length)
    return af.padEnd(size,'0').compareTo(bf.padEnd(size,'0'))
}
internal fun t7Number(value: JsonElement, min: Long, max: Long): JsonPrimitive {
    val canonical=t7Decimal(value)
    if(decimalCompare(canonical,min.toString())<0 || decimalCompare(canonical,max.toString())>0) throw t7BadSetting()
    return value as JsonPrimitive
}
internal fun t7Semantic(value: JsonElement): JsonElement = when(value) {
    is JsonObject -> JsonObject(value.mapValues { t7Semantic(it.value) })
    is JsonArray -> JsonArray(value.map(::t7Semantic))
    is JsonPrimitive -> if(value==JsonNull || value.isString || value.booleanOrNull!=null) value else Json.parseToJsonElement(t7Decimal(value))
}
internal fun t7Same(a: JsonElement, b: JsonElement)=t7Semantic(a)==t7Semantic(b)
private fun settingText(value: JsonElement?)=(value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw t7BadSetting()
internal fun t7Nameservers(value: JsonElement): DnsNameservers {
    val raw=value as? JsonObject ?: throw t7BadSetting();val type=settingText(raw["type"])
    return when(type) {
        "cloudflare.standard","cloudflare.advanced" -> {
            if(raw.keys!=setOf("type")) throw t7BadSetting()
            DnsNameservers.Cloudflare(type=="cloudflare.advanced")
        }
        "custom.account","custom.tenant","custom.zone" -> {
            if((raw.keys-setOf("type","ns_set")).isNotEmpty()) throw t7BadSetting()
            val n=raw["ns_set"]?.let { t7Number(it,1,5);t7Decimal(it).takeIf { c -> '.' !in c }?.toIntOrNull() ?: throw t7BadSetting() }
            DnsNameservers.Existing(type,n)
        }
        "custom" -> {
            if(raw.keys!=setOf("type","nameserver_set_id")) throw t7BadSetting()
            val id=settingText(raw["nameserver_set_id"])
            if(!Regex("[0-9a-f]{32}").matches(id)) throw t7BadSetting()
            DnsNameservers.CustomSet(id)
        }
        else -> throw t7BadSetting()
    }
}
internal fun t7Project(changed: JsonObject): DnsSettingsProjection {
    if(changed.isEmpty() || (changed.keys-t7SettingsKeys).isNotEmpty()) {
        if("foundation_dns" in changed) throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,"foundation_dns已弃用，不能写入；请审核nameservers配置")
        throw t7BadSetting()
    }
    var nameservers:DnsNameservers?=null;var soa:DnsSoaPatch?=null;var internal:DnsInternalDnsPatch?=null
    for((key,value) in changed) when(key) {
        "flatten_all_cnames","multi_provider","secondary_overrides" -> if((value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull==null) throw t7BadSetting()
        "ns_ttl" -> t7Number(value,30,86400)
        "zone_mode" -> if(settingText(value) !in setOf("standard","cdn_only","dns_only")) throw t7BadSetting()
        "nameservers" -> nameservers=t7Nameservers(value)
        "soa" -> {
            val fields=value as? JsonObject ?: throw t7BadSetting()
            if(fields.isEmpty() || (fields.keys-t7SoaKeys).isNotEmpty()) throw t7BadSetting()
            for((field,v) in fields) when(field) {
                "expire" -> t7Number(v,86400,2419200)
                "min_ttl" -> t7Number(v,60,86400)
                "refresh","retry" -> t7Number(v,600,86400)
                "ttl" -> t7Number(v,300,86400)
                "mname" -> if(v!=JsonNull) settingText(v)
                "rname" -> settingText(v)
            }
            soa=DnsSoaPatch(fields["expire"] as? JsonPrimitive,fields["min_ttl"] as? JsonPrimitive,fields["mname"],
                fields["refresh"] as? JsonPrimitive,fields["retry"] as? JsonPrimitive,(fields["rname"] as? JsonPrimitive)?.content,fields["ttl"] as? JsonPrimitive)
        }
        "internal_dns" -> {
            val fields=value as? JsonObject ?: throw t7BadSetting()
            if(fields.keys!=setOf("reference_zone_id")) throw t7BadSetting()
            val reference=settingText(fields["reference_zone_id"])
            if(reference.isBlank()) throw t7ReviewSetting() // Empty is not a proven clear/reset instruction.
            internal=DnsInternalDnsPatch(reference)
        }
    }
    return DnsSettingsProjection(Json.parseToJsonElement(changed.toString()).jsonObject,nameservers,soa,internal)
}
internal fun t7NameserverJson(value: DnsNameservers): JsonObject=buildJsonObject {
    when(value) {
        is DnsNameservers.Cloudflare -> put("type",if(value.advanced) "cloudflare.advanced" else "cloudflare.standard")
        is DnsNameservers.Existing -> { put("type",value.type);value.nsSet?.let { put("ns_set",it) } }
        is DnsNameservers.CustomSet -> { put("type","custom");put("nameserver_set_id",value.id) }
    }
}
internal fun t7SoaJson(value: DnsSoaPatch): JsonObject=buildJsonObject {
    value.expire?.let { put("expire",it) };value.minTtl?.let { put("min_ttl",it) };value.mname?.let { put("mname",it) }
    value.refresh?.let { put("refresh",it) };value.retry?.let { put("retry",it) };value.rname?.let { put("rname",it) };value.ttl?.let { put("ttl",it) }
}
