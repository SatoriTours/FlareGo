package com.flarego.core.demo

import com.flarego.core.application.dns.*
import com.flarego.core.cloudflare.dns.*
import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.serialization.json.*

/** Explicit, volatile examples using the same typed product ports. Never acquires credentials. */
class DemoDnsManagement(private val connection:Connection) : DnsBatchManagementSession,ZoneManagementSession {
    init { if(!connection.demo) throw invalidInputFailure() }
    private val account=AccountScope(connection.id,connection.accountId)
    private var sequence=0
    private val zones=linkedMapOf<String,ZoneDetails>()
    private val records=mutableMapOf<ZoneScope,MutableMap<String,DnsRecordDocument>>()
    init {
        if(connection.accountId!="sandbox") listOf("zone-demo" to "flarego.dev","zone-app" to "getflare.app","zone-design" to "studio.design","zone-io" to "flarego.io").forEach { (id,name) ->
            zones[id]=zoneDetails(id,name,if(id=="zone-design") "pending" else "active")
        }
    }
    private fun zoneDetails(id:String,name:String,status:String="pending",type:String="full"):ZoneDetails {
        val ref=ZoneScope(account,id)
        val raw=buildJsonObject { put("id",id);put("account",buildJsonObject { put("id",account.accountId) });put("name",name);put("status",status);put("paused",false);put("type",type);put("name_servers",JsonArray(listOf(JsonPrimitive("example.ns.cloudflare.com")))) }
        return ZoneDetails(ref,name,status,false,type,listOf("example.ns.cloudflare.com"),raw)
    }
    private fun owned(scope:CloudScope) { ScopeGuard.requireOwned(scope,connection) }
    override fun batchMaxOperations(scope:ZoneScope):Int { owned(scope);return 200 }
    override suspend fun batch(scope:ZoneScope,input:DnsBatchInput):DnsBatchOutcome { val frozen=batchFreeze(input);observeBatch(scope,frozen);return writeBatch(scope,frozen) {} }
    override suspend fun observeBatch(scope:ZoneScope,input:DnsBatchInput):DnsBatchSnapshot=observeBatchInput(scope,input,false)
    private suspend fun observeBatchInput(scope:ZoneScope,input:DnsBatchInput,allowNoop:Boolean):DnsBatchSnapshot {
        batchShape(input)
        val owner=zone(scope);val map=rows(scope)
        val ids=input.deletes+input.patches.map { it.id }+input.puts.map { it.id }
        val snapshot=DnsBatchSnapshot(owner,ids.associateWith { map[it]?:throw CloudFailure(CloudFailureCode.NOT_FOUND,"示例 DNS 不存在") },
            input.posts.map { post -> val valid=dnsWriteInput(owner.name,null,post,DnsWriteMode.CREATE,DnsConstraints());map.values.filter { dnsText(it.raw,"type")==valid.type && dnsText(it.raw,"name")==valid.name } })
        batchBuild(scope,input,snapshot,DnsConstraints(),allowNoop);return snapshot
    }
    override suspend fun writeBatch(scope:ZoneScope,input:DnsBatchInput,beforeSend:(DnsBatchSnapshot)->Unit):DnsBatchOutcome {
        val frozen=batchFreeze(input);val snapshot=observeBatchInput(scope,frozen,true);val bundle=batchBuild(scope,frozen,snapshot,DnsConstraints(),true)
        val replacement=rows(scope).toMutableMap();var next=sequence
        val results=bundle.items.map { item ->
            val id=item.ref.recordId?:"example-created-${++next}"
            if(item.ref.kind==DnsBatchKind.DELETE) replacement.remove(id)
            else replacement[id]=DnsRecordDocument(id,scope,JsonObject((if(item.ref.kind==DnsBatchKind.PATCH) item.before!!.raw else emptyMap())+item.payload!!+("id" to JsonPrimitive(id))))
            DnsBatchItemResult(item.ref,id,DnsBatchVerification.MATCHED)
        }
        beforeSend(snapshot) // After every suspension; one synchronous atomic example swap.
        records[scope]=replacement;sequence=next
        return DnsBatchOutcome(DnsBatchTransaction.CONFIRMED_COMMIT,results,emptyList())
    }
    override suspend fun zone(scope:ZoneScope):ZoneDetails { owned(scope);return zones[scope.zoneId]?:throw CloudFailure(CloudFailureCode.NOT_FOUND,"示例 Zone 不存在") }
    override suspend fun get(scope:ZoneScope)=zone(scope)
    private suspend fun rows(scope:ZoneScope):MutableMap<String,DnsRecordDocument> {
        val owner=zone(scope)
        return records.getOrPut(scope) {
            linkedMapOf<String,DnsRecordDocument>().also { result ->
                listOf(
                    DnsRecordInput("A",owner.name,buildJsonObject { put("content","192.0.2.10");put("ttl",1);put("proxied",true) }),
                    DnsRecordInput("TXT","_example.${owner.name}",buildJsonObject { put("content","示例 TXT\n第二行");put("ttl",60) }),
                    DnsRecordInput("SRV","_https._tcp.${owner.name}",buildJsonObject { put("ttl",60);put("data",buildJsonObject { put("priority",10);put("weight",5);put("port",443);put("target","service.${owner.name}") }) }),
                ).forEachIndexed { index,input -> val id="example-${index+1}";result[id]=DnsRecordDocument(id,scope,JsonObject(dnsPayload(input)+("id" to JsonPrimitive(id)))) }
            }
        }
    }
    private fun <T> page(items:List<T>,request:PageRequest):CloudPage<T> {
        val number=when(val token=request.token) { null->1;is PageToken.Number->token.value;else->throw invalidInputFailure() }
        val start=(number.toLong()-1)*request.limit
        if(start>Int.MAX_VALUE) throw invalidInputFailure()
        val slice=items.drop(start.toInt()).take(request.limit)
        return CloudPage(slice,if(start+slice.size<items.size) PageToken.Number(number+1) else null,items.size.toLong())
    }
    override suspend fun list(scope:ZoneScope,filter:DnsFilter,page:PageRequest):CloudPage<DnsRecordDocument> {
        if(filter.tags.size>1) throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,"多 tag 匹配语义尚未验证")
        val items=rows(scope).values.filter { row ->
            fun match(key:String,value:String?)=value==null || (row.raw[key] as? JsonPrimitive)?.content.orEmpty().contains(value,ignoreCase=true)
            match("name",filter.name) && match("type",filter.type) && match("content",filter.content) && match("comment",filter.comment) && filter.tags.all { tag -> (row.raw["tags"] as? JsonArray)?.any { it.jsonPrimitive.content==tag }==true }
        }
        return page(items,page)
    }
    override suspend fun get(scope:ZoneScope,recordId:String)=rows(scope)[recordId]?:throw CloudFailure(CloudFailureCode.NOT_FOUND,"示例 DNS 不存在")
    override suspend fun list(account:AccountScope,page:PageRequest):CloudPage<ZoneDetails> { owned(account);return page(zones.values.toList(),page) }
    override suspend fun create(scope:ZoneScope,input:DnsRecordInput):DnsRecordDocument {
        val owner=zone(scope);val value=dnsWriteInput(owner.name,null,input,DnsWriteMode.CREATE,DnsConstraints());val id="example-created-${++sequence}"
        return DnsRecordDocument(id,scope,JsonObject(dnsPayload(value)+("id" to JsonPrimitive(id)))).also { rows(scope)[id]=it }
    }
    override suspend fun patch(scope:ZoneScope,recordId:String,fields:JsonObject):DnsRecordDocument {
        val old=get(scope,recordId);val input=DnsRecordInput((fields["type"]?:old.raw.getValue("type")).jsonPrimitive.content,(fields["name"]?:old.raw.getValue("name")).jsonPrimitive.content,JsonObject(fields-"type"-"name"))
        val value=dnsWriteInput(zone(scope).name,old,input,DnsWriteMode.PATCH,DnsConstraints(),true)
        return old.copy(raw=JsonObject(old.raw+dnsPayload(value))).also { rows(scope)[recordId]=it }
    }
    override suspend fun replace(scope:ZoneScope,recordId:String,input:DnsRecordInput):DnsRecordDocument {
        val old=get(scope,recordId);val value=dnsWriteInput(zone(scope).name,old,input,DnsWriteMode.REPLACE,DnsConstraints())
        return old.copy(raw=JsonObject(dnsPayload(value)+("id" to JsonPrimitive(recordId)))).also { rows(scope)[recordId]=it }
    }
    override suspend fun delete(scope:ZoneScope,recordId:String) { get(scope,recordId);rows(scope).remove(recordId) }
    override suspend fun write(action:DnsWriteAction,beforeSend:(DnsRecordDocument?)->Unit):ExecutionReceipt {
        val owner=zone(action.scope);val old=action.recordId?.let { get(action.scope,it) }
        val validated=action.input?.let { dnsWriteInput(owner.name,old,it,action.mode,DnsConstraints(),true) }
        val map=rows(action.scope)
        val id=action.recordId?:"example-created-${++sequence}"
        val result=validated?.let { DnsRecordDocument(id,action.scope,JsonObject((if(action.mode==DnsWriteMode.PATCH) old!!.raw else emptyMap())+dnsPayload(it)+("id" to JsonPrimitive(id)))) }
        beforeSend(old) // Final guard after every suspension; the following mutation is synchronous.
        if(action.mode==DnsWriteMode.DELETE) map.remove(id) else map[id]=result!!
        return ExecutionReceipt.Completed()
    }
    override suspend fun create(account:AccountScope,input:ZoneCreateInput):ZoneDetails { owned(account);return zoneDetails("example-zone-${++sequence}",input.name,type=input.type).also { zones[it.ref.zoneId]=it } }
    override suspend fun edit(scope:ZoneScope,input:ZoneEditInput):ZoneDetails {
        val old=zone(scope);return old.copy(paused=input.paused?:old.paused,type=input.type?:old.type,raw=JsonObject(old.raw+zoneEditFields(input))).also { zones[scope.zoneId]=it }
    }
    override suspend fun delete(scope:ZoneScope) { zone(scope);zones.remove(scope.zoneId);records.remove(scope) }
    override suspend fun activationCheck(scope:ZoneScope) { zone(scope) }
    override suspend fun write(action:ZoneAction,beforeSend:(ZoneDetails?)->Unit):ExecutionReceipt {
        owned(action.scope)
        val old=(action.scope as? ZoneScope)?.let { zone(it) }
        val next=when(action) {
            is ZoneAction.Create -> zoneDetails("example-zone-${++sequence}",action.input.name,type=action.input.type)
            is ZoneAction.Edit -> old!!.copy(paused=action.input.paused?:old.paused,type=action.input.type?:old.type,raw=JsonObject(old.raw+zoneEditFields(action.input)))
            else -> null
        }
        beforeSend(old)
        when(action) {
            is ZoneAction.Create -> zones[next!!.ref.zoneId]=next
            is ZoneAction.Edit -> zones[action.scope.zoneId]=next!!
            is ZoneAction.Delete -> { zones.remove(action.scope.zoneId);records.remove(action.scope) }
            is ZoneAction.ActivationCheck -> return ExecutionReceipt.Accepted() // Check request does not prove activation.
        }
        return ExecutionReceipt.Completed()
    }
}
