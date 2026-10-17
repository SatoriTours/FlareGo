package com.flarego.core

import com.flarego.core.application.dns.*
import com.flarego.core.application.management.*
import com.flarego.core.application.operations.*
import com.flarego.core.cloudflare.identity.*
import com.flarego.core.demo.DemoSession
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ExtendedManagementRuntimeTest {
    private class Harness(val test:TestScope) {
        val provider=ExtendedProvider();val zone get()=provider.zone
        var state=AppState(listOf(provider.connection),provider.connection,domain=Domain(zone.zoneId,"example.com","active",zone.connectionId))
        var epoch=1L;var ids=0;var factories=0
        var failSettlement=false
        val tasks=linkedMapOf<String,OperationTask>()
        val store=object:OperationStore {
            override fun get(id:String)=tasks[id]
            override fun list(connectionId:String)=tasks.values.filter { it.connectionId==connectionId }
            override fun put(task:OperationTask) { if(failSettlement && task.state==TaskState.SUCCEEDED) throw IllegalStateException("settlement unavailable");tasks[task.id]=task }
            override fun putWithSummary(task:OperationTask,summary:Operation) { put(task) }
            override fun removeConnection(connectionId:String) { tasks.entries.removeAll { it.value.connectionId==connectionId } }
        }
        var fileDependencies:AuthorizedDnsFilesDependencies?=null
        var transform:(ManagementServices)->ManagementServices={it}
        val eligibility=object:ManagementEligibilitySource {
            override suspend fun dnsSettings(scope:ZoneScope)=DnsSettingsEligibility(fields=setOf(DnsZoneSettingKey.NS_TTL,DnsZoneSettingKey.ZONE_MODE,DnsZoneSettingKey.NAMESERVERS),nameserverTypes=setOf("cloudflare.standard","cloudflare.advanced"))
        }
        fun runtime(platform:ManagementPlatformPorts?=null)=AppOperationRuntime(test.backgroundScope,store,StandardTestDispatcher(test.testScheduler),{state},{epoch},{provider.revision},SessionFactory { DemoSession(it) },provider.hash,OperationClock { provider.now },OperationIds { "p${++ids}" },{},ManagementSessionFactory { factories++;transform(provider.services(eligibility,fileDependencies)) },platform)
        val runtime by lazy { runtime() }
        fun statusCommand()=ExtendedManagementCommand.Dnssec(DnssecAction.Update(zone,DnssecUpdateInput(status="disabled")))
        fun unknown(key:String=DNS_IMPORT_OPERATION,id:String="unknown",ref:ResourceRef?=null) = OperationTask(id,zone.connectionId,zone,key,ref,"0".repeat(64),null,TaskState.UNKNOWN,"unknown",null,null,1,1)
    }
    @Test fun prepareIsInertAndActualRiskRequiredBeforeSingleSend()=runTest {
        val h=Harness(this);val p=h.runtime.prepareExtended(h.statusCommand(),3) { true }
        assertEquals(0,h.provider.writes);assertTrue(h.tasks.isEmpty())
        assertEquals(setOf(DnssecRisk.DISABLE),assertIs<ExtendedChangeDetails.Dnssec>(p.details).preview.risks)
        assertFailsWith<CloudFailure> { h.runtime.confirmExtended(p.planId,3) };assertEquals(0,h.provider.writes)
        val task=h.runtime.confirmExtended(p.planId,3,ExtendedRiskChoices(dnssec=setOf(DnssecRisk.DISABLE)))
        assertEquals(TaskState.SUCCEEDED,task.state);assertEquals(1,h.provider.writes)
        assertFailsWith<CloudFailure> { h.runtime.confirmExtended(p.planId,3,ExtendedRiskChoices(dnssec=setOf(DnssecRisk.DISABLE))) }
        assertEquals(1,h.provider.writes)
    }
    @Test fun multipleRisksMustAllComeFromActualUserChoices()=runTest {
        val h=Harness(this);val c=ExtendedManagementCommand.Dnssec(DnssecAction.Update(h.zone,DnssecUpdateInput(status="disabled",multiSigner=true)))
        val p=h.runtime.prepareExtended(c,1) { true }
        assertFailsWith<CloudFailure> { h.runtime.confirmExtended(p.planId,1,ExtendedRiskChoices(dnssec=setOf(DnssecRisk.DISABLE))) }
        assertEquals(0,h.provider.writes)
        assertEquals(TaskState.SUCCEEDED,h.runtime.confirmExtended(p.planId,1,ExtendedRiskChoices(dnssec=setOf(DnssecRisk.DISABLE,DnssecRisk.CHANGE_MULTI_SIGNER))).state)
    }
    @Test fun staleDraftRevisionAndAccountAndCredentialCannotSend()=runTest {
        for(change in 0..3) {
            val h=Harness(this);var current=true;val p=h.runtime.prepareExtended(h.statusCommand(),5) { current }
            when(change) { 0->current=false;1->h.epoch++;2->h.provider.revision++;3->h.runtime.invalidateExtended(h.zone) }
            assertFailsWith<CloudFailure> { h.runtime.confirmExtended(p.planId,5,ExtendedRiskChoices(dnssec=setOf(DnssecRisk.DISABLE))) }
            assertEquals(0,h.provider.writes)
        }
        val h=Harness(this);val p=h.runtime.prepareExtended(h.statusCommand(),5) { true }
        assertFailsWith<CloudFailure> { h.runtime.confirmExtended(p.planId,6,ExtendedRiskChoices(dnssec=setOf(DnssecRisk.DISABLE))) };assertEquals(0,h.provider.writes)
    }
    @Test fun scanAndSettingsUseRealCommandsAndSameDomain()=runTest {
        val h=Harness(this);h.provider.candidate()
        val read=assertIs<ExtendedManagementReadResult.ScanReview>(h.runtime.readExtended(h.zone,ExtendedManagementRead.ScanReview) { true })
        val row=read.snapshot.candidates.single()
        val p=h.runtime.prepareExtended(ExtendedManagementCommand.Scan(DnsScanAction.Apply(h.zone,DnsScanReviewInput(emptyList(),listOf(row.id),mapOf(row.id to row.selectionHash)))),1) { true }
        assertEquals(0,h.provider.writes);assertEquals(TaskState.SUCCEEDED,h.runtime.confirmExtended(p.planId,1).state)
        val q=h.runtime.prepareExtended(ExtendedManagementCommand.DnsSettings(DnsZoneSettingsAction(h.zone,h.provider.obj("""{"ns_ttl":60}"""))),2) { true }
        assertEquals(TaskState.SUCCEEDED,h.runtime.confirmExtended(q.planId,2).state);assertEquals(2,h.provider.writes)
    }
    @Test fun zoneRiskCannotBeAutoAcknowledgedOrUsedForOtherFamily()=runTest {
        val h=Harness(this);val c=ExtendedManagementCommand.ZoneSettings(ZoneSettingsAction.Update(h.zone,ZoneSettingKey.SSL,h.provider.obj("""{"value":"full"}""")))
        val p=h.runtime.prepareExtended(c,4) { true }
        assertFailsWith<CloudFailure> { h.runtime.confirmExtended(p.planId,4,ExtendedRiskChoices(dnssec=setOf(DnssecRisk.DISABLE))) }
        assertEquals(0,h.provider.writes)
        assertEquals(TaskState.SUCCEEDED,h.runtime.confirmExtended(p.planId,4,ExtendedRiskChoices(zoneSettings=ZoneSettingsRisk.SSL_DOWNGRADE)).state)
    }
    @Test fun sameAppAdmissionExcludesLegacyAndBatchAndReadDuringWrite()=runTest {
        val h=Harness(this);val p=h.runtime.prepareExtended(h.statusCommand(),1) { true }
        val gate=CompletableDeferred<Unit>();h.provider.before={ if(it.method=="PATCH") gate.await() }
        val job=async { h.runtime.confirmExtended(p.planId,1,ExtendedRiskChoices(dnssec=setOf(DnssecRisk.DISABLE))) };runCurrent()
        assertEquals(1,h.provider.writes)
        val f=assertFailsWith<CloudFailure> { h.runtime.create(h.zone,DnsDraft("TXT","x.example.com","hello")) };assertEquals(CloudFailureCode.CONFLICT,f.code)
        assertFailsWith<CloudFailure> { h.runtime.prepareBatch(h.zone,DnsBatchInput()) { true } }
        assertFailsWith<CloudFailure> { h.runtime.readExtended(h.zone,ExtendedManagementRead.Usage) { true } }
        gate.complete(Unit);job.await();assertEquals(1,h.provider.writes)
    }
    @Test fun cancelWaiterDoesNotCancelAppOwnedSendOrReplay()=runTest {
        val h=Harness(this);val p=h.runtime.prepareExtended(h.statusCommand(),1) { true };val gate=CompletableDeferred<Unit>()
        h.provider.before={ if(it.method=="PATCH") gate.await() }
        val job=launch { h.runtime.confirmExtended(p.planId,1,ExtendedRiskChoices(dnssec=setOf(DnssecRisk.DISABLE))) };runCurrent()
        job.cancel();h.runtime.invalidateExtended(h.zone);gate.complete(Unit);runCurrent()
        assertEquals(1,h.provider.writes);assertEquals(TaskState.SUCCEEDED,h.tasks.values.single().state)
    }
    @Test fun wholeZoneUnknownBlocksApplyAndSettingsButNotReadOrAcceptedTrigger()=runTest {
        val h=Harness(this);h.tasks["unknown"]=h.unknown()
        val c=ExtendedManagementCommand.DnsSettings(DnsZoneSettingsAction(h.zone,h.provider.obj("""{"ns_ttl":60}""")))
        assertFailsWith<CloudFailure> { h.runtime.prepareExtended(c,1) { true } };assertEquals(0,h.provider.writes)
        assertIs<ExtendedManagementReadResult.Usage>(h.runtime.readExtended(h.zone,ExtendedManagementRead.Usage) { true })
        h.tasks.clear();val p=h.runtime.prepareExtended(ExtendedManagementCommand.Scan(DnsScanAction.Trigger(h.zone)),1) { true }
        assertEquals(TaskState.ACCEPTED,h.runtime.confirmExtended(p.planId,1).state)
        val q=h.runtime.prepareExtended(c,2) { true };assertNotNull(q)
    }
    @Test fun secondPreparedConfirmationChecksNewUnknownBeforeSend()=runTest {
        val h=Harness(this);val p=h.runtime.prepareExtended(ExtendedManagementCommand.DnsSettings(DnsZoneSettingsAction(h.zone,h.provider.obj("""{"ns_ttl":60}"""))),1) { true }
        h.tasks["unknown"]=h.unknown()
        assertFailsWith<CloudFailure> { h.runtime.confirmExtended(p.planId,1) };assertEquals(0,h.provider.writes)
    }
    @Test fun exactJobsDispatchNeverReadsWrongProductOrRestoresConfirmation()=runTest {
        val h=Harness(this);val task=h.unknown("future-operation");h.tasks[task.id]=task
        val failure=assertFailsWith<CloudFailure> { h.runtime.reconcile(task.id) }
        assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY,failure.code);assertTrue(h.provider.requests.isEmpty());assertEquals(TaskState.UNKNOWN,h.tasks[task.id]!!.state)
        h.tasks.clear();val p=h.runtime.prepareExtended(h.statusCommand(),1) { true }
        h.provider.before={ if(it.method=="PATCH") throw IllegalStateException("lost") }
        val result=h.runtime.confirmExtended(p.planId,1,ExtendedRiskChoices(dnssec=setOf(DnssecRisk.DISABLE)))
        assertEquals(TaskState.UNKNOWN,result.state);h.provider.before={};val rebuilt=h.runtime();rebuilt.reconcile(result.id)
        assertEquals(1,h.provider.writes);assertEquals(TaskState.UNKNOWN,h.tasks[result.id]!!.state)
        assertFailsWith<CloudFailure> { rebuilt.confirmExtended(p.planId,1,ExtendedRiskChoices(dnssec=setOf(DnssecRisk.DISABLE))) }
    }
    @Test fun pendingFamiliesAreFullScopeAndTargetSpecific()=runTest {
        val h=Harness(this);h.tasks["import"]=h.unknown(id="import")
        h.tasks["sec"]=h.unknown(DnssecMutation.UPDATE.operationId,"sec",dnssecRef(h.zone))
        assertEquals(listOf("import"),h.runtime.pendingExtended(h.zone,ExtendedManagementFamily.FILES).map { it.id })
        assertEquals(listOf("sec"),h.runtime.pendingExtended(h.zone,ExtendedManagementFamily.DNSSEC).map { it.id })
        assertTrue(h.runtime.pendingExtended(h.zone,ExtendedManagementFamily.ZONE_SETTINGS,ZoneSettingsTarget.Cache).isEmpty())
    }
    @Test fun missingPlatformRejectsImportWithoutOpeningAnyProductionSession()=runTest {
        val h=Harness(this);val f=DnsFileRef("opaque","example.txt",1,"0".repeat(64))
        assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY,assertFailsWith<CloudFailure> { h.runtime.prepareExtended(ExtendedManagementCommand.Import(h.zone,f,false),1) { true } }.code)
        assertEquals(0,h.factories);assertEquals(0,h.provider.writes)
    }
    @Test fun demoUsesSamePlansControllersAndVolatileStoreWithoutFactory()=runTest {
        val h=Harness(this);val c=Connection("demo","示例","demo",true);h.state=AppState(listOf(c),c)
        val z=ZoneScope(AccountScope(c.id,c.accountId),"zone-demo")
        val read=h.runtime.readExtended(z,ExtendedManagementRead.Dnssec()) { true };assertIs<ExtendedManagementReadResult.Dnssec>(read)
        val p=h.runtime.prepareExtended(ExtendedManagementCommand.Dnssec(DnssecAction.Update(z,DnssecUpdateInput(status="active"))),1) { true }
        assertEquals(TaskState.SUCCEEDED,h.runtime.confirmExtended(p.planId,1).state)
        assertTrue(h.tasks.isEmpty());assertEquals(0,h.factories);assertTrue(h.provider.requests.isEmpty())
        assertEquals(TaskState.SUCCEEDED,h.runtime.pendingExtended(z,ExtendedManagementFamily.DNSSEC).single().state)
    }
    @Test fun captureDnssecUsesOnlySamePrepareSnapshotAndPlannedConfigProjection()=runTest {
        val h=Harness(this)
        h.runtime.readExtended(h.zone,ExtendedManagementRead.Dnssec()) { true }
        h.provider.dnssec=h.provider.obj("""{"status":"active","ds":"provider-ds","algorithm":"13","dnssec_multi_signer":true,"dnssec_presigned":true,"dnssec_use_nsec3":false}""")
        val count=h.provider.requests.count { it.path.endsWith("/dnssec") }
        val c=ExtendedManagementCommand.Dnssec(DnssecAction.Update(h.zone,DnssecUpdateInput(status="disabled",multiSigner=false)))
        val p=h.runtime.prepareExtended(c,7) { true };val d=assertIs<ExtendedChangeDetails.Dnssec>(p.details)
        assertEquals(count+1,h.provider.requests.count { it.path.endsWith("/dnssec") })
        assertEquals(true,assertNotNull(d.before).details.multiSigner)
        val after=assertNotNull(d.after);assertEquals(false,after.multiSigner);assertEquals(true,after.presigned)
        assertEquals("disabled",after.status);assertNull(after.ds);assertNull(after.algorithm);assertNull(after.keyTag)
        assertEquals(setOf("status","dnssec_multi_signer","dnssec_presigned","dnssec_use_nsec3"),after.raw.keys)
        h.provider.dnssec=h.provider.obj("""{"status":"disabled","dnssec_multi_signer":false,"dnssec_presigned":false,"dnssec_use_nsec3":true}""")
        h.runtime.readExtended(h.zone,ExtendedManagementRead.Dnssec()) { true }
        assertEquals(true,d.before!!.details.presigned);assertEquals(false,d.before!!.details.useNsec3);assertEquals(0,h.provider.writes)
        h.provider.dnssec=h.provider.obj("""{"status":"active","dnssec_multi_signer":false,"dnssec_presigned":false,"dnssec_use_nsec3":false}""")
        val mutableRaw=h.provider.dnssec.toMutableMap()
        h.transform={ services->services.copy(dnssec=object:DnssecSession by services.dnssec!! {
            override suspend fun observe(scope:ZoneScope,mutation:DnssecMutation):DnssecSnapshot {
                val observed=services.dnssec!!.observe(scope,mutation)
                return observed.copy(details=observed.details.copy(raw=JsonObject(mutableRaw)))
            }
        }) }
        val frozen=assertIs<ExtendedChangeDetails.Dnssec>(h.runtime.prepareExtended(h.statusCommand(),8) { true }.details)
        mutableRaw["dnssec_multi_signer"]=JsonPrimitive(true)
        assertEquals(JsonPrimitive(false),frozen.before!!.details.raw["dnssec_multi_signer"])
        assertEquals(false,frozen.after!!.multiSigner)
    }
    @Test fun captureSettingsUsesSameFreshReadAndAuditedPartialProjection()=runTest {
        val h=Harness(this);h.runtime.readExtended(h.zone,ExtendedManagementRead.ZoneSettings(ZoneSettingsTarget.Setting(ZoneSettingKey.SSL))) { true }
        h.provider.ssl="full"
        val p=h.runtime.prepareExtended(ExtendedManagementCommand.ZoneSettings(ZoneSettingsAction.Update(h.zone,ZoneSettingKey.SSL,h.provider.obj("""{"value":"strict"}"""))),1) { true }
        val d=assertIs<ExtendedChangeDetails.ZoneSettings>(p.details)
        assertEquals("full",assertNotNull(d.before).setting!!.value.jsonPrimitive.content);assertEquals(JsonPrimitive("strict"),d.after)
        h.provider.ssl="off";h.runtime.readExtended(h.zone,ExtendedManagementRead.ZoneSettings(ZoneSettingsTarget.Setting(ZoneSettingKey.SSL))) { true }
        assertEquals(JsonPrimitive("full"),d.before!!.setting!!.value)
        val q=h.runtime.prepareExtended(ExtendedManagementCommand.ZoneSettings(ZoneSettingsAction.Update(h.zone,ZoneSettingKey.TRANSFORMATIONS_CONFIG,h.provider.obj("""{"value":{"c2pa":"on"}}"""))),2) { true }
        val partial=assertIs<ExtendedChangeDetails.ZoneSettings>(q.details)
        assertNotNull(partial.before);assertEquals("keep",partial.after!!.jsonObject["future"]!!.jsonPrimitive.content)
        assertEquals("on",partial.after!!.jsonObject["c2pa"]!!.jsonPrimitive.content);assertEquals(0,h.provider.writes)
    }
    @Test fun captureDeleteAndPurgeDoNotFabricateAfterOrTerminalState()=runTest {
        val h=Harness(this);val p=h.runtime.prepareExtended(ExtendedManagementCommand.Dnssec(DnssecAction.Delete(h.zone)),1) { true }
        val d=assertIs<ExtendedChangeDetails.Dnssec>(p.details);assertNotNull(d.before);assertNull(d.after)
        val q=h.runtime.prepareExtended(ExtendedManagementCommand.ZoneSettings(ZoneSettingsAction.Purge(h.zone,CachePurgeInput.All)),2) { true }
        val purge=assertIs<ExtendedChangeDetails.ZoneSettings>(q.details);assertNull(purge.before);assertNull(purge.after)
        assertTrue(h.tasks.isEmpty());assertEquals(0,h.provider.writes)
    }
    @Test fun captureCancelledPrepareCannotBePublishedOrReusedByNextCommand()=runTest {
        val h=Harness(this);var current=true
        h.provider.before={ if(it.path.endsWith("/dnssec")) current=false }
        assertFailsWith<CloudFailure> { h.runtime.prepareExtended(h.statusCommand(),1) { current } }
        h.provider.before={};current=true;h.provider.dnssec=JsonObject(h.provider.dnssec+("dnssec_multi_signer" to JsonPrimitive(true)))
        val p=h.runtime.prepareExtended(h.statusCommand(),2) { true }
        assertEquals(true,assertNotNull(assertIs<ExtendedChangeDetails.Dnssec>(p.details).before).details.multiSigner)
        h.provider.ownerAccount="wrong"
        assertFailsWith<CloudFailure> { h.runtime.prepareExtended(h.statusCommand(),3) { true } }
        assertEquals(0,h.provider.writes)
    }

    private class Files(val provider:ExtendedProvider):DnsFileStore {
        val bytes="example.com. 60 IN TXT \"explicit synthetic\"\n".encodeToByteArray()
        val ref=DnsFileRef("opaque-source","zone.bind",bytes.size.toLong(),provider.hash.sha256(bytes))
        var opens=0;var released=0;var committed=0;var aborted=0
        var onOpen:suspend()->Unit={}
        override suspend fun open(ref:DnsFileRef):ByteSource {
            opens++;onOpen();var offset=0
            return object:ByteSource { override fun read(buffer:ByteArray):Int {
                if(offset==bytes.size) return -1
                val n=minOf(bytes.size-offset,buffer.size);bytes.copyInto(buffer,0,offset,offset+n);offset+=n;return n
            };override fun close(){} }
        }
        override suspend fun release(ref:DnsFileRef) { released++ }
        override suspend fun beginExport(target:DnsExportTarget):DnsExportTransaction=object:DnsExportTransaction {
            var count=0
            override val sink=object:ByteSink { override fun write(buffer:ByteArray,offset:Int,count:Int) { }
                override fun close(){} }
            override suspend fun commit():DnsFileRef { committed++;return ref.copy(id=target.id) }
            override suspend fun abort() { aborted++ }
        }
    }

    private fun Harness.withFiles():Pair<AppOperationRuntime,Files> {
        val files=Files(provider);val dispatcher=StandardTestDispatcher(test.testScheduler)
        val transfer=object:TransferPort {
            override suspend fun download(request:com.flarego.core.cloudflare.HttpRequest,sink:ByteSink):HttpResponseHead {
                val bytes="; synthetic export\n".encodeToByteArray()
                try { sink.write(bytes,0,bytes.size) } finally { sink.close() }
                return HttpResponseHead(200,mapOf("Content-Type" to listOf("text/plain")),bytes.size.toLong())
            }
        }
        fileDependencies=AuthorizedDnsFilesDependencies(transfer,DnsImportLimiter(OperationClock { provider.now }),dispatcher)
        return runtime(ManagementPlatformPorts(files,dispatcher)) to files
    }
    @Test fun filesOwnerAndExportDoNotDependOnRecordListingOrWriteProof()=runTest {
        val h=Harness(this);val (r,files)=h.withFiles();h.provider.failDetails=true;h.provider.failRecords=true
        val state=assertIs<ExtendedManagementReadResult.Files>(r.readExtended(h.zone,ExtendedManagementRead.Files) { true })
        assertEquals(h.zone,state.snapshot.owner.ref);assertNull(state.snapshot.writeAllowed);assertNull(state.readback)
        h.tasks["unknown"]=h.unknown()
        assertEquals(h.zone,r.exportDns(h.zone,DnsExportTarget("target","zone.bind")) { true }.scope)
        assertEquals(1,files.committed);assertEquals(0,h.provider.writes)
    }
    @Test fun filesWrongScopeReadbackIsDroppedAndCancellationStillPropagates()=runTest {
        val h=Harness(this);val (r,_)=h.withFiles()
        h.transform={ s->s.copy(files=object:DnsFilesSession by s.files!! {
            override suspend fun readback(scope:ZoneScope)=DnsImportReadback(scope.copy(zoneId="other"),7,9)
        }) }
        assertNull(assertIs<ExtendedManagementReadResult.Files>(r.readExtended(h.zone,ExtendedManagementRead.Files) { true }).readback)
        h.transform={ s->s.copy(files=object:DnsFilesSession by s.files!! {
            override suspend fun readback(scope:ZoneScope):DnsImportReadback { throw CancellationException("cancelled") }
        }) }
        assertFailsWith<CancellationException> { r.readExtended(h.zone,ExtendedManagementRead.Files) { true } }
    }
    @Test fun filesTypedReceiptIsBoundToSettledTaskAndNeverRecoveredFromCounts()=runTest {
        val h=Harness(this);val (r,files)=h.withFiles()
        val p=r.prepareExtended(ExtendedManagementCommand.Import(h.zone,files.ref,false),1) { true }
        assertEquals(0,h.provider.writes);assertNull(r.importOutcome(h.zone,p.planId))
        val task=r.confirmExtended(p.planId,1);assertEquals(TaskState.SUCCEEDED,task.state)
        val receipt=assertIs<DnsImportOutcome.ReportedSuccess>(r.importOutcome(h.zone,task.id));assertEquals(1L,receipt.added);assertEquals(h.zone,receipt.readback.scope)
        assertEquals(1,h.provider.writes);assertNull(h.runtime().importOutcome(h.zone,task.id))
        h.tasks[task.id]=task.copy(safeDetail="changed metadata");assertNull(r.importOutcome(h.zone,task.id))
        h.tasks[task.id]=task;h.epoch++;assertNull(r.importOutcome(h.zone,task.id));assertEquals(1,h.provider.writes)
    }
    @Test fun filesForeignReceiptObservationNeverPublishesSuccessCounts()=runTest {
        for(partial in listOf(false,true)) {
            val h=Harness(this);val (r,files)=h.withFiles()
            h.transform={ s->s.copy(files=object:DnsFilesSession by s.files!! {
                override suspend fun writeImport(scope:ZoneScope,source:suspend()->ByteSource,length:Long,fingerprint:String,proxied:Boolean,beforeSend:(DnsFileImportState)->Unit,afterRead:()->Unit):DnsImportOutcome {
                    val outcome=s.files!!.writeImport(scope,source,length,fingerprint,proxied,beforeSend,afterRead) as DnsImportOutcome.ReportedSuccess
                    val foreign=outcome.readback.copy(scope=scope.copy(zoneId="foreign"))
                    return if(partial) DnsImportOutcome.Partial(1,2,foreign) else outcome.copy(readback=foreign)
                }
            }) }
            val p=r.prepareExtended(ExtendedManagementCommand.Import(h.zone,files.ref,false),1) { true };val task=r.confirmExtended(p.planId,1)
            assertEquals(if(partial) TaskState.PARTIAL else TaskState.SUCCEEDED,task.state)
            assertNull(r.importOutcome(h.zone,task.id));assertEquals(1,h.provider.writes)
        }
    }

    @Test fun filesPartialAndUnknownReceiptsKeepTheirOwnSettledStateAndScope()=runTest {
        for(partial in listOf(true,false)) {
            val h=Harness(this);val (r,files)=h.withFiles()
            val outcome:DnsImportOutcome=if(partial) DnsImportOutcome.Partial(1,2,DnsImportReadback(h.zone,0,0)) else DnsImportOutcome.Unknown()
            h.transform={ s->s.copy(files=object:DnsFilesSession by s.files!! {
                override suspend fun writeImport(scope:ZoneScope,source:suspend()->ByteSource,length:Long,fingerprint:String,proxied:Boolean,beforeSend:(DnsFileImportState)->Unit,afterRead:()->Unit):DnsImportOutcome {
                    s.files!!.writeImport(scope,source,length,fingerprint,proxied,beforeSend,afterRead);return outcome
                }
            }) }
            val p=r.prepareExtended(ExtendedManagementCommand.Import(h.zone,files.ref,false),1) { true };val t=r.confirmExtended(p.planId,1)
            assertEquals(if(partial) TaskState.PARTIAL else TaskState.UNKNOWN,t.state);assertEquals(outcome,r.importOutcome(h.zone,t.id))
            assertNull(r.importOutcome(h.zone.copy(zoneId="wrong"),t.id))
            h.tasks[t.id]=t.copy(state=TaskState.SUCCEEDED);assertNull(r.importOutcome(h.zone,t.id));assertEquals(1,h.provider.writes)
        }
    }
    @Test fun filesMetadataSettlementFailureNeverPublishesReportedSuccessCounts()=runTest {
        val h=Harness(this);val (r,files)=h.withFiles();val p=r.prepareExtended(ExtendedManagementCommand.Import(h.zone,files.ref,false),1) { true }
        h.failSettlement=true
        val task=r.confirmExtended(p.planId,1)
        assertNotEquals(TaskState.SUCCEEDED,task.state);assertNull(r.importOutcome(h.zone,task.id));assertEquals(1,h.provider.writes)
    }
    @Test fun filesSuspendingPreflightCredentialChangePreventsSendAndReceipt()=runTest {
        val h=Harness(this);val (r,files)=h.withFiles();val p=r.prepareExtended(ExtendedManagementCommand.Import(h.zone,files.ref,false),1) { true }
        files.onOpen={ h.provider.revision++ }
        assertFailsWith<CloudFailure> { r.confirmExtended(p.planId,1) }
        assertEquals(0,h.provider.writes);assertNull(r.importOutcome(h.zone,p.planId))
    }
    @Test fun filesReadbackFailureCannotHideChangedActualContext()=runTest {
        val h=Harness(this);val (r,_)=h.withFiles()
        h.provider.before={ if(it.path.contains("/dns_records?")) { h.epoch++;throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED,"list denied") } }
        assertFailsWith<CloudFailure> { r.readExtended(h.zone,ExtendedManagementRead.Files) { true } };assertEquals(0,h.provider.writes)
    }
    @Test fun safetyCrossFamilyUnknownGateBlocksActualApplyImportSingleBatchAndLegacy()=runTest {
        val h=Harness(this);val (r,files)=h.withFiles();h.provider.candidate()
        val candidate=assertIs<ExtendedManagementReadResult.ScanReview>(r.readExtended(h.zone,ExtendedManagementRead.ScanReview) { true }).snapshot.candidates.single()
        val apply=ExtendedManagementCommand.Scan(DnsScanAction.Apply(h.zone,DnsScanReviewInput(emptyList(),listOf(candidate.id),mapOf(candidate.id to candidate.selectionHash))))
        h.tasks["old"]=h.unknown(DNS_IMPORT_OPERATION,"old")
        assertFailsWith<CloudFailure> { r.prepareExtended(apply,1) { true } }
        h.tasks["old"]=h.unknown(DNS_SCAN_APPLY_OPERATION,"old")
        assertFailsWith<CloudFailure> { r.prepareExtended(ExtendedManagementCommand.Import(h.zone,files.ref,false),1) { true } }
        assertFailsWith<CloudFailure> { r.prepareDns(DnsWriteAction(h.zone,null,DnsRecordInput("TXT","new.example.com",h.provider.obj("""{"content":"new","ttl":60}""")),DnsWriteMode.CREATE)) { true } }
        assertFailsWith<CloudFailure> { r.prepareBatch(h.zone,DnsBatchInput(posts=listOf(DnsRecordInput("TXT","new.example.com",h.provider.obj("""{"content":"new","ttl":60}"""))))) { true } }
        assertFailsWith<CloudFailure> { r.create(h.zone,DnsDraft("TXT","new.example.com","new")) }
        assertEquals(0,h.provider.writes);assertEquals(0,files.opens)
    }
    @Test fun safetyRebuildDispatchesImportScanSettingsDnssecAndZoneTargetsReadOnly()=runTest {
        val h=Harness(this);val (r,files)=h.withFiles()
        val targets=listOf(
            DNS_IMPORT_OPERATION to ResourceRef(h.zone,"dns_import",h.zone.zoneId),
            DNS_SCAN_TRIGGER_OPERATION to ResourceRef(h.zone,"dns_scan_trigger",h.zone.zoneId),
            DNS_SCAN_APPLY_OPERATION to ResourceRef(h.zone,"dns_scan_review",h.zone.zoneId),
            DNS_SETTINGS_PATCH_OPERATION to ResourceRef(h.zone,"dns_settings_v1","ns_ttl"),
            DnssecMutation.UPDATE.operationId to dnssecRef(h.zone),
            ZoneSettingKey.SSL.writeOperation to ResourceRef(h.zone,"zone_setting",ZoneSettingKey.SSL.name),
            UNIVERSAL_SSL_WRITE to ResourceRef(h.zone,"universal_ssl","universal_ssl"),
            "zone-purge" to ResourceRef(h.zone,"cache_purge",h.zone.zoneId))
        for((index,pair) in targets.withIndex()) {
            val (key,ref)=pair;val task=h.unknown(key,"restored$index",ref).copy(desiredHash="0".repeat(64))
            h.tasks.clear();h.tasks[task.id]=task
            r.reconcileExtended(h.zone,task.id) { true }
            assertEquals(TaskState.UNKNOWN,h.tasks[task.id]!!.state)
            assertNull(r.importOutcome(h.zone,task.id))
        }
        assertEquals(0,h.provider.writes);assertEquals(0,files.opens)
    }
    @Test fun safetyDemoFiveDomainsAndExportUseOnlyExampleSessionsAndVolatileMetadata()=runTest {
        val h=Harness(this);val (r,files)=h.withFiles();val c=Connection("demo","示例","demo",true)
        h.state=AppState(listOf(c),c);val z=ZoneScope(AccountScope(c.id,c.accountId),"zone-demo")
        suspend fun submit(c:ExtendedManagementCommand,revision:Long,risks:ExtendedRiskChoices=ExtendedRiskChoices()):OperationTask {
            val p=r.prepareExtended(c,revision) { true };return r.confirmExtended(p.planId,revision,risks)
        }
        assertEquals(TaskState.ACCEPTED,submit(ExtendedManagementCommand.Scan(DnsScanAction.Trigger(z)),1).state)
        val rows=assertIs<ExtendedManagementReadResult.ScanReview>(r.readExtended(z,ExtendedManagementRead.ScanReview) { true }).snapshot.candidates
        val row=rows.single();assertEquals(TaskState.SUCCEEDED,submit(ExtendedManagementCommand.Scan(DnsScanAction.Apply(z,DnsScanReviewInput(emptyList(),listOf(row.id),mapOf(row.id to row.selectionHash)))),2).state)
        assertEquals(TaskState.SUCCEEDED,submit(ExtendedManagementCommand.DnsSettings(DnsZoneSettingsAction(z,h.provider.obj("""{"ns_ttl":60}"""))),3).state)
        assertEquals(TaskState.SUCCEEDED,submit(ExtendedManagementCommand.Dnssec(DnssecAction.Update(z,DnssecUpdateInput(status="active"))),4).state)
        assertEquals(TaskState.ACCEPTED,submit(ExtendedManagementCommand.ZoneSettings(ZoneSettingsAction.Purge(z,CachePurgeInput.All)),5,ExtendedRiskChoices(zoneSettings=ZoneSettingsRisk.PURGE_ALL)).state)
        val imported=submit(ExtendedManagementCommand.Import(z,files.ref,false),6);assertEquals(TaskState.UNKNOWN,imported.state)
        assertIs<DnsImportOutcome.Unknown>(r.importOutcome(z,imported.id))
        r.exportDns(z,DnsExportTarget("example","example.bind")) { true }
        assertEquals(1,files.committed);assertTrue(h.tasks.isEmpty());assertEquals(0,h.factories);assertTrue(h.provider.requests.isEmpty())
    }

    @Test fun safetyDnsSettingsNeedsBothExplicitRiskChoices()=runTest {
        val h=Harness(this)
        val command=ExtendedManagementCommand.DnsSettings(DnsZoneSettingsAction(h.zone,h.provider.obj("""{"nameservers":{"type":"cloudflare.advanced"},"zone_mode":"cdn_only"}""")))
        val p=h.runtime.prepareExtended(command,1) { true };assertEquals(setOf(DnsSettingsRisk.CHANGE_NAMESERVERS,DnsSettingsRisk.CHANGE_ZONE_MODE),assertIs<ExtendedChangeDetails.DnsSettings>(p.details).preview.risks)
        assertFailsWith<CloudFailure> { h.runtime.confirmExtended(p.planId,1,ExtendedRiskChoices(dnsSettings=setOf(DnsSettingsRisk.CHANGE_NAMESERVERS))) }
        assertEquals(0,h.provider.writes)
        assertEquals(TaskState.SUCCEEDED,h.runtime.confirmExtended(p.planId,1,ExtendedRiskChoices(dnsSettings=setOf(DnsSettingsRisk.CHANGE_NAMESERVERS,DnsSettingsRisk.CHANGE_ZONE_MODE))).state)
        assertEquals(1,h.provider.writes)
    }
    @Test fun safetyCaptureRejectsForeignTypedSnapshotAndNeverFallsBackToEarlierCapture()=runTest {
        val h=Harness(this);val previous=h.runtime.prepareExtended(h.statusCommand(),1) { true }
        assertNotNull(assertIs<ExtendedChangeDetails.Dnssec>(previous.details).before)
        h.transform={ s->s.copy(dnssec=object:DnssecSession by s.dnssec!! {
            override suspend fun observe(scope:ZoneScope,mutation:DnssecMutation):DnssecSnapshot {
                val snap=s.dnssec!!.observe(scope,mutation)
                return snap.copy(details=snap.details.copy(scope=scope.copy(zoneId="foreign")))
            }
        }) }
        assertFailsWith<CloudFailure> { h.runtime.prepareExtended(h.statusCommand(),2) { true } }
        assertFailsWith<CloudFailure> { h.runtime.confirmExtended(previous.planId,1,ExtendedRiskChoices(dnssec=setOf(DnssecRisk.DISABLE))) }
        assertEquals(0,h.provider.writes)
    }

}
