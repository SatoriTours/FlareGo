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

class CloudflareDnsScan(private val connection: Connection, private val gateway: CloudflareGateway,
    private val fingerprint: FingerprintPort, private val constraints: (ZoneScope) -> DnsConstraints = { DnsConstraints() },
    private val authority: DnsScanAuthority = object : DnsScanAuthority {}) : DnsScanSession {
    private suspend fun owner(scope: ZoneScope): ZoneDetails {
        t7Scope(scope);ScopeGuard.requireOwned(scope,connection)
        return requireDnsZone(CloudflareZoneClient(connection,gateway).get(scope),scope)
    }
    override suspend fun review(scope: ZoneScope): List<DnsScanCandidate> = readReview(owner(scope))
    private suspend fun readReview(owner: ZoneDetails): List<DnsScanCandidate> {
        val root=t7Read(gateway,DNS_SCAN_REVIEW_OPERATION,"${scanCollection(owner.ref)}/review")
        val rows=(root["result"] as? JsonArray ?: throw dnsInvalidResponse()).map {
            t7Candidate(it as? JsonObject ?: throw dnsInvalidResponse(),owner,fingerprint)
        }
        if(rows.map { it.id }.distinct().size!=rows.size) throw dnsInvalidResponse()
        return rows
    }
    override suspend fun observe(action: DnsScanAction): DnsScanSnapshot {
        val owned=owner(action.scope)
        val rows=if(action is DnsScanAction.Apply) readReview(owned) else emptyList()
        return DnsScanSnapshot(owned,rows,authority.mayWrite(action.scope),constraints(action.scope))
    }
    override suspend fun trigger(scope: ZoneScope): ExecutionReceipt = write(DnsScanAction.Trigger(scope),{}, {})
    override suspend fun apply(scope: ZoneScope, input: DnsScanReviewInput): DnsScanApplyOutcome = perform(DnsScanAction.Apply(scope,input),{}, {})
    override suspend fun write(action: DnsScanAction, beforeSend: (DnsScanSnapshot) -> Unit, afterRead: () -> Unit): ExecutionReceipt =
        perform(action,beforeSend,afterRead).receipt
    private suspend fun perform(requested: DnsScanAction, beforeSend: (DnsScanSnapshot) -> Unit, afterRead: () -> Unit): DnsScanApplyOutcome {
        val action=t7Freeze(requested);val snapshot=t7Checked(observe(action),action.scope,fingerprint);t7Writable(snapshot.writeAllowed)
        val payload=if(action is DnsScanAction.Apply) t7ApplyPayload(snapshot,action.input,snapshot.constraints) else null
        val key=t7Key(action);val request=HttpRequest("POST","${scanCollection(action.scope)}/${if(action is DnsScanAction.Trigger) "trigger" else "review"}",payload?.toString())
        t7Registered(key,request)
        beforeSend(snapshot)
        return try {
            val response=gateway.send(key,request)
            val root=t7Envelope(response,true)
            if(action is DnsScanAction.Trigger) { afterRead();return DnsScanApplyOutcome(ExecutionReceipt.Accepted()) }
            val selected=(action as DnsScanAction.Apply).input
            val result=root["result"] as? JsonObject ?: return unknownScan()
            t7ResultScope(result,action.scope)
            val accepts=(result["accepts"] as? JsonArray) ?: if(selected.acceptIds.isEmpty()) JsonArray(emptyList()) else return unknownScan()
            val rejects=(result["rejects"] as? JsonArray) ?: if(selected.rejectIds.isEmpty()) JsonArray(emptyList()) else return unknownScan()
            val rejected=rejects.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::t7Id) ?: return unknownScan() }
            if(rejected.distinct().size!=rejected.size || rejected.toSet()!=selected.rejectIds.toSet()) return unknownScan()
            val expected=payload!!.get("accepts")?.jsonArray.orEmpty().map { dnsSemanticRecord(it.jsonObject) }
            val accepted=accepts.map { raw ->
                val doc=t7Candidate(raw as? JsonObject ?: return unknownScan(),snapshot.owner,fingerprint).record
                val actual=CloudflareDnsClient(connection,gateway).get(action.scope,doc.id)
                if(dnsSemanticRecord(actual.raw)!=dnsSemanticRecord(doc.raw)) return unknownScan()
                actual
            }
            if(accepted.size!=expected.size || accepted.map { it.id }.distinct().size!=accepted.size ||
                accepted.map { dnsSemanticRecord(it.raw) }.toSet()!=expected.toSet()) return unknownScan()
            afterRead()
            DnsScanApplyOutcome(ExecutionReceipt.Completed(),accepted,rejected)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { unknownScan() }
    }
}
private fun scanCollection(scope: ZoneScope)="/zones/${encodePath(scope.zoneId)}/dns_records/scan"
private fun unknownScan()=DnsScanApplyOutcome(ExecutionReceipt.Unknown())

internal fun t7Registered(key: String, request: HttpRequest) {
    val row=ActionPolicies.registry.registration(key) ?: throw invalidInputFailure()
    if(!row.matches(request.method,request.path)) throw invalidInputFailure()
}
internal suspend fun t7Read(gateway: CloudflareGateway, key: String, path: String): JsonObject =
    t7Envelope(gateway.send(key,HttpRequest("GET",path)),false)
internal fun t7Envelope(response: HttpResponse, writing: Boolean): JsonObject {
    if(response.status!=200) throw CloudFailure(if(response.status==403) CloudFailureCode.PERMISSION_REQUIRED else CloudFailureCode.INVALID_RESPONSE,"DNS接口未返回可确认响应",uncertain=writing)
    val root=ResponseDecoders.envelope(response,writing)
    if(root["success"]!=JsonPrimitive(true) || (root["errors"] as? JsonArray)?.isEmpty()!=true || root["messages"] !is JsonArray) throw dnsInvalidResponse()
    return root
}
internal fun t7ResultScope(result: JsonObject, scope: ZoneScope) {
    result["zone_id"]?.let { if(it!=JsonPrimitive(scope.zoneId)) throw scopeMismatchFailure() }
}
