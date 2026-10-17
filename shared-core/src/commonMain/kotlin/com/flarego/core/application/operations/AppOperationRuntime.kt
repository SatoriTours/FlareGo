package com.flarego.core.application.operations

import com.flarego.core.model.*
import com.flarego.core.application.management.*
import com.flarego.core.application.dns.*
import com.flarego.core.application.zones.ZoneController
import kotlinx.serialization.json.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

/** App lifetime owner of confirmed DNS writes, metadata recovery and read-only reconciliation. */
class AppOperationRuntime(
    private val appScope: CoroutineScope,
    store: OperationStore,
    private val storeDispatcher: CoroutineDispatcher,
    private val currentState: () -> AppState,
    private val selectionEpoch: () -> Long,
    private val credentialRevision: (String) -> Long,
    private val sessionFactory: SessionFactory,
    private val fingerprint: FingerprintPort,
    private val clock: OperationClock,
    private val ids: OperationIds,
    private val publish: (List<OperationTask>) -> Unit,
    private val managementFactory: ManagementSessionFactory? = null,
    private val managementPlatform: ManagementPlatformPorts? = null,
) : DnsOperations, ManagementActions, DnsBatchActions, ExtendedManagementActions {
    private val taskStore=ManagementOperationStore(store,fingerprint) { id -> currentState().connections.any { it.id==id && it.demo } }
    private val examples=mutableMapOf<Connection,com.flarego.core.demo.DemoDnsManagement>()
    val management = com.flarego.core.application.management.CloudflareManagement(
        com.flarego.core.application.dns.DnsController(appScope,{ scope -> managementSession(scope).dns },::managementContext,this),
        com.flarego.core.application.zones.ZonePageController(appScope,{ scope -> managementSession(scope).zones },::managementContext,this),
        batch = DnsBatchPageController(appScope,::batchSession,::managementContext,this,this),
    )
    private class ExtendedActual(val command: ExtendedManagementCommand, val revision: Long, val stillCurrent: () -> Boolean, val context:ManagementContext) {
        var importOutcome:DnsImportOutcome?=null
        var plan: ChangePlan? = null
    }
    private val extendedPrepared=MutableStateFlow<ExtendedActual?>(null)
    private val extendedExecuting=MutableStateFlow<ExtendedActual?>(null)
    private val extendedExamples=mutableMapOf<Connection,com.flarego.core.demo.DemoExtendedManagement>()
    private val fileChanges by lazy {
        val platform=managementPlatform ?: throw extendedUnavailable()
        DnsFileController(resolver,plans,coordinator,clock,fingerprint,taskStore,storeDispatcher,platform.files,platform.fileDispatcher,
            session=::fileSession)
    }
    private val scanChanges by lazy { DnsScanController(resolver,plans,coordinator,clock,fingerprint,taskStore,storeDispatcher,
        session={ managementSession(it).scan ?: throw extendedUnavailable() }) }
    private val dnsSettingsChanges by lazy { DnsZoneSettingsController(resolver,plans,coordinator,clock,fingerprint,taskStore,storeDispatcher,
        session={ managementSession(it).dnsSettings ?: throw extendedUnavailable() }) }
    private val dnssecChanges by lazy { DnssecController(resolver,plans,coordinator,clock,fingerprint,taskStore,storeDispatcher,
        session=::dnssecSession) }
    private val zoneSettingsChanges by lazy { com.flarego.core.application.zones.ZoneSettingsController(resolver,plans,coordinator,clock,fingerprint,taskStore,storeDispatcher,
        session=::zoneSettingsSession) }

    private data class ImportEvidence(val context:ManagementContext,val settled:OperationTask,val outcome:DnsImportOutcome)
    private val importReceipts=MutableStateFlow<Map<String,ImportEvidence>>(emptyMap())
    private fun frozenImport(outcome:DnsImportOutcome):DnsImportOutcome = when(outcome) {
        is DnsImportOutcome.ReportedSuccess -> outcome.copy(readback=outcome.readback.copy())
        is DnsImportOutcome.Partial -> outcome.copy(readback=outcome.readback?.copy())
        is DnsImportOutcome.Unknown -> outcome.copy()
    }
    private fun importScopeMatches(outcome:DnsImportOutcome,scope:ZoneScope)=when(outcome) {
        is DnsImportOutcome.ReportedSuccess -> outcome.readback.scope==scope
        is DnsImportOutcome.Partial -> outcome.readback?.scope?.let { it==scope } ?: true
        is DnsImportOutcome.Unknown -> true
    }
    private fun importStateMatches(outcome:DnsImportOutcome,state:TaskState)=when(outcome) {
        is DnsImportOutcome.ReportedSuccess -> state==TaskState.SUCCEEDED
        is DnsImportOutcome.Partial -> state==TaskState.PARTIAL
        is DnsImportOutcome.Unknown -> state==TaskState.UNKNOWN
    }
    private suspend fun fileSession(scope:ZoneScope):DnsFilesSession {
        val api=managementSession(scope).files ?: throw extendedUnavailable()
        val actual=extendedExecuting.value ?: return api
        val command=actual.command as? ExtendedManagementCommand.Import ?: return api
        if(command.scope!=scope || managementContext(scope)!=actual.context) throw inputChanged()
        return object:DnsFilesSession by api {
            override suspend fun writeImport(scope:ZoneScope,source:suspend()->ByteSource,length:Long,fingerprint:String,proxied:Boolean,
                beforeSend:(DnsFileImportState)->Unit,afterRead:()->Unit):DnsImportOutcome {
                if(extendedExecuting.value!==actual || !actual.stillCurrent() || managementContext(scope)!=actual.context ||
                    scope!=command.scope || length!=command.file.length || fingerprint!=command.file.sha256 || proxied!=command.proxied || actual.importOutcome!=null) throw inputChanged()
                val outcome=frozenImport(api.writeImport(scope,source,length,fingerprint,proxied,beforeSend,afterRead))
                if(extendedExecuting.value!==actual || !actual.stillCurrent() || managementContext(scope)!=actual.context) throw inputChanged()
                actual.importOutcome=outcome
                return outcome
            }
        }
    }
    private suspend fun retainImportReceipt(actual:ExtendedActual,task:OperationTask) {
        val command=actual.command as? ExtendedManagementCommand.Import ?: return
        val outcome=actual.importOutcome ?: return
        val plan=actual.plan ?: return
        if(!actual.stillCurrent() || managementContext(command.scope)!=actual.context || !importScopeMatches(outcome,command.scope) ||
            !importStateMatches(outcome,task.state) || task.id!=plan.id || task.scope!=command.scope ||
            task.actionKey!=DNS_IMPORT_OPERATION || task.resourceRef!=ResourceRef(command.scope,"dns_import",command.scope.zoneId) ||
            task.requestFingerprint!=plan.requestFingerprint) return
        val settled=withContext(storeDispatcher) { taskStore.get(task.id) }
        if(settled!=task || !actual.stillCurrent() || managementContext(command.scope)!=actual.context) return
        importReceipts.value=(importReceipts.value+(task.id to ImportEvidence(actual.context,task,frozenImport(outcome)))).entries.toList().takeLast(32).associate { it.key to it.value }
    }
    override suspend fun importOutcome(scope:ZoneScope,taskId:String):DnsImportOutcome? {
        recovery.await()
        val evidence=importReceipts.value[taskId] ?: return null
        if(evidence.settled.scope!=scope || runCatching { managementContext(scope)==evidence.context }.getOrDefault(false).not()) return null
        val task=try { withContext(storeDispatcher) { taskStore.get(taskId) } }
        catch(cancel:CancellationException) { throw cancel }
        catch(_:Exception) { return null }
        if(task!=evidence.settled || task.id!=taskId || task.scope!=scope || task.actionKey!=DNS_IMPORT_OPERATION ||
            task.resourceRef!=ResourceRef(scope,"dns_import",scope.zoneId) || !importStateMatches(evidence.outcome,task.state) ||
            !importScopeMatches(evidence.outcome,scope) || runCatching { managementContext(scope)==evidence.context }.getOrDefault(false).not()) return null
        return frozenImport(evidence.outcome)
    }
    /** One prepare invocation, never a shared last-snapshot cache. Identity is its nonce. */
    private class PrepareCapture(val actual:ExtendedActual,val context:ManagementContext) {
        var observations=0
        var dnssec:DnssecSnapshot?=null
        var settings:ZoneSettingsSnapshot?=null
    }
    private val preparingExtended=MutableStateFlow<PrepareCapture?>(null)
    private fun captureCurrent(capture:PrepareCapture) {
        if(preparingExtended.value!==capture || extendedPrepared.value!==capture.actual ||
            !capture.actual.stillCurrent() || managementContext(capture.actual.command.scope)!=capture.context) throw inputChanged()
    }
    private fun frozenOwner(owner:ZoneDetails)=owner.copy(raw=dnsCopy(owner.raw),nameservers=owner.nameservers.toList())
    private fun frozenDnssec(snapshot:DnssecSnapshot)=snapshot.copy(owner=frozenOwner(snapshot.owner),details=snapshot.details.copy(raw=dnsCopy(snapshot.details.raw)))
    private fun frozenSettings(snapshot:ZoneSettingsSnapshot)=snapshot.copy(owner=frozenOwner(snapshot.owner),
        setting=snapshot.setting?.let { it.copy(raw=dnsCopy(it.raw),value=Json.parseToJsonElement(it.value.toString())) },
        universal=snapshot.universal?.let { it.copy(raw=dnsCopy(it.raw)) })
    private suspend fun dnssecSession(scope:ZoneScope):DnssecSession {
        val api=managementSession(scope).dnssec ?: throw extendedUnavailable()
        val capture=preparingExtended.value ?: return api
        val action=(capture.actual.command as? ExtendedManagementCommand.Dnssec)?.action ?: return api
        captureCurrent(capture)
        if(scope!=action.scope) throw scopeMismatchFailure()
        return object:DnssecSession by api {
            override suspend fun observe(scope:ZoneScope,mutation:DnssecMutation):DnssecSnapshot {
                captureCurrent(capture)
                if(scope!=action.scope || mutation!=dnssecMutation(action)) throw scopeMismatchFailure()
                val snapshot=frozenDnssec(api.observe(scope,mutation))
                captureCurrent(capture)
                if(snapshot.owner.ref!=scope || snapshot.details.scope!=scope || capture.observations!=0) throw scopeMismatchFailure()
                capture.observations++;capture.dnssec=snapshot
                return snapshot
            }
        }
    }
    private suspend fun zoneSettingsSession(scope:ZoneScope):ZoneSettingsSession {
        val api=managementSession(scope).zoneSettings ?: throw extendedUnavailable()
        val capture=preparingExtended.value ?: return api
        val action=(capture.actual.command as? ExtendedManagementCommand.ZoneSettings)?.action ?: return api
        captureCurrent(capture)
        if(scope!=action.scope) throw scopeMismatchFailure()
        return object:ZoneSettingsSession by api {
            override suspend fun observe(scope:ZoneScope,target:ZoneSettingsTarget):ZoneSettingsSnapshot {
                captureCurrent(capture)
                if(scope!=action.scope || target!=settingsTarget(action)) throw scopeMismatchFailure()
                val snapshot=frozenSettings(api.observe(scope,target))
                captureCurrent(capture)
                if(snapshot.owner.ref!=scope || snapshot.setting?.scope?.let { it!=scope }==true ||
                    snapshot.universal?.scope?.let { it!=scope }==true || capture.observations!=0) throw scopeMismatchFailure()
                capture.observations++;capture.settings=snapshot
                return snapshot
            }
        }
    }
    /** Expected configuration only. Provider-computed DS/public material is deliberately absent. */
    private fun plannedDnssec(action:DnssecAction,before:DnssecSnapshot):DnssecDetails? {
        if(action is DnssecAction.Delete) return null
        action as DnssecAction.Update
        val config=JsonObject(before.details.raw.filterKeys { it in dnssecInputNames }+action.input.fields())
        return decodeDnssecDetails(action.scope,config,false)
    }
    private fun plannedSetting(action:ZoneSettingsAction,before:ZoneSettingsSnapshot):JsonElement? = when(action) {
        is ZoneSettingsAction.Purge -> null
        is ZoneSettingsAction.UniversalSsl -> JsonPrimitive(action.enabled)
        is ZoneSettingsAction.Update -> {
            validateZoneSettingInput(action.key,action.input)
            val desired=settingsInput(action).getValue("value")
            val old=before.setting?.value ?: throw invalidInputFailure()
            requireSettingReplacementSafe(action.key,old,desired)
            if(settingPartialObjectPatch(action.key) && old is JsonObject && desired is JsonObject) JsonObject(old+desired)
            else Json.parseToJsonElement(desired.toString())
        }
    }
    private fun freezeExtended(command: ExtendedManagementCommand): ExtendedManagementCommand = when(command) {
        is ExtendedManagementCommand.Import -> command.copy(file=command.file.copy())
        is ExtendedManagementCommand.Scan -> command.copy(action=t7Freeze(command.action))
        is ExtendedManagementCommand.DnsSettings -> command.copy(action=command.action.copy(changed=dnsCopy(command.action.changed)))
        is ExtendedManagementCommand.Dnssec -> command.copy(action=when(val a=command.action) { is DnssecAction.Update->a.copy(input=a.input.copy());is DnssecAction.Delete->a.copy() })
        is ExtendedManagementCommand.ZoneSettings -> command.copy(action=freezeSettingsAction(command.action))
    }
    private fun extendedFingerprint(command: ExtendedManagementCommand): String = when(command) {
        is ExtendedManagementCommand.Import -> fileChanges.requestFingerprint(command.scope,command.file,command.proxied)
        is ExtendedManagementCommand.Scan -> scanChanges.requestFingerprint(command.action)
        is ExtendedManagementCommand.DnsSettings -> dnsSettingsChanges.requestFingerprint(command.action)
        is ExtendedManagementCommand.Dnssec -> dnssecChanges.requestFingerprint(command.action)
        is ExtendedManagementCommand.ZoneSettings -> zoneSettingsChanges.requestFingerprint(command.action)
    }
    override suspend fun prepareExtended(command: ExtendedManagementCommand, revision: Long, stillCurrent: () -> Boolean): ExtendedManagementPreview = managementGate {
        if(revision<0) throw invalidInputFailure()
        val actual=ExtendedActual(freezeExtended(command),revision,stillCurrent,managementContext(command.scope))
        extendedPrepared.value=actual
        val capture=if(actual.command is ExtendedManagementCommand.Dnssec || actual.command is ExtendedManagementCommand.ZoneSettings)
            PrepareCapture(actual,managementContext(actual.command.scope)) else null
        try {
            // This is independent received product input. Plan fields never supply resolver authority.
            activate(actual.command.scope,extendedFingerprint(actual.command)) {
                (extendedPrepared.value===actual || extendedExecuting.value===actual) && actual.stillCurrent()
            }
            preparingExtended.value=capture
            val plan=when(val c=actual.command) {
                is ExtendedManagementCommand.Import -> fileChanges.prepareImport(c.scope,c.file,c.proxied)
                is ExtendedManagementCommand.Scan -> scanChanges.prepare(c.action)
                is ExtendedManagementCommand.DnsSettings -> dnsSettingsChanges.prepare(c.action)
                is ExtendedManagementCommand.Dnssec -> dnssecChanges.prepare(c.action)
                is ExtendedManagementCommand.ZoneSettings -> zoneSettingsChanges.prepare(c.action)
            }
            if(extendedPrepared.value!==actual || !stillCurrent()) throw inputChanged()
            actual.plan=plan
            val details=when(actual.command) {
                is ExtendedManagementCommand.Import -> ExtendedChangeDetails.Import(fileChanges.preview(plan.id))
                is ExtendedManagementCommand.Scan -> ExtendedChangeDetails.Scan(scanChanges.preview(plan.id))
                is ExtendedManagementCommand.DnsSettings -> ExtendedChangeDetails.DnsSettings(dnsSettingsChanges.preview(plan.id))
                is ExtendedManagementCommand.Dnssec -> {
                    val bound=capture ?: throw invalidInputFailure();captureCurrent(bound)
                    if(bound.observations!=1) throw invalidInputFailure()
                    val before=frozenDnssec(bound.dnssec ?: throw invalidInputFailure())
                    ExtendedChangeDetails.Dnssec(dnssecChanges.preview(plan.id),before,plannedDnssec(actual.command.action,before))
                }
                is ExtendedManagementCommand.ZoneSettings -> {
                    val bound=capture ?: throw invalidInputFailure();captureCurrent(bound)
                    if(bound.observations!=1) throw invalidInputFailure()
                    val before=frozenSettings(bound.settings ?: throw invalidInputFailure())
                    ExtendedChangeDetails.ZoneSettings(zoneSettingsChanges.preview(plan.id),
                        before.takeUnless { actual.command.action is ZoneSettingsAction.Purge },plannedSetting(actual.command.action,before))
                }
            }
            ExtendedManagementPreview(plan.id,revision,actual.command.scope,plan.actionKey,plan.resourceRef ?: throw invalidInputFailure(),details)
        } catch(failure:Exception) {
            if(extendedPrepared.value===actual) extendedPrepared.value=null
            throw failure
        } finally { if(preparingExtended.value===capture) preparingExtended.value=null }
    }
    override suspend fun confirmExtended(planId: String, revision: Long, risks: ExtendedRiskChoices): OperationTask = appWrite {
        val actual=extendedPrepared.value ?: throw inputChanged()
        val plan=actual.plan ?: throw inputChanged()
        if(plan.id!=planId || revision!=actual.revision || !actual.stillCurrent()) throw inputChanged()
        // Validate the entire explicit choice before consuming any confirmation or acknowledgement.
        val sec=if(actual.command is ExtendedManagementCommand.Dnssec) dnssecChanges.preview(planId).risks else emptySet()
        val dns=if(actual.command is ExtendedManagementCommand.DnsSettings) dnsSettingsChanges.preview(planId).risks else emptySet()
        val zone=if(actual.command is ExtendedManagementCommand.ZoneSettings) zoneSettingsChanges.preview(planId).risk else null
        if(risks.dnssec!=sec || risks.dnsSettings!=dns || risks.zoneSettings!=zone) throw invalidInputFailure()
        val secTokens=sec.map { dnssecChanges.acknowledgeRisk(planId,it) }.toSet()
        val dnsTokens=dns.map { dnsSettingsChanges.acknowledgeRisk(planId,it) }.toSet()
        val zoneToken=zone?.let { zoneSettingsChanges.acknowledgeRisk(planId,it) }
        val confirmed=confirmActual(planId)
        extendedExecuting.value=actual;extendedPrepared.value=null
        try {
            val task=when(val c=actual.command) {
                is ExtendedManagementCommand.Import -> fileChanges.submitImport(c.scope,c.file,c.proxied,confirmed)
                is ExtendedManagementCommand.Scan -> scanChanges.submit(c.action,confirmed)
                is ExtendedManagementCommand.DnsSettings -> dnsSettingsChanges.submit(c.action,confirmed,dnsTokens)
                is ExtendedManagementCommand.Dnssec -> dnssecChanges.submit(c.action,confirmed,secTokens)
                is ExtendedManagementCommand.ZoneSettings -> zoneSettingsChanges.submit(c.action,confirmed,zoneToken)
            }
            // Receipt presentation never changes a settled task or substitutes for durable metadata.
            try { retainImportReceipt(actual,task) }
            catch(cancel:CancellationException) { throw cancel }
            catch(_:Exception) { }
            task
        } finally { if(extendedExecuting.value===actual) extendedExecuting.value=null }
    }
    override fun invalidateExtended(scope: ZoneScope) {
        if(extendedPrepared.value?.command?.scope==scope) extendedPrepared.value=null
        // An executing command belongs to appScope. UI cancellation never deletes its durable gate.
    }
    private fun activateExtendedRead(scope: ZoneScope, locator: JsonObject, stillCurrent: () -> Boolean) {
        activate(scope,fingerprint.sha256(CanonicalJson.encode(buildJsonObject {
            put("read_scope",dnsScopeFields(scope));put("locator",locator)
        })),stillCurrent)
    }
    override suspend fun readExtended(scope: ZoneScope, request: ExtendedManagementRead, stillCurrent: () -> Boolean): ExtendedManagementReadResult = managementGate {
        activateExtendedRead(scope,buildJsonObject { put("kind",request.toString()) },stillCurrent)
        val live=resolver.current() ?: throw inputChanged()
        val services=managementSession(scope)
        val result=when(request) {
            ExtendedManagementRead.Files -> {
                val api=services.files ?: throw extendedUnavailable()
                val snapshot=api.observe(scope)
                val owner=requireDnsZone(snapshot.owner,scope)
                if(resolver.current()!=live) throw inputChanged()
                val observation=try { api.readback(scope).takeIf { it.scope==scope } }
                catch(cancel:CancellationException) { throw cancel }
                catch(_:Exception) { null }
                ExtendedManagementReadResult.Files(snapshot.copy(owner=frozenOwner(owner)),observation?.copy())
            }
            ExtendedManagementRead.ScanReview -> { val api=services.scan ?: throw extendedUnavailable()
                // Empty selection is a read-only snapshot request; never passed to prepare or write.
                ExtendedManagementReadResult.ScanReview(api.observe(DnsScanAction.Apply(scope,DnsScanReviewInput(emptyList(),emptyList(),emptyMap())))) }
            ExtendedManagementRead.Usage -> ExtendedManagementReadResult.Usage((services.dnsSettings ?: throw extendedUnavailable()).usage(scope))
            ExtendedManagementRead.DnsSettings -> ExtendedManagementReadResult.DnsSettings((services.dnsSettings ?: throw extendedUnavailable()).observe(DnsZoneSettingsAction(scope,JsonObject(emptyMap()))))
            is ExtendedManagementRead.Dnssec -> ExtendedManagementReadResult.Dnssec((services.dnssec ?: throw extendedUnavailable()).observe(scope,request.mutation))
            ExtendedManagementRead.DnssecKeys -> ExtendedManagementReadResult.DnssecKeys((services.dnssec ?: throw extendedUnavailable()).zsks(scope))
            is ExtendedManagementRead.ZoneSettings -> ExtendedManagementReadResult.ZoneSettings((services.zoneSettings ?: throw extendedUnavailable()).observe(scope,request.target))
        }
        if(resolver.current()!=live) throw inputChanged()
        result
    }
    override suspend fun exportDns(scope: ZoneScope, target: DnsExportTarget, stillCurrent: () -> Boolean): DnsExportResult = managementGate {
        activateExtendedRead(scope,buildJsonObject { put("export_target",target.id);put("name",target.displayName) },stillCurrent)
        fileChanges.exportRecords(scope,target)
    }
    override suspend fun pendingExtended(scope: ZoneScope, family: ExtendedManagementFamily, target: ZoneSettingsTarget?): List<OperationTask> {
        recovery.await();managementContext(scope)
        return withContext(storeDispatcher) { taskStore.list(scope.connectionId) }.filter { task ->
            task.scope==scope && when(family) {
                ExtendedManagementFamily.FILES,ExtendedManagementFamily.DNS_SETTINGS -> task.actionKey in DNS_MUTATION_KEYS
                ExtendedManagementFamily.SCAN -> task.actionKey in DNS_MUTATION_KEYS || task.actionKey==DNS_SCAN_TRIGGER_OPERATION
                ExtendedManagementFamily.DNSSEC -> task.actionKey in DnssecMutation.entries.map { it.operationId }
                ExtendedManagementFamily.ZONE_SETTINGS -> task.actionKey in zoneSettingsKeys && (target==null || settingsTaskOverlaps(task,target))
            }
        }.sortedByDescending { it.updatedAtMs }
    }
    private val zoneSettingsKeys get()=ZoneSettingKey.entries.map { it.writeOperation }.toSet()+setOf(UNIVERSAL_SSL_WRITE,"zone-purge")
    private fun settingsTaskOverlaps(task:OperationTask,target:ZoneSettingsTarget):Boolean {
        val ref=task.resourceRef
        if(ref==null || ref.kind !in setOf("zone_setting","universal_ssl","cache_purge")) return true
        if(ref==settingsReference(task.scope as ZoneScope,target)) return true
        if(ref.kind!="zone_setting" || target !is ZoneSettingsTarget.Setting) return false
        val key=ZoneSettingKey.entries.firstOrNull { it.name==ref.externalId } ?: return true
        val children=setOf(ZoneSettingKey.IMAGE_RESIZING,ZoneSettingKey.TRANSFORMATIONS_ALLOWED_ORIGINS,ZoneSettingKey.TRANSFORMATIONS_C2PA)
        return key==ZoneSettingKey.TRANSFORMATIONS_CONFIG && target.key in children || target.key==ZoneSettingKey.TRANSFORMATIONS_CONFIG && key in children
    }
    override suspend fun reconcileExtended(scope: ZoneScope, taskId: String, stillCurrent: () -> Boolean): OperationTask = reconcileManagement(scope,taskId,stillCurrent)
    private fun managementContext(scope:CloudScope):com.flarego.core.application.management.ManagementContext {
        val state=currentState();val owner=state.selected?:throw invalidInputFailure()
        ScopeGuard.requireOwned(scope,owner)
        if(owner !in state.connections) throw invalidInputFailure()
        return com.flarego.core.application.management.ManagementContext(owner,selectionEpoch(),credentialRevision(owner.id))
    }
    private suspend fun managementSession(scope:CloudScope):ManagementServices {
        val before=managementContext(scope)
        val result=if(before.connection.demo) {
            val example=examples.getOrPut(before.connection) { com.flarego.core.demo.DemoDnsManagement(before.connection) }
            val extended=extendedExamples.getOrPut(before.connection) { com.flarego.core.demo.DemoExtendedManagement(example,fingerprint) }
            ManagementServices(example,example,extended,extended,extended,extended,extended)
        } else (managementFactory?:throw invalidInputFailure()).create(before.connection)
        if(managementContext(scope)!=before) throw invalidInputFailure()
        return result
    }
    private suspend fun batchSession(scope:ZoneScope):DnsBatchManagementSession =
        managementSession(scope).dns as? DnsBatchManagementSession ?: throw batchUnavailable()
    private data class Input(val zone: ZoneScope, val action: String, val recordId: String?, val draft: DnsDraft,
        val connection: Connection, val epoch: Long, val revision: Long)
    private val input = MutableStateFlow<Input?>(null)
    private data class ManagementInput(val scope:CloudScope,val request:String,val context:ManagementContext,val stillCurrent:()->Boolean)
    private val managementInput=MutableStateFlow<ManagementInput?>(null)
    private val writeLock = Mutex()
    private val resolver: OperationContextResolver = object : OperationContextResolver {
        override fun liveConnections() = currentState().connections
        override fun isLive(scope: CloudScope, credentialRevision: Long?): Boolean {
            val account = when(scope) { is AccountScope -> scope; is ZoneScope -> scope.account; else -> return false }
            val owner = liveConnections().find { it.id == account.connectionId && it.accountId == account.accountId } ?: return false
            return credentialRevision == null || this@AppOperationRuntime.credentialRevision(owner.id) == credentialRevision
        }
        override fun current(): LiveOperationContext? {
            val command = input.value
            if(command==null) {
                val actual=managementInput.value?:return null
                if(!actual.stillCurrent() || runCatching { managementContext(actual.scope)==actual.context }.getOrDefault(false).not()) return null
                return LiveOperationContext(actual.context.connection,actual.scope,actual.context.credentialRevision,actual.request)
            }
            val state = currentState()
            if (state.selected != command.connection || selectionEpoch() != command.epoch ||
                state.domain?.id != command.zone.zoneId || !isLive(command.zone,command.revision)) return null
            // Independent real command input, held in memory only; no plan/receipt values feed this hash.
            val hash = fingerprint.sha256(CanonicalJson.encode(lifecycle.semanticInput(command.action,command.zone,command.recordId,command.draft,command.connection.id)))
            return LiveOperationContext(command.connection,command.zone,command.revision,hash)
        }
    }
    private val plans = ChangePlans(resolver,clock,ids)
    private val coordinator = OperationCoordinator(plans,taskStore,resolver,clock,appScope,storeDispatcher)
    private val lifecycle = DnsOperationLifecycle(resolver,plans,coordinator,clock,fingerprint,ZoneOwnerCheck { zone ->
        val state = currentState()
        val directory = state.products[com.flarego.core.application.inventory.ProductKey.ZONES]
        directory?.scope == zone.account && directory.credentialRevision == credentialRevision(zone.connectionId) &&
            (directory.content as? com.flarego.core.application.inventory.ProductContent.Zones)?.items?.any {
                it.id == zone.zoneId && it.connectionId == zone.connectionId
            } == true
    },LegacyDnsReceiptSource {
        val command = input.value ?: throw invalidInputFailure()
        sessionFactory.create(command.connection)
    })
    private val dnsChanges = DnsChanges(resolver,plans,coordinator,clock,fingerprint,taskStore,storeDispatcher,
        session={ managementSession(it).dns })
    private val batchChanges=DnsBatchController(resolver,plans,coordinator,clock,fingerprint,taskStore,storeDispatcher,session=::batchSession)
    private val zoneChanges = ZoneController(resolver,plans,coordinator,clock,fingerprint,taskStore,storeDispatcher,
        session={ managementSession(it).zones })

    private fun activate(scope:CloudScope,request:String,stillCurrent:()->Boolean) {
        val stamp=managementContext(scope)
        if(!stillCurrent()) throw inputChanged()
        managementInput.value=ManagementInput(scope,request,stamp,stillCurrent)
        if(resolver.current()==null) throw inputChanged()
    }
    private suspend fun <T> managementGate(block:suspend()->T):T {
        recovery.await()
        if(!writeLock.tryLock()) throw CloudFailure(CloudFailureCode.CONFLICT,"另一项操作正在执行，请稍后核对")
        try { return block() } finally { writeLock.unlock() }
    }
    override suspend fun pendingManagement(scope:CloudScope):OperationTask? {
        recovery.await();managementContext(scope)
        val owner=when(scope) { is AccountScope->scope.connectionId;is ZoneScope->scope.connectionId;else->throw invalidInputFailure() }
        val tasks=withContext(storeDispatcher) { taskStore.list(owner) }.filter { task ->
            if(scope is ZoneScope) task.scope==scope && task.actionKey in DNS_MUTATION_KEYS
            else task.actionKey in setOf("zones-post","zones-0-patch","zones-0-delete","put-zones-zone_id-activation_check") &&
                (task.scope==scope || (task.scope as? ZoneScope)?.account==scope)
        }
        val unsettled=setOf(TaskState.QUEUED,TaskState.RUNNING,TaskState.ACCEPTED,TaskState.UNKNOWN,TaskState.PARTIAL,TaskState.RECONCILING)
        return tasks.filter { it.state in unsettled }.maxByOrNull { it.updatedAtMs }?:tasks.maxByOrNull { it.updatedAtMs }
    }
    override suspend fun prepareDns(action:DnsWriteAction,stillCurrent:()->Boolean):PreparedDnsChange = managementGate {
        // Hash the independently received actual leaf command BEFORE asking for a plan.
        activate(action.scope,dnsChanges.requestFingerprint(action.scope,action.recordId,action.input,action.mode),stillCurrent)
        dnsChanges.prepare(action.scope,action.recordId,action.input,action.mode)
    }
    override fun dnsPreview(id:String)=dnsChanges.preview(id)
    override suspend fun prepareBatch(scope:ZoneScope,input:DnsBatchInput,stillCurrent:()->Boolean):PreparedDnsBatch = managementGate {
        val frozen=com.flarego.core.cloudflare.dns.batchFreeze(input)
        activate(scope,batchChanges.requestFingerprint(scope,frozen),stillCurrent)
        batchChanges.prepare(scope,frozen)
    }
    override fun batchPreview(id:String)=batchChanges.preview(id)
    override suspend fun confirmBatch(prepared:PreparedDnsBatch):OperationTask = appWrite {
        batchChanges.submit(confirmActual(prepared.plan.id))
    }
    override suspend fun batchOutcome(taskId:String,includeCurrentStateProof:Boolean)=batchChanges.outcome(taskId,includeCurrentStateProof)
    override suspend fun prepareZone(action:ZoneAction,stillCurrent:()->Boolean):ChangePlan = managementGate {
        activate(action.scope,zoneChanges.requestFingerprint(action),stillCurrent)
        zoneChanges.prepare(action)
    }
    override fun zonePreview(id:String)=zoneChanges.preview(id)
    private fun confirmActual(id:String):ConfirmedPlan {
        val live=resolver.current()?:throw inputChanged()
        return plans.confirm(id,live.scope,live.credentialRevision,live.requestFingerprint,clock.nowMs())
    }
    private suspend fun <T> appWrite(block:suspend()->T):T = appScope.async {
        runCatching {
            try { managementGate(block) } finally {
                try { refreshTasks() } catch(cancelled:CancellationException) { throw cancelled } catch(_:Exception) { }
            }
        }
    }.await().getOrThrow()
    override suspend fun confirmDns(prepared:PreparedDnsChange):OperationTask = appWrite {
        dnsChanges.submit(prepared,confirmActual(prepared.plan.id))
    }
    override suspend fun confirmZone(action:ZoneAction,plan:ChangePlan):OperationTask = appWrite {
        zoneChanges.submit(action,confirmActual(plan.id))
    }
    override suspend fun reconcileManagement(scope:CloudScope,taskId:String,stillCurrent:()->Boolean):OperationTask = managementGate {
        // A read intent has its own independent input. Metadata is a read locator only.
        activate(scope,fingerprint.sha256(CanonicalJson.encode(buildJsonObject { put("read_task",taskId) })),stillCurrent)
        val task=withContext(storeDispatcher) { taskStore.get(taskId) }?:throw invalidInputFailure()
        if(task.scope!=scope) throw scopeMismatchFailure()
        // An unavailable adapter must not begin RECONCILING or mutate a restored task.
        val family=when(task.actionKey) {
            DNS_IMPORT_OPERATION -> ExtendedManagementFamily.FILES
            DNS_SCAN_TRIGGER_OPERATION,DNS_SCAN_APPLY_OPERATION -> ExtendedManagementFamily.SCAN
            DNS_SETTINGS_PATCH_OPERATION -> ExtendedManagementFamily.DNS_SETTINGS
            in DnssecMutation.entries.map { it.operationId } -> ExtendedManagementFamily.DNSSEC
            in zoneSettingsKeys -> ExtendedManagementFamily.ZONE_SETTINGS
            else -> null
        }
        if(family!=null) {
            if(scope !is ZoneScope) throw scopeMismatchFailure()
            if(family==ExtendedManagementFamily.FILES && managementPlatform==null) throw extendedUnavailable()
            val services=managementSession(scope)
            val present=when(family) {
                ExtendedManagementFamily.FILES -> services.files!=null
                ExtendedManagementFamily.SCAN -> services.scan!=null
                ExtendedManagementFamily.DNS_SETTINGS -> services.dnsSettings!=null
                ExtendedManagementFamily.DNSSEC -> services.dnssec!=null
                ExtendedManagementFamily.ZONE_SETTINGS -> services.zoneSettings!=null
            }
            if(!present) throw extendedUnavailable()
        }
        var result=when(task.actionKey) {
            DNS_BATCH_KEY -> batchChanges.reconcile(taskId)
            DNS_IMPORT_KEY -> fileChanges.reconcile(taskId)
            DNS_SCAN_TRIGGER_OPERATION,DNS_SCAN_APPLY_KEY -> scanChanges.reconcile(taskId)
            DNS_SETTINGS_PATCH_KEY -> dnsSettingsChanges.reconcile(taskId)
            in DnssecMutation.entries.map { it.operationId } -> dnssecChanges.reconcile(taskId)
            in zoneSettingsKeys -> zoneSettingsChanges.reconcile(taskId)
            in DNS_WRITE_KEYS -> dnsChanges.reconcile(taskId)
            "zones-post","zones-0-patch","zones-0-delete","put-zones-zone_id-activation_check" -> zoneChanges.reconcile(taskId)
            else -> throw extendedUnavailable()
        }
        if(result.state==TaskState.UNKNOWN && result.actionKey==DnsOperationLifecycle.ACTION_PATCH && scope is ZoneScope) {
            result=reconcileLegacyPatch(result,scope)
        }
        refreshTasks();result
    }
    /** P1 persisted hashes have an older shape. Prove it from fresh typed reads only. */
    private suspend fun reconcileLegacyPatch(task:OperationTask,scope:ZoneScope):OperationTask {
        val live=resolver.current()?:throw inputChanged()
        fun guard() { if(resolver.current()!=live || !resolver.isLive(scope,live.credentialRevision)) throw inputChanged() }
        return coordinator.reconcile(task.id) { actual ->
            if(actual!=task || actual.resourceRef?.kind!="dns_record") throw invalidInputFailure()
            val evidence=try {
                val api=managementSession(scope).dns;guard()
                val owner=requireDnsZone(api.zone(scope),scope);guard()
                val id=actual.resourceRef.externalId
                val record=requireDnsDocument(api.get(scope,id),scope,id,owner.name);guard()
                val raw=record.raw
                val type=dnsText(raw,"type")
                if(type !in setOf("A","AAAA","CNAME","MX","TXT")) throw invalidInputFailure()
                val ttl=(raw["ttl"] as? JsonPrimitive)?.intOrNull?:throw invalidInputFailure()
                val proxy=raw["proxied"]?.let { (it as? JsonPrimitive)?.booleanOrNull?:throw invalidInputFailure() }?:false
                val priority=raw["priority"]?.let { (it as? JsonPrimitive)?.intOrNull?:throw invalidInputFailure() }
                val content=(raw["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content?:throw invalidInputFailure()
                val observed=com.flarego.core.application.validateDns(Domain(scope.zoneId,owner.name,owner.status,scope.connectionId),
                    DnsDraft(type,dnsText(raw,"name"),content,ttl,proxy,priority))
                Reconciliation.Matched(fingerprint.sha256(CanonicalJson.encode(dnsDesired(live.connection.id,id,observed))))
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { Reconciliation.StillUnknown }
            guard();evidence
        }
    }
    // Recovery happens exactly once per app lifetime and finishes before accepting a new write.
    private val recovery = appScope.async(start = CoroutineStart.LAZY) {
        withContext(storeDispatcher) { coordinator.recoverPending() }
        refreshTasks()
    }

    override suspend fun create(zone: ZoneScope, draft: DnsDraft, frozenBefore: DnsRecord?): OperationTask =
        submit(zone,DnsOperationLifecycle.ACTION_CREATE,null,draft) { lifecycle.create(zone,draft,frozenBefore) }
    override suspend fun update(zone: ZoneScope, recordId: String, draft: DnsDraft, frozenBefore: DnsRecord?): OperationTask =
        submit(zone,DnsOperationLifecycle.ACTION_PATCH,recordId,draft) { lifecycle.update(zone,recordId,draft,frozenBefore) }
    override suspend fun delete(zone: ZoneScope, recordId: String, frozenBefore: DnsRecord?): OperationTask =
        submit(zone,DnsOperationLifecycle.ACTION_DELETE,recordId,DnsDraft("DELETE","","")) { lifecycle.delete(zone,recordId,frozenBefore) }

    private suspend fun submit(zone: ZoneScope, action: String, recordId: String?, draft: DnsDraft, write: suspend () -> OperationTask): OperationTask {
        val owner = currentState().selected ?: throw invalidInputFailure()
        ScopeGuard.requireOwned(zone,owner)
        val frozen = Input(zone,action,recordId,draft,owner,selectionEpoch(),credentialRevision(owner.id))
        // The job belongs to the app, so cancelling a UI confirmation waiter does not drop its guard or replay it.
        return appScope.async {
          runCatching {
            recovery.await()
            if (!writeLock.tryLock()) throw CloudFailure(CloudFailureCode.CONFLICT,"另一项操作正在执行，请稍后核对")
            try {
                // The legacy route shares the same app mutex and durable whole-Zone family admission.
                DnsUncertaintyGate(taskStore,storeDispatcher).requireClear(zone,action,recordId?.let { ResourceRef(zone,"dns_record",it) })
                managementInput.value=null
                input.value = frozen
                if (resolver.current() == null) throw invalidInputFailure()
                write()
            } finally {
                input.value = null
                writeLock.unlock()
                // A UI list refresh is not part of the write receipt. Never replace an
                // authoritative task/result with a post-send display failure.
                try { refreshTasks() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep the persisted task and the returned receipt. */ }
            }
          }
        }.await().getOrThrow()
    }
    suspend fun recover() { recovery.await() }
    private suspend fun refreshTasks() {
        val tasks = withContext(storeDispatcher) { resolver.liveConnections().flatMap { taskStore.list(it.id) } }
        publish(tasks.filter { resolver.isLive(it.scope,null) })
    }
    suspend fun reconcile(taskId: String) {
        recovery.await()
        val selected = currentState().selected ?: throw invalidInputFailure()
        if(managementFactory!=null || selected.demo) {
            val task=withContext(storeDispatcher) { taskStore.get(taskId) }?:throw invalidInputFailure()
            // Selecting a persisted task authorizes only its typed read locator, never a write plan.
            ScopeGuard.requireOwned(task.scope,selected)
            val stamp=managementContext(task.scope)
            reconcileManagement(task.scope,taskId) { runCatching { managementContext(task.scope)==stamp }.getOrDefault(false) }
            return
        }
        val initial=withContext(storeDispatcher) { taskStore.get(taskId) } ?: throw invalidInputFailure()
        if(initial.actionKey !in DNS_WRITE_KEYS) throw extendedUnavailable()
        val epoch = selectionEpoch()
        val revision = credentialRevision(selected.id)
        coordinator.reconcile(taskId) { task ->
            if (task.connectionId != selected.id) throw scopeMismatchFailure()
            ScopeGuard.requireOwned(task.scope,selected)
            val zone = task.scope as? ZoneScope ?: throw invalidInputFailure()
            val session = sessionFactory.create(selected)
            if (currentState().selected != selected || selectionEpoch() != epoch || credentialRevision(selected.id) != revision) throw invalidInputFailure()
            // Read a typed DNS endpoint only. Persisted pollPath never grants arbitrary request authority.
            val records = session.records(zone.zoneId)
            if (currentState().selected != selected || selectionEpoch() != epoch || credentialRevision(selected.id) != revision) throw invalidInputFailure()
            val record = task.resourceRef?.takeIf { it.kind == "dns_record" }?.externalId?.let { id -> records.singleOrNull { it.id == id } }
            if (task.actionKey == DnsOperationLifecycle.ACTION_PATCH && record != null) {
                val observed = DnsDraft(record.type,record.name,record.content,record.ttl,record.proxied,record.priority)
                Reconciliation.Matched(fingerprint.sha256(CanonicalJson.encode(dnsDesired(selected.id,record.id,observed))))
            } else {
                // Create has no receipt identity; a missing record in a legacy read cannot prove deletion.
                Reconciliation.StillUnknown
            }
        }
        refreshTasks()
    }
    suspend fun removeConnection(connectionId: String, remove: () -> Unit) {
        coordinator.withInvalidatedConnection(connectionId) { taskStore.removeConnection(connectionId);remove() }
    }
}
