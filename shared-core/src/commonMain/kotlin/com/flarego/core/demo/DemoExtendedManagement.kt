package com.flarego.core.demo

import com.flarego.core.application.management.extendedUnavailable
import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.serialization.json.*
import com.flarego.core.application.dns.*
import com.flarego.core.cloudflare.dns.t7SettingsWritable

/** Volatile examples through the same typed controllers. Import deliberately stays unknown: no BIND parser. */
internal class DemoExtendedManagement(private val base: DemoDnsManagement, private val fingerprint: FingerprintPort) : DnsFilesSession, DnsScanSession,
    DnsZoneSettingsSession, DnssecSession, ZoneSettingsSession {
    private val sec=mutableMapOf<ZoneScope,JsonObject>()
    private val dns=mutableMapOf<ZoneScope,JsonObject>()
    private val settings=mutableMapOf<Pair<ZoneScope,ZoneSettingKey>,JsonElement>()
    private val universal=mutableMapOf<ZoneScope,Boolean>()
    private val candidates=mutableMapOf<ZoneScope,List<DnsScanCandidate>>()
    override suspend fun observe(scope:ZoneScope)=DnsFileImportState(base.zone(scope),true)
    override suspend fun readback(scope:ZoneScope):DnsImportReadback {
        val page=base.list(scope,DnsFilter(),PageRequest(20));return DnsImportReadback(scope,page.items.size,page.total)
    }
    override suspend fun exportRecords(scope:ZoneScope,sink:ByteSink):HttpResponseHead {
        val owner=base.zone(scope)
        val bytes=("; FlareGo explicit example, not provider data\n"+owner.name+". 60 IN TXT \"FlareGo example\"\n").encodeToByteArray()
        try { sink.write(bytes,0,bytes.size) } finally { sink.close() }
        return HttpResponseHead(200,mapOf("Content-Type" to listOf("text/plain")),bytes.size.toLong())
    }
    override suspend fun importRecords(scope:ZoneScope,source:ByteSource,length:Long,fingerprint:String,proxied:Boolean):DnsImportOutcome =
        try { writeImport(scope,{source},length,fingerprint,proxied,{}, {}) } finally { source.close() }
    override suspend fun writeImport(scope:ZoneScope,source:suspend()->ByteSource,length:Long,fingerprint:String,proxied:Boolean,beforeSend:(DnsFileImportState)->Unit,afterRead:()->Unit):DnsImportOutcome {
        val before=observe(scope)
        readDnsImport(source(),length,fingerprint,this.fingerprint,kotlinx.coroutines.Dispatchers.Default)
        beforeSend(before);afterRead()
        return DnsImportOutcome.Unknown() // Never fabricate import counts or parser evidence.
    }
    override suspend fun review(scope:ZoneScope):List<DnsScanCandidate> {
        val owner=base.zone(scope)
        return candidates.getOrPut(scope) { listOf(t7Candidate(buildJsonObject {
            put("id","example-candidate");put("type","TXT");put("name","scan."+owner.name);put("content","Explicit scan example");put("ttl",60)
        },owner,fingerprint)) }
    }
    override suspend fun observe(action:DnsScanAction)=DnsScanSnapshot(base.zone(action.scope),if(action is DnsScanAction.Apply) review(action.scope) else emptyList(),true)
    override suspend fun trigger(scope:ZoneScope)=write(DnsScanAction.Trigger(scope),{}, {})
    override suspend fun apply(scope:ZoneScope,input:DnsScanReviewInput)=performScan(DnsScanAction.Apply(scope,input),{}, {})
    override suspend fun write(action:DnsScanAction,beforeSend:(DnsScanSnapshot)->Unit,afterRead:()->Unit)=performScan(action,beforeSend,afterRead).receipt
    private suspend fun performScan(request:DnsScanAction,beforeSend:(DnsScanSnapshot)->Unit,afterRead:()->Unit):DnsScanApplyOutcome {
        val action=t7Freeze(request);val snapshot=observe(action)
        val payload=if(action is DnsScanAction.Apply) t7ApplyPayload(snapshot,action.input,snapshot.constraints) else null
        // Base create validates synchronously after its in-memory owner read; keep final guard adjacent to each example mutation.
        if(action is DnsScanAction.Trigger) { beforeSend(snapshot);afterRead();return DnsScanApplyOutcome(ExecutionReceipt.Accepted()) }
        action as DnsScanAction.Apply
        val accepted=mutableListOf<DnsRecordDocument>()
        beforeSend(snapshot)
        payload?.get("accepts")?.jsonArray.orEmpty().forEach { raw ->
            val row=raw.jsonObject
            accepted+=base.create(action.scope,DnsRecordInput(dnsText(row,"type"),dnsText(row,"name"),JsonObject(row.filterKeys { it !in setOf("type","name") })))
        }
        candidates[action.scope]=snapshot.candidates.filterNot { it.id in action.input.acceptIds+action.input.rejectIds }
        afterRead();return DnsScanApplyOutcome(ExecutionReceipt.Completed(),accepted,action.input.rejectIds)
    }
    override suspend fun usage(scope:ZoneScope):DnsUsage { val rows=base.list(scope,DnsFilter(),PageRequest(20));return DnsUsage(scope,rows.total?:rows.items.size.toLong(),200,buildJsonObject { put("example",true) }) }
    override suspend fun settings(scope:ZoneScope):DnsZoneSettings {
        base.zone(scope)
        return DnsZoneSettings(scope,dns.getOrPut(scope) { buildJsonObject {
            put("flatten_all_cnames",false);put("multi_provider",false);put("secondary_overrides",false);put("ns_ttl",86400);put("zone_mode","standard")
            put("nameservers",buildJsonObject { put("type","cloudflare.standard") })
        } })
    }
    override suspend fun observe(action:DnsZoneSettingsAction)=DnsZoneSettingsSnapshot(base.zone(action.scope),settings(action.scope),true,
        DnsSettingsEligibility(fields=setOf(DnsZoneSettingKey.FLATTEN_ALL_CNAMES,DnsZoneSettingKey.MULTI_PROVIDER,DnsZoneSettingKey.NS_TTL,DnsZoneSettingKey.ZONE_MODE,DnsZoneSettingKey.NAMESERVERS),nameserverTypes=setOf("cloudflare.standard","cloudflare.advanced")))
    override suspend fun updateSettings(scope:ZoneScope,changed:JsonObject):DnsZoneSettings { write(DnsZoneSettingsAction(scope,changed),{}, {});return settings(scope) }
    override suspend fun write(action:DnsZoneSettingsAction,beforeSend:(DnsZoneSettingsSnapshot)->Unit,afterRead:()->Unit):ExecutionReceipt {
        val frozen=action.copy(changed=dnsCopy(action.changed));val before=observe(frozen);val view=dnsZoneSettingsView(before.settings)
        val projection=dnsZoneSettingsDelta(view,frozen.changed);t7SettingsWritable(before,projection)
        beforeSend(before);dns[frozen.scope]=JsonObject(before.settings.raw+projection.changed);afterRead();return ExecutionReceipt.Completed()
    }
    override suspend fun get(scope:ZoneScope):DnssecDetails {
        base.zone(scope)
        return decodeDnssecDetails(scope,sec.getOrPut(scope) { buildJsonObject {
            put("status","disabled");put("dnssec_multi_signer",false);put("dnssec_presigned",false);put("dnssec_use_nsec3",false)
        } },false)
    }
    override suspend fun zsks(scope:ZoneScope):List<DnssecKey> { base.zone(scope);return emptyList() }
    override suspend fun observe(scope:ZoneScope,mutation:DnssecMutation)=DnssecSnapshot(base.zone(scope),get(scope),true,null)
    override suspend fun update(scope:ZoneScope,input:DnssecUpdateInput):DnssecDetails { write(DnssecAction.Update(scope,input),{}, {});return get(scope) }
    override suspend fun delete(scope:ZoneScope) { write(DnssecAction.Delete(scope),{}, {}) }
    override suspend fun write(action:DnssecAction,beforeSend:(DnssecSnapshot)->Unit,afterRead:()->Unit):ExecutionReceipt {
        val before=observe(action.scope,dnssecMutation(action));requireDnssecWritable(action,before)
        val next=when(action) { is DnssecAction.Update->JsonObject(before.details.raw+action.input.fields());is DnssecAction.Delete->JsonObject(before.details.raw+("status" to JsonPrimitive("disabled"))) }
        beforeSend(before);sec[action.scope]=next;afterRead();return ExecutionReceipt.Completed()
    }
    override suspend fun get(scope:ZoneScope,setting:ZoneSettingKey):ZoneSettingValue {
        base.zone(scope)
        val known=setting in setOf(ZoneSettingKey.SSL,ZoneSettingKey.ALWAYS_USE_HTTPS,ZoneSettingKey.MIN_TLS_VERSION,ZoneSettingKey.TLS_1_3,ZoneSettingKey.HTTP2,ZoneSettingKey.HTTP3,ZoneSettingKey.DEVELOPMENT_MODE,ZoneSettingKey.BROWSER_CACHE_TTL)
        val value=settings.getOrPut(scope to setting) { when(setting) {
            ZoneSettingKey.SSL->JsonPrimitive("strict");ZoneSettingKey.MIN_TLS_VERSION->JsonPrimitive("1.2")
            ZoneSettingKey.DEVELOPMENT_MODE,ZoneSettingKey.BROWSER_CACHE_TTL->JsonPrimitive(0)
            else->JsonPrimitive("on")
        } }
        return ZoneSettingValue(scope,setting,value,known,buildJsonObject { put("id",setting.responseId);put("value",value);put("editable",known) })
    }
    override suspend fun universalSsl(scope:ZoneScope):UniversalSslSetting {
        base.zone(scope);val enabled=universal[scope]?:true
        return UniversalSslSetting(scope,enabled,true,buildJsonObject { put("enabled",enabled);put("editable",true) })
    }
    override suspend fun observe(scope:ZoneScope,target:ZoneSettingsTarget)=ZoneSettingsSnapshot(base.zone(scope),
        setting=(target as? ZoneSettingsTarget.Setting)?.let { get(scope,it.key) },universal=if(target==ZoneSettingsTarget.UniversalSsl) universalSsl(scope) else null,writeAllowed=true)
    override suspend fun update(scope:ZoneScope,setting:ZoneSettingKey,input:JsonObject):ZoneSettingValue { write(ZoneSettingsAction.Update(scope,setting,input),{}, {});return get(scope,setting) }
    override suspend fun updateUniversalSsl(scope:ZoneScope,enabled:Boolean):UniversalSslSetting { write(ZoneSettingsAction.UniversalSsl(scope,enabled),{}, {});return universalSsl(scope) }
    override suspend fun purge(scope:ZoneScope,input:CachePurgeInput)=write(ZoneSettingsAction.Purge(scope,input),{}, {})
    override suspend fun write(action:ZoneSettingsAction,beforeSend:(ZoneSettingsSnapshot)->Unit,afterRead:()->Unit):ExecutionReceipt {
        val frozen=freezeSettingsAction(action);val before=observe(frozen.scope,settingsTarget(frozen));requireSettingsWritable(frozen,before)
        beforeSend(before)
        when(frozen) { is ZoneSettingsAction.Update->settings[frozen.scope to frozen.key]=frozen.input.getValue("value")
            is ZoneSettingsAction.UniversalSsl->universal[frozen.scope]=frozen.enabled
            is ZoneSettingsAction.Purge->{} }
        afterRead();return if(frozen is ZoneSettingsAction.Purge) ExecutionReceipt.Accepted() else ExecutionReceipt.Completed()
    }
}
