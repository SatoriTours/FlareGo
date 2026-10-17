package com.flarego.core

import com.flarego.core.application.dns.*
import com.flarego.core.application.operations.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlin.test.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.dns.CloudflareDnsClient
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.demo.DemoDnsManagement
import com.flarego.core.application.management.ManagementOperationStore
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DnsBatchTest {
    private val account=AccountScope("connection","account")
    private val zone=ZoneScope(account,"zone")
    private val single=DNS_WRITE_KEYS.first()
    private class Store:DnsBatchEvidenceStore {
        val evidence=linkedMapOf<String,DnsBatchTaskEvidence>()
        var failMetadata=false
        override fun batchEvidence(taskId:String)=evidence[taskId]
        override fun putWithSummaryAndEvidence(task:OperationTask,summary:Operation,e:DnsBatchTaskEvidence) { putWithSummary(task,summary);evidence[task.id]=e }
        val rows=linkedMapOf<String,OperationTask>()
        override fun get(id:String)=rows[id]
        override fun list(connectionId:String)=rows.values.filter { it.connectionId==connectionId }
        override fun put(task:OperationTask) { rows[task.id]=task }
        override fun putWithSummary(task:OperationTask,summary:Operation) { if(failMetadata) error("synthetic metadata fault");put(task) }
        override fun removeConnection(connectionId:String) { val ids=rows.values.filter { it.connectionId==connectionId }.map { it.id };rows.keys.removeAll(ids.toSet());evidence.keys.removeAll(ids.toSet()) }
    }
    private fun task(key:String,scope:ZoneScope=zone,state:TaskState=TaskState.UNKNOWN,ref:ResourceRef?=ResourceRef(scope,"future-kind","target"))=OperationTask("unsettled",scope.connectionId,scope,key,ref,"a".repeat(64),"b".repeat(64),state,"结果待核对",null,null,1,1)
    @Test fun importAndBatchUncertaintyBlockEverySingleModeRegardlessOfReference() = runTest {
        for(key in listOf(DNS_IMPORT_KEY,DNS_BATCH_KEY)) for(state in DNS_MUTATION_UNSETTLED) for(ref in listOf(null,ResourceRef(zone,"future-kind","anything"))) {
            val store=Store();store.put(task(key,state=state,ref=ref));val gate=DnsUncertaintyGate(store,StandardTestDispatcher(testScheduler))
            for(incoming in DNS_WRITE_KEYS) assertEquals(CloudFailureCode.UNKNOWN_RESULT,assertFailsWith<CloudFailure> { gate.requireClear(zone,incoming,ResourceRef(zone,"dns_record","other")) }.code)
        }
    }
    @Test fun singleBatchAndImportUncertaintyBlockWholeZoneOperations() = runTest {
        for(key in DNS_MUTATION_KEYS) {
            val store=Store();store.put(task(key));val gate=DnsUncertaintyGate(store,StandardTestDispatcher(testScheduler))
            for(incoming in listOf(DNS_BATCH_KEY,DNS_IMPORT_KEY)) assertEquals(CloudFailureCode.UNKNOWN_RESULT,assertFailsWith<CloudFailure> { gate.requireClear(zone,incoming,ResourceRef(zone,"dns_import",zone.zoneId)) }.code)
        }
    }
    @Test fun durableGateDoesNotBlockOtherCompleteScopesTerminalTasksOrReadOnlyExports() = runTest {
        for(row in listOf(task(DNS_IMPORT_KEY,ZoneScope(account,"other-zone")),task(DNS_BATCH_KEY,state=TaskState.FAILED),task(DNS_IMPORT_KEY,state=TaskState.SUCCEEDED),task("dns-records-for-a-zone-export-dns-records"))) {
            val store=Store();store.put(row)
            DnsUncertaintyGate(store,StandardTestDispatcher(testScheduler)).requireClear(zone,DNS_BATCH_KEY)
        }
    }
    @Test fun onlyCurrentTaskMayBeExcludedAndUnknownMutationKindsFailClosed() = runTest {
        val store=Store();store.put(task(DNS_IMPORT_KEY));val gate=DnsUncertaintyGate(store,StandardTestDispatcher(testScheduler))
        gate.requireClear(zone,DNS_BATCH_KEY,excludingTaskId="unsettled")
        assertEquals(CloudFailureCode.UNKNOWN_RESULT,assertFailsWith<CloudFailure> { gate.requireClear(zone,DNS_BATCH_KEY,excludingTaskId="other") }.code)
        assertEquals(CloudFailureCode.INVALID_INPUT,assertFailsWith<CloudFailure> { gate.requireClear(zone,"invented-write") }.code)
    }
    private fun obj(text:String)=Json.parseToJsonElement(text).jsonObject
    private val connection=Connection("connection","Cloudflare","account")
    private fun input(name:String="new",content:String="192.0.2.8")=DnsRecordInput("A",name,obj("""{"content":"$content","ttl":60}"""))
    private fun row(id:String,name:String="www.example.com",extra:JsonObject=JsonObject(emptyMap()))=obj("""{"id":"$id","type":"A","name":"$name","content":"192.0.2.1","ttl":60,"proxied":false,"settings":{},"comment":null,"tags":[],"proxiable":true,"created_on":"2026-01-01T00:00:00Z","modified_on":"2026-01-01T00:00:00Z","meta":{}}""").let { JsonObject(it+extra) }
    private fun ok(result:JsonElement,info:JsonObject?=null)=HttpResponse(200,buildJsonObject { put("success",true);put("errors",JsonArray(emptyList()));put("messages",JsonArray(emptyList()));put("result",result);info?.let { put("result_info",it) } }.toString())
    private inner class Adapter(val dnsConstraints:DnsConstraints=DnsConstraints(),quota:DnsBatchQuotaAuthority?=null) {
        val records=linkedMapOf("delete" to row("delete"),"patch" to row("patch"),"put" to row("put"))
        val requests=mutableListOf<HttpRequest>()
        var response:HttpResponse?=null
        var failAfterApply=false
        var responseTransform:(JsonObject)->JsonObject={ it }
        var onRead:suspend (HttpRequest)->Unit={}
        var onSend:suspend ()->Unit={}
        var ownerAccount="account"
        var listingInfo:JsonObject?=null
        private val gateway=CloudflareGateway(HttpTransport { request ->
            requests+=request
            if(request.method=="GET") {
                onRead(request)
                when {
                    request.path=="/zones/zone" -> ok(obj("""{"id":"zone","name":"example.com","account":{"id":"$ownerAccount"},"status":"active","type":"full","paused":false}"""))
                    request.path.substringBefore('?')=="/zones/zone/dns_records" -> ok(JsonArray(records.values.toList()),listingInfo?:obj("""{"page":1,"per_page":50,"total_pages":1,"total_count":${records.size}}"""))
                    else -> records[java.net.URLDecoder.decode(request.path.substringAfterLast('/'),"UTF-8")]?.let { ok(it) } ?: HttpResponse(404,"")
                }
            } else {
                onSend()
                response ?: run {
                    val body=obj(request.body!!)
                    val result=buildJsonObject {
                        for(group in listOf("deletes","patches","puts","posts")) {
                            val changed=(body[group] as? JsonArray).orEmpty().mapIndexed { index,value ->
                                val payload=value.jsonObject
                                val id=payload["id"]?.jsonPrimitive?.content ?: "provider-$index"
                                when(group) {
                                    "deletes" -> records.remove(id)!!
                                    "patches" -> JsonObject(records.getValue(id)+payload).also { records[id]=it }
                                    else -> row(id,extra=payload).also { records[id]=it }
                                }
                            }
                            put(group,JsonArray(changed.reversed().map { JsonObject(it+mapOf("data" to (it["data"] ?: JsonObject(emptyMap())), "comment" to (it["comment"].takeUnless { c -> c == JsonNull } ?: JsonPrimitive("")))) }))
                        }
                    }
                    if(failAfterApply) throw CloudFailure(CloudFailureCode.UNAVAILABLE,"timeout",uncertain=true)
                    ok(responseTransform(result))
                }
            }
        })
        val api=if(quota==null) CloudflareDnsClient(connection,gateway) { dnsConstraints } else CloudflareDnsClient(connection,gateway,quota) { dnsConstraints }
        val writes get()=requests.filter { it.method!="GET" }
        val mixed get()=DnsBatchInput(listOf("delete"),listOf(DnsBatchPatch("patch",obj("""{"proxied":false,"comment":"changed"}"""))),listOf(DnsBatchPut("put",input("replaced"))),listOf(input()))
    }
    @Test fun batchPolicyIsFixedWriteDestructiveAndNeverRetries() {
        val r=assertNotNull(ActionPolicies.registry.registration(DNS_BATCH_KEY))
        assertEquals("POST",r.method);assertEquals("/zones/{zone_id}/dns_records/batch",r.pathTemplate)
        assertEquals(ActionSemantics.WRITE,r.policy.semantics);assertEquals(RiskLevel.DESTRUCTIVE,r.policy.risk);assertFalse(r.policy.retry.enabled)
    }
    @Test fun directProductionBatchUsesFixedGroupsDeltaAndRealIds() = runTest {
        val h=Adapter();val result=h.api.batch(zone,h.mixed)
        assertEquals(DnsBatchTransaction.CONFIRMED_COMMIT,result.transaction);assertTrue(result.pending.isEmpty());assertEquals(4,result.items.size)
        assertEquals(listOf("delete","patch","put","provider-0"),result.items.map { it.providerId })
        val write=h.writes.single();assertEquals("POST",write.method);assertEquals("/zones/zone/dns_records/batch",write.path)
        val body=obj(write.body!!);assertEquals(obj("""{"id":"delete"}"""),body["deletes"]!!.jsonArray.single())
        assertEquals(setOf("id","comment"),body["patches"]!!.jsonArray.single().jsonObject.keys)
        assertFalse(body["posts"]!!.jsonArray.single().jsonObject.containsKey("id"))
    }
    @Test fun invalidItemDuplicatesAndUnknownNestedHaveZeroBatchSends() = runTest {
        val bad=listOf(DnsBatchInput(posts=listOf(input(),input().copy(type="FUTURE"))),DnsBatchInput(deletes=listOf("delete","delete")),DnsBatchInput(deletes=listOf("patch"),patches=listOf(DnsBatchPatch("patch",obj("""{"comment":"new"}""")))),DnsBatchInput(posts=listOf(input(),input())))
        for(value in bad) { val h=Adapter();assertFailsWith<CloudFailure> { h.api.batch(zone,value) };assertTrue(h.writes.isEmpty()) }
        val h=Adapter();h.records["patch"]=row("patch",extra=obj("""{"settings":{"future":true}}"""))
        assertFailsWith<CloudFailure> { h.api.batch(zone,DnsBatchInput(patches=listOf(DnsBatchPatch("patch",obj("""{"settings":{"ipv4_only":true}}"""))))) };assertTrue(h.writes.isEmpty())
    }
    @Test fun falseIsSentWhenChangedAndOmittedFieldsStayOmitted() = runTest {
        val h=Adapter();h.records["patch"]=row("patch",extra=obj("""{"proxied":true}"""))
        h.api.batch(zone,DnsBatchInput(patches=listOf(DnsBatchPatch("patch",obj("""{"proxied":false}""")))))
        assertEquals(obj("""{"id":"patch","proxied":false}"""),obj(h.writes.single().body!!)["patches"]!!.jsonArray.single())
    }
    @Test fun incompleteContradictoryAndPartialSuccessKeepUnknown() = runTest {
        val transforms:List<(JsonObject)->JsonObject> = listOf({ JsonObject(it-"posts") },{ JsonObject(it+("patches" to JsonArray(emptyList()))) },{ JsonObject(it+("posts" to JsonArray(listOf(row("unrelated"))))) },{ JsonObject(it+("puts" to JsonArray(listOf(row("wrong"))))) })
        for(transform in transforms) { val h=Adapter();h.responseTransform=transform;val result=h.api.batch(zone,h.mixed);assertEquals(DnsBatchTransaction.UNKNOWN,result.transaction);assertTrue(result.pending.isNotEmpty());assertEquals(1,h.writes.size) }
    }
    @Test fun trustedCompleteRejectIsDistinctFromAmbiguousStatuses() = runTest {
        val failure="""{"success":false,"errors":[{"code":1000,"message":"rejected"}],"messages":[],"result":null}"""
        val h=Adapter();h.response=HttpResponse(400,failure);val before=h.records.toMap()
        assertEquals(DnsBatchTransaction.CONFIRMED_REJECT,h.api.batch(zone,h.mixed).transaction);assertEquals(before,h.records);assertEquals(1,h.writes.size)
        for(response in listOf(HttpResponse(429,failure),HttpResponse(500,failure),HttpResponse(400,"{}"),HttpResponse(200,failure))) {
            val bad=Adapter();bad.response=response;assertEquals(DnsBatchTransaction.UNKNOWN,bad.api.batch(zone,bad.mixed).transaction);assertEquals(1,bad.writes.size)
        }
    }
    @Test fun timeoutAfterOneSendNeverReplaysAndRetainsPendingItems() = runTest {
        val h=Adapter();h.failAfterApply=true;val result=h.api.batch(zone,h.mixed)
        assertEquals(DnsBatchTransaction.UNKNOWN,result.transaction);assertEquals(4,result.pending.size);assertEquals(1,h.writes.size)
    }
    @Test fun quotaUnknownRejects201AndFinalGuardFollowsEverySuspension() = runTest {
        val h=Adapter();assertFailsWith<CloudFailure> { h.api.batch(zone,DnsBatchInput(posts=(1..201).map { input("n$it") })) };assertTrue(h.writes.isEmpty())
        var valid=true;h.onRead={ valid=false }
        assertFailsWith<CloudFailure> { h.api.writeBatch(zone,h.mixed) { if(!valid) throw CloudFailure(CloudFailureCode.CONFLICT,"changed") } };assertTrue(h.writes.isEmpty())
    }
    @Test fun responseOrderCannotAssignUnrelatedCreationIds() = runTest {
        val h=Adapter();val result=h.api.batch(zone,DnsBatchInput(posts=listOf(input("one"),input("two","192.0.2.9"))))
        assertEquals(listOf("provider-0","provider-1"),result.items.map { it.providerId });assertTrue(result.pending.isEmpty())
    }
    @Test fun demoUsesSamePortAndAtomicValidationBeforeApplyingAnyItem() = runTest {
        val c=Connection("example","示例","example-account",demo=true);val s=ZoneScope(AccountScope(c.id,c.accountId),"zone-demo");val api=DemoDnsManagement(c)
        val original=api.get(s,"example-1")
        assertFailsWith<CloudFailure> { api.batch(s,DnsBatchInput(deletes=listOf("example-1"),posts=listOf(input().copy(type="FUTURE")))) }
        assertEquals(original,api.get(s,"example-1"))
        val result=api.batch(s,DnsBatchInput(deletes=listOf("example-1"),posts=listOf(input("new.flarego.dev"))))
        assertEquals(DnsBatchTransaction.CONFIRMED_COMMIT,result.transaction);assertTrue(result.pending.isEmpty());assertTrue(result.items.last().providerId!!.startsWith("example-"))
    }

    @Test fun publicPostSemanticReusesIpv6AndKnownDefaultsWithoutNetwork() {
        val one=DnsRecordInput("AAAA","www.example.com",obj("""{"content":"2001:0DB8:0000:0000:0000:0000:0000:1"}"""))
        val two=DnsRecordInput("AAAA","www.example.com.",obj("""{"content":"2001:db8::1","ttl":1,"proxied":false,"comment":null,"tags":[],"settings":{"ipv4_only":false}}"""))
        assertEquals(dnsBatchPostSemantic(one),dnsBatchPostSemantic(two))
        assertNotEquals(dnsBatchPostSemantic(one),dnsBatchPostSemantic(one.copy(fields=obj("""{"content":"2001:db8::2"}"""))))
    }

    @Test fun enterpriseTtl30ResponseUsesSameTrustedScopeConstraints() = runTest {
        val h=Adapter(DnsConstraints(minTtl=30));val value=input().copy(fields=obj("""{"content":"192.0.2.8","ttl":30}"""))
        val result=h.api.batch(zone,DnsBatchInput(posts=listOf(value)))
        assertEquals(DnsBatchTransaction.CONFIRMED_COMMIT,result.transaction);assertTrue(result.pending.isEmpty());assertEquals(1,h.writes.size)
        val unknown=Adapter();assertFailsWith<CloudFailure> { unknown.api.batch(zone,DnsBatchInput(posts=listOf(value))) };assertTrue(unknown.writes.isEmpty())
    }

    private val fingerprint=FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
    private inner class Product(val test:TestScope,val h:Adapter=Adapter(),legacyStore:OperationStore?=null) {
        val real=Store();val store=ManagementOperationStore(legacyStore?:real,fingerprint) { false }
        var live:LiveOperationContext?=null;var current=true;var beforeSession:suspend ()->Unit={}
        private val resolver=object:OperationContextResolver {
            override fun current()=live.takeIf { current }
            override fun liveConnections()=listOf(connection)
            override fun isLive(scope:CloudScope,credentialRevision:Long?)=current && scope==zone && (credentialRevision==null || credentialRevision==live?.credentialRevision)
        }
        val clock=OperationClock { 1000 };var ids=0
        val dispatcher=StandardTestDispatcher(test.testScheduler)
        val plans=ChangePlans(resolver,clock,OperationIds { "product-${++ids}" })
        val coordinator=OperationCoordinator(plans,store,resolver,clock,test.backgroundScope,dispatcher)
        val controller=DnsBatchController(resolver,plans,coordinator,clock,fingerprint,store,dispatcher) { beforeSession();h.api }
        fun select(input:DnsBatchInput,revision:Long=0) { live=LiveOperationContext(connection,zone,revision,controller.requestFingerprint(zone,input)) }
        suspend fun prepare(input:DnsBatchInput=h.mixed):PreparedDnsBatch { select(input);return controller.prepare(zone,input) }
        fun confirm(p:PreparedDnsBatch)=plans.confirm(p.plan.id,zone,live!!.credentialRevision,live!!.requestFingerprint,1000)
        fun restored():DnsBatchController {
            val issuer=ChangePlans(resolver,clock,OperationIds { "restored-${++ids}" })
            return DnsBatchController(resolver,issuer,OperationCoordinator(issuer,store,resolver,clock,test.backgroundScope,dispatcher),clock,fingerprint,store,dispatcher) { h.api }
        }
        fun singles()=DnsChanges(resolver,plans,coordinator,clock,fingerprint,store,dispatcher) { h.api }
        fun selectSingle(value:DnsRecordInput?,mode:DnsWriteMode,id:String?) { live=LiveOperationContext(connection,zone,0,singles().requestFingerprint(zone,id,value,mode)) }
    }
    @Test fun manifestStagingIsInertAndCanonicalWritesAtomicallyBindEvidence() {
        val real=Store();val wrapper=ManagementOperationStore(real,fingerprint) { false }
        val items=listOf(DnsBatchItemEvidence(DnsBatchKind.DELETE,0,"record",null,null,"c".repeat(64),"d".repeat(64)))
        val evidence=DnsBatchTaskEvidence(1,"staged",zone,listOf(1,0,0,0),items,dnsBatchManifestHash(zone,items,fingerprint))
        val task=task(DNS_BATCH_KEY,ref=ResourceRef(zone,"dns_batch",zone.zoneId)).copy(id="staged",desiredHash=evidence.manifestHash,state=TaskState.QUEUED)
        wrapper.stageBatchEvidence(task,evidence);assertTrue(real.rows.isEmpty());assertNull(wrapper.batchEvidence(task.id))
        wrapper.putWithSummary(task,OperationTaskPersistence.summaryFor(task));assertEquals(evidence,real.batchEvidence(task.id))
        wrapper.putWithSummary(task.copy(state=TaskState.UNKNOWN),OperationTaskPersistence.summaryFor(task.copy(state=TaskState.UNKNOWN)))
        assertEquals(evidence,wrapper.batchEvidence(task.id));wrapper.removeConnection(zone.connectionId);assertNull(wrapper.batchEvidence(task.id))
    }
    @Test fun batchPrepareIsInertAndExplicitConfirmationPersistsOnlySafeEvidence() = runTest {
        val p=Product(this);val plan=p.prepare();assertTrue(p.real.rows.isEmpty());assertTrue(p.h.writes.isEmpty())
        assertEquals(4,p.controller.preview(plan.plan.id).targets.size)
        val task=p.controller.submit(p.confirm(plan));assertEquals(TaskState.SUCCEEDED,task.state);assertEquals(1,p.h.writes.size)
        val evidence=assertNotNull(p.real.batchEvidence(task.id));assertEquals(DnsBatchTransaction.CONFIRMED_COMMIT,evidence.transaction);assertEquals("provider-0",evidence.items.last().providerId)
        assertFalse(evidence.toString().contains("192.0.2."));assertFalse(task.toString().contains("changed"))
    }
    @Test fun canonicalRejectedReceiptProducesFailedAndZeroAppliedItems() = runTest {
        val p=Product(this);p.h.response=HttpResponse(422,"""{"success":false,"errors":[{"code":1000,"message":"reject"}],"messages":[],"result":null}""")
        val before=p.h.records.toMap();val plan=p.prepare();val task=p.controller.submit(p.confirm(plan))
        assertEquals(TaskState.FAILED,task.state);assertEquals(before,p.h.records);assertEquals(1,p.h.writes.size)
        assertEquals(DnsBatchTransaction.CONFIRMED_REJECT,p.real.batchEvidence(task.id)!!.transaction)
    }
    @Test fun preflightConflictNeverSendsAndIsExplicitConflict() = runTest {
        val p=Product(this);val plan=p.prepare();p.h.records["patch"]=JsonObject(p.h.records.getValue("patch")+("comment" to JsonPrimitive("external")))
        val failure=assertFailsWith<CloudFailure> { p.controller.submit(p.confirm(plan)) }
        assertEquals(CloudFailureCode.CONFLICT,failure.code);assertTrue(p.h.writes.isEmpty());assertEquals(TaskState.ACTION_REQUIRED,p.real.rows.values.single().state)
    }
    @Test fun actualDraftAndCredentialChangesInvalidatePriorConfirmation() = runTest {
        for(change in listOf("draft","credential","session")) {
            val p=Product(this);val plan=p.prepare();val confirm=p.confirm(plan)
            when(change) {
                "draft" -> p.select(DnsBatchInput(posts=listOf(input("other"))))
                "credential" -> p.live=p.live!!.copy(credentialRevision=1)
                else -> p.beforeSession={ p.current=false }
            }
            assertFailsWith<CloudFailure> { p.controller.submit(confirm) };assertTrue(p.h.writes.isEmpty())
        }
    }
    @Test fun prepreparedSecondConfirmationCannotBypassNewUnknownTask() = runTest {
        val p=Product(this);val first=p.prepare();val second=p.prepare();val c1=p.confirm(first);val c2=p.confirm(second);p.h.failAfterApply=true
        assertEquals(TaskState.UNKNOWN,p.controller.submit(c1).state)
        assertEquals(CloudFailureCode.UNKNOWN_RESULT,assertFailsWith<CloudFailure> { p.controller.submit(c2) }.code);assertEquals(1,p.h.writes.size)
        assertFailsWith<CloudFailure> { p.prepare() };assertEquals(1,p.h.writes.size)
    }
    @Test fun rebuildUsesManifestReadLocatorsOnlyAndDoesNotRestoreConfirmation() = runTest {
        val p=Product(this);p.h.failAfterApply=true;val plan=p.prepare();val confirmed=p.confirm(plan);val task=p.controller.submit(confirmed)
        assertEquals(TaskState.UNKNOWN,task.state);assertEquals(1,p.h.writes.size)
        val rebuilt=p.restored();assertFailsWith<CloudFailure> { rebuilt.submit(confirmed) }
        val read=rebuilt.reconcile(task.id);assertEquals(TaskState.SUCCEEDED,read.state);assertEquals(1,p.h.writes.size)
        assertEquals(DnsBatchTransaction.UNKNOWN,p.real.batchEvidence(task.id)!!.transaction)
    }
    @Test fun missingOrPartialManifestObservationStaysUnknownWithoutReplaying() = runTest {
        for(missing in listOf(false,true)) {
            val p=Product(this);p.h.failAfterApply=true;val plan=p.prepare();val task=p.controller.submit(p.confirm(plan))
            if(missing) p.real.evidence.remove(task.id) else p.h.records["patch"]=JsonObject(p.h.records.getValue("patch")+("comment" to JsonPrimitive("external")))
            assertEquals(TaskState.UNKNOWN,p.restored().reconcile(task.id).state);assertEquals(1,p.h.writes.size)
        }
    }
    @Test fun oldFakeWithoutAtomicEvidenceCapabilityRefusesBeforeAnySend() = runTest {
        val legacy=object:OperationStore by Store() {};val p=Product(this,legacyStore=legacy);val plan=p.prepare()
        assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY,assertFailsWith<CloudFailure> { p.controller.submit(p.confirm(plan)) }.code);assertTrue(p.h.writes.isEmpty())
    }
    @Test fun batchAndImportUnknownBlockRealSingleChangesPrepareInEveryMode() = runTest {
        for(key in listOf(DNS_BATCH_KEY,DNS_IMPORT_KEY)) for(mode in DnsWriteMode.entries) {
            val p=Product(this);p.real.put(task(key,ref=null));val id=if(mode==DnsWriteMode.CREATE) null else "patch"
            val value=when(mode) { DnsWriteMode.DELETE->null;DnsWriteMode.PATCH->input("www.example.com").copy(fields=obj("""{"comment":"new"}"""));else->input() }
            p.selectSingle(value,mode,id)
            assertEquals(CloudFailureCode.UNKNOWN_RESULT,assertFailsWith<CloudFailure> { p.singles().prepare(zone,id,value,mode) }.code);assertTrue(p.h.writes.isEmpty())
        }
    }

    @Test fun patchPreviewAfterRetainsUnchangedKnownFieldsButWireStaysDelta() = runTest {
        val p=Product(this);val plan=p.prepare();val preview=p.controller.preview(plan.plan.id)
        val patch=preview.targets.single { it.kind==DnsBatchKind.PATCH }
        assertEquals(JsonPrimitive("192.0.2.1"),patch.after!!.fields["content"])
        assertEquals(JsonPrimitive(60),patch.after!!.fields["ttl"])
        assertEquals(JsonPrimitive("changed"),patch.after!!.fields["comment"])
        assertNull(preview.targets.single { it.kind==DnsBatchKind.DELETE }.after)
        p.controller.submit(p.confirm(plan))
        assertEquals(setOf("id","comment"),obj(p.h.writes.single().body!!)["patches"]!!.jsonArray.single().jsonObject.keys)
    }
    private inner class Application(val test:TestScope,val demo:Boolean=false,val real:Store=Store()) {
        val owner=if(demo) Connection("example","示例","example-account",demo=true) else connection
        val scope=if(demo) ZoneScope(AccountScope(owner.id,owner.accountId),"zone-demo") else zone
        var state=AppState(listOf(owner),owner,page=Page.DNS,domain=Domain(scope.zoneId,if(demo) "flarego.dev" else "example.com","active",owner.id))
        var epoch=0L;var revision=0L;var ids=0;var legacySessions=0;var legacyWrites=0
        var acquire:suspend ()->Unit={}
        val h=Adapter()
        val runtime=AppOperationRuntime(test.backgroundScope,real,StandardTestDispatcher(test.testScheduler),{state},{epoch},{revision},SessionFactory {
            legacySessions++
            object:CloudSession by com.flarego.core.demo.DemoSession(owner) {
                override suspend fun save(zoneId:String,recordId:String?,draft:DnsDraft) { legacyWrites++ }
                override suspend fun delete(zoneId:String,recordId:String) { legacyWrites++ }
            }
        },fingerprint,OperationClock {1000},OperationIds {"runtime-${++ids}"},{},ManagementSessionFactory {
            acquire();ManagementServices(h.api,DemoDnsManagement(owner.copy(demo=true)))
        })
        val batch get()=runtime.management.batch!!
        fun bind() { batch.onIntent(DnsBatchIntent.Bind(scope)) }
        fun preview(input:DnsBatchInput=if(demo) DnsBatchInput(posts=listOf(DnsRecordInput("TXT","new.flarego.dev",obj("""{"content":"example","ttl":60}""")))) else h.mixed) { batch.onIntent(DnsBatchIntent.Preview(input)) }
        fun confirm() { val p=batch.state.value.preparedChange!!;batch.onIntent(DnsBatchIntent.Confirm(p.id,p.revision)) }
    }
    @Test fun realRuntimePortsBindFreshZonePreviewAndConfirmThroughSingleDomain() = runTest {
        val a=Application(this);a.bind();runCurrent();assertEquals("example.com",a.batch.state.value.zoneName)
        a.preview();runCurrent();assertNotNull(a.batch.state.value.preparedChange);assertTrue(a.real.rows.isEmpty());assertTrue(a.h.writes.isEmpty())
        a.confirm();runCurrent();assertEquals(TaskState.SUCCEEDED,a.batch.state.value.pendingTask?.state);assertNull(a.batch.state.value.preparedChange);assertNull(a.batch.state.value.draft)
        assertEquals(1,a.h.writes.size);assertEquals(1,a.real.evidence.size)
    }
    @Test fun runtimePendingFamilyCoversSingleBatchImportDuringBindDraftAndPreview() = runTest {
        for(key in DNS_MUTATION_KEYS) {
            val a=Application(this);a.real.put(task(key,ref=null));a.bind();runCurrent()
            assertEquals(key,a.batch.state.value.pendingTask?.actionKey)
            a.batch.onIntent(DnsBatchIntent.DraftChanged(a.h.mixed));runCurrent();assertEquals(key,a.batch.state.value.pendingTask?.actionKey)
            a.preview();runCurrent();assertNull(a.batch.state.value.preparedChange);assertTrue(a.h.writes.isEmpty())
        }
    }
    @Test fun legacyCannotBypassPersistedBatchOrImportUnknown() = runTest {
        for(key in listOf(DNS_BATCH_KEY,DNS_IMPORT_KEY)) for(mode in listOf("create","patch","delete")) {
            val a=Application(this);a.real.put(task(key,ref=null))
            val failure=assertFailsWith<CloudFailure> {
                when(mode) { "create"->a.runtime.create(zone,DnsDraft("A","new.example.com","192.0.2.8"));"patch"->a.runtime.update(zone,"patch",DnsDraft("A","www.example.com","192.0.2.8"));else->a.runtime.delete(zone,"patch") }
            }
            assertEquals(CloudFailureCode.UNKNOWN_RESULT,failure.code);assertEquals(0,a.legacySessions);assertEquals(0,a.legacyWrites)
        }
    }
    @Test fun runtimeDraftEpochAndCredentialChangesInvalidateWithoutEditorReset() = runTest {
        val a=Application(this);a.bind();runCurrent();a.preview();runCurrent();val p=a.batch.state.value.preparedChange!!;val editor=a.batch.state.value.editorRevision
        a.batch.onIntent(DnsBatchIntent.DraftInvalidated);assertEquals(editor,a.batch.state.value.editorRevision)
        a.batch.onIntent(DnsBatchIntent.Confirm(p.id,p.revision));runCurrent();assertTrue(a.h.writes.isEmpty())
        a.preview();runCurrent();val old=a.batch.state.value.preparedChange!!;a.revision++
        a.batch.onIntent(DnsBatchIntent.Confirm(old.id,old.revision));runCurrent();assertTrue(a.h.writes.isEmpty());assertNotNull(a.batch.state.value.error)
    }
    @Test fun oldBindReadCannotPublishAfterAccountEpochChange() = runTest {
        val a=Application(this);val pause=CompletableDeferred<Unit>();a.h.onRead={ pause.await() };a.bind();runCurrent()
        a.epoch++;a.state=a.state.copy(selected=connection.copy(id="other",accountId="other-account"));a.batch.onIntent(DnsBatchIntent.Clear)
        pause.complete(Unit);runCurrent();assertNull(a.batch.state.value.scope);assertNull(a.batch.state.value.zoneName)
    }
    @Test fun runtimeBatchSharesGlobalWriteMutexWithLegacyAndSingle() = runTest {
        val a=Application(this);a.bind();runCurrent();a.preview();runCurrent();val pause=CompletableDeferred<Unit>();a.h.onSend={pause.await()};a.confirm();runCurrent()
        assertEquals(1,a.h.writes.size)
        assertEquals(CloudFailureCode.CONFLICT,assertFailsWith<CloudFailure> { a.runtime.update(zone,"patch",DnsDraft("A","www.example.com","192.0.2.8")) }.code)
        a.runtime.management.dns.onIntent(DnsIntent.Load(zone));runCurrent()
        a.runtime.management.dns.onIntent(DnsIntent.Preview(DnsRecordInput("A","www.example.com",obj("""{"comment":"another"}""")),DnsWriteMode.PATCH,"patch"));runCurrent()
        assertNull(a.runtime.management.dns.state.value.preparedChange);assertEquals(1,a.h.writes.size)
        pause.complete(Unit);runCurrent();assertEquals(TaskState.SUCCEEDED,a.batch.state.value.pendingTask?.state)
    }
    @Test fun cancellingBatchPageWaiterDoesNotCancelAppWriteOrReplay() = runTest {
        val a=Application(this);a.bind();runCurrent();a.preview();runCurrent();val pause=CompletableDeferred<Unit>();a.h.onSend={pause.await()};a.confirm();runCurrent()
        a.batch.onIntent(DnsBatchIntent.Cancel);pause.complete(Unit);runCurrent();assertEquals(1,a.h.writes.size);assertEquals(TaskState.SUCCEEDED,a.real.rows.values.single().state)
        a.bind();runCurrent();assertEquals(TaskState.SUCCEEDED,a.batch.state.value.pendingTask?.state);assertEquals(1,a.h.writes.size)
    }
    @Test fun runtimeDisconnectLatchPreventsLateBatchEvidenceResurrection() = runTest {
        val a=Application(this);a.bind();runCurrent();a.preview();runCurrent();val pause=CompletableDeferred<Unit>();a.h.onSend={pause.await()};a.confirm();runCurrent()
        a.runtime.removeConnection(connection.id) { a.state=AppState(emptyList(),null) }
        a.batch.onIntent(DnsBatchIntent.Clear);pause.complete(Unit);runCurrent()
        assertTrue(a.real.rows.isEmpty());assertTrue(a.real.evidence.isEmpty());assertEquals(1,a.h.writes.size)
    }
    @Test fun runtimeRebuildReconcilesSafeManifestAndImportNeverRoutesAsRecord() = runTest {
        val a=Application(this);a.h.failAfterApply=true;a.bind();runCurrent();a.preview();runCurrent();a.confirm();runCurrent()
        val unknown=a.real.rows.values.single();assertEquals(TaskState.UNKNOWN,unknown.state)
        val b=Application(this,real=a.real);b.h.records.clear();b.h.records.putAll(a.h.records);b.bind();runCurrent()
        b.batch.onIntent(DnsBatchIntent.Reconcile(unknown.id));runCurrent();assertEquals(TaskState.SUCCEEDED,b.real.rows.getValue(unknown.id).state);assertTrue(b.h.writes.isEmpty())
        b.real.put(task(DNS_IMPORT_KEY,ref=ResourceRef(zone,"dns_import",zone.zoneId)))
        val before=b.h.requests.size
        assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY,assertFailsWith<CloudFailure> { b.runtime.reconcileManagement(zone,"unsettled") { true } }.code)
        assertEquals(before,b.h.requests.size)
    }
    @Test fun demoBatchUsesSameRuntimeAndKeepsRealPersistenceAndCredentialsUntouched() = runTest {
        val a=Application(this,demo=true);a.bind();runCurrent();assertTrue(a.batch.state.value.demo);assertEquals("flarego.dev",a.batch.state.value.zoneName)
        a.preview();runCurrent();a.confirm();runCurrent();assertEquals(TaskState.SUCCEEDED,a.batch.state.value.pendingTask?.state)
        assertTrue(a.real.rows.isEmpty());assertTrue(a.real.evidence.isEmpty());assertEquals(0,a.legacySessions);assertTrue(a.h.requests.isEmpty())
    }

    @Test fun verifiedCurrentStateRequiresSuccessfulOwnedReadAndDurableSettlement() = runTest {
        val p=Product(this);p.h.failAfterApply=true;val plan=p.prepare();val task=p.controller.submit(p.confirm(plan));val rebuilt=p.restored()
        assertEquals(TaskState.SUCCEEDED,rebuilt.reconcile(task.id).state)
        val outcome=assertNotNull(rebuilt.outcome(task.id,true));assertTrue(outcome.currentStateVerified);assertEquals(DnsBatchTransaction.UNKNOWN,outcome.transaction)
        assertTrue(outcome.pending.isEmpty());assertTrue(outcome.items.isNotEmpty() && outcome.items.all { it.verification==DnsBatchVerification.MATCHED })
        assertFalse(assertNotNull(rebuilt.outcome(task.id,false)).currentStateVerified)
        val other=p.restored();assertFalse(assertNotNull(other.outcome(task.id,true)).currentStateVerified)
    }
    @Test fun incompleteManifestOrFailedSettlementCannotProduceCurrentProof() = runTest {
        val p=Product(this);p.h.failAfterApply=true;val plan=p.prepare();val task=p.controller.submit(p.confirm(plan))
        p.real.evidence.remove(task.id);val rebuilt=p.restored();assertEquals(TaskState.UNKNOWN,rebuilt.reconcile(task.id).state)
        assertNull(rebuilt.outcome(task.id,true));assertEquals(1,p.h.writes.size)
    }

    @Test fun trustedQuotaBoundariesAreScopedCredentialAndExpiryBound() = runTest {
        var revision=3L;var now=1000L;var evidence=VerifiedDnsBatchQuota(zone,3,2000)
        val authority=DnsBatchQuotaAuthority({evidence},{revision},{now});val h=Adapter(quota=authority)
        h.records.clear();(1..3500).forEach { id -> h.records["r$id"]=row("r$id") }
        assertEquals(3500,h.api.observeBatch(zone,DnsBatchInput(deletes=h.records.keys.toList())).records.size)
        assertFailsWith<CloudFailure> { h.api.observeBatch(zone,DnsBatchInput(deletes=(1..3501).map { "r$it" })) }
        val input=DnsBatchInput(deletes=(1..201).map { "r$it" })
        for(case in listOf("scope","revision","expiry")) {
            evidence=VerifiedDnsBatchQuota(if(case=="scope") ZoneScope(account,"other-zone") else zone,3,2000)
            revision=if(case=="revision") 4 else 3;now=if(case=="expiry") 2000 else 1000
            assertFailsWith<CloudFailure> { h.api.observeBatch(zone,input) }
        }
        assertTrue(h.writes.isEmpty())
    }
    @Test fun quotaExpiryDuringOwnedPreflightRefusesFinalSend() = runTest {
        var now=1000L;val authority=DnsBatchQuotaAuthority({VerifiedDnsBatchQuota(zone,0,2000)},{0},{now});val h=Adapter(quota=authority)
        h.records.clear();(1..201).forEach { id -> h.records["r$id"]=row("r$id") };h.onRead={ now=2000 }
        assertFailsWith<CloudFailure> { h.api.writeBatch(zone,DnsBatchInput(deletes=h.records.keys.toList())) {} };assertTrue(h.writes.isEmpty())
    }
    @Test fun externalChangeToExactDesiredPatchIsStillExplicitPreSendConflict() = runTest {
        val p=Product(this);val plan=p.prepare();p.h.records["patch"]=JsonObject(p.h.records.getValue("patch")+("comment" to JsonPrimitive("changed")))
        assertEquals(CloudFailureCode.CONFLICT,assertFailsWith<CloudFailure> { p.controller.submit(p.confirm(plan)) }.code);assertTrue(p.h.writes.isEmpty())
    }
    @Test fun strictGenericRecordRejectsStringReadonlyBooleans() = runTest {
        val h=Adapter();h.responseTransform={ result -> JsonObject(result.mapValues { (_,value) -> JsonArray(value.jsonArray.map { JsonObject(it.jsonObject+("proxiable" to JsonPrimitive("true"))) }) }) }
        assertEquals(DnsBatchTransaction.UNKNOWN,h.api.batch(zone,h.mixed).transaction);assertEquals(1,h.writes.size)
    }
    @Test fun opaqueProviderIdsStayVerbatimInWireReadsAndManifest() = runTest {
        val h=Adapter();h.records["记录"]=row("记录")
        val result=h.api.batch(zone,DnsBatchInput(patches=listOf(DnsBatchPatch("记录",obj("""{"comment":"changed"}""")))))
        assertEquals(DnsBatchTransaction.CONFIRMED_COMMIT,result.transaction);assertTrue(result.pending.isEmpty());assertEquals("记录",result.items.single().providerId)
        val items=listOf(DnsBatchItemEvidence(DnsBatchKind.PATCH,0,"记录",null,null,"c".repeat(64),"d".repeat(64),"记录"))
        val proof=DnsBatchTaskEvidence(1,"opaque",zone,listOf(0,1,0,0),items,dnsBatchManifestHash(zone,items,fingerprint),DnsBatchTransaction.CONFIRMED_COMMIT)
        val parent=task(DNS_BATCH_KEY,ref=ResourceRef(zone,"dns_batch",zone.zoneId)).copy(id="opaque",desiredHash=proof.manifestHash)
        proof.requireValid(parent,fingerprint)
    }
    @Test fun commitEvidenceWithoutRealProviderIdsIsRejectedAsIncomplete() {
        val items=listOf(DnsBatchItemEvidence(DnsBatchKind.PATCH,0,"record",null,null,"c".repeat(64),"d".repeat(64)))
        val proof=DnsBatchTaskEvidence(1,"incomplete",zone,listOf(0,1,0,0),items,dnsBatchManifestHash(zone,items,fingerprint),DnsBatchTransaction.CONFIRMED_COMMIT)
        val parent=task(DNS_BATCH_KEY,ref=ResourceRef(zone,"dns_batch",zone.zoneId)).copy(id="incomplete",desiredHash=proof.manifestHash)
        assertFailsWith<CloudFailure> { proof.requireValid(parent,fingerprint) }
    }
    @Test fun failedFreshSettlementRevokesPreviousCurrentStateProof() = runTest {
        val p=Product(this);p.h.failAfterApply=true;val plan=p.prepare();val task=p.controller.submit(p.confirm(plan));val rebuilt=p.restored()
        rebuilt.reconcile(task.id);assertTrue(rebuilt.outcome(task.id,true)!!.currentStateVerified)
        p.real.failMetadata=true;assertFailsWith<Exception> { rebuilt.reconcile(task.id) };p.real.failMetadata=false
        assertFalse(rebuilt.outcome(task.id,true)!!.currentStateVerified);assertEquals(1,p.h.writes.size)
    }
    @Test fun restoredWrongOwnerIncompletePagesAndAmbiguousCreateStayUnknown() = runTest {
        for(case in listOf("owner","pages","duplicate")) {
            val p=Product(this);p.h.failAfterApply=true;val plan=p.prepare();val task=p.controller.submit(p.confirm(plan))
            when(case) {
                "owner" -> p.h.ownerAccount="other"
                "pages" -> p.h.listingInfo=obj("""{"page":1,"per_page":50,"total_pages":2,"total_count":5}""")
                else -> p.h.records["duplicate"]=JsonObject(p.h.records.getValue("provider-0")+("id" to JsonPrimitive("duplicate")))
            }
            val rebuilt=p.restored();assertEquals(TaskState.UNKNOWN,rebuilt.reconcile(task.id).state)
            assertFalse(rebuilt.outcome(task.id,true)?.currentStateVerified==true);assertEquals(1,p.h.writes.size)
        }
    }

}
