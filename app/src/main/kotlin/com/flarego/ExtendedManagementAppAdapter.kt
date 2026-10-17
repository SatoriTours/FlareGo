package com.flarego

import com.flarego.core.application.management.*
import com.flarego.core.application.safeMessage
import com.flarego.core.model.*
import com.flarego.ui.management.*
import com.flarego.ui.dns.*
import com.flarego.ui.zones.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Queried from VM selection, fresh owned Zone and Application credential owner. */
internal data class ExtendedAppContext(val panel:ManagementPanelContext,val connection:Connection,val credential:ManagementCredentialStamp)

/** Main-thread presentation adapter. The injected runtime alone owns plans, acknowledgements and writes. */
class ExtendedManagementAppAdapter internal constructor(
    private val scope:CoroutineScope,
    private val runtime:ExtendedManagementActions,
    private val actual:()->ExtendedAppContext?,
    private val platform:()->ManagementPlatformOwner,
    private val borrower:()->ManagementPlatformBorrower,
    private val selectDocument:(DnsDocumentKind)->Unit,
    private val invalidateDocument:()->Unit = {},
) {
    private class Lease(override val context:ManagementPanelContext,val actual:ExtendedAppContext,val module:ManagementPanelModule,val route:Long):ManagementPanelLease
    private class Draft(val command:ExtendedManagementCommand,val revision:Long,val lease:Lease) {
        var preview:ExtendedManagementPreview?=null
        val handedOff=AtomicBoolean(false)
    }
    private var active:Lease?=null
    private var routeContext:ExtendedAppContext?=null
    private var routeModule:ManagementPanelModule?=null
    private var routeNonce=0L
    private var revision=0L
    private var documentRevision=0L
    private var editorRevision=0L
    private var draft:Draft?=null
    private var presentationJob:Job?=null
    private var executionJob:Job?=null
    private var executing:Draft?=null
    private var importFile:ManagedDocumentLease?=null
    private var exportFile:ManagedDocumentLease?=null
    private data class ExportCompletion(val actual:ExtendedAppContext,val route:Long,val target:DnsExportTarget,val result:DnsExportResult)
    // App-job observation only: no executable input, grant, confirmation or persisted success inference.
    private var exportCompletion:ExportCompletion?=null
    private var closed=false
    /** Only Main publishes immutable admission; IO/runtime predicates never mutate UI or route ownership. */
    private data class Admission(val active:Lease?,val route:ExtendedAppContext?,val module:ManagementPanelModule?,
        val nonce:Long,val documentRevision:Long,val draft:Draft?,val revision:Long,val closed:Boolean)
    private val admission=AtomicReference(Admission(null,null,null,0,0,null,0,false))
    private fun publishAdmission() {
        admission.set(Admission(active,routeContext,routeModule,routeNonce,documentRevision,draft,revision,closed))
    }
    private fun onMain()=android.os.Looper.myLooper()==android.os.Looper.getMainLooper()
    private val files=MutableStateFlow(PanelPublication<DnsFilesPanelState>(null,null,DnsFilesPanelState()))
    private val scan=MutableStateFlow(PanelPublication<DnsScanPanelState>(null,null,DnsScanPanelState()))
    private val dnsSettings=MutableStateFlow(PanelPublication<DnsZoneSettingsPanelState>(null,null,DnsZoneSettingsPanelState()))
    private val dnssec=MutableStateFlow(PanelPublication<DnssecPanelState>(null,null,DnssecPanelState()))
    private val zoneSettings=MutableStateFlow(PanelPublication<ZoneSettingsPanelState>(null,null,ZoneSettingsPanelState()))
    val ports=ManagementPanelPorts(
        files=object:DnsFilesPanelPort {
            override val state:StateFlow<PanelPublication<DnsFilesPanelState>> = files.asStateFlow()
            override fun bind(actualContext:ManagementPanelContext)=bind(ManagementPanelModule.FILES,actualContext)
            override fun clear(lease:ManagementPanelLease)=clearPresentation(lease,ManagementPanelModule.FILES)
            override fun onIntent(lease:ManagementPanelLease,intent:DnsFilesPanelIntent) { admitted(lease,ManagementPanelModule.FILES)?.let { filesIntent(it,intent) } }
        },scan=object:DnsScanPanelPort {
            override val state:StateFlow<PanelPublication<DnsScanPanelState>> = scan.asStateFlow()
            override fun bind(actualContext:ManagementPanelContext)=bind(ManagementPanelModule.SCAN,actualContext)
            override fun clear(lease:ManagementPanelLease)=clearPresentation(lease,ManagementPanelModule.SCAN)
            override fun onIntent(lease:ManagementPanelLease,intent:DnsScanPanelIntent) { admitted(lease,ManagementPanelModule.SCAN)?.let { scanIntent(it,intent) } }
        },dnsSettings=object:DnsZoneSettingsPanelPort {
            override val state:StateFlow<PanelPublication<DnsZoneSettingsPanelState>> = dnsSettings.asStateFlow()
            override fun bind(actualContext:ManagementPanelContext)=bind(ManagementPanelModule.DNS_SETTINGS,actualContext)
            override fun clear(lease:ManagementPanelLease)=clearPresentation(lease,ManagementPanelModule.DNS_SETTINGS)
            override fun onIntent(lease:ManagementPanelLease,intent:DnsZoneSettingsPanelIntent) { admitted(lease,ManagementPanelModule.DNS_SETTINGS)?.let { settingsIntent(it,intent) } }
        },dnssec=object:DnssecPanelPort {
            override val state:StateFlow<PanelPublication<DnssecPanelState>> = dnssec.asStateFlow()
            override fun bind(actualContext:ManagementPanelContext)=bind(ManagementPanelModule.DNSSEC,actualContext)
            override fun clear(lease:ManagementPanelLease)=clearPresentation(lease,ManagementPanelModule.DNSSEC)
            override fun onIntent(lease:ManagementPanelLease,intent:DnssecPanelIntent) { admitted(lease,ManagementPanelModule.DNSSEC)?.let { secIntent(it,intent) } }
        },zoneSettings=object:ZoneSettingsPanelPort {
            override val state:StateFlow<PanelPublication<ZoneSettingsPanelState>> = zoneSettings.asStateFlow()
            override fun bind(actualContext:ManagementPanelContext)=bind(ManagementPanelModule.ZONE_SETTINGS,actualContext)
            override fun clear(lease:ManagementPanelLease)=clearPresentation(lease,ManagementPanelModule.ZONE_SETTINGS)
            override fun onIntent(lease:ManagementPanelLease,intent:ZoneSettingsPanelIntent) { admitted(lease,ManagementPanelModule.ZONE_SETTINGS)?.let { zoneIntent(it,intent) } }
        },onRouteExit=::exitRoute)

    private fun live(context:ExtendedAppContext)=!admission.get().closed && actual()==context
    private fun current(lease:Lease)=admission.get().active===lease && live(lease.actual)
    private fun admitted(lease:ManagementPanelLease,module:ManagementPanelModule):Lease? {
        if(!onMain())return null
        refreshActual()
        return (lease as? Lease)?.takeIf { it.module==module && current(it) }
    }
    private fun bind(module:ManagementPanelModule,context:ManagementPanelContext):ManagementPanelLease? {
        if(!onMain())return null
        refreshActual()
        val now=actual()?.takeIf { !closed && it.panel==context } ?: return null
        if(routeContext!=now || routeModule!=module) revokeRoute()
        routeContext=now;routeModule=module
        presentationJob?.cancel();invalidateDraft(true)
        val lease=Lease(context,now,module,routeNonce);active=lease;publishAdmission()
        withdraw()
        when(module) {
            ManagementPanelModule.FILES -> put(lease,DnsFilesPanelState(scope=context.scope,epoch=context.currentEpoch,
                file=importFile?.file,selection=if(importFile!=null)DnsFilesSelection.READY else DnsFilesSelection.NONE,
                exportTarget=exportFile?.target,exportStage=if(exportFile!=null)DnsFilesExportStage.READY else DnsFilesExportStage.IDLE,
                proxied=null,revision=revision,editorRevision=editorRevision))
            ManagementPanelModule.SCAN -> put(lease,DnsScanPanelState(scope=context.scope,epoch=context.currentEpoch,revision=revision,editorRevision=editorRevision))
            ManagementPanelModule.DNS_SETTINGS -> put(lease,DnsZoneSettingsPanelState(scope=context.scope,epoch=context.currentEpoch,revision=revision,editorRevision=editorRevision))
            ManagementPanelModule.DNSSEC -> put(lease,DnssecPanelState(scope=context.scope,revision=revision,editorRevision=editorRevision))
            ManagementPanelModule.ZONE_SETTINGS -> put(lease,ZoneSettingsPanelState(scope=context.scope,revision=revision,editorRevision=editorRevision))
        }
        refresh(lease)
        return lease
    }
    private fun withdraw() {
        files.value=files.value.copy(context=null,lease=null)
        scan.value=scan.value.copy(context=null,lease=null)
        dnsSettings.value=dnsSettings.value.copy(context=null,lease=null)
        dnssec.value=dnssec.value.copy(context=null,lease=null)
        zoneSettings.value=zoneSettings.value.copy(context=null,lease=null)
    }
    private fun clearPresentation(lease:ManagementPanelLease,module:ManagementPanelModule) {
        val issued=admitted(lease,module) ?: return
        active=null;publishAdmission();presentationJob?.cancel();invalidateDraft(true);withdraw()
        // Route/request and app jobs survive composition disposal. User exit is a distinct callback.
        check(issued.route==routeNonce)
    }
    private fun exitRoute(lease:ManagementPanelLease) {
        if(!onMain())return
        val issued=(lease as? Lease)?.takeIf { current(it) && routeContext==it.actual && routeModule==it.module } ?: return
        if(issued.route==routeNonce) revokeRoute()
    }
    private fun releaseLater(lease:ManagedDocumentLease?) {
        if(lease!=null) scope.launch { withContext(NonCancellable) { platform().release(lease) } }
    }
    private fun revokeRoute() {
        presentationJob?.cancel();invalidateDraft(true);active=null;withdraw()
        routeContext=null;routeModule=null;routeNonce++;documentRevision++;publishAdmission();invalidateDocument()
        releaseLater(importFile);importFile=null;releaseLater(exportFile);exportFile=null;exportCompletion=null
    }
    internal fun refreshActual() { check(onMain());if(routeContext?.let { !live(it) }==true) revokeRoute() }
    internal fun invalidateConnection(connectionId:String) { check(onMain());if(routeContext?.connection?.id==connectionId) revokeRoute() }
    private fun invalidateDraft(editor:Boolean=false) {
        val old=draft;draft=null;revision++;if(editor)editorRevision++;publishAdmission()
        // Do not revoke the runtime's queued confirmation after app ownership was handed off.
        if(executing==null) (old?.command?.scope ?: routeContext?.panel?.scope)?.let(runtime::invalidateExtended)
        active?.takeIf(::current)?.let { l -> when(l.module) {
            ManagementPanelModule.FILES -> put(l,files.value.state.copy(prepared=null,revision=revision,editorRevision=editorRevision,loading=false,errors=emptyList()))
            ManagementPanelModule.SCAN -> put(l,scan.value.state.copy(prepared=null,revision=revision,editorRevision=editorRevision,loading=emptySet(),errors=emptyList()))
            ManagementPanelModule.DNS_SETTINGS -> put(l,dnsSettings.value.state.copy(prepared=null,draft=null,revision=revision,editorRevision=editorRevision,loading=false,errors=emptyList()))
            ManagementPanelModule.DNSSEC -> put(l,dnssec.value.state.copy(prepared=null,revision=revision,editorRevision=editorRevision,loading=false,errors=emptyList()))
            ManagementPanelModule.ZONE_SETTINGS -> put(l,zoneSettings.value.state.copy(prepared=null,revision=revision,editorRevision=editorRevision,loading=false,errors=emptyList()))
        } }
    }
    private fun put(l:Lease,s:DnsFilesPanelState) { if(current(l)) files.value=PanelPublication(l.context,l,s) }
    private fun put(l:Lease,s:DnsScanPanelState) { if(current(l)) scan.value=PanelPublication(l.context,l,s) }
    private fun put(l:Lease,s:DnsZoneSettingsPanelState) { if(current(l)) dnsSettings.value=PanelPublication(l.context,l,s) }
    private fun put(l:Lease,s:DnssecPanelState) { if(current(l)) dnssec.value=PanelPublication(l.context,l,s) }
    private fun put(l:Lease,s:ZoneSettingsPanelState) { if(current(l)) zoneSettings.value=PanelPublication(l.context,l,s) }
    private fun loading(l:Lease,busy:Boolean) { when(l.module) {
        ManagementPanelModule.FILES -> put(l,files.value.state.copy(loading=busy))
        ManagementPanelModule.SCAN -> put(l,scan.value.state.copy(loading=if(busy)setOf(DnsTask7Section.SCAN_REVIEW) else emptySet()))
        ManagementPanelModule.DNS_SETTINGS -> put(l,dnsSettings.value.state.copy(loading=busy))
        ManagementPanelModule.DNSSEC -> put(l,dnssec.value.state.copy(loading=busy))
        ManagementPanelModule.ZONE_SETTINGS -> put(l,zoneSettings.value.state.copy(loading=busy))
    } }
    private fun failure(l:Lease,e:Exception,export:Boolean=false) {
        if(!current(l)) return
        val message=safeMessage(e)
        loading(l,false)
        when(l.module) {
            ManagementPanelModule.FILES -> put(l,files.value.state.copy(errors=listOf(DnsFilesPanelError(l.context.scope,l.context.currentEpoch,
                if(export)DnsFilesSection.EXPORT else DnsFilesSection.IMPORT,message)),exportStage=if(export)DnsFilesExportStage.FAILED else files.value.state.exportStage))
            ManagementPanelModule.SCAN -> put(l,scan.value.state.copy(errors=listOf(DnsTask7Error(l.context.scope,l.context.currentEpoch,DnsTask7Section.SCAN_REVIEW,message))))
            ManagementPanelModule.DNS_SETTINGS -> put(l,dnsSettings.value.state.copy(errors=listOf(DnsTask7Error(l.context.scope,l.context.currentEpoch,DnsTask7Section.SETTINGS,message))))
            ManagementPanelModule.DNSSEC -> put(l,dnssec.value.state.copy(errors=listOf(DnssecPanelError(l.context.scope,DnssecPanelSource.DNSSEC,message))))
            ManagementPanelModule.ZONE_SETTINGS -> put(l,zoneSettings.value.state.copy(errors=listOf(ZoneSettingsPanelError(l.context.scope,zoneSettings.value.state.target,message))))
        }
    }
    /** Serialization only around presentation work; every actual operation still enters the single runtime lock. */
    private fun work(l:Lease,block:suspend()->Unit) {
        val previous=presentationJob;previous?.cancel()
        val running=executionJob
        presentationJob=scope.launch {
            previous?.join();running?.join()
            if(!current(l)) return@launch
            try { block() }
            catch(cancel:CancellationException) { throw cancel }
            catch(e:Exception) { failure(l,e) }
        }
    }
    private fun family(l:Lease)=ExtendedManagementFamily.valueOf(l.module.name)
    private suspend fun pending(l:Lease) {
        val tasks=runtime.pendingExtended(l.context.scope,family(l),if(l.module==ManagementPanelModule.ZONE_SETTINGS)zoneSettings.value.state.target else null)
        if(!current(l))return
        when(l.module) {
            ManagementPanelModule.FILES -> {
                // Query this task's real in-memory receipt; never fall back to an older success
                // when the current task has no receipt (including a freshly rebuilt runtime).
                val task=tasks.firstOrNull { it.actionKey==DNS_IMPORT_OPERATION }
                val receipt=task?.let { runtime.importOutcome(l.context.scope,it.id) }
                val observation=receipt?.let { DnsFilesImportObservation(l.context.scope,l.context.currentEpoch,it) }
                if(!current(l))return
                val exported=exportCompletion?.takeIf { it.actual==l.actual && it.route==l.route &&
                    it.target===exportFile?.target && it.result.scope==l.context.scope }
                put(l,files.value.state.copy(pendingTasks=tasks,outcome=observation,
                    exportStage=if(exported!=null)DnsFilesExportStage.SUCCEEDED else files.value.state.exportStage,
                    exportResult=exported?.let { DnsFilesExportObservation(l.context.currentEpoch,it.target,it.result) }))
            }
            ManagementPanelModule.SCAN -> put(l,scan.value.state.copy(pendingTasks=tasks))
            ManagementPanelModule.DNS_SETTINGS -> put(l,dnsSettings.value.state.copy(pendingTasks=tasks))
            ManagementPanelModule.DNSSEC -> put(l,dnssec.value.state.copy(pendingTasks=tasks))
            ManagementPanelModule.ZONE_SETTINGS -> put(l,zoneSettings.value.state.copy(pendingTasks=tasks))
        }
    }
    private fun refresh(l:Lease,request:ExtendedManagementRead=when(l.module) {
        ManagementPanelModule.FILES -> ExtendedManagementRead.Files
        ManagementPanelModule.SCAN -> ExtendedManagementRead.ScanReview
        ManagementPanelModule.DNS_SETTINGS -> ExtendedManagementRead.DnsSettings
        ManagementPanelModule.DNSSEC -> ExtendedManagementRead.Dnssec(dnssec.value.state.mutation)
        ManagementPanelModule.ZONE_SETTINGS -> ExtendedManagementRead.ZoneSettings(zoneSettings.value.state.target)
    }) {
        invalidateDraft();loading(l,true)
        work(l) {
            val result=runtime.readExtended(l.context.scope,request) { current(l) }
            if(!current(l))return@work
            when(result) {
                is ExtendedManagementReadResult.Files -> put(l,files.value.state.copy(snapshot=result.snapshot,exportAllowed=true,errors=emptyList()))
                is ExtendedManagementReadResult.ScanReview -> put(l,scan.value.state.copy(snapshot=result.snapshot,constraints=result.snapshot.constraints,reviewLoaded=true,errors=emptyList()))
                is ExtendedManagementReadResult.Usage -> put(l,scan.value.state.copy(usage=DnsScanUsage(l.context.currentEpoch,result.usage),errors=emptyList()))
                is ExtendedManagementReadResult.DnsSettings -> put(l,dnsSettings.value.state.copy(snapshot=result.snapshot,errors=emptyList()))
                is ExtendedManagementReadResult.Dnssec -> put(l,dnssec.value.state.copy(snapshot=result.snapshot,snapshotMutation=(request as ExtendedManagementRead.Dnssec).mutation,errors=emptyList()))
                is ExtendedManagementReadResult.DnssecKeys -> put(l,dnssec.value.state.copy(keys=result.keys,errors=emptyList()))
                is ExtendedManagementReadResult.ZoneSettings -> {
                    val target=(request as ExtendedManagementRead.ZoneSettings).target
                    put(l,zoneSettings.value.state.copy(snapshots=zoneSettings.value.state.snapshots+(target to result.snapshot),errors=emptyList()))
                }
            }
            pending(l);loading(l,false)
        }
    }
    private fun freeze(command:ExtendedManagementCommand):ExtendedManagementCommand=when(command) {
        is ExtendedManagementCommand.Import -> command.copy(file=command.file.copy())
        is ExtendedManagementCommand.Dnssec -> command.copy(action=when(val a=command.action) { is DnssecAction.Update -> a.copy(input=a.input.copy());is DnssecAction.Delete -> a.copy() })
        is ExtendedManagementCommand.DnsSettings -> command.copy(action=command.action.copy(changed=copyJson(command.action.changed)))
        is ExtendedManagementCommand.Scan -> command.copy(action=when(val a=command.action) {
            is DnsScanAction.Trigger -> a.copy()
            is DnsScanAction.Apply -> a.copy(input=a.input.copy(acceptIds=a.input.acceptIds.toList(),rejectIds=a.input.rejectIds.toList(),expectedCandidateHashes=a.input.expectedCandidateHashes.toMap()))
        })
        is ExtendedManagementCommand.ZoneSettings -> command.copy(action=when(val a=command.action) {
            is ZoneSettingsAction.Update -> a.copy(input=copyJson(a.input))
            is ZoneSettingsAction.UniversalSsl -> a.copy()
            is ZoneSettingsAction.Purge -> a.copy(input=when(val p=a.input) {
                CachePurgeInput.All -> p
                is CachePurgeInput.Files -> p.copy(files=p.files.map { it.copy(headers=it.headers.toMap()) })
                is CachePurgeInput.Hosts -> p.copy(hosts=p.hosts.toList())
                is CachePurgeInput.Tags -> p.copy(tags=p.tags.toList())
                is CachePurgeInput.Prefixes -> p.copy(prefixes=p.prefixes.toList())
            })
        })
    }
    private fun copyJson(value:JsonObject)=Json.parseToJsonElement(value.toString()).jsonObject
    private fun valid(d:Draft):Boolean {
        val snapshot=admission.get()
        return !snapshot.closed && actual()==d.lease.actual && (d.handedOff.get() ||
            (snapshot.draft===d && snapshot.revision==d.revision && snapshot.active===d.lease))
    }
    private fun preview(l:Lease,command:ExtendedManagementCommand) {
        if(command.scope!=l.context.scope)return
        if(command is ExtendedManagementCommand.Import) {
            // Preview consumes the already explicit file/options draft, without changing its revision.
            // Replace only the admission identity: an older prepare/confirmation cannot revive it.
            draft=null;publishAdmission()
            if(executing==null)runtime.invalidateExtended(command.scope)
            put(l,files.value.state.copy(prepared=null,errors=emptyList()))
        } else invalidateDraft()
        val d=Draft(freeze(command),revision,l);draft=d;publishAdmission()
        if(d.command is ExtendedManagementCommand.DnsSettings)put(l,dnsSettings.value.state.copy(draft=d.command.action))
        loading(l,true)
        work(l) {
            if(!valid(d))return@work
            val p=runtime.prepareExtended(d.command,d.revision) { valid(d) }
            if(!valid(d))return@work
            d.preview=p
            when(val detail=p.details) {
                is ExtendedChangeDetails.Import -> { val c=d.command as ExtendedManagementCommand.Import
                    put(l,files.value.state.copy(prepared=DnsFilesPanelPreview(p.planId,p.revision,l.context.currentEpoch,detail.preview,DnsFilesImportRequest(c.scope,c.file,c.proxied),p.resourceRef))) }
                is ExtendedChangeDetails.Scan -> put(l,scan.value.state.copy(prepared=DnsScanPanelPreview(p.planId,p.revision,l.context.currentEpoch,(d.command as ExtendedManagementCommand.Scan).action,detail.preview,p.resourceRef)))
                is ExtendedChangeDetails.DnsSettings -> put(l,dnsSettings.value.state.copy(prepared=DnsSettingsPanelPreview(p.planId,p.revision,l.context.currentEpoch,(d.command as ExtendedManagementCommand.DnsSettings).action,detail.preview,p.resourceRef)))
                is ExtendedChangeDetails.Dnssec -> {
                    val before=detail.before ?: throw CloudFailure(CloudFailureCode.INVALID_INPUT,"管理输入或确认已失效，请重新选择并预览")
                    val action=(d.command as ExtendedManagementCommand.Dnssec).action
                    put(l,dnssec.value.state.copy(snapshot=before,snapshotMutation=detail.preview.mutation,
                        prepared=DnssecPanelPreview(p.planId,p.revision,detail.preview,action,p.resourceRef,before.details,detail.after)))
                }
                is ExtendedChangeDetails.ZoneSettings -> {
                    val action=(d.command as ExtendedManagementCommand.ZoneSettings).action
                    val target=zoneTarget(action)
                    if(action !is ZoneSettingsAction.Purge && detail.before==null)throw CloudFailure(CloudFailureCode.INVALID_INPUT,"管理输入或确认已失效，请重新选择并预览")
                    val snapshots=detail.before?.let { zoneSettings.value.state.snapshots+(target to it) } ?: zoneSettings.value.state.snapshots
                    put(l,zoneSettings.value.state.copy(snapshots=snapshots,prepared=ZoneSettingsPanelPreview(p.planId,p.revision,detail.preview,action,p.resourceRef,
                        detail.before?.setting?.value ?: detail.before?.universal?.let { JsonPrimitive(it.enabled) },detail.after)))
                }
            }
            loading(l,false)
        }
    }
    private fun zoneTarget(a:ZoneSettingsAction)=when(a) { is ZoneSettingsAction.Update -> ZoneSettingsTarget.Setting(a.key);is ZoneSettingsAction.UniversalSsl -> ZoneSettingsTarget.UniversalSsl;is ZoneSettingsAction.Purge -> ZoneSettingsTarget.Cache }
    private fun confirm(l:Lease,id:String,rev:Long,risks:ExtendedRiskChoices=ExtendedRiskChoices()) {
        val d=draft?.takeIf { it.lease===l && valid(it) && it.preview?.planId==id && it.revision==rev } ?: return
        if(executionJob?.isActive==true)return
        val queuedRead=presentationJob
        executionJob=scope.launch {
            queuedRead?.join()
            if(!valid(d))return@launch
            var fileJob:ManagedDocumentLease?=null
            try {
                if(d.command is ExtendedManagementCommand.Import) {
                    val held=importFile?.takeIf { it.file==d.command.file } ?: throw CloudFailure(CloudFailureCode.INVALID_INPUT,"管理输入或确认已失效，请重新选择并预览")
                    fileJob=platform().claimJob(borrower(),held)
                }
                // No suspension between the final presentation admission, capability claim and handoff.
                if(!valid(d))throw CloudFailure(CloudFailureCode.INVALID_INPUT,"管理输入或确认已失效，请重新选择并预览")
                d.handedOff.set(true);executing=d;draft=null;invalidateDraft();loading(l,true)
                val task=runtime.confirmExtended(id,rev,risks)
                val receipt=if(d.command is ExtendedManagementCommand.Import)runtime.importOutcome(l.context.scope,task.id) else null
                if(current(l)) {
                    pending(l)
                    if(receipt!=null)put(l,files.value.state.copy(outcome=DnsFilesImportObservation(l.context.scope,l.context.currentEpoch,receipt)))
                    loading(l,false)
                }
            } catch(cancel:CancellationException) { throw cancel }
            catch(e:Exception) { failure(l,e) }
            finally {
                // End only this job's actual capability. A failed before-consumption confirmation
                // must not leave the runtime's old prepared closure live; newer plans are untouched.
                d.handedOff.set(false)
                if(executing===d)executing=null
                withContext(NonCancellable) { fileJob?.let { platform().release(it) } }
            }
        }
    }
    private fun reconcile(l:Lease,id:String) {
        invalidateDraft();loading(l,true)
        work(l) {
            runtime.reconcileExtended(l.context.scope,id) { current(l) }
            if(current(l)) { pending(l);loading(l,false) }
        }
    }
    private fun scanIntent(l:Lease,i:DnsScanPanelIntent) { when(i) {
        is DnsScanPanelIntent.RefreshReview -> if(i.scope==l.context.scope)refresh(l)
        is DnsScanPanelIntent.RefreshUsage -> if(i.scope==l.context.scope)refresh(l,ExtendedManagementRead.Usage)
        is DnsScanPanelIntent.DraftInvalidated -> if(i.scope==l.context.scope)invalidateDraft()
        is DnsScanPanelIntent.SelectionChanged -> if(i.scope==l.context.scope) { invalidateDraft();put(l,scan.value.state.copy(selection=i.input.copy(acceptIds=i.input.acceptIds.toList(),rejectIds=i.input.rejectIds.toList(),expectedCandidateHashes=i.input.expectedCandidateHashes.toMap()))) }
        is DnsScanPanelIntent.Preview -> preview(l,ExtendedManagementCommand.Scan(i.action))
        is DnsScanPanelIntent.Confirm -> confirm(l,i.planId,i.revision)
        is DnsScanPanelIntent.BackToDraft -> if(i.scope==l.context.scope)invalidateDraft()
        is DnsScanPanelIntent.Cancel -> if(i.scope==l.context.scope)invalidateDraft(true)
        is DnsScanPanelIntent.Reconcile -> if(i.scope==l.context.scope)reconcile(l,i.taskId)
        is DnsScanPanelIntent.Clear -> if(i.scope==l.context.scope)clearPresentation(l,l.module)
    } }
    private fun settingsIntent(l:Lease,i:DnsZoneSettingsPanelIntent) { when(i) {
        is DnsZoneSettingsPanelIntent.Refresh -> if(i.scope==l.context.scope)refresh(l)
        is DnsZoneSettingsPanelIntent.DraftInvalidated -> if(i.scope==l.context.scope)invalidateDraft()
        is DnsZoneSettingsPanelIntent.DraftChanged -> if(i.action.scope==l.context.scope) { invalidateDraft();put(l,dnsSettings.value.state.copy(draft=i.action.copy(changed=copyJson(i.action.changed)))) }
        is DnsZoneSettingsPanelIntent.Preview -> preview(l,ExtendedManagementCommand.DnsSettings(i.action))
        is DnsZoneSettingsPanelIntent.Confirm -> confirm(l,i.planId,i.revision,ExtendedRiskChoices(dnsSettings=i.acknowledgedRisks.toSet()))
        is DnsZoneSettingsPanelIntent.BackToDraft -> if(i.scope==l.context.scope)invalidateDraft()
        is DnsZoneSettingsPanelIntent.Cancel -> if(i.scope==l.context.scope)invalidateDraft(true)
        is DnsZoneSettingsPanelIntent.Reconcile -> if(i.scope==l.context.scope)reconcile(l,i.taskId)
        is DnsZoneSettingsPanelIntent.Clear -> if(i.scope==l.context.scope)clearPresentation(l,l.module)
    } }
    private fun secIntent(l:Lease,i:DnssecPanelIntent) { when(i) {
        is DnssecPanelIntent.SelectMutation -> if(i.scope==l.context.scope) { invalidateDraft(true);put(l,dnssec.value.state.copy(mutation=i.mutation,snapshot=null,snapshotMutation=null));refresh(l) }
        is DnssecPanelIntent.Refresh -> if(i.scope==l.context.scope)refresh(l,if(i.source==DnssecPanelSource.ZSK)ExtendedManagementRead.DnssecKeys else ExtendedManagementRead.Dnssec(dnssec.value.state.mutation))
        is DnssecPanelIntent.DraftInvalidated -> if(i.scope==l.context.scope)invalidateDraft()
        is DnssecPanelIntent.Preview -> if((if(i.action is DnssecAction.Update)DnssecMutation.UPDATE else DnssecMutation.DELETE)==dnssec.value.state.mutation)
            preview(l,ExtendedManagementCommand.Dnssec(i.action))
        is DnssecPanelIntent.Confirm -> confirm(l,i.planId,i.revision,ExtendedRiskChoices(dnssec=i.acknowledgedRisks.toSet()))
        is DnssecPanelIntent.BackToDraft -> if(i.scope==l.context.scope)invalidateDraft()
        is DnssecPanelIntent.Cancel -> if(i.scope==l.context.scope)invalidateDraft(true)
        is DnssecPanelIntent.Reconcile -> if(i.scope==l.context.scope)reconcile(l,i.taskId)
    } }
    private fun zoneIntent(l:Lease,i:ZoneSettingsPanelIntent) { when(i) {
        is ZoneSettingsPanelIntent.Select -> if(i.scope==l.context.scope) { invalidateDraft(true);put(l,zoneSettings.value.state.copy(target=i.target));refresh(l) }
        is ZoneSettingsPanelIntent.Refresh -> if(i.scope==l.context.scope && i.target==zoneSettings.value.state.target)refresh(l)
        is ZoneSettingsPanelIntent.DraftInvalidated -> if(i.scope==l.context.scope && i.target==zoneSettings.value.state.target)invalidateDraft()
        is ZoneSettingsPanelIntent.Preview -> if(zoneTarget(i.action)==zoneSettings.value.state.target)preview(l,ExtendedManagementCommand.ZoneSettings(i.action))
        is ZoneSettingsPanelIntent.Confirm -> confirm(l,i.planId,i.revision,ExtendedRiskChoices(zoneSettings=i.acknowledgedRisk))
        is ZoneSettingsPanelIntent.BackToDraft -> if(i.scope==l.context.scope)invalidateDraft()
        is ZoneSettingsPanelIntent.Cancel -> if(i.scope==l.context.scope)invalidateDraft(true)
        is ZoneSettingsPanelIntent.Reconcile -> if(i.scope==l.context.scope)reconcile(l,i.taskId)
    } }
    private fun filesIntent(l:Lease,i:DnsFilesPanelIntent) { when(i) {
        is DnsFilesPanelIntent.SelectImportFile -> if(i.scope==l.context.scope)choose(l,DnsDocumentKind.IMPORT)
        is DnsFilesPanelIntent.SelectExportTarget -> if(i.scope==l.context.scope)choose(l,DnsDocumentKind.EXPORT)
        is DnsFilesPanelIntent.DraftInvalidated -> if(i.scope==l.context.scope) { documentRevision++;publishAdmission();invalidateDocument();invalidateDraft() }
        is DnsFilesPanelIntent.OptionsChanged -> if(i.scope==l.context.scope) { documentRevision++;publishAdmission();invalidateDocument();invalidateDraft();put(l,files.value.state.copy(proxied=i.proxied)) }
        is DnsFilesPanelIntent.Preview -> if(i.request.scope==l.context.scope && i.request.file==importFile?.file && i.request.proxied==files.value.state.proxied)
            preview(l,ExtendedManagementCommand.Import(i.request.scope,i.request.file,i.request.proxied))
        is DnsFilesPanelIntent.Confirm -> confirm(l,i.planId,i.revision)
        is DnsFilesPanelIntent.BackToDraft -> if(i.scope==l.context.scope)invalidateDraft()
        is DnsFilesPanelIntent.CancelImport -> if(i.scope==l.context.scope) { documentRevision++;publishAdmission();invalidateDocument();invalidateDraft(true);releaseLater(importFile);importFile=null;put(l,files.value.state.copy(file=null,selection=DnsFilesSelection.CANCELLED)) }
        is DnsFilesPanelIntent.Reconcile -> if(i.scope==l.context.scope)reconcile(l,i.taskId)
        is DnsFilesPanelIntent.Refresh -> if(i.scope==l.context.scope)refresh(l)
        is DnsFilesPanelIntent.Export -> if(i.scope==l.context.scope && i.target===exportFile?.target)export(l,i.target)
        is DnsFilesPanelIntent.CancelExport -> if(i.scope==l.context.scope) { exportCompletion=null;documentRevision++;publishAdmission();invalidateDocument();releaseLater(exportFile);exportFile=null;put(l,files.value.state.copy(exportTarget=null,exportStage=DnsFilesExportStage.CANCELLED)) }
        is DnsFilesPanelIntent.Clear -> if(i.scope==l.context.scope)clearPresentation(l,l.module)
    } }
    private fun choose(l:Lease,kind:DnsDocumentKind) {
        invalidateDraft();documentRevision++;publishAdmission()
        if(kind==DnsDocumentKind.IMPORT)put(l,files.value.state.copy(selection=DnsFilesSelection.SELECTING,errors=emptyList()))
        else { exportCompletion=null;put(l,files.value.state.copy(exportStage=DnsFilesExportStage.SELECTING,exportResult=null,errors=emptyList())) }
        try { selectDocument(kind) } catch(e:Exception) { failure(l,e,kind==DnsDocumentKind.EXPORT) }
    }
    /** Also called by picker/Owner and core fileDispatcher on IO: pure, nonblocking, no cleanup or publication. */
    internal fun documentRequestStamp():DnsDocumentRequestStamp? {
        val snapshot=admission.get()
        val context=snapshot.route?.takeIf { !snapshot.closed && snapshot.module==ManagementPanelModule.FILES && actual()==it } ?: return null
        return DnsDocumentRequestStamp(context.panel.scope,context.panel.currentEpoch,context.credential,snapshot.documentRevision,snapshot.nonce)
    }
    internal fun documentSelected(lease:ManagedDocumentLease,stamp:DnsDocumentRequestStamp) {
        if(documentRequestStamp()!=stamp) { releaseLater(lease);return }
        if(lease.file!=null) { releaseLater(importFile);importFile=lease }
        else if(lease.target!=null) { releaseLater(exportFile);exportFile=lease }
        else { releaseLater(lease);return }
        active?.takeIf { it.module==ManagementPanelModule.FILES && current(it) }?.let { l ->
            put(l,files.value.state.copy(file=importFile?.file,selection=if(importFile!=null)DnsFilesSelection.READY else files.value.state.selection,
                exportTarget=exportFile?.target,exportStage=if(exportFile!=null)DnsFilesExportStage.READY else files.value.state.exportStage))
        }
    }
    internal fun documentCancelled(kind:DnsDocumentKind) { active?.takeIf { it.module==ManagementPanelModule.FILES && current(it) }?.let { l ->
        if(kind==DnsDocumentKind.IMPORT)put(l,files.value.state.copy(selection=DnsFilesSelection.CANCELLED))
        else put(l,files.value.state.copy(exportStage=DnsFilesExportStage.CANCELLED))
    } }
    internal fun documentFailed(kind:DnsDocumentKind,error:Exception) { active?.takeIf { current(it) }?.let { failure(it,error,kind==DnsDocumentKind.EXPORT) } }
    private fun export(l:Lease,target:DnsExportTarget) {
        if(executionJob?.isActive==true)return
        val held=exportFile?.takeIf { it.target===target } ?: return
        val oldRead=presentationJob
        executionJob=scope.launch {
            oldRead?.join()
            if(!current(l))return@launch
            var claimed:ManagedDocumentLease?=null
            val captured=l.actual
            try {
                claimed=platform().claimJob(borrower(),held)
                if(!current(l))throw CloudFailure(CloudFailureCode.INVALID_INPUT,"管理输入或确认已失效，请重新选择并预览")
                put(l,files.value.state.copy(exportStage=DnsFilesExportStage.TEMPORARY,errors=emptyList()))
                val result=runtime.exportDns(l.context.scope,target) { live(captured) }
                if(live(captured) && routeContext==captured && routeModule==ManagementPanelModule.FILES &&
                    routeNonce==l.route && exportFile===held && result.scope==l.context.scope) {
                    val frozen=result.copy(file=result.file.copy(),head=result.head.copy(headers=result.head.headers.mapValues { it.value.toList() }))
                    exportCompletion=ExportCompletion(captured,l.route,target,frozen)
                    if(current(l))put(l,files.value.state.copy(exportStage=DnsFilesExportStage.SUCCEEDED,exportResult=DnsFilesExportObservation(l.context.currentEpoch,target,frozen)))
                }
            } catch(cancel:CancellationException) { throw cancel }
            catch(e:Exception) { failure(l,e,true) }
            finally { withContext(NonCancellable) { claimed?.let { platform().release(it) } } }
        }
    }
    internal fun close() { check(onMain());revokeRoute();closed=true;publishAdmission() }
}
