package com.flarego.core.cloudflare.dns

import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.cloudflare.zones.CloudflareZoneClient
import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.dns.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

class CloudflareDnsZoneSettings(private val connection: Connection, private val gateway: CloudflareGateway,
    private val fingerprint: FingerprintPort, private val authority: DnsZoneSettingsAuthority = object : DnsZoneSettingsAuthority {}) : DnsZoneSettingsSession {
    private suspend fun owner(scope: ZoneScope): ZoneDetails {
        t7Scope(scope);ScopeGuard.requireOwned(scope,connection)
        return requireDnsZone(CloudflareZoneClient(connection,gateway).get(scope),scope)
    }
    override suspend fun usage(scope: ZoneScope): DnsUsage {
        owner(scope)
        val result=t7Read(gateway,DNS_USAGE_OPERATION,"/zones/${encodePath(scope.zoneId)}/dns_records/usage")["result"] as? JsonObject ?: throw dnsInvalidResponse()
        t7ResultScope(result,scope)
        fun number(value: JsonElement?):Long = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it>=0 } ?: throw dnsInvalidResponse()
        if(!result.containsKey("record_quota")) throw dnsInvalidResponse()
        return DnsUsage(scope,number(result["record_usage"]),if(result["record_quota"]==JsonNull) null else number(result["record_quota"]),dnsCopy(result))
    }
    override suspend fun settings(scope: ZoneScope): DnsZoneSettings { owner(scope);return readSettings(scope) }
    private suspend fun readSettings(scope: ZoneScope): DnsZoneSettings {
        val result=t7Read(gateway,DNS_SETTINGS_GET_OPERATION,settingsPath(scope))["result"] as? JsonObject ?: throw dnsInvalidResponse()
        t7ResultScope(result,scope)
        return DnsZoneSettings(scope,dnsCopy(result))
    }
    override suspend fun observe(action: DnsZoneSettingsAction): DnsZoneSettingsSnapshot {
        val owned=owner(action.scope);val actual=readSettings(action.scope)
        return t7SettingsChecked(DnsZoneSettingsSnapshot(owned,actual,authority.mayWrite(action.scope),authority.eligibility(action.scope)),action.scope)
    }
    override suspend fun updateSettings(scope: ZoneScope, changed: JsonObject): DnsZoneSettings {
        val result=perform(DnsZoneSettingsAction(scope,changed),{}, {})
        if(result.first !is ExecutionReceipt.Completed || result.second==null) throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT,"DNS配置更新结果未确认，请只读核对，不要重发",uncertain=true)
        return result.second!!
    }
    override suspend fun write(action: DnsZoneSettingsAction, beforeSend: (DnsZoneSettingsSnapshot) -> Unit, afterRead: () -> Unit): ExecutionReceipt =
        perform(action,beforeSend,afterRead).first
    private suspend fun perform(requested: DnsZoneSettingsAction, beforeSend: (DnsZoneSettingsSnapshot) -> Unit, afterRead: () -> Unit): Pair<ExecutionReceipt,DnsZoneSettings?> {
        val action=requested.copy(changed=dnsCopy(requested.changed))
        val before=observe(action);val view=dnsZoneSettingsView(before.settings)
        val projection=dnsZoneSettingsDelta(view,action.changed);t7SettingsWritable(before,projection)
        val desired=dnsZoneSettingsAfter(view,projection)
        val request=HttpRequest("PATCH",settingsPath(action.scope),projection.changed.toString())
        t7Registered(DNS_SETTINGS_PATCH_OPERATION,request)
        beforeSend(before)
        return try {
            val root=t7Envelope(gateway.send(DNS_SETTINGS_PATCH_OPERATION,request),true)
            val result=root["result"] as? JsonObject ?: return unknownSettings()
            t7ResultScope(result,action.scope)
            if(!t7TargetMatches(desired,DnsZoneSettings(action.scope,result),projection.changed.keys)) return unknownSettings()
            val actual=settings(action.scope)
            if(!t7TargetMatches(desired,actual,projection.changed.keys)) return unknownSettings()
            afterRead()
            ExecutionReceipt.Completed() to actual
        } catch (cancel: CancellationException) { throw cancel }
        catch (_:Exception) { unknownSettings() }
    }
}
private fun settingsPath(scope: ZoneScope)="/zones/${encodePath(scope.zoneId)}/dns_settings"
private fun unknownSettings():Pair<ExecutionReceipt,DnsZoneSettings?> = ExecutionReceipt.Unknown() to null
internal fun t7SettingsChecked(snapshot: DnsZoneSettingsSnapshot,scope: ZoneScope): DnsZoneSettingsSnapshot {
    val owner=requireDnsZone(snapshot.owner,scope)
    if(snapshot.settings.scope!=scope) throw scopeMismatchFailure()
    t7ResultScope(snapshot.settings.raw,scope)
    val q=snapshot.eligibility
    return snapshot.copy(owner=owner.copy(raw=dnsCopy(owner.raw)),settings=snapshot.settings.copy(raw=dnsCopy(snapshot.settings.raw)),
        eligibility=q.copy(fields=q.fields.toSet(),nestedPatchSemanticsVerified=q.nestedPatchSemanticsVerified.toSet(),nameserverTypes=q.nameserverTypes.toSet(),
            ownedCustomSetIds=q.ownedCustomSetIds.toSet(),verifiedReferenceZoneIds=q.verifiedReferenceZoneIds.toSet(),existingNameserverSets=q.existingNameserverSets.mapValues { it.value.toSet() }))
}
internal fun t7SettingsWritable(before: DnsZoneSettingsSnapshot, projection: DnsSettingsProjection) {
    t7Writable(before.writeAllowed)
    val q=before.eligibility
    for(key in projection.changed.keys) {
        val field=DnsZoneSettingKey.entries.singleOrNull { it.wire==key } ?: throw t7BadSetting()
        if(field !in q.fields) throw t7ReviewSetting()
        if(field in setOf(DnsZoneSettingKey.SOA,DnsZoneSettingKey.NS_TTL,DnsZoneSettingKey.MULTI_PROVIDER) && before.owner.type!="full") throw t7ReviewSetting()
        if(field==DnsZoneSettingKey.SECONDARY_OVERRIDES && before.owner.type!="secondary") throw t7ReviewSetting()
        if(field in setOf(DnsZoneSettingKey.SOA,DnsZoneSettingKey.INTERNAL_DNS) && field !in q.nestedPatchSemanticsVerified) throw t7ReviewSetting()
    }
    projection.nameservers?.let { nameservers ->
        val type=t7NameserverJson(nameservers).getValue("type").jsonPrimitive.content
        if(type !in q.nameserverTypes) throw t7ReviewSetting()
        when(nameservers) {
            is DnsNameservers.CustomSet -> if(nameservers.id !in q.ownedCustomSetIds) throw t7ReviewSetting()
            is DnsNameservers.Existing -> if(nameservers.nsSet==null || nameservers.nsSet !in q.existingNameserverSets[type].orEmpty()) throw t7ReviewSetting()
            else -> Unit
        }
    }
    projection.internalDns?.let { if(it.referenceZoneId !in q.verifiedReferenceZoneIds) throw t7ReviewSetting() }
}
internal fun t7TargetMatches(desired: DnsSettingsReadView, actual: DnsZoneSettings, keys: Set<String>): Boolean {
    val expected=t7ViewJson(desired)
    return keys.all { key ->
        actual.raw[key]?.let { value -> expected[key]?.let { t7Same(it,value) } }==true
    }
}
