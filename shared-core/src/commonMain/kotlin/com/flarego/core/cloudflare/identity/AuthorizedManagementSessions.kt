package com.flarego.core.cloudflare.identity

import com.flarego.core.application.dns.*
import com.flarego.core.application.management.extendedUnavailable
import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.cloudflare.dns.*
import com.flarego.core.cloudflare.zones.*
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.*

/** Trusted provider qualifications, independent of Token permission. Defaults never grant paid features. */
interface ManagementEligibilitySource {
    suspend fun nsec3Available(scope: ZoneScope): Boolean? = null
    suspend fun settingEligible(scope: ZoneScope, operationId: String): Boolean? = null
    suspend fun purgeTier(scope: ZoneScope): CachePurgeTier? = null
    suspend fun dnsSettings(scope: ZoneScope): DnsSettingsEligibility = DnsSettingsEligibility()
}
/** The limiter is supplied by the one app owner; never constructed by a short credential session. */
data class AuthorizedDnsFilesDependencies(val transfers: TransferPort, val limiter: DnsImportLimiter, val ioDispatcher: CoroutineDispatcher)
class AuthorizedManagementSessions(private val connection: Connection, private val gateway: CloudflareGateway,
    private val issuer: TokenAuthorityIssuer, private val fingerprint: FingerprintPort,
    private val fileDependencies: AuthorizedDnsFilesDependencies? = null,
    private val eligibility: ManagementEligibilitySource = object : ManagementEligibilitySource {},
    private val constraints: (ZoneScope) -> DnsConstraints = { DnsConstraints() }) {
    private val authority=CloudflareWriteAuthorityClient(issuer,gateway)
    private class Binding(val proof: WriteAuthorityProof?, val scope: ZoneScope, val operation: String)
    private suspend fun binding(scope: ZoneScope, operation: String, required: Boolean): Binding {
        val observed=authority.observe(scope,operation,forceRefresh=true)
        // Public observation values are never converted to true. Only the issuer's identity table grants a proof.
        val proof=if(observed.decision==WriteAuthorityDecision.TOKEN_POLICY_GRANTED) issuer.proofFor(observed) else null
        if(required && proof==null) throw extendedUnavailable()
        return Binding(proof,scope,operation)
    }
    private fun permission(b: Binding, scope: ZoneScope, operation: String): Boolean? {
        if(scope!=b.scope || operation!=b.operation) throw scopeMismatchFailure()
        val proof=b.proof ?: return null
        issuer.requireCurrent(proof,scope,operation);return true
    }
    private fun finalGuard(b: Binding, block: ()->Unit) {
        permission(b,b.scope,b.operation) ?: throw extendedUnavailable()
        block()
        // A composed product guard may synchronously invalidate credentials; check immediately again.
        permission(b,b.scope,b.operation) ?: throw extendedUnavailable()
    }
    private suspend fun dnssec(scope: ZoneScope, mutation: DnssecMutation, required: Boolean): Pair<CloudflareDnssecClient,Binding> {
        val b=binding(scope,mutation.operationId,required)
        val nsec3=eligibility.nsec3Available(scope)
        val api=CloudflareDnssecClient(connection,gateway,object:DnssecAuthority {
            override fun mayWrite(scope: ZoneScope, mutation: DnssecMutation)=permission(b,scope,mutation.operationId)
            override fun nsec3Available(scope: ZoneScope): Boolean? { if(scope!=b.scope) throw scopeMismatchFailure();return nsec3 }
        })
        return api to b
    }
    private suspend fun zoneSettings(scope: ZoneScope, target: ZoneSettingsTarget, required: Boolean): Pair<CloudflareZoneSettings,Binding> {
        val operation=when(target) { is ZoneSettingsTarget.Setting->target.key.writeOperation;ZoneSettingsTarget.UniversalSsl->UNIVERSAL_SSL_WRITE;ZoneSettingsTarget.Cache->"zone-purge" }
        val b=binding(scope,operation,required)
        val qualified=eligibility.settingEligible(scope,operation);val tier=eligibility.purgeTier(scope)
        return CloudflareZoneSettings(connection,gateway,object:ZoneSettingsAuthority {
            override fun mayWrite(scope: ZoneScope, operationId: String)=permission(b,scope,operationId)
            override fun eligible(scope: ZoneScope, operationId: String): Boolean? { if(scope!=b.scope || operationId!=b.operation) throw scopeMismatchFailure();return qualified }
            override fun purgeTier(scope: ZoneScope): CachePurgeTier? { if(scope!=b.scope) throw scopeMismatchFailure();return tier }
        }) to b
    }
    private suspend fun scan(action: DnsScanAction, required: Boolean): Pair<CloudflareDnsScan,Binding> {
        val b=binding(action.scope,t7Key(action),required)
        return CloudflareDnsScan(connection,gateway,fingerprint,constraints,object:DnsScanAuthority {
            override suspend fun mayWrite(scope: ZoneScope)=permission(b,scope,b.operation)
        }) to b
    }
    private suspend fun settings(scope: ZoneScope, required: Boolean): Pair<CloudflareDnsZoneSettings,Binding> {
        val b=binding(scope,DNS_SETTINGS_PATCH_OPERATION,required);val qualified=eligibility.dnsSettings(scope)
        return CloudflareDnsZoneSettings(connection,gateway,fingerprint,object:DnsZoneSettingsAuthority {
            override suspend fun mayWrite(scope: ZoneScope)=permission(b,scope,DNS_SETTINGS_PATCH_OPERATION)
            override suspend fun eligibility(scope: ZoneScope)=qualified
        }) to b
    }
    private suspend fun files(scope: ZoneScope, required: Boolean): Pair<CloudflareDnsFiles,Binding> {
        val deps=fileDependencies ?: throw extendedUnavailable();val b=binding(scope,DNS_IMPORT_OPERATION,required)
        return CloudflareDnsFiles(connection,gateway,deps.transfers,fingerprint,deps.limiter,deps.ioDispatcher,object:DnsFilesAuthority {
            override fun mayImport(scope: ZoneScope)=permission(b,scope,DNS_IMPORT_OPERATION)
        }) to b
    }
    fun services(): ManagementServices {
        val rawSec=CloudflareDnssecClient(connection,gateway)
        val rawZone=CloudflareZoneSettings(connection,gateway)
        val rawScan=CloudflareDnsScan(connection,gateway,fingerprint,constraints)
        val rawSettings=CloudflareDnsZoneSettings(connection,gateway,fingerprint)
        return ManagementServices(CloudflareDnsClient(connection,gateway,constraints),CloudflareZoneClient(connection,gateway),
            files=if(fileDependencies==null) null else object:DnsFilesSession {
                override suspend fun observe(scope:ZoneScope)=files(scope,false).first.observe(scope)
                override suspend fun readback(scope:ZoneScope)=CloudflareDnsFiles(connection,gateway,fileDependencies.transfers,fingerprint,fileDependencies.limiter,fileDependencies.ioDispatcher).readback(scope)
                override suspend fun exportRecords(scope:ZoneScope,sink:ByteSink)=CloudflareDnsFiles(connection,gateway,fileDependencies.transfers,fingerprint,fileDependencies.limiter,fileDependencies.ioDispatcher).exportRecords(scope,sink)
                override suspend fun writeImport(scope:ZoneScope,source:suspend()->ByteSource,length:Long,fingerprint:String,proxied:Boolean,beforeSend:(DnsFileImportState)->Unit,afterRead:()->Unit):DnsImportOutcome {
                    val (api,b)=files(scope,true)
                    return api.writeImport(scope,source,length,fingerprint,proxied,{ row->finalGuard(b) { beforeSend(row) } },afterRead)
                }
                override suspend fun importRecords(scope:ZoneScope,source:ByteSource,length:Long,fingerprint:String,proxied:Boolean):DnsImportOutcome =
                    try { writeImport(scope,{source},length,fingerprint,proxied,{}, {}) } finally { source.close() }
            },scan=object:DnsScanSession {
                override suspend fun review(scope:ZoneScope)=rawScan.review(scope)
                override suspend fun observe(action:DnsScanAction)=scan(action,false).first.observe(action)
                override suspend fun write(action:DnsScanAction,beforeSend:(DnsScanSnapshot)->Unit,afterRead:()->Unit):ExecutionReceipt {
                    val (api,b)=scan(action,true);return api.write(action,{ row->finalGuard(b) { beforeSend(row) } },afterRead)
                }
                override suspend fun trigger(scope:ZoneScope)=write(DnsScanAction.Trigger(scope),{}, {})
                override suspend fun apply(scope:ZoneScope,input:DnsScanReviewInput):DnsScanApplyOutcome {
                    val action=t7Freeze(DnsScanAction.Apply(scope,input)) as DnsScanAction.Apply
                    var observed:DnsScanSnapshot?=null
                    val receipt=write(action,{ observed=it },{})
                    if(receipt !is ExecutionReceipt.Completed) return DnsScanApplyOutcome(receipt)
                    val snapshot=observed ?: throw invalidInputFailure()
                    // The guarded session method deliberately returns only a receipt. Recover public
                    // accepted documents with typed, complete, unique current reads; never guess IDs.
                    return try {
                        val accepted=action.input.acceptIds.map { id ->
                            val row=snapshot.candidates.single { it.id==id }
                            val expected=t7Accept(row.record,snapshot.owner.name,snapshot.constraints)
                            val page=CloudflareDnsClient(connection,gateway,constraints).list(scope,
                                DnsFilter(type=dnsText(expected,"type"),name=dnsText(expected,"name")),PageRequest(100))
                            if(page.next!=null) throw invalidInputFailure()
                            page.items.filter { dnsSemanticRecord(it.raw)==dnsSemanticRecord(expected) }.single()
                        }
                        DnsScanApplyOutcome(receipt,accepted,action.input.rejectIds)
                    } catch(cancel:kotlinx.coroutines.CancellationException) { throw cancel }
                    catch(_:Exception) { DnsScanApplyOutcome(ExecutionReceipt.Unknown()) }
                }
            },dnsSettings=object:DnsZoneSettingsSession {
                override suspend fun usage(scope:ZoneScope)=rawSettings.usage(scope)
                override suspend fun settings(scope:ZoneScope)=rawSettings.settings(scope)
                override suspend fun observe(action:DnsZoneSettingsAction)=settings(action.scope,false).first.observe(action)
                override suspend fun write(action:DnsZoneSettingsAction,beforeSend:(DnsZoneSettingsSnapshot)->Unit,afterRead:()->Unit):ExecutionReceipt {
                    val (api,b)=settings(action.scope,true);return api.write(action,{ row->finalGuard(b) { beforeSend(row) } },afterRead)
                }
                override suspend fun updateSettings(scope:ZoneScope,changed:JsonObject):DnsZoneSettings {
                    if(write(DnsZoneSettingsAction(scope,changed),{}, {}) !is ExecutionReceipt.Completed) throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT,"设置结果未知，请只读核对",uncertain=true)
                    return rawSettings.settings(scope)
                }
            },dnssec=object:DnssecSession {
                override suspend fun get(scope:ZoneScope)=rawSec.get(scope)
                override suspend fun zsks(scope:ZoneScope)=rawSec.zsks(scope)
                override suspend fun observe(scope:ZoneScope,mutation:DnssecMutation)=dnssec(scope,mutation,false).first.observe(scope,mutation)
                override suspend fun write(action:DnssecAction,beforeSend:(DnssecSnapshot)->Unit,afterRead:()->Unit):ExecutionReceipt {
                    val (api,b)=dnssec(action.scope,dnssecMutation(action),true)
                    return api.write(action,{ row->finalGuard(b) { beforeSend(row) } },afterRead)
                }
                override suspend fun update(scope:ZoneScope,input:DnssecUpdateInput):DnssecDetails {
                    if(write(DnssecAction.Update(scope,input),{}, {}) !is ExecutionReceipt.Completed) throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT,"DNSSEC结果未知，请只读核对",uncertain=true)
                    return rawSec.get(scope)
                }
                override suspend fun delete(scope:ZoneScope) { write(DnssecAction.Delete(scope),{}, {}).let { if(it !is ExecutionReceipt.Completed) throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT,"DNSSEC结果未知，请只读核对",uncertain=true) } }
            },zoneSettings=object:ZoneSettingsSession {
                override suspend fun get(scope:ZoneScope,setting:ZoneSettingKey)=rawZone.get(scope,setting)
                override suspend fun universalSsl(scope:ZoneScope)=rawZone.universalSsl(scope)
                override suspend fun observe(scope:ZoneScope,target:ZoneSettingsTarget)=zoneSettings(scope,target,false).first.observe(scope,target)
                override suspend fun write(action:ZoneSettingsAction,beforeSend:(ZoneSettingsSnapshot)->Unit,afterRead:()->Unit):ExecutionReceipt {
                    val (api,b)=zoneSettings(action.scope,settingsTarget(action),true)
                    return api.write(action,{ row->finalGuard(b) { beforeSend(row) } },afterRead)
                }
                override suspend fun update(scope:ZoneScope,setting:ZoneSettingKey,input:JsonObject):ZoneSettingValue {
                    if(write(ZoneSettingsAction.Update(scope,setting,input),{}, {}) !is ExecutionReceipt.Completed) throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT,"设置结果未知，请只读核对",uncertain=true)
                    return rawZone.get(scope,setting)
                }
                override suspend fun updateUniversalSsl(scope:ZoneScope,enabled:Boolean):UniversalSslSetting {
                    if(write(ZoneSettingsAction.UniversalSsl(scope,enabled),{}, {}) !is ExecutionReceipt.Completed) throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT,"设置结果未知，请只读核对",uncertain=true)
                    return rawZone.universalSsl(scope)
                }
                override suspend fun purge(scope:ZoneScope,input:CachePurgeInput)=write(ZoneSettingsAction.Purge(scope,input),{}, {})
            })
    }
}
