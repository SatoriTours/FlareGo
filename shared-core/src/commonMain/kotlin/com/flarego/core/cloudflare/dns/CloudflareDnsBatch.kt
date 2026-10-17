package com.flarego.core.cloudflare.dns

import com.flarego.core.application.dns.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.generated.DnsSchemas
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal data class BatchItem(val ref:DnsBatchItemRef,val before:DnsRecordDocument?,val input:DnsRecordInput?,val payload:JsonObject?,val desired:JsonObject)
internal data class BatchWrite(val scope:ZoneScope,val input:DnsBatchInput,val snapshot:DnsBatchSnapshot,val items:List<BatchItem>,val body:JsonObject)
internal fun batchInvalid():Nothing = throw CloudFailure(CloudFailureCode.INVALID_INPUT,"批量 DNS 输入无效、重复或超限，请检查各项后重新预览")
internal fun batchShape(input:DnsBatchInput,limit:Int=200) {
    if(limit !in setOf(200,3500)) batchInvalid()
    val count=input.deletes.size.toLong()+input.patches.size+input.puts.size+input.posts.size
    if(count !in 1..limit.toLong()) batchInvalid()
    val ids=input.deletes+input.patches.map { it.id }+input.puts.map { it.id }
    if(ids.any { !validDnsBatchProviderId(it) } || ids.distinct().size!=ids.size) batchInvalid()
    if(input.posts.any { it.type.trim().uppercase() !in DnsSchemas.types } || input.puts.any { it.input.type.trim().uppercase() !in DnsSchemas.types }) batchInvalid()
}
internal fun batchFreeze(input:DnsBatchInput)=DnsBatchInput(input.deletes.toList(),input.patches.map { it.copy(fields=dnsCopy(it.fields)) },
    input.puts.map { it.copy(input=it.input.copy(fields=dnsCopy(it.input.fields))) },input.posts.map { it.copy(fields=dnsCopy(it.fields)) })
internal fun batchBuild(scope:ZoneScope,input:DnsBatchInput,snapshot:DnsBatchSnapshot,constraints:DnsConstraints,allowNoop:Boolean=false):BatchWrite {
    batchShape(input,snapshot.maxOperations)
    val owner=requireDnsZone(snapshot.owner,scope)
    val requested=input.deletes+input.patches.map { it.id }+input.puts.map { it.id }
    if(snapshot.records.keys!=requested.toSet() || snapshot.postCandidates.size!=input.posts.size) batchInvalid()
    snapshot.records.forEach { (id,row) -> requireDnsDocument(row,scope,id,owner.name) }
    val items=mutableListOf<BatchItem>()
    input.deletes.forEachIndexed { index,id -> items+=BatchItem(DnsBatchItemRef(DnsBatchKind.DELETE,index,id),snapshot.records.getValue(id),null,null,dnsDeletedState(scope,id)) }
    input.patches.forEachIndexed { index,patch ->
        val before=snapshot.records.getValue(patch.id);val fields=patch.fields
        fun value(key:String)=fields[key]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: batchInvalid() } ?: dnsText(before.raw,key)
        val typed=DnsRecordInput(value("type"),value("name"),JsonObject(fields-"type"-"name"))
        val valid=dnsWriteInput(owner.name,before,typed,DnsWriteMode.PATCH,constraints,allowNoop)
        val payload=JsonObject(valid.fields+if(valid.name!=dnsText(before.raw,"name").lowercase().removeSuffix(".")) mapOf("name" to JsonPrimitive(valid.name)) else emptyMap())
        items+=BatchItem(DnsBatchItemRef(DnsBatchKind.PATCH,index,patch.id),before,valid,payload,dnsSemanticRecord(JsonObject(before.raw+payload)))
    }
    input.puts.forEachIndexed { index,put ->
        val before=snapshot.records.getValue(put.id)
        val valid=dnsWriteInput(owner.name,before,put.input,DnsWriteMode.REPLACE,constraints,allowNoop)
        items+=BatchItem(DnsBatchItemRef(DnsBatchKind.PUT,index,put.id),before,valid,dnsPayload(valid),dnsBatchPostSemantic(valid))
    }
    val postSemantics=mutableSetOf<JsonObject>()
    input.posts.forEachIndexed { index,post ->
        val valid=dnsWriteInput(owner.name,null,post,DnsWriteMode.CREATE,constraints,allowNoop)
        val desired=dnsBatchPostSemantic(valid)
        if(!postSemantics.add(desired)) batchInvalid()
        val candidates=snapshot.postCandidates[index]
        if(candidates.map { it.id }.distinct().size!=candidates.size) batchInvalid()
        candidates.forEach { requireDnsDocument(it,scope,it.id,owner.name) }
        if(candidates.any { dnsSemanticRecord(it.raw)==desired }) throw CloudFailure(CloudFailureCode.CONFLICT,"已有等价 DNS 记录，请查看现有记录，不要重复创建")
        items+=BatchItem(DnsBatchItemRef(DnsBatchKind.POST,index,null,valid.type,valid.name),null,valid,dnsPayload(valid),desired)
    }
    val body=buildJsonObject {
        for(kind in DnsBatchKind.entries) {
            val group=items.filter { it.ref.kind==kind }
            if(group.isNotEmpty()) put(batchGroup(kind),JsonArray(group.map { item ->
                if(kind==DnsBatchKind.POST) item.payload!! else JsonObject(item.payload.orEmpty()+mapOf("id" to JsonPrimitive(item.ref.recordId!!)))
            }))
        }
    }
    return BatchWrite(scope,input,snapshot,items,body)
}
internal fun batchGroup(kind:DnsBatchKind)=when(kind) { DnsBatchKind.DELETE->"deletes";DnsBatchKind.PATCH->"patches";DnsBatchKind.PUT->"puts";DnsBatchKind.POST->"posts" }
internal fun unknownBatch(items:List<BatchItem>)=DnsBatchOutcome(DnsBatchTransaction.UNKNOWN,items.map { DnsBatchItemResult(it.ref,null,DnsBatchVerification.UNKNOWN) },items.map { it.ref })

/** Typed reads only, complete candidate pages and fresh details. Never uses a name as a write locator. */
internal suspend fun batchCandidates(api:DnsManagementSession,scope:ZoneScope,type:String,name:String,zoneName:String):List<DnsRecordDocument> {
    val seen=mutableSetOf<String>();val tokens=mutableSetOf<PageToken>();val result=mutableListOf<DnsRecordDocument>()
    var token:PageToken?=null;var total:Long?=null;var count=0L;var pages=0
    do {
        if(++pages>1000) throw dnsInvalidResponse()
        val page=api.list(scope,DnsFilter(name=name,type=type),PageRequest(50,token))
        if(page.total==null || page.total<0 || (total!=null && total!=page.total)) throw dnsInvalidResponse()
        total=page.total
        for(row in page.items) {
            requireDnsDocument(row,scope,row.id,zoneName)
            if(!seen.add(row.id)) throw dnsInvalidResponse()
            count++
            if(dnsText(row.raw,"type").uppercase()==type && dnsText(row.raw,"name").lowercase().removeSuffix(".")==name) result+=row
        }
        token=page.next
        if(token!=null && !tokens.add(token)) throw dnsInvalidResponse()
    } while(token!=null)
    if(count!=total) throw dnsInvalidResponse()
    return result
}

/** Production batch transport. Every suspension precedes the final bound callback. */
internal class CloudflareDnsBatch(private val api:DnsManagementSession,private val gateway:CloudflareGateway,
    private val constraints:(ZoneScope)->DnsConstraints,private val limit:(ZoneScope)->Int={200}) {
    suspend fun observe(scope:ZoneScope,requested:DnsBatchInput,allowNoop:Boolean=false):DnsBatchSnapshot {
        val input=batchFreeze(requested);val max=limit(scope);batchShape(input,max)
        val owner=requireDnsZone(api.zone(scope),scope)
        val rows=linkedMapOf<String,DnsRecordDocument>()
        (input.deletes+input.patches.map { it.id }+input.puts.map { it.id }).forEach { id -> rows[id]=requireDnsDocument(api.get(scope,id),scope,id,owner.name) }
        val candidates=input.posts.map { post ->
            val valid=dnsWriteInput(owner.name,null,post,DnsWriteMode.CREATE,constraints(scope))
            batchCandidates(api,scope,valid.type,valid.name,owner.name)
        }
        val snapshot=DnsBatchSnapshot(owner,rows,candidates,max)
        batchBuild(scope,input,snapshot,constraints(scope),allowNoop)
        return snapshot
    }
    suspend fun write(scope:ZoneScope,requested:DnsBatchInput,beforeSend:(DnsBatchSnapshot)->Unit):DnsBatchOutcome {
        val input=batchFreeze(requested);val snapshot=observe(scope,input,allowNoop=true)
        val bundle=batchBuild(scope,input,snapshot,constraints(scope),allowNoop=true)
        // Quota is re-evaluated after every read. No UI/draft assertion can raise it.
        batchShape(input,limit(scope))
        val request=HttpRequest("POST","/zones/${encodePath(scope.zoneId)}/dns_records/batch",bundle.body.toString())
        beforeSend(snapshot)
        return try {
            val response=gateway.send(DNS_BATCH_KEY,request)
            val root=Json.parseToJsonElement(response.body) as? JsonObject ?: return unknownBatch(bundle.items)
            if(response.status in setOf(400,422) && validMessages(root["errors"],nonempty=true) && validMessages(root["messages"]) &&
                root["success"]==JsonPrimitive(false) && root["result"]==JsonNull) {
                DnsBatchOutcome(DnsBatchTransaction.CONFIRMED_REJECT,bundle.items.map { DnsBatchItemResult(it.ref,null,DnsBatchVerification.NOT_APPLIED) },emptyList())
            } else if(response.status!=200 || root["success"]!=JsonPrimitive(true) || !validMessages(root["messages"]) ||
                root["errors"]!=JsonArray(emptyList())) unknownBatch(bundle.items)
            else decodeCommitted(scope,bundle,root["result"] as? JsonObject ?: return unknownBatch(bundle.items))
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(_:Exception) { unknownBatch(bundle.items) }
    }
    private suspend fun decodeCommitted(scope:ZoneScope,bundle:BatchWrite,result:JsonObject):DnsBatchOutcome {
        val bindings=mutableMapOf<DnsBatchItemRef,String>();val ids=mutableSetOf<String>()
        for(kind in DnsBatchKind.entries) {
            val wanted=bundle.items.filter { it.ref.kind==kind }
            val raw=result[batchGroup(kind)]
            val rows=if(raw==null && wanted.isEmpty()) emptyList() else (raw as? JsonArray)?.map { it as? JsonObject ?: return unknownBatch(bundle.items) } ?: return unknownBatch(bundle.items)
            if(rows.size!=wanted.size) return unknownBatch(bundle.items)
            for(row in rows) {
                if(!completeRecord(row,bundle.snapshot.owner.name,constraints(scope))) return unknownBatch(bundle.items)
                val id=dnsText(row,"id")
                if(!ids.add(id)) return unknownBatch(bundle.items)
                val matches=if(kind==DnsBatchKind.POST) wanted.filter { it.desired==dnsSemanticRecord(row) }
                    else wanted.filter { it.ref.recordId==id && (kind==DnsBatchKind.DELETE || it.desired==dnsSemanticRecord(row)) }
                if(matches.size!=1 || bindings.put(matches.single().ref,id)!=null) return unknownBatch(bundle.items)
            }
        }
        val observed=bundle.items.map { item ->
            val id=bindings[item.ref] ?: return unknownBatch(bundle.items)
            val match=try {
                if(item.ref.kind==DnsBatchKind.DELETE) {
                    requireDnsZone(api.zone(scope),scope)
                    try { api.get(scope,id);false } catch(e:CloudFailure) { e.code==CloudFailureCode.NOT_FOUND }
                } else {
                    val actual=requireDnsDocument(api.get(scope,id),scope,id,bundle.snapshot.owner.name)
                    val matches=dnsSemanticRecord(actual.raw)==item.desired
                    if(item.ref.kind!=DnsBatchKind.POST) matches else {
                        val candidates=batchCandidates(api,scope,item.ref.createType!!,item.ref.createName!!,bundle.snapshot.owner.name)
                        val exact=candidates.map { requireDnsDocument(api.get(scope,it.id),scope,it.id,bundle.snapshot.owner.name) }.filter { dnsSemanticRecord(it.raw)==item.desired }
                        matches && exact.size==1 && exact.single().id==id && bundle.snapshot.postCandidates[item.ref.index].none { it.id==id }
                    }
                }
            } catch(cancelled:CancellationException) { throw cancelled } catch(_:Exception) { false }
            DnsBatchItemResult(item.ref,id,if(match) DnsBatchVerification.MATCHED else DnsBatchVerification.UNKNOWN)
        }
        return DnsBatchOutcome(DnsBatchTransaction.CONFIRMED_COMMIT,observed,observed.filter { it.verification!=DnsBatchVerification.MATCHED }.map { it.ref })
    }
}
private fun validMessages(value:JsonElement?,nonempty:Boolean=false):Boolean {
    val rows=value as? JsonArray ?: return false
    if(nonempty && rows.isEmpty()) return false
    if(rows.distinct().size!=rows.size) return false
    return rows.all { v ->
        val o=v as? JsonObject ?: return@all false
        val code=o["code"] as? JsonPrimitive
        val message=o["message"] as? JsonPrimitive
        code!=null && !code.isString && (code.longOrNull ?: 0)>=1000 && message?.isString==true &&
            (o["documentation_url"]==null || (o["documentation_url"] as? JsonPrimitive)?.isString==true) &&
            (o["source"]==null || (o["source"] is JsonObject && ((o["source"] as JsonObject)["pointer"]==null || ((o["source"] as JsonObject)["pointer"] as? JsonPrimitive)?.isString==true)))
    }
}
/** Faithful generic response required fields; unknown metadata survives but never grants write capability. */
private fun completeRecord(row:JsonObject,zoneName:String,constraints:DnsConstraints):Boolean = try {
    val required=setOf("name","type","content","data","proxied","ttl","settings","comment","tags","id","proxiable","created_on","modified_on","meta")
    if(!row.keys.containsAll(required) || dnsText(row,"id").length !in 1..32) false
    else {
        val type=dnsText(row,"type");val schema=DnsSchemas.forType(type) ?: throw dnsInvalidResponse()
        val keys=LocalDnsSchema(schema).properties().keys
        val valid=validateDnsInput(zoneName,DnsRecordInput(type,dnsText(row,"name"),JsonObject(row.filterKeys { it in keys && it !in setOf("type","name") })),constraints,DnsWriteMethod.POST)
        (row["data"] is JsonObject) && row["settings"] is JsonObject && row["meta"] is JsonObject &&
            (row["content"] as? JsonPrimitive)?.isString==true && (row["proxied"] as? JsonPrimitive)?.booleanOrNull!=null &&
            (row["proxiable"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull!=null &&
            listOf("created_on","modified_on").all { key -> dnsText(row,key).matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?Z")) } && valid.type==type
    }
} catch(_:Exception) { false }
